package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresEventColumns
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
    val handledByDedicatedJob: Boolean,
    val participantScope: ParticipantScope = ParticipantScope.SCALAR_COLUMN,
)

public enum class ParticipantScope(internal val predicate: String) {
    SCALAR_COLUMN("participant_id = ?"),
    TEXT_ARRAY_COLUMN("? = ANY(participant_ids)"),
    BLOCK_TOKEN_COLUMN("participant_block_token = chronicle_participant_block_token(study_id, ?::text)"),

    /** Access codes minus the exchanged enrollment code a still-enrolled device acknowledges against. */
    NON_ENROLLMENT_EVIDENCE_CODES("participant_id = ? AND NOT $EXCHANGED_ENROLLMENT_CODE"),
}

/** The device's enrollment evidence: collection acknowledgments require this row unrevoked. */
internal const val EXCHANGED_ENROLLMENT_CODE = "(form_kind = 'ENROLLMENT' AND exchanged_at IS NOT NULL)"

public object ChronicleDataAssetRegistry {
    private val SQL_IDENTIFIER = Regex("^[a-z][a-z0-9_]*$")

    public val participantAssets: List<ParticipantDataAsset> = listOf(
        ParticipantDataAsset("usage-events", "chronicle_usage_events", true),
        ParticipantDataAsset("usage-stats", "chronicle_usage_stats", true),
        ParticipantDataAsset("preprocessed-usage", "preprocessed_usage_events", true),
        ParticipantDataAsset("ios-sensor", "sensor_data", true),
        ParticipantDataAsset("android-sensor", "android_sensor_data", true),
        ParticipantDataAsset("app-usage-survey", "app_usage_survey", true),
        ParticipantDataAsset("questionnaire-submissions", "questionnaire_submissions", true),
        ParticipantDataAsset("tud-submissions", "time_use_diary_submissions", true),
        ParticipantDataAsset("participant-stats", "participant_stats", true),
        ParticipantDataAsset("upload-buffer", "upload_buffer", true),
        ParticipantDataAsset("battery-telemetry", "battery_telemetry", true),
        ParticipantDataAsset("interaction-events", "interaction_events", true),
        ParticipantDataAsset("app-audio-activity", "app_audio_activity", true),
        ParticipantDataAsset("app-audio-content", "app_audio_content", true),
        ParticipantDataAsset("ambient-audio", "ambient_audio_events", true),
        ParticipantDataAsset("notification-activity", "notification_activity", true),
        ParticipantDataAsset("sleep-events", "sleep_events", true),
        ParticipantDataAsset("activity-recognition", "activity_recognition_events", true),
        ParticipantDataAsset("health-metrics", "health_metrics", true),
        ParticipantDataAsset("connectivity-state", "connectivity_state_events", true),
        ParticipantDataAsset("app-network-usage", "app_network_usage", true),
        ParticipantDataAsset("device-settings", "device_settings", true),
        ParticipantDataAsset("upload-diagnostics", "upload_diagnostics", false),
        ParticipantDataAsset("encrypted-payloads", "encrypted_payloads", false),
        ParticipantDataAsset("data-quality-alerts", "data_quality_alerts", false),
        ParticipantDataAsset("tud-summarized", "time_use_diary_summarized", false),
        ParticipantDataAsset("study-event-stream", "study_event_stream", false),
        ParticipantDataAsset("android-device-sensor-availability", "android_device_sensor_availability", false),
        ParticipantDataAsset("participant-form-receipts", "participant_form_submission_receipts", false),
        ParticipantDataAsset("participant-form-sessions", "participant_form_sessions", false),
        ParticipantDataAsset("usage-event-annotations", "usage_event_annotations", false),
        ParticipantDataAsset("participant-pseudonyms", "participant_pseudonyms", false),
        ParticipantDataAsset("notifications", "notifications", false),
        ParticipantDataAsset("webhook-deliveries", "webhook_deliveries", false),
        ParticipantDataAsset("jobs", "jobs", false, ParticipantScope.TEXT_ARRAY_COLUMN),
        // Keep access codes last: deleting one cascades sessions and receipts, which would
        // otherwise make their independently verified step counts inaccurate.
        ParticipantDataAsset("participant-form-access-codes", "participant_form_access_codes", false),
    )

    /** Enrollment metadata and replay fences survive collected-data purge, then leave with enrollment. */
    public val withdrawalAssets: List<ParticipantDataAsset> = listOf(
        ParticipantDataAsset("devices", "devices", false),
        ParticipantDataAsset("purge-cutoffs", "participant_purge_cutoffs", false, ParticipantScope.BLOCK_TOKEN_COLUMN),
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

    public fun participantAsset(assetId: String): ParticipantDataAsset =
        participantAssets.singleOrNull { it.id == assetId }
            ?: throw IllegalArgumentException("Unknown participant data asset")

    /** Observation times match upload admission; receipt time cannot preserve an undated capture. */
    internal fun purgeCaptureTimeSql(asset: ParticipantDataAsset): String? = when (asset.tableName) {
        "chronicle_usage_events" -> "COALESCE(${PostgresEventColumns.TIMESTAMP.name}, ${PostgresEventColumns.COLLECTED_AT.name})"
        "chronicle_usage_stats", "usage_event_annotations" -> PostgresEventColumns.TIMESTAMP.name
        "preprocessed_usage_events" -> PostgresEventColumns.APP_DATETIME_END.name
        "sensor_data" -> "LEAST(COALESCE(${PostgresEventColumns.EXACT_RECORDED_DATE_TIME.name}, ${PostgresEventColumns.RECORDED_DATE_TIME.name}), " +
            "CASE WHEN ${PostgresEventColumns.SENSOR_TYPE.name} = '${SensorType.deviceUsage.name}' THEN ${PostgresEventColumns.END_DATE_TIME.name} END)"
        "android_sensor_data", "battery_telemetry", "interaction_events", "app_audio_activity",
        "app_audio_content", "ambient_audio_events", "notification_activity", "sleep_events",
        "activity_recognition_events", "connectivity_state_events", "device_settings" ->
            PostgresColumns.SAMPLE_TIMESTAMP.name
        "health_metrics" -> "to_timestamp(${PostgresColumns.HEALTH_END_MILLIS.name} / 1000.0)"
        "app_network_usage" -> "to_timestamp(${PostgresColumns.APP_NETWORK_BUCKET_END_MILLIS.name} / 1000.0)"
        "app_usage_survey", "time_use_diary_submissions" -> PostgresColumns.SUBMISSION_DATE.name
        "questionnaire_submissions" -> PostgresColumns.COMPLETED_AT.name
        "android_device_sensor_availability" -> PostgresColumns.REPORTED_AT.name
        "upload_diagnostics" -> "last_occurred_at"
        // Aggregates and access artifacts have no single observation time; ciphertext is undated.
        else -> null
    }
}
