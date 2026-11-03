package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.IOS_SCREEN_TIME_USAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RANDOM_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_APP_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_BUNDLE_IDENTIFIER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_CAPTURED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_CATEGORY_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_OBSERVATION_END
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_OBSERVATION_START
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_RECORD_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_ROW_KIND
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_TIMEZONE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_WEB_DOMAIN
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import java.time.OffsetDateTime

/**
 * The capture rules of [IOS_SCREEN_TIME_USAGE]. Apple reports a cumulative total for each app or web
 * domain per observation window (an hour), and the phone re-reads the last day on every run, so one
 * window arrives once per capture with a growing total.
 */
internal object ScreenTimeUsageCaptures {
    /** One app/domain row of one observation window on one device; every capture of that window shares it. */
    val WINDOW_ENTRY = listOf(
        STUDY_ID, PARTICIPANT_ID, RANDOM_DEVICE_ID, SCREEN_TIME_ROW_KIND,
        SCREEN_TIME_APP_NAME, SCREEN_TIME_BUNDLE_IDENTIFIER, SCREEN_TIME_WEB_DOMAIN, SCREEN_TIME_CATEGORY_NAME,
        SCREEN_TIME_OBSERVATION_START, SCREEN_TIME_OBSERVATION_END,
    )

    /** Logical capture identity; V1 hashes its nullable labels to keep the unique btree key small. */
    val CAPTURE_KEY = WINDOW_ENTRY + SCREEN_TIME_CAPTURED_AT

    const val LABELS_HASH_COLUMN = "capture_labels_hash"

    const val CAPTURE_KEY_CONSTRAINT = "ios_screen_time_usage_capture_key"

    /**
     * The instant up to which a capture counts as collected, for the purge cutoff at upload and for erasure: a
     * window still being measured has been collected only up to its capture instant.
     */
    fun captureTime(capturedAt: OffsetDateTime, observationEnd: OffsetDateTime): OffsetDateTime =
        minOf(observationEnd, capturedAt)

    /** [captureTime] over a row of [IOS_SCREEN_TIME_USAGE]. */
    val CAPTURE_TIME_SQL = "LEAST(${SCREEN_TIME_CAPTURED_AT.name}, ${SCREEN_TIME_OBSERVATION_END.name})"

    /**
     * Participant-local capture days of each participant of one study (1. study id, as text). Rows whose zone
     * id PostgreSQL does not know are skipped rather than failing the query.
     */
    val CAPTURE_DAYS_SQL = """
        SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name},
               string_agg(DISTINCT ((${SCREEN_TIME_CAPTURED_AT.name} AT TIME ZONE ${SCREEN_TIME_TIMEZONE.name})::date)::text, ',')
                   AS ${PostgresEventTables.UNIQUE_DATES}
        FROM ${IOS_SCREEN_TIME_USAGE.name}
        WHERE ${STUDY_ID.name} = ?::uuid AND ${SCREEN_TIME_TIMEZONE.name} IN (SELECT name FROM pg_timezone_names)
        GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
    """.trimIndent()

    /**
     * Every column of the newest capture of each window entry among the rows matching [where]. Callers
     * filter on window-entry columns only, which every capture of a window shares.
     */
    fun newestCapturesSql(where: String): String {
        val columns = IOS_SCREEN_TIME_USAGE.columns.joinToString(", ") { it.name }
        return """
            SELECT $columns FROM (
                SELECT $columns, row_number() OVER (
                    PARTITION BY ${WINDOW_ENTRY.joinToString(", ") { it.name }}
                    ORDER BY ${SCREEN_TIME_CAPTURED_AT.name} DESC, ${SCREEN_TIME_RECORD_ID.name} DESC
                ) AS capture_rank
                FROM ${IOS_SCREEN_TIME_USAGE.name}
                WHERE $where
            ) AS ranked
            WHERE capture_rank = 1
        """.trimIndent()
    }
}
