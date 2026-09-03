package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresColumnsIndexDefinition
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACL_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ALERT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ALERT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ASSIGNED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ASSIGNED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ANDROID_FIRST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ANDROID_LAST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ANDROID_LAST_PING
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ANDROID_UNIQUE_DATES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_USERS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BASE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_CHARGING_STATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_HEALTH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_LEVEL_PERCENT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_LOW_POWER_MODE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_PLUG_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_SAMPLE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_TEMPERATURE_DECI_C
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BATTERY_VOLTAGE_MILLIVOLTS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_DWELL_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_ELEMENT_ROLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_EPISODE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_EVENT_TIME_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_FOREGROUND_PACKAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_POSITION_SOURCE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NODE_BOUNDS_LEFT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NODE_BOUNDS_TOP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NODE_BOUNDS_RIGHT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NODE_BOUNDS_BOTTOM
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_DISPLAY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_ORIENTATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_GRID_COL
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_GRID_COLS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_GRID_ROW
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_GRID_ROWS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NORMALIZED_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_NORMALIZED_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_RAW_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_RAW_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCREEN_DENSITY_DPI
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCREEN_HEIGHT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCREEN_WIDTH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCROLL_DELTA_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCROLL_DELTA_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCROLL_REVERSED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCROLL_VELOCITY_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_SCROLL_VELOCITY_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_CLASSIFIER_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_CONFIDENCE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_LABEL
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_WINDOW_END_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AMBIENT_AUDIO_WINDOW_START_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_AUDIO_ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_AUDIO_PACKAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_CONTENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_PLAYBACK_STATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_OUTPUT_ROUTE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_ROUTE_CONNECTED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_MEDIA_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_MAX_MEDIA_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_RINGER_MODE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_DND_ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_ACTIVITY_CALL_ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_AUDIO_PACKAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_TITLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_ARTIST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_ALBUM
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_DURATION_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIO_CONTENT_POSITION_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_CATEGORY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_ONGOING
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ACTIVITY_IMPORTANCE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_SEGMENT_START_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_SEGMENT_END_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_SEGMENT_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_CONFIDENCE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_LIGHT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SLEEP_MOTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVITY_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVITY_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVITY_CONFIDENCE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACTIVITY_TRANSITION_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_METRIC_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_METRIC_VALUE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_METRIC_UNIT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_START_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_END_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HEALTH_SOURCE_PACKAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_TRANSPORT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_CONNECTED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_METERED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONNECTIVITY_VALIDATED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_NETWORK_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_RX_BYTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_TX_BYTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_BUCKET_START_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_NETWORK_BUCKET_END_MILLIS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_DARK_MODE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_FONT_SCALE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_ACCESSIBILITY_ENABLED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_DND_ACTIVE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_BATTERY_SAVER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_THERMAL_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_AUTO_ROTATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_LOCATION_SERVICES_ENABLED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_STORAGE_FREE_BYTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_STORAGE_TOTAL_BYTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_SCREEN_BRIGHTNESS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_SCREEN_BRIGHTNESS_AUTO
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_MEDIA_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_MEDIA_VOLUME_MAX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_RING_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_RING_VOLUME_MAX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_NOTIFICATION_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_NOTIFICATION_VOLUME_MAX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_ALARM_VOLUME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_ALARM_VOLUME_MAX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_SETTINGS_RINGER_MODE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BODY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CANDIDATE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COMPLETED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONTACT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DATA_EXPIRES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DATA_RETENTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETED_ROWS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETION_OPERATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETION_PREVIOUS_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELIVERY_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CHANGE_SUMMARY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AUDIT_ENTRY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACKNOWLEDGED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RECORDED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DISCLOSURE_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MANIFEST_DIGEST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DECLINED_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UNAVAILABLE_MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_ACCESS_CODE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVIDENCE_API_KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COLLECTION_TRIGGER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_IP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTING_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.BEFORE_VALUE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AFTER_VALUE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETE_AFTER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DESCRIPTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DESTINATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_TOKEN
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENDED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EVENT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPIRATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPIRATION_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FEATURES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FILE_PATH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.HTML
import com.openlattice.chronicle.storage.PostgresColumns.Companion.IOS_FIRST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.IOS_LAST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.IOS_LAST_PING
import com.openlattice.chronicle.storage.PostgresColumns.Companion.IOS_UNIQUE_DATES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.JOB_DEFINITION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.JOB_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LAST_UPDATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LAT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LEGACY_STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LIFECYCLE_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LON
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LSB
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MODULES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MSB
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NEW_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATIONS_ENABLED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_LIMIT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_NOTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_TAGS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTITION_INDEX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PREVIOUS_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_OF_ACL_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTIONNAIRE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.QUESTION_TITLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REASON
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RECURRENCE_RULE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RESPONSES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REQUEST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REVOKED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ROW_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCHEDULED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCOPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_HASH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_PREFIX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DOWNLOAD_TOKEN
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ERROR_MESSAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPIRES_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPORT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FORMAT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LAST_USED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.USAGE_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SECURABLE_OBJECT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SECURABLE_OBJECT_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SECURABLE_OBJECT_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SECURABLE_PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ROLE_NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCOPE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCOPE_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCORE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SETTINGS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SOURCE_DEVICE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STARTED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STORAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_DURATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ENDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_GROUP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_PHONE_NUMBER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBJECT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBMISSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBMISSION_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBMISSION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUMMARY_DATA
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TITLE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TUD_FIRST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TUD_LAST_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TUD_UNIQUE_DATES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPDATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPGRADE_CLASS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPGRADE_STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_DATA
import com.openlattice.chronicle.storage.PostgresColumns.Companion.USER_DATA
import com.openlattice.chronicle.storage.PostgresColumns.Companion.USER_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SAMPLE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SAMPLE_TIMESTAMP
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_X
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_Y
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_Z
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_W
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_VALUES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.AVAILABLE_SENSORS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PAYLOAD_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PAYLOAD_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENVELOPE_VERSION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENVELOPE_ALG
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENCRYPTION_KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENCRYPTED_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ENVELOPE_IV
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CIPHERTEXT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SAMPLE_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONTENT_HASH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UNAVAILABLE_SENSORS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_WIDTH_PIXELS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCREEN_HEIGHT_PIXELS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DISPLAY_SCREEN_DENSITY_DPI
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DISPLAY_ROTATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INTERACTION_POINTER_CAPTURE_CAPABILITY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REPORTED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_ACCURACY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SENSOR_TIMEZONE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RUN_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STEPS_COMPLETED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.TOTAL_STEPS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.INPUT_ROWS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.OUTPUT_ROWS

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class ChroniclePostgresTables private constructor() {
    // reason: single authoritative registry of every Chronicle Postgres table definition + SQL
    // builders; splitting would scatter the schema source-of-truth and break the cross-referenced
    // @JvmField table constants
    @Suppress("LargeClass")
    internal companion object {
        public const val MAX_BIND_PARAMETERS = 32767

        @JvmField
        public val NOTIFICATIONS = PostgresTableDefinition("notifications")
            .addColumns(
                NOTIFICATION_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                CREATED_AT,
                UPDATED_AT,
                MESSAGE_ID,
                STATUS,
                NOTIFICATION_TYPE,
                DELIVERY_TYPE,
                SUBJECT,
                BODY,
                DESTINATION,
                HTML
            )
            .primaryKey(NOTIFICATION_ID)
            .overwriteOnConflict()

        @JvmField
        public val ORGANIZATIONS = PostgresTableDefinition("organizations")
            .addColumns(
                ORGANIZATION_ID,
                TITLE,
                DESCRIPTION,
                SETTINGS
            )
            .primaryKey(ORGANIZATION_ID)
            .overwriteOnConflict()

        @JvmField
        public val STUDIES = PostgresTableDefinition("studies")
            .addColumns(
                STUDY_ID,
                TITLE,
                DESCRIPTION,
                CREATED_AT,
                UPDATED_AT,
                STARTED_AT,
                ENDED_AT,
                LAT,
                LON,
                STUDY_GROUP,
                STUDY_VERSION,
                CONTACT,
                NOTIFICATIONS_ENABLED,
                STORAGE,
                SETTINGS,
                MODULES,
                STUDY_PHONE_NUMBER,
                LIFECYCLE_STATUS
            )
            .primaryKey(STUDY_ID)
            .overwriteOnConflict()

        @JvmField
        public val LEGACY_STUDY_IDS = PostgresTableDefinition("legacy_study_ids")
            .addColumns(STUDY_ID, LEGACY_STUDY_ID)
            .primaryKey(STUDY_ID, LEGACY_STUDY_ID)

        @JvmField
        public val LEGACY_STUDY_SETTINGS = PostgresTableDefinition("legacy_study_settings")
            .addColumns(ORGANIZATION_ID, SETTINGS)
            .primaryKey(ORGANIZATION_ID)

        @JvmField
        public val STUDY_LIFECYCLE_EVENTS = PostgresTableDefinition("study_lifecycle_events")
            .addColumns(
                EVENT_ID,
                STUDY_ID,
                PREVIOUS_STATUS,
                NEW_STATUS,
                CHANGED_BY,
                REASON,
                CREATED_AT
            )
            .primaryKey(EVENT_ID)

        @JvmField
        public val STUDY_DELETION_SCHEDULE = PostgresTableDefinition("study_deletion_schedule")
            .addColumns(
                STUDY_ID,
                SCHEDULED_BY,
                DELETE_AFTER,
                DELETION_OPERATION_ID,
                DELETION_PREVIOUS_STATUS,
                CREATED_AT
            )
            .primaryKey(STUDY_ID)

        @JvmField
        public val STUDY_SETTINGS_AUDIT = PostgresTableDefinition("study_settings_audit")
            .addColumns(
                AUDIT_ENTRY_ID,
                STUDY_ID,
                CHANGED_BY,
                CHANGED_AT,
                SOURCE_IP,
                SETTING_KEY,
                BEFORE_VALUE,
                AFTER_VALUE,
                CHANGE_SUMMARY
            )
            .primaryKey(AUDIT_ENTRY_ID)

        // Append-only trail of participant collection-module acknowledgments
        // (collection loop closure §5.3). recorded_at is the server-stamped audit
        // anchor; acknowledged_at is the advisory device-reported time. Made immutable
        // (DELETE/UPDATE revoked) by V26, mirroring STUDY_SETTINGS_AUDIT.
        @JvmField
        public val PARTICIPANT_COLLECTION_ACKNOWLEDGMENT =
            PostgresTableDefinition("participant_collection_acknowledgment")
                .addColumns(
                    AUDIT_ENTRY_ID,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SOURCE_DEVICE_ID,
                    ACKNOWLEDGED_MODULES,
                    ACKNOWLEDGED_AT,
                    RECORDED_AT,
                    APP_VERSION,
                    SETTINGS_VERSION,
                    DISCLOSURE_VERSION,
                    MANIFEST_DIGEST,
                    DECLINED_MODULES,
                    UNAVAILABLE_MODULES,
                    EVIDENCE_ACCESS_CODE_ID,
                    EVIDENCE_API_KEY_ID,
                    COLLECTION_TRIGGER
                )
                .primaryKey(AUDIT_ENTRY_ID)

        @JvmField
        public val EXPORT_JOBS = PostgresTableDefinition("export_jobs")
            .addColumns(
                EXPORT_ID,
                STUDY_ID,
                STATUS,
                FORMAT,
                REQUEST,
                CREATED_BY,
                CREATED_AT,
                COMPLETED_AT,
                DOWNLOAD_TOKEN,
                ROW_COUNT,
                ERROR_MESSAGE,
                FILE_PATH
            )
            .primaryKey(EXPORT_ID)

        @JvmField
        public val API_KEYS = PostgresTableDefinition("api_keys")
            .addColumns(
                KEY_ID,
                STUDY_ID,
                KEY_HASH,
                KEY_PREFIX,
                NAME,
                SCOPE,
                CREATED_BY,
                CREATED_AT,
                EXPIRES_AT,
                LAST_USED_AT,
                USAGE_COUNT,
                REVOKED,
                PostgresColumnDefinition("participant_id", PostgresDatatype.TEXT),
                PostgresColumnDefinition("device_id", PostgresDatatype.UUID),
                PostgresColumnDefinition("is_honey_token", PostgresDatatype.BOOLEAN).notNull().withDefault("false")
            )
            .primaryKey(KEY_ID)

        @JvmField
        public val ORGANIZATION_STUDIES = PostgresTableDefinition("organization_studies")
            .addColumns(
                ORGANIZATION_ID,
                STUDY_ID,
                USER_ID,
                CREATED_AT,
                SETTINGS
            )
            .primaryKey(ORGANIZATION_ID, STUDY_ID)

        @JvmField
        public val STUDY_PARTICIPANTS = PostgresTableDefinition("study_participants")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                CANDIDATE_ID,
                PARTICIPATION_STATUS,
                PARTICIPANT_NOTES,
                PARTICIPANT_TAGS,
                UPDATED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID)

        @JvmField
        public val STUDY_LIMITS = PostgresTableDefinition("study_limits")
            .addColumns(STUDY_ID, PARTICIPANT_LIMIT, STUDY_DURATION, DATA_RETENTION, STUDY_ENDS, DATA_EXPIRES, FEATURES)
            .primaryKey(STUDY_ID)
            .overwriteOnConflict()

        @JvmField
        public val CANDIDATES = PostgresTableDefinition("candidates")
            .addColumns(
                CANDIDATE_ID,
                EXPIRATION_DATE
            )
            .primaryKey(CANDIDATE_ID)

        @Suppress("DEPRECATION") // DEVICE_TOKEN is deprecated but still in use
        @JvmField
        public val DEVICES = PostgresTableDefinition("DEVICES")
            .addColumns(
                STUDY_ID,
                DEVICE_ID,
                PARTICIPANT_ID, //Make sure this is indexed.
                DEVICE_TYPE,
                SOURCE_DEVICE,
                DEVICE_TOKEN
            )
            .primaryKey(STUDY_ID, DEVICE_ID) //Just in case device is used across multiple studies

        @JvmField
        public val TIME_USE_DIARY_SUBMISSIONS = PostgresTableDefinition("time_use_diary_submissions")
            .addColumns(
                SUBMISSION_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                SUBMISSION_DATE,
                SUBMISSION
            )
            .primaryKey(SUBMISSION_ID)

        @JvmField
        public val BASE_LONG_IDS: PostgresTableDefinition = PostgresTableDefinition("base_long_ids")
            .addColumns(SCOPE, BASE)
            .primaryKey(SCOPE)

        @JvmField
        public val APP_USAGE_SURVEY: PostgresTableDefinition = PostgresTableDefinition("app_usage_survey")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                SUBMISSION_DATE, // date when survey was submitted
                PostgresEventColumns.APPLICATION_LABEL,
                PostgresEventColumns.APP_PACKAGE_NAME,
                PostgresEventColumns.TIMESTAMP, // usage event
                PostgresEventColumns.TIMEZONE, // usage event timezone
                APP_USERS
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, PostgresEventColumns.APP_PACKAGE_NAME, PostgresEventColumns.TIMESTAMP)

        @JvmField
        public val QUESTIONNAIRES: PostgresTableDefinition = PostgresTableDefinition("questionnaires")
            .addColumns(
                STUDY_ID,
                QUESTIONNAIRE_ID,
                TITLE,
                DESCRIPTION,
                QUESTIONS,
                ACTIVE,
                CREATED_AT,
                RECURRENCE_RULE
            ).primaryKey(QUESTIONNAIRE_ID)

        @JvmField
        public val QUESTIONNAIRE_SUBMISSIONS = PostgresTableDefinition("questionnaire_submissions")
            .addColumns(
                SUBMISSION_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                QUESTIONNAIRE_ID,
                COMPLETED_AT,
                QUESTION_TITLE,
                RESPONSES,
            ).primaryKey(SUBMISSION_ID, QUESTION_TITLE)
        // All the questions in a single submission are unique. A single submission can write multiple records in the table

        @JvmField
        public val ROLE_ASSIGNMENTS = PostgresTableDefinition("role_assignments")
            .addColumns(
                PRINCIPAL_ID,
                PRINCIPAL_TYPE,
                SCOPE_TYPE,
                SCOPE_ID,
                ROLE_NAME,
                ASSIGNED_BY,
                ASSIGNED_AT,
            ).primaryKey(PRINCIPAL_ID, SCOPE_TYPE, SCOPE_ID)

        @JvmField
        public val PIPELINE_RUNS = PostgresTableDefinition("pipeline_runs")
            .addColumns(
                RUN_ID,
                STUDY_ID,
                JOB_ID,
                STATUS,
                STEPS_COMPLETED,
                TOTAL_STEPS,
                INPUT_ROWS,
                OUTPUT_ROWS,
                STARTED_AT,
                COMPLETED_AT,
                ERROR_MESSAGE,
            )
            .primaryKey(RUN_ID)

        @JvmField
        public val DATA_QUALITY_ALERTS = PostgresTableDefinition("data_quality_alerts")
            .addColumns(
                ALERT_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                ALERT_TYPE,
                MESSAGE,
                SCORE,
                CREATED_AT,
            ).primaryKey(ALERT_ID)

        @JvmField
        public val PARTICIPANT_STATS = PostgresTableDefinition("participant_stats")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                ANDROID_LAST_PING,
                ANDROID_FIRST_DATE,
                ANDROID_LAST_DATE,
                ANDROID_UNIQUE_DATES,
                IOS_LAST_PING,
                IOS_FIRST_DATE,
                IOS_LAST_DATE,
                IOS_UNIQUE_DATES,
                TUD_FIRST_DATE,
                TUD_LAST_DATE,
                TUD_UNIQUE_DATES
            ).primaryKey(STUDY_ID, PARTICIPANT_ID)

        @JvmField
        public val TIME_USE_DIARY_SUMMARIZED = PostgresTableDefinition("time_use_diary_summarized")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                SUBMISSION_ID,
                SUBMISSION_DATE,
                SUMMARY_DATA
            )
            .primaryKey(SUBMISSION_ID)

        @JvmField
        public val FILTERED_APPS = PostgresTableDefinition("filtered_apps")
            .addColumns(STUDY_ID, PostgresEventColumns.APP_PACKAGE_NAME)
            .primaryKey(STUDY_ID, PostgresEventColumns.APP_PACKAGE_NAME)

        @JvmField
        public val SYSTEM_APPS = PostgresTableDefinition("default_filtered_apps")
            .addColumns(PostgresEventColumns.APP_PACKAGE_NAME)
            .primaryKey(PostgresEventColumns.APP_PACKAGE_NAME)

        @JvmField
        public val UPGRADES = PostgresTableDefinition("upgrades")
            .addColumns(UPGRADE_CLASS, UPGRADE_STATUS, LAST_UPDATE)
            .primaryKey(UPGRADE_CLASS)
        /**
         * Authorization tables
         *
         */

        /**
         * Table containing all securable principals
         */
        @JvmField
        public val PRINCIPALS = PostgresTableDefinition("principals")
            .addColumns(ACL_KEY, PRINCIPAL_TYPE, PRINCIPAL_ID, TITLE, DESCRIPTION)
            .primaryKey(ACL_KEY)
            .setUnique(PRINCIPAL_TYPE, PRINCIPAL_ID)
            .overwriteOnConflict()

        @JvmField
        public val PRINCIPAL_TREES = PostgresTableDefinition("principal_trees")
            .addColumns(ACL_KEY, PRINCIPAL_OF_ACL_KEY)
            .primaryKey(ACL_KEY, PRINCIPAL_OF_ACL_KEY)

        @JvmField
        public val ID_GENERATION = PostgresTableDefinition("id_gen")
            .primaryKey(PARTITION_INDEX)
            .addColumns(PARTITION_INDEX, MSB, LSB)

        @JvmField
        public val PERMISSIONS = PostgresTableDefinition("permissions")
            .addColumns(
                ACL_KEY,
                PRINCIPAL_TYPE,
                PRINCIPAL_ID,
                PostgresColumns.PERMISSIONS,
                PostgresColumns.EXPIRATION_DATE
            )
            .primaryKey(ACL_KEY, PRINCIPAL_TYPE, PRINCIPAL_ID)

        @JvmField
        public val SECURABLE_OBJECTS = PostgresTableDefinition("securable_objects")
            .addColumns(ACL_KEY, SECURABLE_OBJECT_TYPE, SECURABLE_OBJECT_ID, SECURABLE_OBJECT_NAME)
            .primaryKey(ACL_KEY)

        @JvmField
        public val USERS = PostgresTableDefinition("users")
            .addColumns(USER_ID, USER_DATA, EXPIRATION)
            .primaryKey(USER_ID)
            .overwriteOnConflict()

        @JvmField
        public val JOBS = PostgresTableDefinition("jobs")
            .addColumns(
                JOB_ID,
                SECURABLE_PRINCIPAL_ID,
                PRINCIPAL_TYPE,
                PRINCIPAL_ID,
                CREATED_AT,
                UPDATED_AT,
                COMPLETED_AT,
                STATUS,
                CONTACT,
                JOB_DEFINITION,
                MESSAGE,
                DELETED_ROWS,
            )
            .primaryKey(JOB_ID)

        @JvmField
        public val ANDROID_SENSOR_DATA = PostgresTableDefinition("android_sensor_data")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                SAMPLE_ID,
                SENSOR_TYPE,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                DEVICE_ID,
                SENSOR_X,
                SENSOR_Y,
                SENSOR_Z,
                SENSOR_W,
                SENSOR_VALUES,
                SENSOR_ACCURACY
            )
            .primaryKey(SAMPLE_ID)

        @JvmField
        public val ANDROID_DEVICE_SENSOR_AVAILABILITY = PostgresTableDefinition("android_device_sensor_availability")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                DEVICE_ID,
                AVAILABLE_SENSORS,
                UNAVAILABLE_SENSORS,
                SCREEN_WIDTH_PIXELS,
                SCREEN_HEIGHT_PIXELS,
                DISPLAY_SCREEN_DENSITY_DPI,
                DISPLAY_ROTATION,
                INTERACTION_POINTER_CAPTURE_CAPABILITY,
                REPORTED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, DEVICE_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for battery telemetry samples uploaded by the Android
         * `battery_telemetry` collection module. One row per [BatterySample], scoped to a
         * study + participant. SAMPLE_ID is the per-sample dedup key (see BatterySample.id);
         * the (study_id, participant_id, sample_id) primary key makes re-uploads idempotent.
         */
        @JvmField
        public val BATTERY_TELEMETRY = PostgresTableDefinition("battery_telemetry")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                BATTERY_SAMPLE_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                BATTERY_LEVEL_PERCENT,
                BATTERY_CHARGING_STATE,
                BATTERY_PLUG_TYPE,
                BATTERY_HEALTH,
                BATTERY_TEMPERATURE_DECI_C,
                BATTERY_VOLTAGE_MILLIVOLTS,
                UPLOADED_AT,
                // Last so fresh installs match migrated databases (V64 ADD COLUMN appends).
                BATTERY_LOW_POWER_MODE
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, BATTERY_SAMPLE_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for interaction-salience events uploaded by the Android
         * `interaction_events` collection module. One row per [com.openlattice.chronicle.collection.AndroidInteractionEvent],
         * scoped to a study + participant. INTERACTION_METADATA-class data — content-free by
         * construction (grid cell + element role + foreground package, never element text).
         * EVENT_ID is the per-event dedup key; the (study_id, participant_id, event_id) primary
         * key makes re-uploads idempotent.
         */
        @JvmField
        public val INTERACTION_EVENTS = PostgresTableDefinition("interaction_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                INTERACTION_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                INTERACTION_EVENT_TYPE,
                INTERACTION_GRID_ROWS,
                INTERACTION_GRID_COLS,
                INTERACTION_GRID_ROW,
                INTERACTION_GRID_COL,
                INTERACTION_ELEMENT_ROLE,
                INTERACTION_FOREGROUND_PACKAGE,
                INTERACTION_POSITION_SOURCE,
                INTERACTION_NODE_BOUNDS_LEFT,
                INTERACTION_NODE_BOUNDS_TOP,
                INTERACTION_NODE_BOUNDS_RIGHT,
                INTERACTION_NODE_BOUNDS_BOTTOM,
                INTERACTION_DISPLAY_ID,
                INTERACTION_RAW_X,
                INTERACTION_RAW_Y,
                INTERACTION_SCREEN_WIDTH,
                INTERACTION_SCREEN_HEIGHT,
                INTERACTION_NORMALIZED_X,
                INTERACTION_NORMALIZED_Y,
                INTERACTION_SCROLL_DELTA_X,
                INTERACTION_SCROLL_DELTA_Y,
                INTERACTION_EVENT_TIME_MILLIS,
                INTERACTION_EPISODE_ID,
                INTERACTION_DWELL_MILLIS,
                INTERACTION_ORIENTATION,
                INTERACTION_SCREEN_DENSITY_DPI,
                INTERACTION_SCROLL_VELOCITY_X,
                INTERACTION_SCROLL_VELOCITY_Y,
                INTERACTION_SCROLL_REVERSED,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, INTERACTION_EVENT_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for app-audio-activity samples uploaded by the Android `audio_activity`
         * collection module. One row per [com.openlattice.chronicle.collection.AndroidAudioActivityEvent],
         * scoped to a study + participant. BEHAVIORAL_METADATA-class data — mic-free by construction
         * (device playback/output state, never an audio waveform). EVENT_ID is the per-event dedup key;
         * the (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
         */
        @JvmField
        public val APP_AUDIO_ACTIVITY = PostgresTableDefinition("app_audio_activity")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                AUDIO_ACTIVITY_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                AUDIO_ACTIVITY_EVENT_TYPE,
                AUDIO_ACTIVITY_AUDIO_ACTIVE,
                AUDIO_ACTIVITY_AUDIO_PACKAGE,
                AUDIO_ACTIVITY_CONTENT_TYPE,
                AUDIO_ACTIVITY_PLAYBACK_STATE,
                AUDIO_ACTIVITY_OUTPUT_ROUTE,
                AUDIO_ACTIVITY_ROUTE_CONNECTED,
                AUDIO_ACTIVITY_MEDIA_VOLUME,
                AUDIO_ACTIVITY_MAX_MEDIA_VOLUME,
                AUDIO_ACTIVITY_RINGER_MODE,
                AUDIO_ACTIVITY_DND_ACTIVE,
                AUDIO_ACTIVITY_CALL_ACTIVE,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, AUDIO_ACTIVITY_EVENT_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for ambient-audio classification events uploaded by the `ambient_audio`
         * collection module (currently iOS SoundAnalysis). One row per
         * [com.openlattice.chronicle.collection.AmbientAudioClassificationEvent]: a single
         * on-device sound-class label + confidence within one short listen window, scoped to a
         * study + participant. AMBIENT_AUDIO_CONTEXT-class — labels-only by construction; no
         * audio representation exists past the on-device classifier. EVENT_ID is the per-event
         * dedup key; the (study_id, participant_id, event_id) primary key makes re-uploads
         * idempotent.
         */
        @JvmField
        public val AMBIENT_AUDIO_EVENTS = PostgresTableDefinition("ambient_audio_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                AMBIENT_AUDIO_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                AMBIENT_AUDIO_WINDOW_START_MILLIS,
                AMBIENT_AUDIO_WINDOW_END_MILLIS,
                AMBIENT_AUDIO_LABEL,
                AMBIENT_AUDIO_CONFIDENCE,
                AMBIENT_AUDIO_CLASSIFIER_VERSION,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, AMBIENT_AUDIO_EVENT_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for media-metadata samples uploaded by the Android `audio_content`
         * collection module. One row per [com.openlattice.chronicle.collection.AndroidAudioContentEvent],
         * scoped to a study + participant. MEDIA_CONTENT-class data — *what* the participant is playing
         * (track title/artist/album published by the producing app), still mic-free. EVENT_ID is the
         * per-event dedup key; the (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
         */
        @JvmField
        public val APP_AUDIO_CONTENT = PostgresTableDefinition("app_audio_content")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                AUDIO_CONTENT_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                AUDIO_CONTENT_AUDIO_PACKAGE,
                AUDIO_CONTENT_TITLE,
                AUDIO_CONTENT_ARTIST,
                AUDIO_CONTENT_ALBUM,
                AUDIO_CONTENT_DURATION_MILLIS,
                AUDIO_CONTENT_POSITION_MILLIS,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, AUDIO_CONTENT_EVENT_ID)
            .overwriteOnConflict()

        /**
         * Per-row storage for notification-activity samples uploaded by the Android
         * `notification_activity` collection module. One row per
         * [com.openlattice.chronicle.collection.AndroidNotificationActivityEvent], scoped to a study +
         * participant. BEHAVIORAL_METADATA-class data — content-free by construction (package + Android
         * category constant + posted/removed, never the notification title/text). EVENT_ID is the
         * per-event dedup key; the (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
         */
        @JvmField
        public val NOTIFICATION_ACTIVITY = PostgresTableDefinition("notification_activity")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                NOTIFICATION_ACTIVITY_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                NOTIFICATION_ACTIVITY_EVENT_TYPE,
                NOTIFICATION_ACTIVITY_PACKAGE_NAME,
                NOTIFICATION_ACTIVITY_CATEGORY,
                NOTIFICATION_ACTIVITY_ONGOING,
                NOTIFICATION_ACTIVITY_IMPORTANCE,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, NOTIFICATION_ACTIVITY_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val SLEEP_EVENTS = PostgresTableDefinition("sleep_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                SLEEP_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                SLEEP_EVENT_TYPE,
                SLEEP_SEGMENT_START_MILLIS,
                SLEEP_SEGMENT_END_MILLIS,
                SLEEP_SEGMENT_STATUS,
                SLEEP_CONFIDENCE,
                SLEEP_LIGHT,
                SLEEP_MOTION,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, SLEEP_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val ACTIVITY_RECOGNITION_EVENTS = PostgresTableDefinition("activity_recognition_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                ACTIVITY_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                ACTIVITY_TYPE,
                ACTIVITY_CONFIDENCE,
                ACTIVITY_TRANSITION_TYPE,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, ACTIVITY_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val HEALTH_METRICS = PostgresTableDefinition("health_metrics")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                HEALTH_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                HEALTH_METRIC_TYPE,
                HEALTH_METRIC_VALUE,
                HEALTH_METRIC_UNIT,
                HEALTH_START_MILLIS,
                HEALTH_END_MILLIS,
                HEALTH_SOURCE_PACKAGE,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, HEALTH_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val CONNECTIVITY_STATE_EVENTS = PostgresTableDefinition("connectivity_state_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                CONNECTIVITY_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                CONNECTIVITY_EVENT_TYPE,
                CONNECTIVITY_TRANSPORT,
                CONNECTIVITY_CONNECTED,
                CONNECTIVITY_METERED,
                CONNECTIVITY_VALIDATED,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, CONNECTIVITY_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val APP_NETWORK_USAGE = PostgresTableDefinition("app_network_usage")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                APP_NETWORK_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                APP_NETWORK_PACKAGE_NAME,
                APP_NETWORK_NETWORK_TYPE,
                APP_NETWORK_RX_BYTES,
                APP_NETWORK_TX_BYTES,
                APP_NETWORK_BUCKET_START_MILLIS,
                APP_NETWORK_BUCKET_END_MILLIS,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, APP_NETWORK_EVENT_ID)
            .overwriteOnConflict()

        @JvmField
        public val DEVICE_SETTINGS = PostgresTableDefinition("device_settings")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                DEVICE_SETTINGS_EVENT_ID,
                SAMPLE_TIMESTAMP,
                SENSOR_TIMEZONE,
                DEVICE_SETTINGS_DARK_MODE,
                DEVICE_SETTINGS_FONT_SCALE,
                DEVICE_SETTINGS_ACCESSIBILITY_ENABLED,
                DEVICE_SETTINGS_DND_ACTIVE,
                DEVICE_SETTINGS_BATTERY_SAVER,
                DEVICE_SETTINGS_THERMAL_STATUS,
                DEVICE_SETTINGS_AUTO_ROTATE,
                DEVICE_SETTINGS_LOCATION_SERVICES_ENABLED,
                DEVICE_SETTINGS_STORAGE_FREE_BYTES,
                DEVICE_SETTINGS_STORAGE_TOTAL_BYTES,
                DEVICE_SETTINGS_SCREEN_BRIGHTNESS,
                DEVICE_SETTINGS_SCREEN_BRIGHTNESS_AUTO,
                DEVICE_SETTINGS_MEDIA_VOLUME,
                DEVICE_SETTINGS_MEDIA_VOLUME_MAX,
                DEVICE_SETTINGS_RING_VOLUME,
                DEVICE_SETTINGS_RING_VOLUME_MAX,
                DEVICE_SETTINGS_NOTIFICATION_VOLUME,
                DEVICE_SETTINGS_NOTIFICATION_VOLUME_MAX,
                DEVICE_SETTINGS_ALARM_VOLUME,
                DEVICE_SETTINGS_ALARM_VOLUME_MAX,
                DEVICE_SETTINGS_RINGER_MODE,
                UPLOADED_AT
            )
            .primaryKey(STUDY_ID, PARTICIPANT_ID, DEVICE_SETTINGS_EVENT_ID)
            .overwriteOnConflict()

        /**
         * Blind storage for envelope-encrypted upload batches (HIPAA-2028 W2). One row per
         * [com.openlattice.chronicle.crypto.EncryptedEnvelope] posted by an Android device to
         * the v4 `/android/encrypted` endpoint, scoped to a study + participant + device.
         *
         * The backend NEVER decrypts on ingest: encrypted_key / iv / ciphertext are opaque
         * BYTEA, and the study private key lives only in Vault (fetched solely at authorized
         * export). content_hash = SHA-256(encrypted_key||iv||ciphertext); the
         * (study_id, participant_id, content_hash) UNIQUE constraint makes re-sends of the
         * same sealed batch idempotent (mirrors battery_telemetry's ON CONFLICT DO NOTHING).
         */
        @JvmField
        public val ENCRYPTED_PAYLOADS = PostgresTableDefinition("encrypted_payloads")
            .addColumns(
                PAYLOAD_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                DEVICE_ID,
                PAYLOAD_TYPE,
                ENVELOPE_VERSION,
                ENVELOPE_ALG,
                ENCRYPTION_KEY_ID,
                ENCRYPTED_KEY,
                ENVELOPE_IV,
                CIPHERTEXT,
                SAMPLE_COUNT,
                CONTENT_HASH,
                UPLOADED_AT
            )
            .primaryKey(PAYLOAD_ID)
            .setUnique(STUDY_ID, PARTICIPANT_ID, CONTENT_HASH)

        @JvmField
        public val UPLOAD_BUFFER = PostgresTableDefinition("upload_buffer")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                UPLOAD_DATA,
                UPLOADED_AT,
                UPLOAD_TYPE,
                DEVICE_ID
            )

        @JvmField
        public val AUDIT_BUFFER = PostgresTableDefinition("audit_buffer")
            .addColumns(
                PostgresEventColumns.ACL_KEY,
                PostgresEventColumns.SECURABLE_PRINCIPAL_ID,
                PostgresEventColumns.PRINCIPAL_TYPE,
                PostgresEventColumns.PRINCIPAL_ID,
                PostgresEventColumns.AUDIT_EVENT_TYPE,
                PostgresEventColumns.STUDY_ID,
                PostgresEventColumns.ORGANIZATION_ID,
                PostgresEventColumns.DESCRIPTION,
                PostgresEventColumns.DATA,
                PostgresEventColumns.TIMESTAMP
            )

        /**
         * Atomically claims and deletes at most [batchSize] exact, deletion-eligible buffer rows.
         *
         * The explicit mutation predicate is required even though upload_buffer has RLS: background/raw data
         * sources may bypass table policies. `ctid` is safe here because selection, deletion, and row locking
         * occur in the same statement and transaction.
         */
        @JvmStatic
        public fun getMoveSql(batchSize: Int = 65536, uploadType: UploadType): String {
            return buildMoveSql(batchSize, uploadType, scoped = false)
        }

        /**
         * Claims rows for one exact subject. Unlike the production opportunistic drain, this deliberately waits
         * for an existing row claimant. A scoped flush therefore cannot report completion while another
         * transaction still owns that subject's upload-buffer row.
         *
         * Bind parameters are study id then participant id.
         */
        @JvmStatic
        public fun getScopedMoveSql(batchSize: Int = 65536, uploadType: UploadType): String {
            return buildMoveSql(batchSize, uploadType, scoped = true)
        }

        private fun buildMoveSql(batchSize: Int, uploadType: UploadType, scoped: Boolean): String {
            require(batchSize > 0) { "Move batch size must be positive" }
            val subjectFilter = if (scoped) {
                """
                    AND candidate.${STUDY_ID.name} = ?
                    AND candidate.${PARTICIPANT_ID.name} = ?
                """.trimIndent()
            } else {
                ""
            }
            val lockClause = if (scoped) "FOR UPDATE" else "FOR UPDATE SKIP LOCKED"
            return """
                DELETE FROM ${UPLOAD_BUFFER.name} AS claimed
                WHERE claimed.ctid IN (
                    SELECT candidate.ctid
                    FROM ${UPLOAD_BUFFER.name} AS candidate
                    WHERE candidate.${UPLOAD_TYPE.name} = '${uploadType.name}'
                      AND chronicle_participant_mutation_allowed(
                          candidate.${STUDY_ID.name},
                          candidate.${PARTICIPANT_ID.name}
                      )
                    $subjectFilter
                    ORDER BY candidate.${UPLOADED_AT.name}, candidate.ctid
                    $lockClause
                    LIMIT $batchSize
                )
                RETURNING claimed.*
            """.trimIndent()
        }

        init {
            ORGANIZATION_STUDIES
                .addIndexes(PostgresColumnsIndexDefinition(ORGANIZATION_STUDIES, ORGANIZATION_ID).ifNotExists())
            DEVICES
                .addIndexes(
                    //(study id, participant id, device id) is unique per device
                    PostgresColumnsIndexDefinition(
                        DEVICES,
                        STUDY_ID,
                        PARTICIPANT_ID,
                        DEVICE_ID
                    ).ifNotExists().unique(),
                    PostgresColumnsIndexDefinition(
                        DEVICES,
                        STUDY_ID,
                    ).ifNotExists()
                )
            STUDY_LIMITS.addIndexes(
                PostgresColumnsIndexDefinition(STUDY_LIMITS, STUDY_ENDS).ifNotExists(),
                PostgresColumnsIndexDefinition(STUDY_LIMITS, DATA_EXPIRES).ifNotExists()
            )
            FILTERED_APPS.addIndexes(
                PostgresColumnsIndexDefinition(FILTERED_APPS, STUDY_ID).ifNotExists()
            )
            UPLOAD_BUFFER.addIndexes(
                PostgresColumnsIndexDefinition(
                    UPLOAD_BUFFER,
                    STUDY_ID,
                    PARTICIPANT_ID
                ).ifNotExists()
            )
            ANDROID_SENSOR_DATA.addIndexes(
                PostgresColumnsIndexDefinition(
                    ANDROID_SENSOR_DATA,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            BATTERY_TELEMETRY.addIndexes(
                PostgresColumnsIndexDefinition(
                    BATTERY_TELEMETRY,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            INTERACTION_EVENTS.addIndexes(
                PostgresColumnsIndexDefinition(
                    INTERACTION_EVENTS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            APP_AUDIO_ACTIVITY.addIndexes(
                PostgresColumnsIndexDefinition(
                    APP_AUDIO_ACTIVITY,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            AMBIENT_AUDIO_EVENTS.addIndexes(
                PostgresColumnsIndexDefinition(
                    AMBIENT_AUDIO_EVENTS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            APP_AUDIO_CONTENT.addIndexes(
                PostgresColumnsIndexDefinition(
                    APP_AUDIO_CONTENT,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            NOTIFICATION_ACTIVITY.addIndexes(
                PostgresColumnsIndexDefinition(
                    NOTIFICATION_ACTIVITY,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            SLEEP_EVENTS.addIndexes(
                PostgresColumnsIndexDefinition(
                    SLEEP_EVENTS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            ACTIVITY_RECOGNITION_EVENTS.addIndexes(
                PostgresColumnsIndexDefinition(
                    ACTIVITY_RECOGNITION_EVENTS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            HEALTH_METRICS.addIndexes(
                PostgresColumnsIndexDefinition(
                    HEALTH_METRICS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            CONNECTIVITY_STATE_EVENTS.addIndexes(
                PostgresColumnsIndexDefinition(
                    CONNECTIVITY_STATE_EVENTS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            APP_NETWORK_USAGE.addIndexes(
                PostgresColumnsIndexDefinition(
                    APP_NETWORK_USAGE,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            DEVICE_SETTINGS.addIndexes(
                PostgresColumnsIndexDefinition(
                    DEVICE_SETTINGS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    SAMPLE_TIMESTAMP
                ).ifNotExists()
            )
            ENCRYPTED_PAYLOADS.addIndexes(
                PostgresColumnsIndexDefinition(
                    ENCRYPTED_PAYLOADS,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    UPLOADED_AT
                ).ifNotExists()
            )
            PIPELINE_RUNS.addIndexes(
                PostgresColumnsIndexDefinition(
                    PIPELINE_RUNS,
                    STUDY_ID
                ).ifNotExists()
            )
            STUDY_SETTINGS_AUDIT.addIndexes(
                PostgresColumnsIndexDefinition(
                    STUDY_SETTINGS_AUDIT,
                    STUDY_ID,
                    CHANGED_AT
                ).ifNotExists()
            )
            PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.addIndexes(
                PostgresColumnsIndexDefinition(
                    PARTICIPANT_COLLECTION_ACKNOWLEDGMENT,
                    STUDY_ID,
                    RECORDED_AT
                ).ifNotExists()
            )
            UPLOAD_BUFFER.addIndexes(
                PostgresColumnsIndexDefinition(
                    UPLOAD_BUFFER,
                    UPLOAD_TYPE,
                    STUDY_ID,
                    PARTICIPANT_ID
                ).ifNotExists()
            )
            APP_USAGE_SURVEY.addIndexes(
                PostgresColumnsIndexDefinition(
                    APP_USAGE_SURVEY,
                    STUDY_ID,
                    PARTICIPANT_ID,
                    PostgresEventColumns.TIMESTAMP
                ).ifNotExists()
            )
            QUESTIONNAIRE_SUBMISSIONS.addIndexes(
                PostgresColumnsIndexDefinition(
                    QUESTIONNAIRE_SUBMISSIONS,
                    STUDY_ID,
                    QUESTIONNAIRE_ID
                ).ifNotExists()
            )
            AUDIT_BUFFER.addIndexes(
                PostgresColumnsIndexDefinition(
                    AUDIT_BUFFER,
                    PostgresEventColumns.TIMESTAMP
                ).ifNotExists()
            )
            TIME_USE_DIARY_SUBMISSIONS.addIndexes(
                PostgresColumnsIndexDefinition(
                    TIME_USE_DIARY_SUBMISSIONS,
                    STUDY_ID, PARTICIPANT_ID, SUBMISSION_DATE,
                ).ifNotExists(),
                PostgresColumnsIndexDefinition(
                    TIME_USE_DIARY_SUBMISSIONS,
                    STUDY_ID, SUBMISSION_DATE
                ).ifNotExists()
            )
            TIME_USE_DIARY_SUMMARIZED.addIndexes(
                PostgresColumnsIndexDefinition(
                    TIME_USE_DIARY_SUMMARIZED,
                    STUDY_ID, PARTICIPANT_ID, SUBMISSION_DATE,
                ).ifNotExists(),
                PostgresColumnsIndexDefinition(
                    TIME_USE_DIARY_SUMMARIZED,
                    STUDY_ID, SUBMISSION_DATE
                ).ifNotExists()
            )
        }
    }
}
