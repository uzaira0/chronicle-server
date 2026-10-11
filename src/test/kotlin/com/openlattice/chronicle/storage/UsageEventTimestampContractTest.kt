package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.COLLECTED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventTimestampContractTest {

    @Test
    fun nullAndEmptyActivityClassesEachRetainOneRetry() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        pg.createConnection("").use { connection ->
            val study = java.util.UUID.randomUUID().toString()
            connection.createStatement().use { statement ->
                statement.execute("CREATE TEMP TABLE chronicle_usage_events (LIKE public.chronicle_usage_events INCLUDING DEFAULTS)")
                statement.execute(PostgresEventTables.createTempTableOfDuplicates("nullable_duplicates"))
                statement.execute("""INSERT INTO chronicle_usage_events (study_id, participant_id, activity_class, event_type, event_timestamp, uploaded_at)
                    SELECT '$study', 'p1', activity, 1, now(), now() + receipt * interval '1 second'
                    FROM (VALUES (NULL::text), (''::text)) classes(activity) CROSS JOIN generate_series(1, 2) receipt""")
            }
            connection.prepareStatement(PostgresEventTables.buildTempTableOfDuplicates("nullable_duplicates")).use { statement ->
                statement.setArray(1, connection.createArrayOf("text", arrayOf(study)))
                statement.setArray(2, connection.createArrayOf("text", arrayOf("p1")))
                statement.setObject(3, java.time.OffsetDateTime.now().minusDays(1))
                statement.setObject(4, java.time.OffsetDateTime.now().plusDays(1))
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate(PostgresEventTables.getDeleteUsageEventsFromTempTable("nullable_duplicates"))
                statement.executeQuery("SELECT activity_class, count(*) FROM chronicle_usage_events GROUP BY activity_class").use { rows ->
                    var groups = 0
                    while (rows.next()) { groups++; org.junit.Assert.assertEquals(1, rows.getInt(2)) }
                    org.junit.Assert.assertEquals(2, groups)
                }
            }
        }
    }

    @Test
    fun usageEventsExposeOccurrenceCollectionAndReceiptTimes() {
        val columns = PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.map { it.name }

        assertTrue(columns.contains(TIMESTAMP.name))
        assertTrue(columns.contains(COLLECTED_AT.name))
        assertTrue(columns.contains(UPLOADED_AT.name))
    }

    @Test
    fun transportTimestampsDoNotChangeLogicalDeduplicationIdentity() {
        val sql = PostgresEventTables.buildTempTableOfDuplicates("duplicate_events_test")
        val partition = sql.substringAfter("PARTITION BY ").substringBefore(")")

        assertFalse(partition.contains(COLLECTED_AT.name))
        assertFalse(partition.contains(UPLOADED_AT.name))
        assertTrue(sql.contains("ORDER BY ${COLLECTED_AT.name} ASC, ${UPLOADED_AT.name} ASC"))
        assertTrue(sql.contains("duplicate_count > 1 AND duplicate_rank = 1"))

        val deleteSql = PostgresEventTables.getDeleteUsageEventsFromTempTable("duplicate_events_test")
        assertTrue(deleteSql.contains("${COLLECTED_AT.name} IS DISTINCT FROM"))
        assertTrue(deleteSql.contains("${UPLOADED_AT.name} IS DISTINCT FROM"))
    }
}
