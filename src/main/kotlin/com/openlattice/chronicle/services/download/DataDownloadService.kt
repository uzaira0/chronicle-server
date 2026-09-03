package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.PostgresTableDefinition
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.constants.OutputConstants
import com.openlattice.chronicle.constants.ParticipantDataType
import com.openlattice.chronicle.converters.PostgresDownloadWrapper
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.APP_USERS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SUBMISSION_DATE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACCELEROMETER_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_DATETIME_START
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DEVICE_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.KEYBOARD_METRICS_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.MESSAGES_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PHONE_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.RECORDED_DATE_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SENSOR_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SHARED_SENSOR_COLS
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
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.IOS_SENSOR_DATA
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.PREPROCESSED_USAGE_EVENTS
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.ParticipantDataType as StudyParticipantDataType
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
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
        private val CHRONICLE_USAGE_EVENT_SINGLE_SQL = """
            SELECT $CHRONICLE_USAGE_EVENTS_COLS 
            FROM ${CHRONICLE_USAGE_EVENTS.name} 
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ?
            ORDER BY ${TIMESTAMP.name} ASC
        """.trimIndent()

        private fun chronicleUsageEventSql(filterParticipants: Boolean): String = """
            SELECT $CHRONICLE_USAGE_EVENTS_COLS
            FROM ${CHRONICLE_USAGE_EVENTS.name}
            WHERE ${STUDY_ID.name} = ?
            ${participantFilterSql(filterParticipants)}
            AND ${TIMESTAMP.name} >= ?
            AND ${TIMESTAMP.name} < ?
            ORDER BY ${TIMESTAMP.name} ASC
        """.trimIndent()

        private val PREPROCESSED_DATA_COLS = PREPROCESSED_USAGE_EVENTS.columns.map { it.name }
        private val PREPROCESSED_DATA_COLS_STR = PREPROCESSED_DATA_COLS.joinToString(", ")

        /**
         * PreparedStatement binding
         * 1) studyId
         * 2) participant ids
         * 3) startDate
         * 4) endDate
         */
        private fun preprocessedDataSql(filterParticipants: Boolean): String = """
            SELECT $PREPROCESSED_DATA_COLS_STR
            FROM ${PREPROCESSED_USAGE_EVENTS.name}
            WHERE ${STUDY_ID.name} = ?
            ${participantFilterSql(filterParticipants)}
            AND ${APP_DATETIME_START.name} >= ?
            AND ${APP_DATETIME_START.name} < ?
        """.trimIndent()

        private val APP_USAGE_SURVEY_COLS = APP_USAGE_SURVEY.columns.joinToString { it.name }

        internal fun appUsageSurveySql(filterParticipants: Boolean): String = """
             SELECT $APP_USAGE_SURVEY_COLS
             FROM ${APP_USAGE_SURVEY.name}
             WHERE ${STUDY_ID.name} = ?
             ${participantFilterSql(filterParticipants)}
             AND ${TIMESTAMP.name} >= ?
             AND ${TIMESTAMP.name} < ?
        """.trimIndent()

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

        private data class CollectionExportDefinition(
            val table: PostgresTableDefinition,
            val timestampColumn: PostgresColumnDefinition,
        )

        private val COLLECTION_EXPORTS: Map<StudyParticipantDataType, CollectionExportDefinition> = mapOf(
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
        )

        internal fun collectionDataSql(dataType: StudyParticipantDataType, filterParticipants: Boolean): String {
            val definition = COLLECTION_EXPORTS[dataType]
                ?: throw IllegalArgumentException("Participant data type $dataType is not a collection-table export")
            val columns = definition.table.columns.joinToString(",") { it.name }
            return """
                SELECT $columns
                FROM ${definition.table.name}
                WHERE ${PostgresColumns.STUDY_ID.name} = ?
                ${participantFilterSql(filterParticipants)}
                AND ${definition.timestampColumn.name} >= ?
                AND ${definition.timestampColumn.name} < ?
                ORDER BY ${definition.timestampColumn.name} ASC
            """.trimIndent()
        }

        private const val FETCH_SIZE = 1_000
        private const val QUERY_TIMEOUT_SECONDS = 600

        private fun configureStreamingStatement(statement: PreparedStatement) {
            statement.queryTimeout = QUERY_TIMEOUT_SECONDS
        }

        public fun associateString(rs: ResultSet, pcd: PostgresColumnDefinition) = pcd.name to rs.getString(pcd.name)
        public fun associateInteger(rs: ResultSet, pcd: PostgresColumnDefinition) = pcd.name to rs.getInt(pcd.name)
        public fun associateDouble(rs: ResultSet, pcd: PostgresColumnDefinition) = pcd.name to rs.getDouble(pcd.name)
        public fun associateOffsetDatetimeWithTimezone(
            rs: ResultSet,
            timezoneColumn: PostgresColumnDefinition,
            timestampColumn: PostgresColumnDefinition
        ): Pair<String, Any> {
            val zoneId = ZoneId.of(rs.getString(timezoneColumn.name) ?: OutputConstants.DEFAULT_TIMEZONE)
            val odt = rs.getObject(timestampColumn.name, OffsetDateTime::class.java)
            return if(odt == null ) timestampColumn.name to ""
            else timestampColumn.name to odt.toInstant().atZone(zoneId).toOffsetDateTime()
        }

        public fun associateObject(rs: ResultSet, pcd: PostgresColumnDefinition, clazz: Class<*>) =
            pcd.name to rs.getObject(pcd.name, clazz)

        private fun participantFilterSql(enabled: Boolean): String =
            if (enabled) "AND ${PARTICIPANT_ID.name} = ANY(?)" else ""

        private data class SensorDataQuery(
            val columns: Set<PostgresColumnDefinition>,
            val sql: String,
            val sensorFilter: Set<SensorType>,
        )

        private fun getSensorDataQuery(
            sensors: Set<SensorType>,
            filterParticipants: Boolean,
        ): SensorDataQuery {
            val mapping = mapOf(
                SensorType.accelerometer to ACCELEROMETER_SENSOR_COLS,
                SensorType.pedometer to ACCELEROMETER_SENSOR_COLS,
                SensorType.motionActivity to ACCELEROMETER_SENSOR_COLS,
                SensorType.phoneUsage to PHONE_USAGE_SENSOR_COLS,
                SensorType.keyboardMetrics to KEYBOARD_METRICS_SENSOR_COLS,
                SensorType.deviceUsage to DEVICE_USAGE_SENSOR_COLS,
                SensorType.messagesUsage to MESSAGES_USAGE_SENSOR_COLS
            )

            val selectedSensors = if (sensors.isEmpty()) mapping.keys else sensors
            val cols = SHARED_SENSOR_COLS + selectedSensors.flatMap { mapping.getValue(it) }.toSet()
            val values = cols.joinToString(",") { it.name }
            val sensorFilterSql = if (sensors.isEmpty()) "" else "AND ${SENSOR_TYPE.name} = Any(?)"

            val sql = """
                SELECT $values FROM ${IOS_SENSOR_DATA.name}
                WHERE ${STUDY_ID.name} = ?
                ${participantFilterSql(filterParticipants)}
                $sensorFilterSql
                AND ${RECORDED_DATE_TIME.name} >= ?
                AND ${RECORDED_DATE_TIME.name} < ?
                ORDER BY ${RECORDED_DATE_TIME.name} ASC
            """.trimIndent()

            return SensorDataQuery(cols, sql, sensors)
        }

    }

    private fun getParticipantDataHelper(
        studyId: UUID,
        participantId: String,
    ): Iterable<Map<String, Any>> {
        val (flavor, hds) = storageResolver.resolveAndGetFlavor(studyId)
        val pgIter = BasePostgresIterable<Map<String, Any>>(
            PreparedStatementHolderSupplier(
                hds,
                CHRONICLE_USAGE_EVENT_SINGLE_SQL,
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                ps.setString(1, studyId.toString())
                ps.setString(2, participantId)
            }) { rs ->
            mapOf(
                associateString(rs, STUDY_ID),
                associateString(rs, PARTICIPANT_ID),
                associateString(rs, APP_PACKAGE_NAME),
                associateString(rs, ACTIVITY_CLASS),
                associateString(rs, INTERACTION_TYPE),
                associateOffsetDatetimeWithTimezone(rs, TIMEZONE, TIMESTAMP),
                associateString(rs, TIMEZONE),
                associateString(rs, USERNAME),
                associateString(rs, APPLICATION_LABEL)
            )
        }

        return PostgresDownloadWrapper(pgIter).withColumnAdvice(CHRONICLE_USAGE_EVENTS.columns.map { it.name })
    }

    override fun getParticipantData(
        studyId: UUID,
        participantId: String,
        dataType: ParticipantDataType,
        token: String
    ): Iterable<Map<String, Any>> {
        return getParticipantDataHelper(
            studyId,
            participantId
        )
    }

    override fun getParticipantsSensorData(
        studyId: UUID,
        participantIds: Set<String>,
        sensors: Set<SensorType>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>> {
        if (sensors.isEmpty()) {
            return emptyList()
        }
        val (_, hds) = storageResolver.resolveAndGetFlavor(studyId)
        val query = getSensorDataQuery(sensors, participantIds.isNotEmpty())
        val cols = query.columns

        val iterable = BasePostgresIterable<Map<String, Any>>(
            PreparedStatementHolderSupplier(
                hds,
                query.sql,
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                var index = 0
                ps.setString(++index, studyId.toString())
                if (participantIds.isNotEmpty()) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, participantIds))
                }
                if (query.sensorFilter.isNotEmpty()) {
                    ps.setArray(
                        ++index,
                        PostgresArrays.createTextArray(ps.connection, query.sensorFilter.map { it.name }),
                    )
                }
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
            }
        ) { rs ->
            cols.associate { col ->
                when (col.datatype) {
                    PostgresDatatype.TIMESTAMPTZ -> associateOffsetDatetimeWithTimezone(rs, TIMEZONE, col)
                    else -> associateString(rs, col)
                }
            }
        }

        return PostgresDownloadWrapper(iterable).withColumnAdvice(cols.map { it.name })
    }

    override fun getParticipantsAppUsageSurveyData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>> {

        val hds = storageResolver.getPlatformReadStorage()
        val filterParticipants = participantIds.isNotEmpty()
        val iterable = BasePostgresIterable<Map<String, Any>>(
            PreparedStatementHolderSupplier(
                hds,
                appUsageSurveySql(filterParticipants),
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                var index = 0
                ps.setObject(++index, studyId)
                if (filterParticipants) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, participantIds))
                }
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
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
                associateString(rs, APP_USERS)
            )
        }

        return PostgresDownloadWrapper(iterable).withColumnAdvice(APP_USAGE_SURVEY.columns.map { it.name })
    }

    override fun getParticipantsUsageEventsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>> {
        val (_, hds) = storageResolver.resolveAndGetFlavor(studyId)
        val filterParticipants = participantIds.isNotEmpty()
        val pgIter = BasePostgresIterable<Map<String, Any>>(
            PreparedStatementHolderSupplier(
                hds,
                chronicleUsageEventSql(filterParticipants),
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                var index = 0
                ps.setString(++index, studyId.toString())
                if (filterParticipants) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, participantIds))
                }
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
            }) { rs ->
            mapOf(
                associateString(rs, STUDY_ID),
                associateString(rs, PARTICIPANT_ID),
                associateString(rs, APP_PACKAGE_NAME),
                associateString(rs, ACTIVITY_CLASS),
                associateString(rs, INTERACTION_TYPE),
                associateOffsetDatetimeWithTimezone(rs, TIMEZONE, TIMESTAMP),
                associateString(rs, TIMEZONE),
                associateString(rs, USERNAME),
                associateString(rs, APPLICATION_LABEL)
            )
        }

        return PostgresDownloadWrapper(pgIter).withColumnAdvice(CHRONICLE_USAGE_EVENTS.columns.map { it.name })
    }

    override fun getPreprocessedUsageEventsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>> {
        val (_, hds) = storageResolver.resolveAndGetFlavor(studyId)
        val filterParticipants = participantIds.isNotEmpty()

        val resultSetAwareCols = PREPROCESSED_USAGE_EVENTS.columns.map {
            val name = it.name.replace("\"", "")
            PostgresColumnDefinition(name, it.datatype)
        }
        val pgIterable = BasePostgresIterable<Map<String, Any>>(
            PreparedStatementHolderSupplier(
                hds,
                preprocessedDataSql(filterParticipants),
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                var index = 0
                ps.setString(++index, studyId.toString())
                if (filterParticipants) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, participantIds))
                }
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
            }
        ) { rs ->
            resultSetAwareCols.associate {
                when(it.datatype) {
                    PostgresDatatype.TEXT -> associateString(rs, it)
                    PostgresDatatype.TEXT_128 -> associateString(rs, it)
                    PostgresDatatype.TEXT_256 -> associateString(rs, it)
                    PostgresDatatype.TIMESTAMPTZ -> associateOffsetDatetimeWithTimezone(rs, APP_TIMEZONE, it)
                    PostgresDatatype.TEXT_UUID -> associateString(rs, it)
                    PostgresDatatype.INTEGER -> associateInteger(rs, it)
                    PostgresDatatype.DOUBLE -> associateDouble(rs, it)
                    else -> error("Invalid column type: ${it.datatype}")
                }
            }
        }

        return PostgresDownloadWrapper(pgIterable).withColumnAdvice(resultSetAwareCols.map { it.name })
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
            PreparedStatementHolderSupplier(
                hds,
                sql,
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
                var index = 0
                ps.setObject(++index, studyId)
                if (filterParticipants) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, participantIds))
                }
                ps.setObject(++index, startDateTime)
                ps.setObject(++index, endDateTime)
                if (hasSensorFilter) {
                    ps.setArray(++index, PostgresArrays.createTextArray(ps.connection, sensorTypes))
                }
            }
        ) { rs ->
            ANDROID_SENSOR_DATA.columns.associate { col ->
                when (col.datatype) {
                    PostgresDatatype.TIMESTAMPTZ -> col.name to (rs.getObject(col.name, OffsetDateTime::class.java) ?: "")
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
            PreparedStatementHolderSupplier(
                storageResolver.getPlatformReadStorage(),
                collectionDataSql(dataType, filterParticipants),
                FETCH_SIZE,
            ) { statement ->
                configureStreamingStatement(statement)
                var index = 0
                statement.setObject(++index, studyId)
                if (filterParticipants) {
                    statement.setArray(++index, PostgresArrays.createTextArray(statement.connection, participantIds))
                }
                statement.setObject(++index, startDateTime)
                statement.setObject(++index, endDateTime)
            },
        ) { resultSet ->
            definition.table.columns.associate { column ->
                val value: Any = when (column.datatype) {
                    PostgresDatatype.TIMESTAMPTZ ->
                        resultSet.getObject(column.name, OffsetDateTime::class.java) ?: ""
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
            PreparedStatementHolderSupplier(
                hds = storageResolver.getPlatformStorage(),
                SurveysService.GET_QUESTIONNAIRE_SUBMISSIONS_SQL,
                FETCH_SIZE
            ) { ps ->
                configureStreamingStatement(ps)
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
