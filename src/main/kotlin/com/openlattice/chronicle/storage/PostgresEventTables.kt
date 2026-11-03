package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.MAX_BIND_PARAMETERS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACL_KEY
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.AUDIT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.AUDIT_EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.CLIENT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.CLIENT_IP_REF
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DATA
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DESCRIPTION
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.DURATION
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.END_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PRINCIPAL_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.REQUEST_METHOD
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.REQUEST_PATH
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.SECURABLE_PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.START_TIME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USER_AGENT

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
        private const val DUPLICATE_TABLE_OID = "duplicate_tableoid"
        private const val DUPLICATE_ROW_ID = "duplicate_ctid"

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
                // UsageEventColumn.colIndex values are serialized into upload_buffer rows, so
                // new columns append at the end.
                CLIENT_EVENT_ID,
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
                TIMESTAMP,
                AUDIT_EVENT_ID,
                CLIENT_IP_REF,
                USER_AGENT,
                REQUEST_PATH,
                REQUEST_METHOD,
            )
            .addDataSourceNames(POSTGRES_EVENT_DATASOURCE_NAME)

        public fun buildMultilineInsertUsageEvents(numLines: Int, includeOnConflict: Boolean): String =
            buildMultilineInsert(CHRONICLE_USAGE_EVENTS, numLines, includeOnConflict)

        public fun buildInsertAuditBufferEvents(): String =
            buildMultilineInsert(ChroniclePostgresTables.AUDIT_BUFFER, 1, false)

        public fun buildMultilineInsertAuditEvents(numLines: Int, includeOnConflict: Boolean): String =
            buildMultilineInsert(AUDIT, numLines, includeOnConflict)

        private fun buildMultilineInsert(
            table: PostgresTableDefinition,
            numLines: Int,
            includeOnConflict: Boolean,
        ): String {
            check((table.columns.size * numLines) < MAX_BIND_PARAMETERS) {
                "Maximum number of postgres bind parameters would be exceeded with this amount of lines"
            }
            val columns = table.columns.joinToString(",") { it.name }
            val header = "INSERT INTO ${table.name} ($columns) VALUES"
            val params = table.columns.joinToString(",") { "?" }
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
        public fun createTempTableOfDuplicates(tempTableName: String): String =
            "CREATE TEMPORARY TABLE $tempTableName ($DUPLICATE_TABLE_OID oid, $DUPLICATE_ROW_ID tid)"

        /**
         * Transport metadata (uploaded_at, client_event_id) is excluded from logical duplicate
         * identity: a retry of the same event remains one research event. The retained row is the
         * actual row received first (physical identity breaks ties),
         * never a combination of minima that may have come from different rows.
         */
        public fun buildTempTableOfDuplicates(tempTableName: String): String {
            val logicalIdentityCols =
                (CHRONICLE_USAGE_EVENTS.columns - UPLOADED_AT - CLIENT_EVENT_ID).joinToString(",") { it.name }
            return """
                INSERT INTO $tempTableName ($DUPLICATE_TABLE_OID, $DUPLICATE_ROW_ID)
                    SELECT $DUPLICATE_TABLE_OID, $DUPLICATE_ROW_ID FROM (
                        SELECT tableoid AS $DUPLICATE_TABLE_OID, ctid AS $DUPLICATE_ROW_ID,
                            row_number() OVER (
                                PARTITION BY $logicalIdentityCols
                                ORDER BY ${UPLOADED_AT.name} ASC, tableoid, ctid
                            ) AS duplicate_rank
                        FROM ${CHRONICLE_USAGE_EVENTS.name}
                        WHERE ${STUDY_ID.name} = ANY(?) AND ${PARTICIPANT_ID.name} = ANY(?) AND
                            ${TIMESTAMP.name} >= ? AND ${TIMESTAMP.name} <= ?
                    ) ranked_duplicates
                    WHERE duplicate_rank > 1
            """.trimIndent()
        }

        /**
         * Deletes ranked duplicate rows by physical identity, including equal transport stamps.
         */
        public fun getDeleteUsageEventsFromTempTable(tempTableName: String): String {
            return """
            DELETE FROM ${CHRONICLE_USAGE_EVENTS.name}
                USING $tempTableName
                WHERE ${CHRONICLE_USAGE_EVENTS.name}.tableoid = $tempTableName.$DUPLICATE_TABLE_OID
                    AND ${CHRONICLE_USAGE_EVENTS.name}.ctid = $tempTableName.$DUPLICATE_ROW_ID
            """.trimIndent()
        }

        internal val INSERT_USAGE_EVENT_COLUMN_INDICES: Map<String, Int> =
            CHRONICLE_USAGE_EVENTS.columns.mapIndexed { index, pcd -> pcd.name to (index + 1) }
                .toMap() //remeber postgres is 1 based index

        public const val UNIQUE_DATES = "unique_dates"
        // A zone id PostgreSQL does not know (stored as sent) would fail the whole query; its rows are skipped.
        private val KNOWN_ZONE = "${TIMEZONE.name} IN (SELECT name FROM pg_timezone_names)"

        internal val participantStatsAndroidPostgresSql = """
                SELECT ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, string_agg(distinct (((${TIMESTAMP.name} at time zone ${TIMEZONE.name})::date)::text), ',') as $UNIQUE_DATES
                FROM ${CHRONICLE_USAGE_EVENTS.name}
                WHERE ${STUDY_ID.name} = ? AND $KNOWN_ZONE
                GROUP BY ${STUDY_ID.name}, ${PARTICIPANT_ID.name}
            """.trimIndent()

        public fun getInsertUsageEventColumnIndex(
            column: PostgresColumnDefinition,
        ): Int = INSERT_USAGE_EVENT_COLUMN_INDICES.getValue(column.name)
    }
}
