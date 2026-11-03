package com.openlattice.chronicle.services.upload

import com.geekbeast.postgres.PostgresColumnDefinition
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.IOS_SCREEN_TIME_USAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RANDOM_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_APP_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_BUNDLE_IDENTIFIER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_CAPTURED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_CATEGORY_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_DURATION_SECONDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_NOTIFICATION_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_OBSERVATION_END
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_OBSERVATION_START
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_PICKUP_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_RECORD_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_ROW_KIND
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_TIMEZONE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_TIME_WEB_DOMAIN
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.storage.StorageResolver
import java.sql.Connection
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Writes iOS Screen Time (DeviceActivity App & Web Usage) uploads to [IOS_SCREEN_TIME_USAGE] in the upload
 * transaction. Each app or web-domain record becomes one row; a retried capture hits
 * [ScreenTimeUsageCaptures.CAPTURE_KEY_CONSTRAINT] and writes nothing. A record whose labels contradict its row
 * kind, or whose zone id this server does not know, refuses the upload as a row defect.
 */
public class ScreenTimeUsageUploadService(private val storageResolver: StorageResolver) {

    /** One record of one upload, as the row it becomes. */
    internal class Row(
        val studyId: UUID,
        val participantId: String,
        val randomDeviceId: UUID,
        val record: ScreenTimeUsageRecord,
    )

    internal companion object {
        /** Every written column with its value; the column list and the binds both come from it. */
        val BINDINGS: List<Pair<PostgresColumnDefinition, (Row) -> Any?>> = listOf(
            STUDY_ID to { it.studyId },
            PARTICIPANT_ID to { it.participantId },
            RANDOM_DEVICE_ID to { it.randomDeviceId },
            SCREEN_TIME_RECORD_ID to { it.record.id },
            SCREEN_TIME_ROW_KIND to { it.record.rowKind.name },
            SCREEN_TIME_CAPTURED_AT to { it.record.capturedAt },
            SCREEN_TIME_OBSERVATION_START to { it.record.observationStart },
            SCREEN_TIME_OBSERVATION_END to { it.record.observationEnd },
            SCREEN_TIME_TIMEZONE to { it.record.timezoneIdentifier },
            SCREEN_TIME_APP_NAME to { it.record.appName },
            SCREEN_TIME_BUNDLE_IDENTIFIER to { it.record.bundleIdentifier },
            SCREEN_TIME_WEB_DOMAIN to { it.record.webDomain },
            SCREEN_TIME_CATEGORY_NAME to { it.record.categoryName },
            SCREEN_TIME_DURATION_SECONDS to { it.record.durationSeconds },
            SCREEN_TIME_NOTIFICATION_COUNT to { it.record.notificationCount },
            SCREEN_TIME_PICKUP_COUNT to { it.record.pickupCount },
        )

        // uploaded_at is the SQL receipt instant.
        private val INSERT_SQL = """
            INSERT INTO ${IOS_SCREEN_TIME_USAGE.name} (${BINDINGS.joinToString(", ") { it.first.name }}, ${UPLOADED_AT.name})
            VALUES (${BINDINGS.joinToString(", ") { "?" }}, now())
            ON CONFLICT ON CONSTRAINT ${ScreenTimeUsageCaptures.CAPTURE_KEY_CONSTRAINT} DO NOTHING
        """.trimIndent()
    }

    /**
     * Stores the records of [envelope] that the participant's erasure cutoff admits. The collection gate and erasure
     * cutoff run even when no record is stored.
     *
     * @return the number of records submitted, whether or not a retry already stored them.
     */
    public fun upload(studyId: UUID, participantId: String, randomDeviceId: UUID, envelope: ScreenTimeUsageEnvelope): Int {
        requireUploadBatchSize(envelope.records.size)
        envelope.records.forEach { record ->
            record.requireLabelsMatchRowKind()
            record.requireKnownTimezone()
        }
        ParticipantPurgeCutoff.withPermittedBatch(
            storageResolver.getPlatformStorage(), studyId, participantId, envelope.records,
            { ScreenTimeUsageCaptures.captureTime(it.capturedAt, it.observationEnd) },
        ) { connection, retained ->
            val captures = retained.map { it.capturedInOwnZone() }
            insert(connection, retained.map { Row(studyId, participantId, randomDeviceId, it) })
            if (retained.isNotEmpty()) {
                ParticipantStatsMapstore(storageResolver.getPlatformStorage())
                    .recordIosUploadUsing(connection, studyId, participantId, captures)
            }
        }
        ChronicleMetrics.uploadTotal.labels("ios_screen_time").inc()
        return envelope.records.size
    }

    private fun insert(connection: Connection, rows: List<Row>) {
        if (rows.isEmpty()) return
        connection.prepareStatement(INSERT_SQL).use { statement ->
            rows.forEach { row ->
                BINDINGS.forEachIndexed { index, (_, value) -> statement.setObject(index + 1, value(row)) }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun ScreenTimeUsageRecord.capturedInOwnZone(): ZonedDateTime =
        capturedAt.atZoneSameInstant(ZoneId.of(timezoneIdentifier))
}
