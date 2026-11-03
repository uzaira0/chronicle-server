package com.openlattice.chronicle.storage

import com.openlattice.chronicle.constants.EdmConstants
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import org.slf4j.LoggerFactory

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PostgresEventColumns private constructor() {
    internal companion object {
        private val logger = LoggerFactory.getLogger(PostgresEventColumns::class.java)

        @JvmField val APP_PACKAGE_NAME = PostgresColumnDefinition("app_package_name", PostgresDatatype.TEXT)
        @JvmField val ACTIVITY_CLASS = PostgresColumnDefinition("activity_class", PostgresDatatype.TEXT)

        @JvmField val ID = PostgresColumnDefinition("id", PostgresDatatype.TEXT_UUID).notNull()
        //IMPORTANCE in client code base
        @JvmField val INTERACTION_TYPE = PostgresColumnDefinition("interaction_type", PostgresDatatype.TEXT)
        @JvmField val TIMESTAMP = PostgresColumnDefinition("event_timestamp", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val TIMEZONE = PostgresColumnDefinition("timezone", PostgresDatatype.TEXT)
        @JvmField val USERNAME = PostgresColumnDefinition("username", PostgresDatatype.TEXT)
        @JvmField val APPLICATION_LABEL = PostgresColumnDefinition("application_label", PostgresDatatype.TEXT)
        @JvmField val START_TIME = PostgresColumnDefinition("start_time", PostgresDatatype.TIMESTAMPTZ )
        @JvmField val END_TIME = PostgresColumnDefinition("end_time", PostgresDatatype.TIMESTAMPTZ )
        @JvmField val RECORDED_DATE = PostgresColumnDefinition("date", PostgresDatatype.DATE) //important for app usage survey to query events by date
        // Convert all durations to milliseconds for stable Postgres storage and exports.
        @JvmField val DURATION = PostgresColumnDefinition("duration", PostgresDatatype.BIGINT )

        @JvmField val ORGANIZATION_ID = PostgresColumnDefinition("organization_id", PostgresDatatype.TEXT_UUID).notNull()
        @JvmField val STUDY_ID = PostgresColumnDefinition("study_id", PostgresDatatype.TEXT_UUID).notNull()
        @JvmField val PARTICIPANT_ID = PostgresColumnDefinition("participant_id", PostgresDatatype.TEXT).notNull()


        @JvmField val COMPLETED = PostgresColumnDefinition(EdmConstants.COMPLETED_DATE_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val DATE_LOGGED = PostgresColumnDefinition(EdmConstants.DATE_LOGGED_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val DATE = PostgresColumnDefinition(EdmConstants.DATE_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val END_DATE_TIME = PostgresColumnDefinition(EdmConstants.END_DATE_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val MODEL = PostgresColumnDefinition(EdmConstants.MODEL_FQN.name, PostgresDatatype.TEXT)
        @JvmField val OL_ID = PostgresColumnDefinition(EdmConstants.OL_ID_FQN.name, PostgresDatatype.TEXT)
        @JvmField val PERSON_ID = PostgresColumnDefinition(EdmConstants.PERSON_ID_FQN.name, PostgresDatatype.TEXT)

        @JvmField val RECORDED_DATE_TIME = PostgresColumnDefinition(EdmConstants.RECORDED_DATE_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val UPLOADED_AT = PostgresColumnDefinition("uploaded_at", PostgresDatatype.TIMESTAMPTZ).withDefault("now()")
        // Durable device queue-write time; defaults to receipt time for clients that predate it.
        // Phone-assigned usage event identity; UNIQUE per study participant so retried uploads insert once.
        @JvmField val CLIENT_EVENT_ID = PostgresColumnDefinition("client_event_id", PostgresDatatype.TEXT)
        @JvmField val START_DATE_TIME = PostgresColumnDefinition(EdmConstants.START_DATE_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val STATUS = PostgresColumnDefinition(EdmConstants.STATUS_FQN.name, PostgresDatatype.TEXT)

        @JvmField val STRING_ID = PostgresColumnDefinition(EdmConstants.STRING_ID_FQN.name, PostgresDatatype.TEXT)
        @JvmField val TITLE = PostgresColumnDefinition(EdmConstants.TITLE_FQN.name, PostgresDatatype.TEXT)
        @JvmField val VALUES = PostgresColumnDefinition(EdmConstants.VALUES_FQN.name, PostgresDatatype.TEXT)
        @JvmField val VERSION = PostgresColumnDefinition(EdmConstants.VERSION_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val NEW_APP = PostgresColumnDefinition("new_app", PostgresDatatype.BOOLEAN)
        @JvmField val GENERAL_END_TIME = PostgresColumnDefinition(EdmConstants.GENERAL_END_TIME_FQN.name, PostgresDatatype.TIMESTAMPTZ)
        @JvmField val NEW_PERIOD = PostgresColumnDefinition("new_period", PostgresDatatype.BOOLEAN)
        @JvmField val WARNING = PostgresColumnDefinition("warning", PostgresDatatype.TEXT)
        @JvmField val APP_DURATION_SECONDS = PostgresColumnDefinition("app_duration_seconds", PostgresDatatype.DOUBLE)
        @JvmField val APP_RECORD_TYPE = PostgresColumnDefinition("app_record_type", PostgresDatatype.TEXT)
        @JvmField val DAY = PostgresColumnDefinition("day", PostgresDatatype.INTEGER)
        @JvmField val APP_ENGAGE_30S = PostgresColumnDefinition("app_engage_30s", PostgresDatatype.INTEGER)
        @JvmField val APP_SWITCHED_APP = PostgresColumnDefinition("app_switched_app", PostgresDatatype.INTEGER)
        @JvmField val APP_USAGE_FLAGS = PostgresColumnDefinition("app_usage_flags", PostgresDatatype.TEXT)
        @JvmField val WEEKDAY_MONDAY_FRIDAY = PostgresColumnDefinition("\"weekdayMF\"", PostgresDatatype.INTEGER)
        @JvmField val WEEKDAY_MONDAY_THURSDAY = PostgresColumnDefinition("\"weekdayMTh\"", PostgresDatatype.INTEGER)
        @JvmField val WEEKDAY_SUNDAY_THURSDAY = PostgresColumnDefinition("\"weekdaySTh\"", PostgresDatatype.INTEGER)
        @JvmField val APP_TIMEZONE = PostgresColumnDefinition("app_timezone", PostgresDatatype.TEXT)
        @JvmField val APP_DATETIME_START = PostgresColumnDefinition("app_datetime_start", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val APP_DATETIME_END = PostgresColumnDefinition("app_datetime_end", PostgresDatatype.TIMESTAMPTZ)
        @JvmField val DATE_WITH_TIMEZONE = PostgresColumnDefinition("startdate_tzaware", PostgresDatatype.TIMESTAMPTZ).notNull()
        @JvmField val APP_TITLE = PostgresColumnDefinition("app_title", PostgresDatatype.TEXT).notNull()
        @JvmField val APP_FULL_NAME = PostgresColumnDefinition("app_full_name", PostgresDatatype.TEXT).notNull()
        @JvmField val EVENT_TYPE = PostgresColumnDefinition("event_type", PostgresDatatype.INTEGER).notNull()

        //AUDIT
        @JvmField val SECURABLE_PRINCIPAL_ID = PostgresColumnDefinition("id", PostgresDatatype.TEXT_UUID).notNull()
        @JvmField val PRINCIPAL_TYPE = PostgresColumnDefinition("principal_type", PostgresDatatype.TEXT_128).notNull()
        @JvmField val PRINCIPAL_ID = PostgresColumnDefinition("principal_id", PostgresDatatype.TEXT_256).notNull()
        @JvmField val ACL_KEY = PostgresColumnDefinition("acl_key", PostgresDatatype.TEXT_256).notNull()
        @JvmField val AUDIT_EVENT_TYPE = PostgresColumnDefinition("audit_event_type", PostgresDatatype.TEXT_256).notNull()
        @JvmField val DESCRIPTION = PostgresColumnDefinition("description", PostgresDatatype.TEXT).notNull()
        @JvmField val DATA = PostgresColumnDefinition("data", PostgresDatatype.VARCHAR_MAX).notNull()
        @JvmField val AUDIT_EVENT_ID = PostgresColumnDefinition("event_id", PostgresDatatype.UUID).notNull()
        // Request context of an audit row: keyed client IP reference (never a raw address), agent, path, method.
        @JvmField val CLIENT_IP_REF = PostgresColumnDefinition("client_ip_ref", PostgresDatatype.TEXT)
        @JvmField val USER_AGENT = PostgresColumnDefinition("user_agent", PostgresDatatype.TEXT)
        @JvmField val REQUEST_PATH = PostgresColumnDefinition("request_path", PostgresDatatype.TEXT)
        @JvmField val REQUEST_METHOD = PostgresColumnDefinition("request_method", PostgresDatatype.TEXT)

        public val columnTypes : Map<String, PostgresDatatype> = eventStorageColumns().associate { it.name to it.datatype }

        @JvmStatic
        public fun eventStorageColumns(): List<PostgresColumnDefinition> =
            reflectedPostgresColumns(PostgresEventColumns::class.java) { field, error ->
                logger.debug("Skipping inaccessible column field {}", field.name, error)
            }

    }
}
