package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.PostgresTableDefinition
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.openlattice.chronicle.storage.studyReadScope
import com.openlattice.chronicle.storage.zoneOrOffset
import com.openlattice.chronicle.constants.OutputConstants
import com.openlattice.chronicle.converters.PostgresDownloadWrapper
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_USERS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBMISSION_DATE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SURVEY_DATE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.CLIENT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_SENSOR_DATA
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ACTIVITY_RECOGNITION_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_DEVICE_SENSOR_AVAILABILITY
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_AUDIO_ACTIVITY
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_AUDIO_CONTENT
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_NETWORK_USAGE
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.BATTERY_TELEMETRY
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.CONNECTIVITY_STATE_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.DEVICE_SETTINGS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.HEALTH_METRICS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.INTERACTION_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATION_ACTIVITY
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.SLEEP_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.IOS_SCREEN_TIME_USAGE
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.ParticipantDataType as StudyParticipantDataType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class DataDownloadService(
    private val storageResolver: StorageResolver,
) : DataDownloadManager {
    internal companion object {
        private val CHRONICLE_USAGE_EVENTS_DEFS = CHRONICLE_USAGE_EVENTS.columns.map { it.name }
        private val CHRONICLE_USAGE_EVENTS_COLS = CHRONICLE_USAGE_EVENTS_DEFS.joinToString(",")

        private fun chronicleUsageEventSql(filterParticipants: Boolean): String = """
            SELECT $CHRONICLE_USAGE_EVENTS_COLS
            FROM ${CHRONICLE_USAGE_EVENTS.name}
            WHERE ${STUDY_ID.name} = ?
            ${participantFilterSql(filterParticipants)}
            AND ${TIMESTAMP.name} >= ?
            AND ${TIMESTAMP.name} < ?
            ORDER BY ${TIMESTAMP.name} ASC
        """.trimIndent()

        
        

        /**
         * PreparedStatement binding
         * 1) studyId
         * 2) participant ids
         * 3) startDate
         * 4) endDate
         */
        

        private val APP_USAGE_SURVEY_COLS = APP_USAGE_SURVEY.columns.joinToString { it.name }

        /** Seconds from the end of the answered survey day to the answer; null on rows without a survey day. */
        internal const val ANSWER_LAG_SECONDS = "answer_lag_seconds"

        internal fun appUsageSurveySql(filterParticipants: Boolean): String = """
             SELECT $APP_USAGE_SURVEY_COLS,
                 EXTRACT(EPOCH FROM ${SUBMISSION_DATE.name} - (((${SURVEY_DATE.name} + 1)::timestamp) AT TIME ZONE ${TIMEZONE.name}))::bigint
                     AS $ANSWER_LAG_SECONDS
             FROM ${APP_USAGE_SURVEY.name}
             WHERE ${STUDY_ID.name} = ?
             ${participantFilterSql(filterParticipants)}
             AND ${SURVEY_DATE.name} >= COALESCE(?::date, '-infinity'::date)
             AND ${SURVEY_DATE.name} < COALESCE(?::date, 'infinity'::date)
        """.trimIndent()

        /**
         * The first survey day starting at or after [instant] in the study's current [zone]. A range of days selects
         * the survey days that start inside it, judged by `survey_date` rather than the stored `event_timestamp`,
         * which keeps the day start of the zone in force when the answer was written.
         */
        internal fun firstSurveyDayFrom(instant: OffsetDateTime, zone: ZoneId): LocalDate? = when (instant) {
            OffsetDateTime.MIN, OffsetDateTime.MAX -> null // open bound
            else -> instant.atZoneSameInstant(zone).toLocalDate().let { day ->
                if (day.atStartOfDay(zone).toInstant() < instant.toInstant()) day.plusDays(1) else day
            }
        }

        private val ANDROID_SENSOR_DATA_COLS = ANDROID_SENSOR_DATA.columns.map { it.name }
        private val ANDROID_SENSOR_DATA_COLS_STR = ANDROID_SENSOR_DATA_COLS.joinToString(",")

        private fun androidSensorDataSql(
            filterParticipants: Boolean,
            filterSensors: Boolean,
        ): String = """
            SELECT $ANDROID_SENSOR_DATA_COLS_STR
            FROM ${ANDROID_SENSOR_DATA.name}
            WHERE ${STUDY_ID.name} = ?
            ${participantFilterSql(filterParticipants)}
            AND ${PostgresColumns.SAMPLE_TIMESTAMP.name} >= ?
            AND ${PostgresColumns.SAMPLE_TIMESTAMP.name} < ?
            ${if (filterSensors) "AND ${PostgresColumns.SENSOR_TYPE.name} = ANY(?)" else ""}
            ORDER BY ${PostgresColumns.SAMPLE_TIMESTAMP.name} ASC
        """.trimIndent()

        internal class CollectionExportDefinition(
            val table: PostgresTableDefinition,
            val timestampColumn: PostgresColumnDefinition,
            /** Every column of the [table] rows to export that match a WHERE clause. */
            val rows: (where: String) -> String = { where ->
                "SELECT ${table.columns.joinToString(",") { it.name }} FROM ${table.name} WHERE $where"
            },
        )

        internal val COLLECTION_EXPORTS: Map<StudyParticipantDataType, CollectionExportDefinition> = mapOf(
            StudyParticipantDataType.SensorAvailability to
                CollectionExportDefinition(ANDROID_DEVICE_SENSOR_AVAILABILITY, PostgresColumns.REPORTED_AT),
            StudyParticipantDataType.BatteryTelemetry to
                CollectionExportDefinition(BATTERY_TELEMETRY, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.InteractionEvents to
                CollectionExportDefinition(INTERACTION_EVENTS, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.AudioActivity to
                CollectionExportDefinition(APP_AUDIO_ACTIVITY, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.AudioContent to
                CollectionExportDefinition(APP_AUDIO_CONTENT, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.NotificationActivity to
                CollectionExportDefinition(NOTIFICATION_ACTIVITY, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.SleepEvents to
                CollectionExportDefinition(SLEEP_EVENTS, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.ActivityRecognition to
                CollectionExportDefinition(ACTIVITY_RECOGNITION_EVENTS, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.HealthMetrics to
                CollectionExportDefinition(HEALTH_METRICS, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.ConnectivityState to
                CollectionExportDefinition(CONNECTIVITY_STATE_EVENTS, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.AppNetworkUsage to
                CollectionExportDefinition(APP_NETWORK_USAGE, PostgresColumns.SAMPLE_TIMESTAMP),
            StudyParticipantDataType.DeviceSettings to
                CollectionExportDefinition(DEVICE_SETTINGS, PostgresColumns.SAMPLE_TIMESTAMP),
            // Each window is exported once, at its newest capture, and selected by the window it measures.
            StudyParticipantDataType.IOSScreenTime to CollectionExportDefinition(
                IOS_SCREEN_TIME_USAGE, PostgresColumns.SCREEN_TIME_OBSERVATION_START,
                ScreenTimeUsageCaptures::newestCapturesSql,
            ),
        )

        private val UPLOAD_DIAGNOSTICS_COLUMNS = listOf(
            "study_id", "participant_id", "random_device_id", "event_id", "diagnostic_day", "module_family",
            "issue_code", "occurrence_count", "first_occurred_at", "last_occurred_at", "http_status",
            "error_type", "uploaded_at",
        )
        

        internal fun diagnosticsExclusiveEndDay(endDateTime: OffsetDateTime): java.time.LocalDate =
            endDateTime.toLocalDate().let { day ->
                if (endDateTime.toLocalTime() == java.time.LocalTime.MIDNIGHT) day else day.plusDays(1)
            }

        internal fun retainedDiagnosticsSql(
            filterParticipants: Boolean,
            filterStart: Boolean,
            filterEnd: Boolean,
        ): String = """
            SELECT study_id, participant_id, random_device_id, event_id, diagnostic_day, module_family,
                   issue_code, occurrence_count, first_occurred_at, last_occurred_at,
                   http_status, error_type, uploaded_at
            FROM upload_diagnostics
            WHERE study_id = ?
              ${participantFilterSql(filterParticipants)}
              ${if (filterStart) "AND diagnostic_day >= ?::date" else ""}
              ${if (filterEnd) "AND diagnostic_day < ?::date" else ""}
            ORDER BY diagnostic_day, participant_id, random_device_id, module_family, issue_code, event_id
        """.trimIndent()

        

        internal fun collectionDataSql(dataType: StudyParticipantDataType, filterParticipants: Boolean): String {
            val definition = COLLECTION_EXPORTS[dataType]
                ?: throw IllegalArgumentException("Participant data type $dataType is not a collection-table export")
            val where = """
                ${PostgresColumns.STUDY_ID.name} = ?
                ${participantFilterSql(filterParticipants)}
                AND ${definition.timestampColumn.name} >= ?
                AND ${definition.timestampColumn.name} < ?
            """.trimIndent()
            return "${definition.rows(where)}\nORDER BY ${definition.timestampColumn.name} ASC"
        }

        internal const val FETCH_SIZE = 1_000

        internal fun bindDownloadScope(
            statement: PreparedStatement,
            studyId: UUID,
            participantIds: Set<String>,
            eventStorage: Boolean = false,
        ): Int {
            if (eventStorage) statement.setString(1, studyId.toString()) else statement.setObject(1, studyId)
            if (participantIds.isEmpty()) return 1
            statement.setArray(2, PostgresArrays.createTextArray(statement.connection, participantIds))
            return 2
        }

        internal fun usageEventRow(rs: ResultSet): Map<String, Any> = mapOf(
            associateString(rs, STUDY_ID),
            associateString(rs, PARTICIPANT_ID),
            associateString(rs, CLIENT_EVENT_ID),
            associateString(rs, APP_PACKAGE_NAME),
            associateString(rs, ACTIVITY_CLASS),
            associateString(rs, INTERACTION_TYPE),
            associateInteger(rs, EVENT_TYPE),
            associateOffsetDatetimeWithTimezone(rs, TIMEZONE, TIMESTAMP),
            associateOffsetDatetimeWithTimezone(rs, TIMEZONE, UPLOADED_AT),
            associateString(rs, TIMEZONE),
            associateString(rs, USERNAME),
            associateString(rs, APPLICATION_LABEL),
        )

        public fun associateString(rs: ResultSet, pcd: PostgresColumnDefinition) = pcd.name to rs.getString(pcd.name)
        public fun associateInteger(rs: ResultSet, pcd: PostgresColumnDefinition) = pcd.name to rs.getInt(pcd.name)
        public fun associateOffsetDatetimeWithTimezone(
            rs: ResultSet,
            timezoneColumn: PostgresColumnDefinition,
            timestampColumn: PostgresColumnDefinition
        ): Pair<String, Any> {
            val odt = rs.getObject(timestampColumn.name, OffsetDateTime::class.java)
                ?: return timestampColumn.name to ""
            val zone = zoneOrOffset(rs.getString(timezoneColumn.name) ?: OutputConstants.DEFAULT_TIMEZONE, odt)
            return timestampColumn.name to odt.toInstant().atZone(zone).toOffsetDateTime()
        }

        public fun associateObject(rs: ResultSet, pcd: PostgresColumnDefinition, clazz: Class<*>) =
            pcd.name to rs.getObject(pcd.name, clazz)

        /**
         * The row's own `timezone` column as a zone, or null when the table has none or the
         * value is not a valid zone id. Module and sensor exports render every TIMESTAMPTZ in
         * this zone so they read like the usage-event export (participant-local offset), not a
         * mix of `-04:00` on one sheet and `Z` on the next.
         */
        internal fun rowZone(rs: ResultSet, columns: Collection<PostgresColumnDefinition>): ZoneId? {
            val timezoneColumn = columns.firstOrNull { it.name == PostgresColumns.SENSOR_TIMEZONE.name } ?: return null
            val zone = rs.getString(timezoneColumn.name) ?: return null
            return runCatching { ZoneId.of(zone) }.getOrNull()
        }

        internal fun localizedTimestamp(rs: ResultSet, column: PostgresColumnDefinition, zone: ZoneId?): Any {
            val odt = rs.getObject(column.name, OffsetDateTime::class.java) ?: return ""
            return if (zone == null) odt else odt.toInstant().atZone(zone).toOffsetDateTime()
        }

        private fun participantFilterSql(enabled: Boolean): String =
            if (enabled) "AND ${PARTICIPANT_ID.name} = ANY(?)" else ""

    }

    override fun getParticipantsAppUsageSurveyData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        studyZone: ZoneId,
    ): Iterable<Map<String, Any>> {

        val hds = storageResolver.getPlatformReadStorage()
        val filterParticipants = participantIds.isNotEmpty()
        val iterable = BasePostgresIterable<Map<String, Any>>(
            StreamingReadTransaction(
                hds,
                appUsageSurveySql(filterParticipants),
                FETCH_SIZE,
                studyScope = storageResolver.studyReadScope(studyId),
            ) { ps ->
                var index = bindDownloadScope(ps, studyId, participantIds)
                ps.setObject(++index, firstSurveyDayFrom(startDateTime, studyZone))
                ps.setObject(++index, firstSurveyDayFrom(endDateTime, studyZone))
            }
        ) { rs ->
            mapOf(
                associateObject(rs, STUDY_ID, UUID::class.java),
                associateString(rs, PARTICIPANT_ID),
                associateString(rs, APPLICATION_LABEL),
                associateString(rs, APP_PACKAGE_NAME),
                associateOffsetDatetimeWithTimezone(rs, TIMEZONE, TIMESTAMP),
                associateOffsetDatetimeWithTimezone(rs, TIMEZONE, SUBMISSION_DATE),
                associateString(rs, TIMEZONE),
                associateString(rs, APP_USERS),
                associateString(rs, SURVEY_DATE),
                ANSWER_LAG_SECONDS to rs.getObject(ANSWER_LAG_SECONDS),
            )
        }

        return PostgresDownloadWrapper(iterable).withColumnAdvice(APP_USAGE_SURVEY.columns.map { it.name } + ANSWER_LAG_SECONDS)
    }

    override fun getParticipantsUsageEventsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>> {
        val hds = storageResolver.resolveAndGetFlavor(studyId).dataSource
        val filterParticipants = participantIds.isNotEmpty()
        val pgIter = BasePostgresIterable<Map<String, Any>>(
            StreamingReadTransaction(
                hds,
                chronicleUsageEventSql(filterParticipants),
                FETCH_SIZE,
                studyScope = storageResolver.studyReadScope(studyId),
            ) { ps ->
                var index = bindDownloadScope(ps, studyId, participantIds, eventStorage = true)
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
            }) { rs ->
            usageEventRow(rs)
        }

        return PostgresDownloadWrapper(pgIter).withColumnAdvice(CHRONICLE_USAGE_EVENTS.columns.map { it.name })
    }

    

    override fun getParticipantsAndroidSensorData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        sensorTypes: Set<String>?
    ): Iterable<Map<String, Any>> {
        val hds = storageResolver.getPlatformStorage()
        val hasSensorFilter = !sensorTypes.isNullOrEmpty()
        val filterParticipants = participantIds.isNotEmpty()
        val sql = androidSensorDataSql(filterParticipants, hasSensorFilter)
        val iterable = BasePostgresIterable<Map<String, Any>>(
            StreamingReadTransaction(
                hds,
                sql,
                FETCH_SIZE,
                studyScope = storageResolver.studyReadScope(studyId),
            ) { ps ->
                var index = bindDownloadScope(ps, studyId, participantIds)
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
                if (hasSensorFilter) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, sensorTypes))
                }
            }
        ) { rs ->
            val zone = rowZone(rs, ANDROID_SENSOR_DATA.columns)
            ANDROID_SENSOR_DATA.columns.associate { col ->
                when (col.datatype) {
                    PostgresDatatype.TIMESTAMPTZ -> col.name to localizedTimestamp(rs, col, zone)
                    PostgresDatatype.REAL -> {
                        val v = rs.getFloat(col.name)
                        col.name to if (rs.wasNull()) "" else v
                    }
                    PostgresDatatype.INTEGER -> {
                        val v = rs.getInt(col.name)
                        col.name to if (rs.wasNull()) "" else v
                    }
                    PostgresDatatype.JSONB -> col.name to (rs.getString(col.name) ?: "[]")
                    PostgresDatatype.UUID -> col.name to rs.getString(col.name)
                    else -> col.name to (rs.getString(col.name) ?: "")
                }
            }
        }

        return PostgresDownloadWrapper(iterable).withColumnAdvice(ANDROID_SENSOR_DATA_COLS)
    }

    override fun getParticipantsCollectionData(
        studyId: UUID,
        participantIds: Set<String>,
        dataType: StudyParticipantDataType,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>> {
        val definition = COLLECTION_EXPORTS[dataType]
            ?: throw IllegalArgumentException("Participant data type $dataType is not a collection-table export")
        val filterParticipants = participantIds.isNotEmpty()
        val iterable = BasePostgresIterable<Map<String, Any>>(
            StreamingReadTransaction(
                storageResolver.getPlatformReadStorage(),
                collectionDataSql(dataType, filterParticipants),
                FETCH_SIZE,
                studyScope = storageResolver.studyReadScope(studyId),
            ) { statement ->
                var index = bindDownloadScope(statement, studyId, participantIds)
                statement.setObject(++index, startDateTime)
                statement.setObject(++index, endDateTime)
            },
        ) { resultSet ->
            val zone = rowZone(resultSet, definition.table.columns)
            definition.table.columns.associate { column ->
                val value: Any = when (column.datatype) {
                    PostgresDatatype.TIMESTAMPTZ -> localizedTimestamp(resultSet, column, zone)
                    PostgresDatatype.JSON,
                    PostgresDatatype.JSONB,
                    PostgresDatatype.TEXT_ARRAY,
                    PostgresDatatype.UUID_ARRAY,
                    PostgresDatatype.INTEGER_ARRAY,
                    PostgresDatatype.BIGINT_ARRAY,
                    PostgresDatatype.DOUBLE_ARRAY,
                    PostgresDatatype.BOOLEAN_ARRAY -> resultSet.getString(column.name) ?: ""
                    PostgresDatatype.UUID -> resultSet.getString(column.name) ?: ""
                    else -> resultSet.getObject(column.name) ?: ""
                }
                column.name to value
            }
        }
        return PostgresDownloadWrapper(iterable).withColumnAdvice(definition.table.columns.map { it.name })
    }

    private fun retainedDataSupplier(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        sql: (Boolean, Boolean, Boolean) -> String,
    ): StreamingReadTransaction {
        val filterStart = startDateTime != OffsetDateTime.MIN
        val filterEnd = endDateTime != OffsetDateTime.MAX
        return StreamingReadTransaction(
            storageResolver.getPlatformReadStorage(),
            sql(participantIds.isNotEmpty(), filterStart, filterEnd),
            FETCH_SIZE,
            studyScope = storageResolver.studyReadScope(studyId),
        ) { statement ->
            var index = bindDownloadScope(statement, studyId, participantIds)
            // Diagnostics are kept per day: the range binds as whole days.
            if (filterStart) statement.setObject(++index, startDateTime.toLocalDate())
            if (filterEnd) statement.setObject(++index, diagnosticsExclusiveEndDay(endDateTime))
        }
    }

    override fun getParticipantsUploadDiagnosticsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>> {
        val iterable = BasePostgresIterable<Map<String, Any>>(
            retainedDataSupplier(studyId, participantIds, startDateTime, endDateTime, ::retainedDiagnosticsSql),
        ) { resultSet ->
            val httpStatus = resultSet.getInt("http_status").let { if (resultSet.wasNull()) "" else it }
            mapOf(
                "study_id" to (resultSet.getString("study_id") ?: ""),
                "participant_id" to (resultSet.getString("participant_id") ?: ""),
                "random_device_id" to (resultSet.getString("random_device_id") ?: ""),
                "event_id" to (resultSet.getString("event_id") ?: ""),
                "diagnostic_day" to (resultSet.getObject("diagnostic_day") ?: ""),
                "module_family" to (resultSet.getString("module_family") ?: ""),
                "issue_code" to (resultSet.getString("issue_code") ?: ""),
                "occurrence_count" to resultSet.getLong("occurrence_count"),
                "first_occurred_at" to (resultSet.getObject("first_occurred_at", OffsetDateTime::class.java) ?: ""),
                "last_occurred_at" to (resultSet.getObject("last_occurred_at", OffsetDateTime::class.java) ?: ""),
                "http_status" to httpStatus,
                "error_type" to (resultSet.getString("error_type") ?: ""),
                "uploaded_at" to (resultSet.getObject("uploaded_at", OffsetDateTime::class.java) ?: ""),
            )
        }
        return PostgresDownloadWrapper(iterable).withColumnAdvice(UPLOAD_DIAGNOSTICS_COLUMNS)
    }

    

    override fun getQuestionnaireResponses(
        studyId: UUID,
        questionnaireId: UUID
    ): Iterable<Map<String, Any>> {
        val cols = listOf(
            PostgresColumns.PARTICIPANT_ID,
            PostgresColumns.COMPLETED_AT,
            PostgresColumns.QUESTION_TITLE,
            PostgresColumns.RESPONSES
        )
        val iterable = BasePostgresIterable<Map<String, Any>>(
            StreamingReadTransaction(
                hds = storageResolver.getPlatformStorage(),
                SurveysService.GET_QUESTIONNAIRE_SUBMISSIONS_SQL,
                FETCH_SIZE,
                studyScope = storageResolver.studyReadScope(studyId),
            ) { ps ->
                ps.setObject(1, studyId)
                ps.setObject(2, questionnaireId)
            }
        ) { rs ->
            mapOf(
                associateString(rs, PostgresColumns.PARTICIPANT_ID),
                associateObject(rs, PostgresColumns.COMPLETED_AT, OffsetDateTime::class.java),
                associateString(rs, PostgresColumns.QUESTION_TITLE),
                associateString(rs, PostgresColumns.RESPONSES)
            )
        }

        return PostgresDownloadWrapper(iterable).withColumnAdvice(cols.map { it.name })
    }
}
