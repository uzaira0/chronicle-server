package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.MAX_BIND_PARAMETERS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACL_KEY
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACCELEROMETER_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_DATETIME_END
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_DATETIME_START
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_DURATION_SECONDS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_ENGAGE_30S
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_FULL_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_RECORD_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_SWITCHED_APP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_TITLE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_USAGE_FLAGS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.AUDIT_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.COLLECTED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DATA
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DAY
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DESCRIPTION
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DEVICE_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DURATION
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.END_DATE_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.END_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EXACT_RECORDED_DATE_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.IOS_UTILITY_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.IOS_SCREEN_TIME_SOURCE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.KEYBOARD_METRICS_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.MESSAGES_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PHONE_USAGE_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PRINCIPAL_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.RECORDED_DATE_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.RUN_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SAMPLE_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SECURABLE_PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SHARED_SENSOR_COLS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.START_DATE_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.START_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.WEEKDAY_MONDAY_FRIDAY
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.WEEKDAY_MONDAY_THURSDAY
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.WEEKDAY_SUNDAY_THURSDAY
import java.security.InvalidParameterException
import java.time.LocalDate

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class PostgresEventTables private constructor() {
    // reason: cohesive namespace of SQL builders + column-index maps for the event tables;
    // splitting would fragment tightly coupled DDL/DML generators
    @Suppress("TooManyFunctions")
    internal companion object {
        public const val POSTGRES_EVENT_ENVIRONMENT = "postgres_event"
        internal const val POSTGRES_EVENT_DATASOURCE_NAME = "chronicle"

        @JvmField
        public val CHRONICLE_USAGE_EVENTS = PostgresTableDefinition("chronicle_usage_events")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                APP_PACKAGE_NAME,
                ACTIVITY_CLASS,
                INTERACTION_TYPE,
                EVENT_TYPE,
                TIMESTAMP,
                TIMEZONE,
                USERNAME,
                APPLICATION_LABEL,
                UPLOADED_AT,
                // COLLECTED_AT must stay last: UsageEventColumn.colIndex values are serialized
                // into upload_buffer rows, so buffered entries written before this column existed
                // keep valid indices only if new columns append at the end.
                COLLECTED_AT
            )
            .addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val CHRONICLE_USAGE_STATS = PostgresTableDefinition("chronicle_usage_stats")
            .addColumns(
                STUDY_ID,
                PARTICIPANT_ID,
                APP_PACKAGE_NAME,
                INTERACTION_TYPE,
                START_TIME,
                END_TIME,
                DURATION,
                TIMESTAMP,
                TIMEZONE,
                APPLICATION_LABEL
            )
            .addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)


        @JvmField
        public val AUDIT = PostgresTableDefinition("audit")
            .addColumns(
                ACL_KEY,
                SECURABLE_PRINCIPAL_ID,
                PRINCIPAL_TYPE,
                PRINCIPAL_ID,
                AUDIT_EVENT_TYPE,
                STUDY_ID,
                ORGANIZATION_ID,
                DESCRIPTION,
                DATA,
                TIMESTAMP
            )
            .addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)

        @JvmField
        public val PREPROCESSED_USAGE_EVENTS = PostgresTableDefinition("preprocessed_usage_events")
            .addColumns(
                RUN_ID,
                STUDY_ID,
                PARTICIPANT_ID,
                APP_RECORD_TYPE,
                APP_TITLE,
                APP_FULL_NAME,
                APP_DATETIME_START,
                APP_DATETIME_END,
                APP_TIMEZONE,
                APP_DURATION_SECONDS,
                DAY,
                WEEKDAY_MONDAY_FRIDAY,
                WEEKDAY_MONDAY_THURSDAY,
                WEEKDAY_SUNDAY_THURSDAY,
                APP_ENGAGE_30S,
                APP_SWITCHED_APP,
                APP_USAGE_FLAGS
            ).addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)

        // reason: vararg addColumns API requires spread; the column lists are small and static
        @Suppress("SpreadOperator")
        @JvmField
        public val IOS_SENSOR_DATA = PostgresTableDefinition("sensor_data")
            .addColumns(
                *(
                    SHARED_SENSOR_COLS +
                        DEVICE_USAGE_SENSOR_COLS +
                        PHONE_USAGE_SENSOR_COLS +
                        MESSAGES_USAGE_SENSOR_COLS +
                        KEYBOARD_METRICS_SENSOR_COLS +
                        IOS_UTILITY_COLS +
                        ACCELEROMETER_SENSOR_COLS
                    ).toTypedArray()
            )
            .addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)

        private val INSERT_SENSOR_DATA_COL_INDICES =
            IOS_SENSOR_DATA.columns.mapIndexed { index, col -> col.name to index + 1 }.toMap()

        public fun getInsertSensorDataColumnIndex(col: PostgresColumnDefinition): Int {
            return INSERT_SENSOR_DATA_COL_INDICES.getValue(col.name)
        }

        private val USAGE_EVENT_COLS = CHRONICLE_USAGE_EVENTS.columns.joinToString(",") { it.name }
        private val USAGE_EVENT_PARAMS = CHRONICLE_USAGE_EVENTS.columns.joinToString(",") { "?" }


        /**
         * Returns the merge clause for matching duplicate rows on insert.
         */
        private fun getMergeClause(
            srcMergeTableName: String,
            table: PostgresTableDefinition = CHRONICLE_USAGE_EVENTS,
            columnsToExclude: Set<PostgresColumnDefinition> = setOf()
        ): String {
            //These are the columns
            return (table.columns - columnsToExclude).joinToString(
                " AND "
            ) {
                val defaultValue = when (it.datatype) {
                    PostgresDatatype.BOOLEAN -> "false"
                    PostgresDatatype.TEXT_UUID, PostgresDatatype.TEXT, PostgresDatatype.TEXT_128,
                    PostgresDatatype.TEXT_256, PostgresDatatype.TEXT_512, PostgresDatatype.VARCHAR_MAX -> "''"
                    PostgresDatatype.BIGINT, PostgresDatatype.INTEGER, PostgresDatatype.NUMERIC,
                    PostgresDatatype.DECIMAL, PostgresDatatype.DOUBLE, PostgresDatatype.REAL,
                    PostgresDatatype.SMALLINT, PostgresDatatype.SERIAL, PostgresDatatype.BIGSERIAL -> "0"
                    PostgresDatatype.DATE -> LocalDate.MIN.toString()
                    PostgresDatatype.TIMESTAMP, PostgresDatatype.TIMESTAMPTZ -> "to_timestamp(0) AT TIME ZONE 'UTC'"
                    PostgresDatatype.SMALLINT_ARRAY, PostgresDatatype.UUID_ARRAY, PostgresDatatype.INTEGER_ARRAY,
                    PostgresDatatype.TIMETZ_ARRAY, PostgresDatatype.TIMESTAMPTZ_ARRAY, PostgresDatatype.TIME_ARRAY,
                    PostgresDatatype.BYTEA_ARRAY, PostgresDatatype.DATE_ARRAY, PostgresDatatype.DOUBLE_ARRAY,
                    PostgresDatatype.BIGINT_ARRAY, PostgresDatatype.BOOLEAN_ARRAY -> "ARRAY()"
                    else -> throw InvalidParameterException(
                        "Unsupported data type ${it.datatype} for duplicate comparison column ${it.name}."
                    )
                }
                "COALESCE(${table.name}.${it.name},$defaultValue) = COALESCE(${srcMergeTableName}.${it.name},$defaultValue)"
            }
        }

        /**
         * Inserts a row into the usage events table.
         * @param tableName The name of table that will serve as the source to merge into the
         * CHRONICLE_USAGE_EVENTS table.
         *
         * The bina parameters for this query are in the following order:
         * 1. organization_id (text/uuid)
         * 2. study_id (text/uuid)
         * 3. participant_id (text)
         * 4. app_package_name (text)
         * 5. interaction_type (text)
         * 6. event_type (int)
         * 7. timestamp (timestamptz)
         * 8. timezone (text)
         * 9. user (text)
         * 10. application_label (text)
         */
        public fun getInsertIntoUsageEventsTableSql(tableName: String, includeOnConflict: Boolean = false): String {
            return if (includeOnConflict) {
                """
                    INSERT INTO $tableName (${USAGE_EVENT_COLS}) VALUES (${USAGE_EVENT_PARAMS}) ON CONFLICT DO NOTHING
                    """.trimIndent()
            } else {
                """
                    INSERT INTO $tableName (${USAGE_EVENT_COLS}) VALUES (${USAGE_EVENT_PARAMS}) 
                    """.trimIndent()
            }
        }

        /**
         * Builds a multi-line prepared statement for inserting batches of data into Postgres.
         * @param numLines The number of lines containing usage events to insert
         * @param includeOnConflict Whether or not it should include the on conflict statement
         */
        public fun buildMultilineInsertUsageEvents(numLines: Int, includeOnConflict: Boolean): String {
            check((CHRONICLE_USAGE_EVENTS.columns.size * numLines) < MAX_BIND_PARAMETERS) {
                "Maximum number of postgres bind parameters would be exceeded with this amount of lines"
            }
            val columns = CHRONICLE_USAGE_EVENTS.columns.joinToString(",") { it.name }
            val header = "INSERT INTO ${CHRONICLE_USAGE_EVENTS.name} ($columns) VALUES"
            val params = CHRONICLE_USAGE_EVENTS.columns.joinToString(",") { "?" }
            val line = "($params)"
            val lines = (1..numLines).joinToString(",\n") { line }

            check((header.length + (line.length * numLines)) < 16777216) {
                "SQL exceeds maximum length allowed for Postgres."
            }

            return if (includeOnConflict) {
                "$header\n$lines ON CONFLICT DO NOTHING"
            } else {
                "$header\n$lines"
            }
        }

        /**
         * Builds a multi-line prepared statement for inserting batches of data into Postgres.
         * @param numLines The number of lines containing usage events to insert
         * @param includeOnConflict Whether or not it should include the on conflict statement
         */
        public fun buildMultilineInsertSensorEvents(numLines: Int, includeOnConflict: Boolean): String {
            check((IOS_SENSOR_DATA.columns.size * numLines) < MAX_BIND_PARAMETERS) {
                "Maximum number of postgres bind parameters would be exceeded with this amount of lines"
            }
            val columns = IOS_SENSOR_DATA.columns.joinToString(",") { it.name }
            val header = "INSERT INTO ${IOS_SENSOR_DATA.name} ($columns) VALUES"
            val params = IOS_SENSOR_DATA.columns.joinToString(",") { "?" }
            val line = "($params)"
            val lines = (1..numLines).joinToString(",\n") { line }

            check((header.length + (line.length * numLines)) < 16777216) {
                "SQL exceeds maximum length allowed for Postgres."
            }

            return if (includeOnConflict) {
                "$header\n$lines ON CONFLICT DO NOTHING"
            } else {
                "$header\n$lines"
            }
        }

        /**
         * Builds a multi-line prepared statement for inserting batches of audit events into Postgres.
         * @param numLines The number of lines containing usage events to insert
         * @param includeOnConflict Whether or not it should include the on conflict statement
         */
        public fun buildMultilineInsertAuditEvents(numLines: Int, includeOnConflict: Boolean): String {
            check((AUDIT.columns.size * numLines) < MAX_BIND_PARAMETERS) {
                "Maximum number of postgres bind parameters would be exceeded with this amount of lines"
            }

            val columns = AUDIT.columns.joinToString(",") { it.name }
            val header = "INSERT INTO ${AUDIT.name} ($columns) VALUES"
            val params = AUDIT.columns.joinToString(",") { "?" }
            val line = "($params)"
            val lines = (1..numLines).joinToString(",\n") { line }

            check((header.length + (line.length * numLines)) < 16777216) {
                "SQL exceeds maximum length allowed for Postgres."
            }

            return if (includeOnConflict) {
                "$header\n$lines ON CONFLICT DO NOTHING"
            } else {
                "$header\n$lines"
            }
        }


        /**
         * Generates SQL for creating a temp table of duplicate rows that may have been inserted.
         *
         * Default chronicle usage events table
         * 1. study id
         * 2. participant id
         * 3. event_timestamp lowerbound
         * 4. event_timestamp upperbound.
         * @param tempTableName The
         */
        public fun createTempTableOfDuplicates(
            tempTableName: String,
            likeTable: PostgresTableDefinition = CHRONICLE_USAGE_EVENTS
        ): String {
            return """
                CREATE TEMPORARY TABLE $tempTableName (LIKE ${likeTable.name}) 
            """.trimIndent()
        }

        /**
         * Transport metadata (collected_at, uploaded_at) is excluded from logical duplicate
         * identity: a retry of the same event remains one research event. The retained row is the
         * actual row with the earliest collection time (earliest receipt time as tie-breaker),
         * never a combination of minima that may have come from different rows.
         */
        public fun buildTempTableOfDuplicates(tempTableName: String): String {
            val logicalIdentityCols =
                (CHRONICLE_USAGE_EVENTS.columns - UPLOADED_AT - COLLECTED_AT).joinToString(",") { it.name }
            val allCols = CHRONICLE_USAGE_EVENTS.columns.joinToString(",") { it.name }
            return """
                INSERT INTO $tempTableName ($allCols)
                    SELECT $allCols FROM (
                        SELECT $allCols,
                            count(*) OVER (PARTITION BY $logicalIdentityCols) AS duplicate_count,
                            row_number() OVER (
                                PARTITION BY $logicalIdentityCols
                                ORDER BY ${COLLECTED_AT.name} ASC, ${UPLOADED_AT.name} ASC
                            ) AS duplicate_rank
                        FROM ${CHRONICLE_USAGE_EVENTS.name}
                        WHERE ${STUDY_ID.name} = ANY(?) AND ${PARTICIPANT_ID.name} = ANY(?) AND
                            ${TIMESTAMP.name} >= ? AND ${TIMESTAMP.name} <= ?
                    ) ranked_duplicates
                    WHERE duplicate_count > 1 AND duplicate_rank = 1
            """.trimIndent()
        }

        public fun buildTempTableOfDuplicatesForIos(tempTableName: String): String {
            val legacyExcluded = setOf(
                SAMPLE_ID,
                RECORDED_DATE_TIME,
                START_DATE_TIME,
                END_DATE_TIME,
                EXACT_RECORDED_DATE_TIME
            )
            val directExportExcluded = setOf(SAMPLE_ID)
            //Future: We don't have to run this on every insert, we could run it every 15 minutes to be more efficient,
            //with the trade off that users would see duplicates in downloads until it ran.
            val allCols = IOS_SENSOR_DATA.columns.joinToString(",") { it.name }
            val legacyGroupByCols = (IOS_SENSOR_DATA.columns - legacyExcluded).joinToString(",") { it.name }
            val directExportGroupByCols =
                (IOS_SENSOR_DATA.columns - directExportExcluded).joinToString(",") { it.name }

            fun selectCols(excluded: Set<PostgresColumnDefinition>): String {
                return IOS_SENSOR_DATA.columns.joinToString(",") { column ->
                    when {
                        column !in excluded -> column.name
                        column == SAMPLE_ID -> "min(${column.name}) AS ${column.name}"
                        column == END_DATE_TIME -> "max(${column.name}) AS ${column.name}"
                        else -> "min(${column.name}) AS ${column.name}"
                    }
                }
            }

            return """
                WITH scoped_sensor_data AS (
                    SELECT *
                    FROM ${IOS_SENSOR_DATA.name}
                    WHERE ${STUDY_ID.name} = ANY(?) AND ${PARTICIPANT_ID.name} = ANY(?)
                        AND ${START_DATE_TIME.name} <= ? AND ${END_DATE_TIME.name} >= ?
                ), duplicate_rows AS (
                    SELECT ${selectCols(legacyExcluded)}
                        FROM scoped_sensor_data
                        WHERE ${IOS_SCREEN_TIME_SOURCE.name} IS DISTINCT FROM 'deviceActivityExport'
                        GROUP BY $legacyGroupByCols
                        HAVING count(*) > 1
                    UNION ALL
                    SELECT ${selectCols(directExportExcluded)}
                        FROM scoped_sensor_data
                        WHERE ${IOS_SCREEN_TIME_SOURCE.name} = 'deviceActivityExport'
                        GROUP BY $directExportGroupByCols
                        HAVING count(*) > 1
                )
                INSERT INTO $tempTableName ($allCols)
                    SELECT $allCols
                    FROM duplicate_rows
            """.trimIndent()
        }

        public fun getDeleteIosSensorDataFromTempTable(tempTableName: String): String {
            val legacyMerge = getMergeClause(
                tempTableName,
                table = IOS_SENSOR_DATA,
                setOf(SAMPLE_ID, RECORDED_DATE_TIME, START_DATE_TIME, END_DATE_TIME, EXACT_RECORDED_DATE_TIME)
            )
            val directExportMerge = getMergeClause(
                tempTableName,
                table = IOS_SENSOR_DATA,
                setOf(SAMPLE_ID)
            )
            return """
            DELETE FROM ${IOS_SENSOR_DATA.name} 
                USING $tempTableName 
                WHERE (
                    $tempTableName.${IOS_SCREEN_TIME_SOURCE.name} = 'deviceActivityExport'
                    AND $directExportMerge
                ) OR (
                    $tempTableName.${IOS_SCREEN_TIME_SOURCE.name} IS DISTINCT FROM 'deviceActivityExport'
                    AND $legacyMerge
                )
            """.trimIndent()
        }

        /**
         * Deletes every row that shares a retained row's logical identity but carries different
         * transport stamps — the retained row itself never matches the IS DISTINCT FROM clause.
         */
        public fun getDeleteUsageEventsFromTempTable(tempTableName: String): String {
            return """
            DELETE FROM ${CHRONICLE_USAGE_EVENTS.name}
                USING $tempTableName
                WHERE ${getMergeClause(tempTableName, columnsToExclude = setOf(COLLECTED_AT, UPLOADED_AT))}
                    AND (
                        ${CHRONICLE_USAGE_EVENTS.name}.${COLLECTED_AT.name} IS DISTINCT FROM $tempTableName.${COLLECTED_AT.name}
                        OR ${CHRONICLE_USAGE_EVENTS.name}.${UPLOADED_AT.name} IS DISTINCT FROM $tempTableName.${UPLOADED_AT.name}
                    )
            """.trimIndent()
        }

        public fun getAppendTempTableSql(srcMergeTableName: String): String {

            return """
                INSERT INTO ${CHRONICLE_USAGE_EVENTS.name} ($USAGE_EVENT_COLS) SELECT $USAGE_EVENT_COLS FROM $srcMergeTableName
            """.trimIndent()
        }

        /**
         * Inserts a row into the usage stats table.
         * 1. organization_id (text/uuid)
         * 2. study_id (text/uuid)
         * 3.
         */
        internal val INSERT_USAGE_STATS_SQL = """
        INSERT INTO $CHRONICLE_USAGE_EVENTS (${USAGE_EVENT_COLS}) VALUES (USAGE_EVENT_PARAMS) 
        """.trimIndent()

        internal val INSERT_USAGE_EVENT_COLUMN_INDICES: Map<String, Int> =
            CHRONICLE_USAGE_EVENTS.columns.mapIndexed { index, pcd -> pcd.name to (index + 1) }
                .toMap() //remeber postgres is 1 based index
        internal val INSERT_USAGE_STATS_COLUMN_INDICES: Map<String, Int> =
            CHRONICLE_USAGE_STATS.columns.mapIndexed { index, pcd -> pcd.name to (index + 1) }.toMap()

        public const val UNIQUE_DATES = "unique_dates"
        internal val participantStatsIosSql = """
                SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, listagg(distinct TRUNC(${RECORDED_DATE_TIME.name} at time zone ${TIMEZONE.name}), ',') as $UNIQUE_DATES
                FROM ${IOS_SENSOR_DATA.name}
                WHERE ${STUDY_ID.name} = ?
                GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
            """.trimIndent()

        internal val participantStatsIosPostgresSql = """
                SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, string_agg(distinct (((${RECORDED_DATE_TIME.name} at time zone nullif(${TIMEZONE.name}, ''))::date)::text), ',') as $UNIQUE_DATES
                FROM ${IOS_SENSOR_DATA.name}
                WHERE ${STUDY_ID.name} = ? AND nullif(${TIMEZONE.name}, '') is not null
                GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
            """.trimIndent()

        internal val participantStatsAndroidSql = """
                SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, listagg(distinct TRUNC(${TIMESTAMP.name} at time zone ${TIMEZONE.name}), ',') as $UNIQUE_DATES
                FROM ${CHRONICLE_USAGE_EVENTS.name}
                WHERE ${STUDY_ID.name} = ? AND timezone != ''
                GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
            """.trimIndent()

        internal val participantStatsAndroidPostgresSql = """
                SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, string_agg(distinct (((${TIMESTAMP.name} at time zone nullif(${TIMEZONE.name}, ''))::date)::text), ',') as $UNIQUE_DATES
                FROM ${CHRONICLE_USAGE_EVENTS.name}
                WHERE ${STUDY_ID.name} = ? AND nullif(${TIMEZONE.name}, '') is not null
                GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
            """.trimIndent()

        public fun getInsertUsageEventColumnIndex(
            column: PostgresColumnDefinition,
        ): Int = INSERT_USAGE_EVENT_COLUMN_INDICES.getValue(column.name)

        public fun getInsertUsageEventColumnIndex(
            columnName: String
        ): Int = INSERT_USAGE_EVENT_COLUMN_INDICES.getValue(columnName)

        public fun getInsertUsageStatColumnIndex(
            column: PostgresColumnDefinition,
        ): Int = INSERT_USAGE_STATS_COLUMN_INDICES.getValue(column.name)


    }
}
