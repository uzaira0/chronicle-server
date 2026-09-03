package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.services.jobs.ChronicleStudyJobDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.AMBIENT_AUDIO_EVENTS as AMBIENT_AUDIO_EVENTS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_SENSOR_DATA as ANDROID_SENSOR_DATA_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_AUDIO_ACTIVITY as APP_AUDIO_ACTIVITY_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_AUDIO_CONTENT as APP_AUDIO_CONTENT_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY as APP_USAGE_SURVEY_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.BATTERY_TELEMETRY as BATTERY_TELEMETRY_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.INTERACTION_EVENTS as INTERACTION_EVENTS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATION_ACTIVITY as NOTIFICATION_ACTIVITY_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.SLEEP_EVENTS as SLEEP_EVENTS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ACTIVITY_RECOGNITION_EVENTS as ACTIVITY_RECOGNITION_EVENTS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.HEALTH_METRICS as HEALTH_METRICS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.CONNECTIVITY_STATE_EVENTS as CONNECTIVITY_STATE_EVENTS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_NETWORK_USAGE as APP_NETWORK_USAGE_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.DEVICE_SETTINGS as DEVICE_SETTINGS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_STATS as PARTICIPANT_STATS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRE_SUBMISSIONS as QUESTIONNAIRE_SUBMISSIONS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.TIME_USE_DIARY_SUBMISSIONS as TIME_USE_DIARY_SUBMISSIONS_TABLE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER as UPLOAD_BUFFER_TABLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID as POSTGRES_STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID as POSTGRES_EVENT_STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_STATS
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.IOS_SENSOR_DATA as IOS_SENSOR_DATA_TABLE
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.PREPROCESSED_USAGE_EVENTS as PREPROCESSED_USAGE_EVENTS_TABLE
import java.util.UUID

public open class DeleteStudyTableData(
    override val studyId: UUID,
    public val tables: Set<StudyDeletionTable>,
    public val eventDataSourceName: String? = null,
) : ChronicleStudyJobDefinition

public enum class StudyDeletionStorage {
    EVENT,
    PLATFORM,
}

public enum class StudyDeletionTable(
    public val tableName: String,
    public val studyIdColumnName: String,
    public val storage: StudyDeletionStorage,
    public val auditEventType: AuditEventType,
) {
    USAGE_EVENTS(
        CHRONICLE_USAGE_EVENTS.name,
        POSTGRES_EVENT_STUDY_ID.name,
        StudyDeletionStorage.EVENT,
        AuditEventType.BACKGROUND_USAGE_DATA_DELETION,
    ),
    USAGE_STATS(
        CHRONICLE_USAGE_STATS.name,
        POSTGRES_EVENT_STUDY_ID.name,
        StudyDeletionStorage.EVENT,
        AuditEventType.BACKGROUND_USAGE_STATS_DATA_DELETION,
    ),
    PREPROCESSED_USAGE_EVENTS(
        PREPROCESSED_USAGE_EVENTS_TABLE.name,
        POSTGRES_EVENT_STUDY_ID.name,
        StudyDeletionStorage.EVENT,
        AuditEventType.BACKGROUND_PREPROCESSED_USAGE_DATA_DELETION,
    ),
    IOS_SENSOR_DATA(
        IOS_SENSOR_DATA_TABLE.name,
        POSTGRES_EVENT_STUDY_ID.name,
        StudyDeletionStorage.EVENT,
        AuditEventType.BACKGROUND_SENSOR_DATA_DELETION,
    ),
    ANDROID_SENSOR_DATA(
        ANDROID_SENSOR_DATA_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_ANDROID_SENSOR_DATA_DELETION,
    ),
    BATTERY_TELEMETRY(
        BATTERY_TELEMETRY_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_BATTERY_TELEMETRY_DATA_DELETION,
    ),
    INTERACTION_EVENTS(
        INTERACTION_EVENTS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_INTERACTION_EVENTS_DATA_DELETION,
    ),
    APP_AUDIO_ACTIVITY(
        APP_AUDIO_ACTIVITY_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_APP_AUDIO_ACTIVITY_DATA_DELETION,
    ),
    APP_AUDIO_CONTENT(
        APP_AUDIO_CONTENT_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_APP_AUDIO_CONTENT_DATA_DELETION,
    ),
    AMBIENT_AUDIO_EVENTS(
        AMBIENT_AUDIO_EVENTS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_AMBIENT_AUDIO_DATA_DELETION,
    ),
    NOTIFICATION_ACTIVITY(
        NOTIFICATION_ACTIVITY_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_NOTIFICATION_ACTIVITY_DATA_DELETION,
    ),
    SLEEP_EVENTS(
        SLEEP_EVENTS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_SLEEP_EVENTS_DATA_DELETION,
    ),
    ACTIVITY_RECOGNITION_EVENTS(
        ACTIVITY_RECOGNITION_EVENTS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_ACTIVITY_RECOGNITION_EVENTS_DATA_DELETION,
    ),
    HEALTH_METRICS(
        HEALTH_METRICS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_HEALTH_METRICS_DATA_DELETION,
    ),
    CONNECTIVITY_STATE_EVENTS(
        CONNECTIVITY_STATE_EVENTS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_CONNECTIVITY_STATE_EVENTS_DATA_DELETION,
    ),
    APP_NETWORK_USAGE(
        APP_NETWORK_USAGE_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_APP_NETWORK_USAGE_DATA_DELETION,
    ),
    DEVICE_SETTINGS(
        DEVICE_SETTINGS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_DEVICE_SETTINGS_DATA_DELETION,
    ),
    UPLOAD_DIAGNOSTICS(
        "upload_diagnostics",
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_UPLOAD_DIAGNOSTICS_DATA_DELETION,
    ),
    APP_USAGE_SURVEY(
        APP_USAGE_SURVEY_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_APP_USAGE_SURVEY_DATA_DELETION,
    ),
    QUESTIONNAIRE_SUBMISSIONS(
        QUESTIONNAIRE_SUBMISSIONS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_QUESTIONNAIRE_SUBMISSION_DATA_DELETION,
    ),
    TIME_USE_DIARY_SUBMISSIONS(
        TIME_USE_DIARY_SUBMISSIONS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_TUD_DATA_DELETION,
    ),
    PARTICIPANT_STATS(
        PARTICIPANT_STATS_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_PARTICIPANT_STATS_DELETION,
    ),
    UPLOAD_BUFFER(
        UPLOAD_BUFFER_TABLE.name,
        POSTGRES_STUDY_ID.name,
        StudyDeletionStorage.PLATFORM,
        AuditEventType.BACKGROUND_UPLOAD_BUFFER_DELETION,
    ),
    ;

    public companion object {
        public fun dataTableNames(): List<String> = values().map { it.tableName }
    }
}
