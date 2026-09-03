package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.COLLECTED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventTimestampContractTest {

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
