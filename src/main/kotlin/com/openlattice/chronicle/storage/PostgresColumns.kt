package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Modifier


/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PostgresColumns private constructor() {
    internal companion object {
        private val logger = LoggerFactory.getLogger(PostgresColumns::class.java)
        @JvmField val ACL_KEY = PostgresColumnDefinition("acl_key", PostgresDatatype.UUID_ARRAY)
        @JvmField val ACTIVE = PostgresColumnDefinition("active", PostgresDatatype.BOOLEAN)
        @JvmField val ANDROID_UNIQUE_DATES = PostgresColumnDefinition("android_unique_dates", PostgresDatatype.DATE_ARRAY).withDefault("'{}'")
        @JvmField val ANDROID_FIRST_DATE = PostgresColumnDefinition("android_first_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val ANDROID_LAST_PING = PostgresColumnDefinition("android_last_ping", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val ANDROID_LAST_DATE = PostgresColumnDefinition("android_last_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val APP_USERS = PostgresColumnDefinition("users", PostgresDatatype.TEXT_ARRAY)
        @JvmField val BASE = PostgresColumnDefinition("base", PostgresDatatype.BIGINT).notNull()
        @JvmField val CANDIDATE_ID = PostgresColumnDefinition("candidate_id", PostgresDatatype.UUID).notNull()
        @JvmField val CATEGORY = PostgresColumnDefinition("category", PostgresDatatype.TEXT).notNull()
        @JvmField val CHANGED_BY = PostgresColumnDefinition("changed_by", PostgresDatatype.TEXT).notNull()
        @JvmField val COMPLETED_AT = PostgresColumnDefinition("completed_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("'infinity'")
        @JvmField val CONTACT = PostgresColumnDefinition("contact", PostgresDatatype.TEXT)
        @JvmField val CREATED_AT = PostgresColumnDefinition("created_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val DATA_EXPIRES = PostgresColumnDefinition("data_expires", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val DATE_OF_BIRTH = PostgresColumnDefinition("dob", PostgresDatatype.DATE)
        @JvmField val DATA_RETENTION = PostgresColumnDefinition("data_retention", PostgresDatatype.JSONB).notNull()
        @JvmField val DELETED_ROWS = PostgresColumnDefinition("deleted_rows", PostgresDatatype.BIGINT).notNull()
        @JvmField val DELETION_OPERATION_ID = PostgresColumnDefinition("operation_id", PostgresDatatype.UUID)
        @JvmField val DELETION_PREVIOUS_STATUS = PostgresColumnDefinition("previous_status", PostgresDatatype.TEXT)
        @JvmField val DESCRIPTION = PostgresColumnDefinition("description", PostgresDatatype.TEXT)
        @JvmField val DEVICE_ID = PostgresColumnDefinition("device_id", PostgresDatatype.UUID).notNull()
        @Deprecated("device_token will be removed in a future migration")
        @JvmField val DEVICE_TOKEN = PostgresColumnDefinition("device_token", PostgresDatatype.TEXT)
        @JvmField val DEVICE_TYPE = PostgresColumnDefinition("device_type", PostgresDatatype.TEXT)
        @JvmField val DELETE_AFTER = PostgresColumnDefinition("delete_after", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val EMAIL = PostgresColumnDefinition("email", PostgresDatatype.TEXT).unique()
        @JvmField val EMAIL_NOT_UNIQUE = PostgresColumnDefinition("email", PostgresDatatype.TEXT)
        @JvmField val EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.UUID).notNull()
        @JvmField val ENDED_AT = PostgresColumnDefinition("ended_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("'infinity'")
        @JvmField val EXPIRATION = PostgresColumnDefinition("expiration", PostgresDatatype.BIGINT)
        @JvmField val EXPIRATION_DATE = PostgresColumnDefinition("expiration_date", PostgresDatatype.TIMESTAMPTZ).withDefault("'infinity'").notNull()
        @JvmField val FIRST_NAME = PostgresColumnDefinition("first_name", PostgresDatatype.TEXT)
        @JvmField val FEATURES = PostgresColumnDefinition("features", PostgresDatatype.TEXT_ARRAY).notNull().withDefault("'{}'")
        @JvmField val IOS_UNIQUE_DATES = PostgresColumnDefinition("ios_unique_dates", PostgresDatatype.DATE_ARRAY).withDefault("'{}'")
        @JvmField val IOS_FIRST_DATE = PostgresColumnDefinition("ios_first_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val IOS_LAST_DATE = PostgresColumnDefinition("ios_last_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val IOS_LAST_PING = PostgresColumnDefinition("ios_last_ping", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val JOB_DEFINITION = PostgresColumnDefinition("definition", PostgresDatatype.JSONB).withDefault("'{}'::jsonb")
        @JvmField val JOB_ID = PostgresColumnDefinition("job_id", PostgresDatatype.UUID).notNull()
        @JvmField val LAST_NAME = PostgresColumnDefinition("last_name", PostgresDatatype.TEXT)
        @JvmField val LAT = PostgresColumnDefinition("lat", PostgresDatatype.DOUBLE)
        @JvmField val LEGACY_STUDY_ID = PostgresColumnDefinition("legacy_study_id", PostgresDatatype.UUID).notNull()
        @JvmField val LIFECYCLE_STATUS = PostgresColumnDefinition("lifecycle_status", PostgresDatatype.TEXT).notNull().withDefault("'ACTIVE'")
        @JvmField val LON = PostgresColumnDefinition("lon", PostgresDatatype.DOUBLE)
        @JvmField val LSB = PostgresColumnDefinition("lsb", PostgresDatatype.BIGINT).notNull()
        @JvmField val MESSAGE = PostgresColumnDefinition("message", PostgresDatatype.TEXT)
        @JvmField val MSB = PostgresColumnDefinition("msb", PostgresDatatype.BIGINT).notNull()
        @JvmField val NAME = PostgresColumnDefinition("name", PostgresDatatype.TEXT)
        @JvmField val NEW_STATUS = PostgresColumnDefinition("new_status", PostgresDatatype.TEXT).notNull()
        @JvmField val NOTIFICATIONS_ENABLED = PostgresColumnDefinition("notifications_enabled", PostgresDatatype.BOOLEAN)
        @JvmField val ORGANIZATION_ID = PostgresColumnDefinition("organization_id", PostgresDatatype.UUID).notNull()
        @JvmField val ORGANIZATION_IDS = PostgresColumnDefinition("organization_ids", PostgresDatatype.UUID_ARRAY).notNull()
        @JvmField val PARTICIPANT_LIMIT = PostgresColumnDefinition("participant_limit", PostgresDatatype.INTEGER).notNull()
        @JvmField val PARTICIPANT_ID = PostgresColumnDefinition("participant_id", PostgresDatatype.TEXT).notNull()
        @JvmField val PARTICIPATION_STATUS = PostgresColumnDefinition("participation_status", PostgresDatatype.TEXT).notNull()
        @JvmField val PARTITION_INDEX = PostgresColumnDefinition("partition_index", PostgresDatatype.BIGINT).notNull()
        @JvmField val PERMISSION = PostgresColumnDefinition("permission", PostgresDatatype.TEXT)
        @JvmField val PERMISSIONS = PostgresColumnDefinition("permissions", PostgresDatatype.TEXT_ARRAY)
        @JvmField val PHONE_NUMBER = PostgresColumnDefinition("phone_number", PostgresDatatype.TEXT).unique()
        @JvmField val STUDY_PHONE_NUMBER = PostgresColumnDefinition("study_phone_number", PostgresDatatype.TEXT)
        @JvmField val PHONE_NUMBER_NOT_UNIQUE = PostgresColumnDefinition("phone_number", PostgresDatatype.TEXT)
        @JvmField val PREVIOUS_STATUS = PostgresColumnDefinition("previous_status", PostgresDatatype.TEXT).notNull()
        @JvmField val PRINCIPAL_ID = PostgresColumnDefinition("principal_id", PostgresDatatype.TEXT)
        @JvmField val PRINCIPAL_OF_ACL_KEY = PostgresColumnDefinition("principal_of_acl_key", PostgresDatatype.UUID_ARRAY)
        @JvmField val PRINCIPAL_TYPE = PostgresColumnDefinition("principal_type", PostgresDatatype.TEXT)
        @JvmField val QUESTIONNAIRE_ID = PostgresColumnDefinition("questionnaire_id", PostgresDatatype.UUID).notNull()
        @JvmField val QUESTIONS = PostgresColumnDefinition("questions", PostgresDatatype.JSONB).notNull()
        @JvmField val QUESTION_TITLE = PostgresColumnDefinition("question_title", PostgresDatatype.TEXT).notNull()
        @JvmField val REASON = PostgresColumnDefinition("reason", PostgresDatatype.TEXT).withDefault("''")
        @JvmField val RECURRENCE_RULE = PostgresColumnDefinition("recurrence_rule", PostgresDatatype.TEXT)
        @JvmField val RESPONSES = PostgresColumnDefinition("response", PostgresDatatype.TEXT_ARRAY)
        @JvmField val SCOPE = PostgresColumnDefinition("scope", PostgresDatatype.TEXT).notNull()
        @JvmField val SECURABLE_OBJECT_ID = PostgresColumnDefinition("id", PostgresDatatype.UUID).unique().notNull()
        @JvmField val SECURABLE_OBJECT_NAME = PostgresColumnDefinition("name", PostgresDatatype.TEXT).notNull().unique()
        @JvmField val SECURABLE_OBJECT_TYPE = PostgresColumnDefinition("securable_object_type", PostgresDatatype.TEXT).notNull()
        @JvmField val SECURABLE_PRINCIPAL_ID = PostgresColumnDefinition("securable_principal_id", PostgresDatatype.UUID).notNull()
        @JvmField val SETTINGS = PostgresColumnDefinition("settings", PostgresDatatype.JSONB).withDefault("'{}'::jsonb")
        @JvmField val MODULES = PostgresColumnDefinition("modules", PostgresDatatype.JSONB).withDefault("'{}'::jsonb")
        @JvmField val SOURCE_DEVICE = PostgresColumnDefinition("source_device", PostgresDatatype.JSONB).notNull()
        @JvmField val STARTED_AT = PostgresColumnDefinition("started_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val STATUS = PostgresColumnDefinition("status", PostgresDatatype.TEXT)
        @JvmField val STORAGE = PostgresColumnDefinition("storage", PostgresDatatype.TEXT).notNull().withDefault("'default'")
        @JvmField val STUDY_DURATION = PostgresColumnDefinition("study_duration", PostgresDatatype.JSONB).notNull()
        @JvmField val STUDY_ENDS = PostgresColumnDefinition("study_ends", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val STUDY_GROUP = PostgresColumnDefinition("study_group", PostgresDatatype.TEXT)
        @JvmField val STUDY_ID = PostgresColumnDefinition("study_id", PostgresDatatype.UUID).notNull()
        @JvmField val STUDY_VERSION = PostgresColumnDefinition("study_version", PostgresDatatype.TEXT)
        @JvmField val SUBMISSION = PostgresColumnDefinition("submission", PostgresDatatype.JSONB)
        @JvmField val SUBMISSION_DATE = PostgresColumnDefinition("submission_date", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("NOW()")
        @JvmField val SUBMISSION_ID = PostgresColumnDefinition("submission_id", PostgresDatatype.UUID).notNull()
        @JvmField val SUMMARY_DATA = PostgresColumnDefinition("summary_data", PostgresDatatype.JSONB).notNull()
        @JvmField val TITLE = PostgresColumnDefinition("title", PostgresDatatype.TEXT)
        @JvmField val TUD_UNIQUE_DATES = PostgresColumnDefinition("tud_unique_dates", PostgresDatatype.DATE_ARRAY).withDefault("'{}'")
        @JvmField val TUD_FIRST_DATE = PostgresColumnDefinition("tud_first_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val TUD_LAST_DATE = PostgresColumnDefinition("tud_last_date", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val UPDATED_AT = PostgresColumnDefinition("updated_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val URL = PostgresColumnDefinition("url", PostgresDatatype.TEXT)
        @JvmField val USER_DATA = PostgresColumnDefinition("user_data", PostgresDatatype.JSONB)
        @JvmField val USER_ID = PostgresColumnDefinition("user_id", PostgresDatatype.TEXT).notNull()
        @JvmField val UPGRADE_CLASS = PostgresColumnDefinition("upgrade_class", PostgresDatatype.TEXT).notNull()
        @JvmField val UPGRADE_STATUS = PostgresColumnDefinition("upgrade_status", PostgresDatatype.TEXT)
            .notNull().withDefault("'Registered'") // historical ledger; enum retired with the upgrade framework
        @JvmField val LAST_UPDATE = PostgresColumnDefinition("last_update",PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val UPLOAD_DATA = PostgresColumnDefinition("data", PostgresDatatype.JSONB).notNull()
        @JvmField val UPLOADED_AT = PostgresColumnDefinition("uploaded_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val UPLOAD_TYPE = PostgresColumnDefinition("upload_type", PostgresDatatype.TEXT).notNull()

        @JvmField val SCHEDULED_BY = PostgresColumnDefinition("scheduled_by", PostgresDatatype.TEXT).notNull()
        @JvmField val SENSOR_TYPE = PostgresColumnDefinition("sensor_type", PostgresDatatype.TEXT).notNull()
        @JvmField val SAMPLE_ID = PostgresColumnDefinition("sample_id", PostgresDatatype.UUID).notNull()
        @JvmField val SAMPLE_TIMESTAMP = PostgresColumnDefinition("sample_timestamp", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val SENSOR_X = PostgresColumnDefinition("x", PostgresDatatype.REAL)
        @JvmField val SENSOR_Y = PostgresColumnDefinition("y", PostgresDatatype.REAL)
        @JvmField val SENSOR_Z = PostgresColumnDefinition("z", PostgresDatatype.REAL)
        @JvmField val SENSOR_W = PostgresColumnDefinition("w", PostgresDatatype.REAL)
        @JvmField val SENSOR_VALUES = PostgresColumnDefinition("values", PostgresDatatype.JSONB).notNull().withDefault("'[]'::jsonb")
        @JvmField val SENSOR_ACCURACY = PostgresColumnDefinition("accuracy", PostgresDatatype.INTEGER)
        @JvmField val SENSOR_TIMEZONE = PostgresColumnDefinition("timezone", PostgresDatatype.TEXT).notNull()

        // Battery telemetry columns (battery_telemetry collection module). A BatterySample
        // carries a free-form String id (not necessarily a UUID), so BATTERY_SAMPLE_ID is TEXT.
        @JvmField val BATTERY_SAMPLE_ID = PostgresColumnDefinition("sample_id", PostgresDatatype.TEXT).notNull()
        @JvmField val BATTERY_LEVEL_PERCENT = PostgresColumnDefinition("level_percent", PostgresDatatype.INTEGER).notNull()
        @JvmField val BATTERY_CHARGING_STATE = PostgresColumnDefinition("charging_state", PostgresDatatype.TEXT).notNull()

        // Android-hardware battery fields are nullable (V64): iOS has no plug type,
        // health, temperature, or voltage — iOS rows store NULL, never fabricated values.
        @JvmField val BATTERY_PLUG_TYPE = PostgresColumnDefinition("plug_type", PostgresDatatype.TEXT)
        @JvmField val BATTERY_HEALTH = PostgresColumnDefinition("health", PostgresDatatype.TEXT)
        @JvmField val BATTERY_TEMPERATURE_DECI_C = PostgresColumnDefinition("temperature_deci_c", PostgresDatatype.INTEGER)
        @JvmField val BATTERY_VOLTAGE_MILLIVOLTS = PostgresColumnDefinition("voltage_millivolts", PostgresDatatype.INTEGER)
        @JvmField val BATTERY_LOW_POWER_MODE = PostgresColumnDefinition("low_power_mode", PostgresDatatype.BOOLEAN)

        // Interaction-events columns (interaction_events collection module). An
        // AndroidInteractionEvent carries a free-form String id, so INTERACTION_EVENT_ID is TEXT.
        // Content-free by construction: element_role is the view class name, never element text.
        // scroll_delta_x/y are nullable (populated only for SCROLL events; may be unknown).
        // Node bounds + provenance are authoritative. Center/normalized/grid values are retained
        // as compatibility derivations and are not pointer coordinates.
        @JvmField val INTERACTION_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val INTERACTION_EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.TEXT).notNull()
        @JvmField val INTERACTION_GRID_ROWS = PostgresColumnDefinition("grid_rows", PostgresDatatype.INTEGER).notNull()
        @JvmField val INTERACTION_GRID_COLS = PostgresColumnDefinition("grid_cols", PostgresDatatype.INTEGER).notNull()
        @JvmField val INTERACTION_GRID_ROW = PostgresColumnDefinition("grid_row", PostgresDatatype.INTEGER).notNull()
        @JvmField val INTERACTION_GRID_COL = PostgresColumnDefinition("grid_col", PostgresDatatype.INTEGER).notNull()
        @JvmField val INTERACTION_ELEMENT_ROLE = PostgresColumnDefinition("element_role", PostgresDatatype.TEXT).notNull()
        @JvmField val INTERACTION_FOREGROUND_PACKAGE = PostgresColumnDefinition("foreground_package", PostgresDatatype.TEXT).notNull()
        @JvmField val INTERACTION_POSITION_SOURCE = PostgresColumnDefinition("position_source", PostgresDatatype.TEXT)
        @JvmField val INTERACTION_NODE_BOUNDS_LEFT = PostgresColumnDefinition("node_bounds_left", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_NODE_BOUNDS_TOP = PostgresColumnDefinition("node_bounds_top", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_NODE_BOUNDS_RIGHT = PostgresColumnDefinition("node_bounds_right", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_NODE_BOUNDS_BOTTOM = PostgresColumnDefinition("node_bounds_bottom", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_DISPLAY_ID = PostgresColumnDefinition("display_id", PostgresDatatype.INTEGER)
        // Legacy derived element-center coordinates.
        @JvmField val INTERACTION_RAW_X = PostgresColumnDefinition("raw_x", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_RAW_Y = PostgresColumnDefinition("raw_y", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_SCREEN_WIDTH = PostgresColumnDefinition("screen_width", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_SCREEN_HEIGHT = PostgresColumnDefinition("screen_height", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_NORMALIZED_X = PostgresColumnDefinition("normalized_x", PostgresDatatype.DOUBLE)
        @JvmField val INTERACTION_NORMALIZED_Y = PostgresColumnDefinition("normalized_y", PostgresDatatype.DOUBLE)
        @JvmField val INTERACTION_SCROLL_DELTA_X = PostgresColumnDefinition("scroll_delta_x", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_SCROLL_DELTA_Y = PostgresColumnDefinition("scroll_delta_y", PostgresDatatype.INTEGER)
        // Salience kinematics + context. event_time_millis is the monotonic uptime clock
        // (ordering + kinematics basis); episode_id groups an interaction burst; dwell/velocity
        // are derived; orientation/density let raw pixels be interpreted spatially/physically.
        @JvmField val INTERACTION_EVENT_TIME_MILLIS = PostgresColumnDefinition("event_time_millis", PostgresDatatype.BIGINT)
        @JvmField val INTERACTION_EPISODE_ID = PostgresColumnDefinition("episode_id", PostgresDatatype.TEXT)
        @JvmField val INTERACTION_DWELL_MILLIS = PostgresColumnDefinition("dwell_millis_since_prev", PostgresDatatype.BIGINT)
        @JvmField val INTERACTION_ORIENTATION = PostgresColumnDefinition("orientation", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_SCREEN_DENSITY_DPI = PostgresColumnDefinition("screen_density_dpi", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_SCROLL_VELOCITY_X = PostgresColumnDefinition("scroll_velocity_x", PostgresDatatype.DOUBLE)
        @JvmField val INTERACTION_SCROLL_VELOCITY_Y = PostgresColumnDefinition("scroll_velocity_y", PostgresDatatype.DOUBLE)
        @JvmField val INTERACTION_SCROLL_REVERSED = PostgresColumnDefinition("scroll_reversed", PostgresDatatype.BOOLEAN)

        // Audio-activity columns (audio_activity collection module). An AndroidAudioActivityEvent
        // carries a free-form String id, so AUDIO_ACTIVITY_EVENT_ID is TEXT. BEHAVIORAL_METADATA-class,
        // mic-free by construction. Enums (event_type/content_type/playback_state/output_route/ringer_mode)
        // are stored as TEXT (the enum name). Tier-2-only fields are nullable (no listener grant).
        @JvmField val AUDIO_ACTIVITY_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val AUDIO_ACTIVITY_EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.TEXT).notNull()
        @JvmField val AUDIO_ACTIVITY_AUDIO_ACTIVE = PostgresColumnDefinition("audio_active", PostgresDatatype.BOOLEAN).notNull()
        @JvmField val AUDIO_ACTIVITY_AUDIO_PACKAGE = PostgresColumnDefinition("audio_package", PostgresDatatype.TEXT)
        @JvmField val AUDIO_ACTIVITY_CONTENT_TYPE = PostgresColumnDefinition("content_type", PostgresDatatype.TEXT)
        @JvmField val AUDIO_ACTIVITY_PLAYBACK_STATE = PostgresColumnDefinition("playback_state", PostgresDatatype.TEXT)
        @JvmField val AUDIO_ACTIVITY_OUTPUT_ROUTE = PostgresColumnDefinition("output_route", PostgresDatatype.TEXT)
        @JvmField val AUDIO_ACTIVITY_ROUTE_CONNECTED = PostgresColumnDefinition("route_connected", PostgresDatatype.BOOLEAN)
        @JvmField val AUDIO_ACTIVITY_MEDIA_VOLUME = PostgresColumnDefinition("media_volume", PostgresDatatype.INTEGER)
        @JvmField val AUDIO_ACTIVITY_MAX_MEDIA_VOLUME = PostgresColumnDefinition("max_media_volume", PostgresDatatype.INTEGER)
        @JvmField val AUDIO_ACTIVITY_RINGER_MODE = PostgresColumnDefinition("ringer_mode", PostgresDatatype.TEXT)
        @JvmField val AUDIO_ACTIVITY_DND_ACTIVE = PostgresColumnDefinition("dnd_active", PostgresDatatype.BOOLEAN)
        @JvmField val AUDIO_ACTIVITY_CALL_ACTIVE = PostgresColumnDefinition("call_active", PostgresDatatype.BOOLEAN)

        // Ambient-audio columns (ambient_audio collection module — currently iOS SoundAnalysis).
        // AMBIENT_AUDIO_CONTEXT-class, labels-only by construction: one row per on-device
        // sound-classification label within a short listen window; the audio itself is discarded
        // at the classifier boundary and never reaches the server in any form.
        @JvmField val AMBIENT_AUDIO_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val AMBIENT_AUDIO_WINDOW_START_MILLIS = PostgresColumnDefinition("window_start_millis", PostgresDatatype.BIGINT).notNull()
        @JvmField val AMBIENT_AUDIO_WINDOW_END_MILLIS = PostgresColumnDefinition("window_end_millis", PostgresDatatype.BIGINT).notNull()
        @JvmField val AMBIENT_AUDIO_LABEL = PostgresColumnDefinition("label", PostgresDatatype.TEXT).notNull()
        @JvmField val AMBIENT_AUDIO_CONFIDENCE = PostgresColumnDefinition("confidence", PostgresDatatype.DOUBLE).notNull()
        @JvmField val AMBIENT_AUDIO_CLASSIFIER_VERSION = PostgresColumnDefinition("classifier_version", PostgresDatatype.TEXT)

        // Audio-content columns (audio_content collection module). An AndroidAudioContentEvent
        // carries a free-form String id, so AUDIO_CONTENT_EVENT_ID is TEXT. MEDIA_CONTENT-class:
        // title/artist/album are the producing app's published metadata (nullable). Duration/position
        // are media timing in milliseconds (BIGINT, nullable).
        @JvmField val AUDIO_CONTENT_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val AUDIO_CONTENT_AUDIO_PACKAGE = PostgresColumnDefinition("audio_package", PostgresDatatype.TEXT).notNull()
        @JvmField val AUDIO_CONTENT_TITLE = PostgresColumnDefinition("title", PostgresDatatype.TEXT)
        @JvmField val AUDIO_CONTENT_ARTIST = PostgresColumnDefinition("artist", PostgresDatatype.TEXT)
        @JvmField val AUDIO_CONTENT_ALBUM = PostgresColumnDefinition("album", PostgresDatatype.TEXT)
        @JvmField val AUDIO_CONTENT_DURATION_MILLIS = PostgresColumnDefinition("duration_millis", PostgresDatatype.BIGINT)
        @JvmField val AUDIO_CONTENT_POSITION_MILLIS = PostgresColumnDefinition("position_millis", PostgresDatatype.BIGINT)

        // Notification-activity columns (notification_activity collection module). An
        // AndroidNotificationActivityEvent carries a free-form String id, so
        // NOTIFICATION_ACTIVITY_EVENT_ID is TEXT. BEHAVIORAL_METADATA-class, content-free by
        // construction: category is a fixed Android constant (msg/call/email…), never message text.
        // event_type is the POSTED/REMOVED enum stored as TEXT.
        @JvmField val NOTIFICATION_ACTIVITY_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val NOTIFICATION_ACTIVITY_EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.TEXT).notNull()
        @JvmField val NOTIFICATION_ACTIVITY_PACKAGE_NAME = PostgresColumnDefinition("package_name", PostgresDatatype.TEXT).notNull()
        @JvmField val NOTIFICATION_ACTIVITY_CATEGORY = PostgresColumnDefinition("category", PostgresDatatype.TEXT)
        @JvmField val NOTIFICATION_ACTIVITY_ONGOING = PostgresColumnDefinition("ongoing", PostgresDatatype.BOOLEAN)
        @JvmField val NOTIFICATION_ACTIVITY_IMPORTANCE = PostgresColumnDefinition("importance", PostgresDatatype.INTEGER)

        // Sleep columns (sleep collection module). An AndroidSleepEvent carries a free-form String
        // id, so SLEEP_EVENT_ID is TEXT. HEALTH_METRICS-class, content-free/mic-free by construction:
        // a sleep label/confidence + coarse light/motion levels from the Play Services Sleep API.
        // event_type / segment_status are enums stored as TEXT (the enum name).
        @JvmField val SLEEP_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val SLEEP_EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.TEXT).notNull()
        @JvmField val SLEEP_SEGMENT_START_MILLIS = PostgresColumnDefinition("segment_start_millis", PostgresDatatype.BIGINT)
        @JvmField val SLEEP_SEGMENT_END_MILLIS = PostgresColumnDefinition("segment_end_millis", PostgresDatatype.BIGINT)
        @JvmField val SLEEP_SEGMENT_STATUS = PostgresColumnDefinition("segment_status", PostgresDatatype.TEXT)
        @JvmField val SLEEP_CONFIDENCE = PostgresColumnDefinition("confidence", PostgresDatatype.INTEGER)
        @JvmField val SLEEP_LIGHT = PostgresColumnDefinition("light", PostgresDatatype.INTEGER)
        @JvmField val SLEEP_MOTION = PostgresColumnDefinition("motion", PostgresDatatype.INTEGER)

        // Activity-recognition columns (activity_recognition collection module). An
        // AndroidActivityRecognitionEvent carries a free-form String id, so ACTIVITY_EVENT_ID is TEXT.
        // BEHAVIORAL_METADATA-class, content-free: an activity label + confidence, no raw sensors/location.
        @JvmField val ACTIVITY_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val ACTIVITY_TYPE = PostgresColumnDefinition("activity_type", PostgresDatatype.TEXT).notNull()
        @JvmField val ACTIVITY_CONFIDENCE = PostgresColumnDefinition("confidence", PostgresDatatype.INTEGER).notNull()
        @JvmField val ACTIVITY_TRANSITION_TYPE = PostgresColumnDefinition("transition_type", PostgresDatatype.TEXT)

        // Health-metric columns (health_connect collection module). An AndroidHealthMetricEvent carries
        // a free-form String id, so HEALTH_EVENT_ID is TEXT. HEALTH_METRICS-class: one aggregated/
        // instantaneous Health Connect record (value + unit interpreted per metric_type).
        @JvmField val HEALTH_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val HEALTH_METRIC_TYPE = PostgresColumnDefinition("metric_type", PostgresDatatype.TEXT).notNull()
        @JvmField val HEALTH_METRIC_VALUE = PostgresColumnDefinition("metric_value", PostgresDatatype.DOUBLE).notNull()
        @JvmField val HEALTH_METRIC_UNIT = PostgresColumnDefinition("unit", PostgresDatatype.TEXT).notNull()
        @JvmField val HEALTH_START_MILLIS = PostgresColumnDefinition("start_millis", PostgresDatatype.BIGINT).notNull()
        @JvmField val HEALTH_END_MILLIS = PostgresColumnDefinition("end_millis", PostgresDatatype.BIGINT).notNull()
        @JvmField val HEALTH_SOURCE_PACKAGE = PostgresColumnDefinition("source_package", PostgresDatatype.TEXT)

        // Connectivity-state columns (connectivity_state collection module). An
        // AndroidConnectivityStateEvent carries a free-form String id, so CONNECTIVITY_EVENT_ID is TEXT.
        // DEVICE_STATE_METADATA-class: transport + metered/validated flags only — no SSID/BSSID/IP/cell id.
        @JvmField val CONNECTIVITY_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val CONNECTIVITY_EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.TEXT).notNull()
        @JvmField val CONNECTIVITY_TRANSPORT = PostgresColumnDefinition("transport", PostgresDatatype.TEXT).notNull()
        @JvmField val CONNECTIVITY_CONNECTED = PostgresColumnDefinition("connected", PostgresDatatype.BOOLEAN).notNull()
        @JvmField val CONNECTIVITY_METERED = PostgresColumnDefinition("metered", PostgresDatatype.BOOLEAN)
        @JvmField val CONNECTIVITY_VALIDATED = PostgresColumnDefinition("validated", PostgresDatatype.BOOLEAN)

        // App-network-usage columns (app_network_usage collection module). An AndroidAppNetworkUsageEvent
        // carries a free-form String id, so APP_NETWORK_EVENT_ID is TEXT. BEHAVIORAL_METADATA-class:
        // per-app byte counts only — zero payload/destination/domain/URL visibility.
        @JvmField val APP_NETWORK_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val APP_NETWORK_PACKAGE_NAME = PostgresColumnDefinition("package_name", PostgresDatatype.TEXT).notNull()
        @JvmField val APP_NETWORK_NETWORK_TYPE = PostgresColumnDefinition("network_type", PostgresDatatype.TEXT).notNull()
        @JvmField val APP_NETWORK_RX_BYTES = PostgresColumnDefinition("rx_bytes", PostgresDatatype.BIGINT).notNull()
        @JvmField val APP_NETWORK_TX_BYTES = PostgresColumnDefinition("tx_bytes", PostgresDatatype.BIGINT).notNull()
        @JvmField val APP_NETWORK_BUCKET_START_MILLIS = PostgresColumnDefinition("bucket_start_millis", PostgresDatatype.BIGINT).notNull()
        @JvmField val APP_NETWORK_BUCKET_END_MILLIS = PostgresColumnDefinition("bucket_end_millis", PostgresDatatype.BIGINT).notNull()

        // Device-settings columns (device_settings collection module). An AndroidDeviceSettingsEvent
        // carries a free-form String id, so DEVICE_SETTINGS_EVENT_ID is TEXT. DEVICE_STATE_METADATA-class:
        // a content-free/identity-free snapshot of display/sound/accessibility/system toggles. All
        // descriptive columns nullable (a partial snapshot still persists).
        @JvmField val DEVICE_SETTINGS_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.TEXT).notNull()
        @JvmField val DEVICE_SETTINGS_DARK_MODE = PostgresColumnDefinition("dark_mode", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_FONT_SCALE = PostgresColumnDefinition("font_scale", PostgresDatatype.DOUBLE)
        @JvmField val DEVICE_SETTINGS_ACCESSIBILITY_ENABLED = PostgresColumnDefinition("accessibility_enabled", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_DND_ACTIVE = PostgresColumnDefinition("dnd_active", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_BATTERY_SAVER = PostgresColumnDefinition("battery_saver", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_THERMAL_STATUS = PostgresColumnDefinition("thermal_status", PostgresDatatype.TEXT)
        @JvmField val DEVICE_SETTINGS_AUTO_ROTATE = PostgresColumnDefinition("auto_rotate", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_LOCATION_SERVICES_ENABLED = PostgresColumnDefinition("location_services_enabled", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_STORAGE_FREE_BYTES = PostgresColumnDefinition("storage_free_bytes", PostgresDatatype.BIGINT)
        @JvmField val DEVICE_SETTINGS_STORAGE_TOTAL_BYTES = PostgresColumnDefinition("storage_total_bytes", PostgresDatatype.BIGINT)
        // Audio settings (folded in — no microphone) + screen brightness. Content-free device config.
        @JvmField val DEVICE_SETTINGS_SCREEN_BRIGHTNESS = PostgresColumnDefinition("screen_brightness", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_SCREEN_BRIGHTNESS_AUTO = PostgresColumnDefinition("screen_brightness_auto", PostgresDatatype.BOOLEAN)
        @JvmField val DEVICE_SETTINGS_MEDIA_VOLUME = PostgresColumnDefinition("media_volume", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_MEDIA_VOLUME_MAX = PostgresColumnDefinition("media_volume_max", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_RING_VOLUME = PostgresColumnDefinition("ring_volume", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_RING_VOLUME_MAX = PostgresColumnDefinition("ring_volume_max", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_NOTIFICATION_VOLUME = PostgresColumnDefinition("notification_volume", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_NOTIFICATION_VOLUME_MAX = PostgresColumnDefinition("notification_volume_max", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_ALARM_VOLUME = PostgresColumnDefinition("alarm_volume", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_ALARM_VOLUME_MAX = PostgresColumnDefinition("alarm_volume_max", PostgresDatatype.INTEGER)
        @JvmField val DEVICE_SETTINGS_RINGER_MODE = PostgresColumnDefinition("ringer_mode", PostgresDatatype.TEXT)

        // Encrypted payload envelope columns (HIPAA-2028 W2). The backend stores each
        // EncryptedEnvelope blind: it never holds the study private key, so these BYTEA
        // columns are opaque ciphertext + a wrapped content key it cannot open. content_hash
        // is SHA-256 over (encrypted_key||iv||ciphertext) and is the idempotency key.
        @JvmField val PAYLOAD_ID = PostgresColumnDefinition("payload_id", PostgresDatatype.UUID).notNull()
        @JvmField val PAYLOAD_TYPE = PostgresColumnDefinition("payload_type", PostgresDatatype.TEXT).notNull()
        @JvmField val ENVELOPE_VERSION = PostgresColumnDefinition("envelope_version", PostgresDatatype.INTEGER).notNull()
        @JvmField val ENVELOPE_ALG = PostgresColumnDefinition("alg", PostgresDatatype.TEXT).notNull()
        @JvmField val ENCRYPTION_KEY_ID = PostgresColumnDefinition("key_id", PostgresDatatype.TEXT).notNull()
        @JvmField val ENCRYPTED_KEY = PostgresColumnDefinition("encrypted_key", PostgresDatatype.BYTEA).notNull()
        @JvmField val ENVELOPE_IV = PostgresColumnDefinition("iv", PostgresDatatype.BYTEA).notNull()
        @JvmField val CIPHERTEXT = PostgresColumnDefinition("ciphertext", PostgresDatatype.BYTEA).notNull()
        @JvmField val SAMPLE_COUNT = PostgresColumnDefinition("sample_count", PostgresDatatype.INTEGER).notNull().withDefault("0")
        @JvmField val CONTENT_HASH = PostgresColumnDefinition("content_hash", PostgresDatatype.BYTEA).notNull()

        @JvmField val AVAILABLE_SENSORS = PostgresColumnDefinition("available_sensors", PostgresDatatype.TEXT_ARRAY).notNull().withDefault("'{}'")
        @JvmField val UNAVAILABLE_SENSORS = PostgresColumnDefinition("unavailable_sensors", PostgresDatatype.TEXT_ARRAY).notNull().withDefault("'{}'")
        // Static display context reported alongside sensor availability (nullable; older clients omit).
        @JvmField val SCREEN_WIDTH_PIXELS = PostgresColumnDefinition("screen_width_pixels", PostgresDatatype.INTEGER)
        @JvmField val SCREEN_HEIGHT_PIXELS = PostgresColumnDefinition("screen_height_pixels", PostgresDatatype.INTEGER)
        @JvmField val DISPLAY_SCREEN_DENSITY_DPI = PostgresColumnDefinition("screen_density_dpi", PostgresDatatype.INTEGER)
        @JvmField val DISPLAY_ROTATION = PostgresColumnDefinition("display_rotation", PostgresDatatype.INTEGER)
        @JvmField val INTERACTION_POINTER_CAPTURE_CAPABILITY =
            PostgresColumnDefinition("interaction_pointer_capture_capability", PostgresDatatype.TEXT)
        @JvmField val REPORTED_AT = PostgresColumnDefinition("reported_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")

        @JvmField val PARTICIPANT_NOTES = PostgresColumnDefinition("participant_notes", PostgresDatatype.TEXT)
        @JvmField val PARTICIPANT_TAGS = PostgresColumnDefinition("participant_tags", PostgresDatatype.TEXT_ARRAY).withDefault("'{}'")

        @JvmField val EXPORT_ID = PostgresColumnDefinition("export_id", PostgresDatatype.UUID).notNull()
        @JvmField val REQUEST = PostgresColumnDefinition("request", PostgresDatatype.JSONB).notNull().withDefault("'{}'::jsonb")
        @JvmField val DOWNLOAD_TOKEN = PostgresColumnDefinition("download_token", PostgresDatatype.TEXT).unique()
        @JvmField val ROW_COUNT = PostgresColumnDefinition("row_count", PostgresDatatype.BIGINT).notNull().withDefault("0")
        @JvmField val ERROR_MESSAGE = PostgresColumnDefinition("error_message", PostgresDatatype.TEXT)
        @JvmField val FILE_PATH = PostgresColumnDefinition("file_path", PostgresDatatype.TEXT)
        @JvmField val FORMAT = PostgresColumnDefinition("format", PostgresDatatype.TEXT).notNull()

        @JvmField val KEY_ID = PostgresColumnDefinition("key_id", PostgresDatatype.UUID).notNull()
        @JvmField val KEY_HASH = PostgresColumnDefinition("key_hash", PostgresDatatype.TEXT).notNull()
        @JvmField val KEY_PREFIX = PostgresColumnDefinition("key_prefix", PostgresDatatype.TEXT).notNull()
        @JvmField val CREATED_BY = PostgresColumnDefinition("created_by", PostgresDatatype.TEXT).notNull()
        @JvmField val EXPIRES_AT = PostgresColumnDefinition("expires_at", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val LAST_USED_AT = PostgresColumnDefinition("last_used_at", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val USAGE_COUNT = PostgresColumnDefinition("usage_count", PostgresDatatype.BIGINT).notNull().withDefault("0")
        @JvmField val REVOKED = PostgresColumnDefinition("revoked", PostgresDatatype.BOOLEAN).notNull().withDefault("false")

        @JvmField val NOTIFICATION_ID = PostgresColumnDefinition("notification_id", PostgresDatatype.UUID).notNull()
        @JvmField val MESSAGE_ID = PostgresColumnDefinition("message_id", PostgresDatatype.TEXT).unique()
        @JvmField val NOTIFICATION_TYPE = PostgresColumnDefinition("notification_type", PostgresDatatype.TEXT)
        @JvmField val DELIVERY_TYPE = PostgresColumnDefinition("delivery_type", PostgresDatatype.TEXT)
        @JvmField val DESTINATION = PostgresColumnDefinition("destination", PostgresDatatype.TEXT).notNull()
        @JvmField val BODY = PostgresColumnDefinition("body", PostgresDatatype.TEXT)
        @JvmField val SUBJECT = PostgresColumnDefinition("subject", PostgresDatatype.TEXT)
        @JvmField val HTML = PostgresColumnDefinition("is_html", PostgresDatatype.BOOLEAN)

        @JvmField val ALERT_ID = PostgresColumnDefinition("alert_id", PostgresDatatype.UUID).notNull()
        @JvmField val ALERT_TYPE = PostgresColumnDefinition("alert_type", PostgresDatatype.TEXT).notNull()
        @JvmField val SCORE = PostgresColumnDefinition("score", PostgresDatatype.DOUBLE).notNull()

        @JvmField val RUN_ID = PostgresColumnDefinition("run_id", PostgresDatatype.UUID).notNull()
        @JvmField val STEPS_COMPLETED = PostgresColumnDefinition("steps_completed", PostgresDatatype.INTEGER).notNull().withDefault("0")
        @JvmField val TOTAL_STEPS = PostgresColumnDefinition("total_steps", PostgresDatatype.INTEGER).notNull()
        @JvmField val INPUT_ROWS = PostgresColumnDefinition("input_rows", PostgresDatatype.BIGINT).notNull().withDefault("0")
        @JvmField val OUTPUT_ROWS = PostgresColumnDefinition("output_rows", PostgresDatatype.BIGINT).notNull().withDefault("0")

        @JvmField val AUDIT_ENTRY_ID = PostgresColumnDefinition("id", PostgresDatatype.UUID).notNull()
        @JvmField val SOURCE_IP = PostgresColumnDefinition("source_ip", PostgresDatatype.TEXT)
        @JvmField val SETTING_KEY = PostgresColumnDefinition("setting_key", PostgresDatatype.TEXT).notNull()
        @JvmField val BEFORE_VALUE = PostgresColumnDefinition("before_value", PostgresDatatype.JSONB)
        @JvmField val AFTER_VALUE = PostgresColumnDefinition("after_value", PostgresDatatype.JSONB).notNull()
        @JvmField val CHANGE_SUMMARY = PostgresColumnDefinition("change_summary", PostgresDatatype.TEXT).notNull()
        @JvmField val CHANGED_AT = PostgresColumnDefinition("changed_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")

        // Participant collection-acknowledgment trail (collection loop closure §5.3).
        @JvmField val SOURCE_DEVICE_ID = PostgresColumnDefinition("source_device_id", PostgresDatatype.TEXT).notNull()
        @JvmField val ACKNOWLEDGED_MODULES = PostgresColumnDefinition("acknowledged_modules", PostgresDatatype.JSONB).notNull()
        @JvmField val ACKNOWLEDGED_AT = PostgresColumnDefinition("acknowledged_at", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val RECORDED_AT = PostgresColumnDefinition("recorded_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")
        @JvmField val APP_VERSION = PostgresColumnDefinition("app_version", PostgresDatatype.TEXT)
        @JvmField val SETTINGS_VERSION = PostgresColumnDefinition("settings_version", PostgresDatatype.INTEGER)
        @JvmField val DISCLOSURE_VERSION = PostgresColumnDefinition("disclosure_version", PostgresDatatype.TEXT)
        @JvmField val MANIFEST_DIGEST = PostgresColumnDefinition("manifest_digest", PostgresDatatype.TEXT)
        @JvmField val UNAVAILABLE_MODULES =
            PostgresColumnDefinition("unavailable_modules", PostgresDatatype.JSONB)
                .notNull().withDefault("'[]'::jsonb")
        @JvmField val EVIDENCE_ACCESS_CODE_ID =
            PostgresColumnDefinition("evidence_access_code_id", PostgresDatatype.UUID)
        @JvmField val EVIDENCE_API_KEY_ID =
            PostgresColumnDefinition("evidence_api_key_id", PostgresDatatype.UUID)

        // Per-module consent (design §3.3): the declined set + what triggered the decision.
        @JvmField val DECLINED_MODULES =
            PostgresColumnDefinition("declined_modules", PostgresDatatype.JSONB)
                .notNull().withDefault("'[]'::jsonb")
        @JvmField val COLLECTION_TRIGGER =
            PostgresColumnDefinition("collection_trigger", PostgresDatatype.TEXT)
                .notNull().withDefault("'ENROLLMENT'")

        @JvmField val SCOPE_TYPE = PostgresColumnDefinition("scope_type", PostgresDatatype.TEXT).notNull()
        @JvmField val SCOPE_ID = PostgresColumnDefinition("scope_id", PostgresDatatype.UUID).notNull()
        @JvmField val ROLE_NAME = PostgresColumnDefinition("role_name", PostgresDatatype.TEXT).notNull()
        @JvmField val ASSIGNED_BY = PostgresColumnDefinition("assigned_by", PostgresDatatype.TEXT).notNull()
        @JvmField val ASSIGNED_AT = PostgresColumnDefinition("assigned_at", PostgresDatatype.TIMESTAMPTZ).notNull().withDefault("now()")

        public val columnTypes : Map<String, PostgresDatatype> = postgresColumns().associate { it.name to it.datatype }

        // reason: function name mirrors the reflected class on purpose; renaming would obscure that it enumerates this class's columns
        @Suppress("MemberNameEqualsClassName")
        public fun postgresColumns(): List<PostgresColumnDefinition> {
            return (PostgresColumns::class.java.fields.asList() + PostgresColumns::class.java.declaredFields)
                .filter { field: Field -> (Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers)) }
                .filter { field: Field -> PostgresColumnDefinition::class.java.isAssignableFrom(field.type) }
                .mapNotNull { field: Field ->
                    try {
                        return@mapNotNull field[null] as PostgresColumnDefinition
                    } catch (e: IllegalAccessException) {
                        logger.debug("Skipping inaccessible PostgresColumns field {}: {}", field.name, e.message)
                        return@mapNotNull null
                    }
                }
        }
    }
}
