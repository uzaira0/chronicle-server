package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

internal const val STUDY_ERASURE_PREDICATE = "study_id = ?"

/** An unspecified PostgreSQL parameter takes the native UUID or text column type. */
internal fun PreparedStatement.bindErasureStudyId(studyId: UUID) {
    setObject(1, studyId.toString(), Types.OTHER)
}

/**
 * Canonical, framework-neutral inventory of participant-scoped persisted assets.
 *
 * Table identifiers are compile-time constants and are the only identifiers accepted by
 * the generic deletion runner. A future Rust maintenance worker must consume the same
 * registry export and pass the same coverage tests before it can own erasure.
 */
public data class ParticipantDataAsset(
    val id: String,
    val tableName: String,
    val participantScope: ParticipantScope = ParticipantScope.SCALAR_COLUMN,
    val captureTimeSql: String? = null,
    val processingData: Boolean = true,
)

public enum class ParticipantScope {
    SCALAR_COLUMN,
    TEXT_ARRAY_COLUMN,
    BLOCK_TOKEN_COLUMN,
    NON_ENROLLMENT_EVIDENCE_CODES,
}

internal enum class ParticipantAssetOperation { COUNT, DELETE }

public object ChronicleDataAssetRegistry {
    private val SQL_IDENTIFIER = Regex("^[a-z][a-z0-9_]*$")

    internal fun participantStatement(asset: ParticipantDataAsset, operation: ParticipantAssetOperation): String {
        val action = when (operation) {
            ParticipantAssetOperation.COUNT -> "SELECT COUNT(*)"
            ParticipantAssetOperation.DELETE -> "DELETE"
        }
        val predicate = when (asset.participantScope) {
            ParticipantScope.SCALAR_COLUMN -> "participant_id = ?"
            ParticipantScope.TEXT_ARRAY_COLUMN -> "? = ANY(participant_ids)"
            ParticipantScope.BLOCK_TOKEN_COLUMN -> "participant_block_token = chronicle_participant_block_token(study_id, ?::text)"
            ParticipantScope.NON_ENROLLMENT_EVIDENCE_CODES -> """
                participant_id = ? AND access_code_id NOT IN (
                    SELECT access_code_id FROM exchanged_enrollment_access_codes
                )
            """.trimIndent()
        }
        return "$action FROM ${asset.tableName} WHERE $STUDY_ERASURE_PREDICATE AND $predicate"
    }

    public val participantAssets: List<ParticipantDataAsset> = listOf(
        ParticipantDataAsset(
            "usage-events", PostgresEventTables.CHRONICLE_USAGE_EVENTS.name,
            captureTimeSql = PostgresEventColumns.TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "usage-stats", PostgresEventTables.CHRONICLE_USAGE_STATS.name,
            captureTimeSql = PostgresEventColumns.TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "ios-screen-time-usage", ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.name,
            captureTimeSql = ScreenTimeUsageCaptures.CAPTURE_TIME_SQL,
        ),
        ParticipantDataAsset(
            "android-sensor", ChroniclePostgresTables.ANDROID_SENSOR_DATA.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "app-usage-survey", ChroniclePostgresTables.APP_USAGE_SURVEY.name,
            captureTimeSql = PostgresColumns.SUBMISSION_DATE.name,
        ),
        ParticipantDataAsset(
            "questionnaire-submissions", ChroniclePostgresTables.QUESTIONNAIRE_SUBMISSIONS.name,
            captureTimeSql = PostgresColumns.COMPLETED_AT.name,
        ),
        ParticipantDataAsset(
            "tud-submissions", ChroniclePostgresTables.TIME_USE_DIARY_SUBMISSIONS.name,
            captureTimeSql = PostgresColumns.SUBMISSION_DATE.name,
        ),
        ParticipantDataAsset("participant-stats", ChroniclePostgresTables.PARTICIPANT_STATS.name),
        ParticipantDataAsset("upload-buffer", ChroniclePostgresTables.UPLOAD_BUFFER.name),
        ParticipantDataAsset(
            "battery-telemetry", ChroniclePostgresTables.BATTERY_TELEMETRY.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "interaction-events", ChroniclePostgresTables.INTERACTION_EVENTS.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "app-audio-activity", ChroniclePostgresTables.APP_AUDIO_ACTIVITY.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "app-audio-content", ChroniclePostgresTables.APP_AUDIO_CONTENT.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "notification-activity", ChroniclePostgresTables.NOTIFICATION_ACTIVITY.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "sleep-events", ChroniclePostgresTables.SLEEP_EVENTS.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "activity-recognition", ChroniclePostgresTables.ACTIVITY_RECOGNITION_EVENTS.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "health-metrics", ChroniclePostgresTables.HEALTH_METRICS.name,
            captureTimeSql = "to_timestamp(${PostgresColumns.HEALTH_END_MILLIS.name} / 1000.0)",
        ),
        ParticipantDataAsset(
            "connectivity-state", ChroniclePostgresTables.CONNECTIVITY_STATE_EVENTS.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "app-network-usage", ChroniclePostgresTables.APP_NETWORK_USAGE.name,
            captureTimeSql = "to_timestamp(${PostgresColumns.APP_NETWORK_BUCKET_END_MILLIS.name} / 1000.0)",
        ),
        ParticipantDataAsset(
            "device-settings", ChroniclePostgresTables.DEVICE_SETTINGS.name,
            captureTimeSql = PostgresColumns.SAMPLE_TIMESTAMP.name,
        ),
        ParticipantDataAsset(
            "upload-diagnostics", "upload_diagnostics",
            captureTimeSql = "last_occurred_at",
        ),
        ParticipantDataAsset("tud-summarized", ChroniclePostgresTables.TIME_USE_DIARY_SUMMARIZED.name),
        ParticipantDataAsset(
            "android-device-sensor-availability", ChroniclePostgresTables.ANDROID_DEVICE_SENSOR_AVAILABILITY.name,
            captureTimeSql = PostgresColumns.REPORTED_AT.name,
        ),
        ParticipantDataAsset("participant-form-receipts", "participant_form_submission_receipts", processingData = false),
        ParticipantDataAsset("participant-form-sessions", "participant_form_sessions", processingData = false),
        ParticipantDataAsset("notifications", ChroniclePostgresTables.NOTIFICATIONS.name, processingData = false),
        ParticipantDataAsset("webhook-deliveries", "webhook_deliveries", processingData = false),
        ParticipantDataAsset("jobs", ChroniclePostgresTables.JOBS.name, ParticipantScope.TEXT_ARRAY_COLUMN, processingData = false),
        // Keep access codes last: deleting one cascades sessions and receipts, which would
        // otherwise make their independently verified step counts inaccurate.
        ParticipantDataAsset("participant-form-access-codes", "participant_form_access_codes", processingData = false),
    )

    /** Enrollment metadata and replay fences survive collected-data purge, then leave with enrollment. */
    public val withdrawalAssets: List<ParticipantDataAsset> = listOf(
        ParticipantDataAsset("devices", ChroniclePostgresTables.DEVICES.name),
        ParticipantDataAsset("purge-cutoffs", "participant_purge_cutoffs", ParticipantScope.BLOCK_TOKEN_COLUMN),
    )

    internal fun assetsFor(mode: DataDeletionMode): List<ParticipantDataAsset> = when (mode) {
        DataDeletionMode.WITHDRAW_AND_ERASE -> participantAssets + withdrawalAssets
        // A purge keeps the participant enrolled, so it keeps the enrollment evidence too.
        DataDeletionMode.COLLECTED_DATA_PURGE -> participantAssets.map {
            if (it.tableName == "participant_form_access_codes") {
                it.copy(participantScope = ParticipantScope.NON_ENROLLMENT_EVIDENCE_CODES)
            } else {
                it
            }
        }
        DataDeletionMode.STUDY_ERASURE -> participantAssets
    }

    init {
        check(participantAssets.map { it.id }.distinct().size == participantAssets.size) {
            "Participant data-asset IDs must be unique"
        }
        check(participantAssets.map { it.tableName }.distinct().size == participantAssets.size) {
            "Participant data-asset table names must be unique"
        }
        check(participantAssets.all { SQL_IDENTIFIER.matches(it.tableName) }) {
            "Participant data-asset table names must be trusted SQL identifiers"
        }
    }
}
