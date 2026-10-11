package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresDatatype
import org.junit.Assert.assertEquals
import org.junit.Test

class PostgresColumnDiscoveryTest {
    @Test
    fun discoversBothColumnOwnersUsedForDownloadSchema() {
        val platform = PostgresColumns.columnTypes
        assertEquals(PostgresDatatype.UUID, platform.getValue(PostgresColumns.STUDY_ID.name))
        assertEquals(PostgresDatatype.TIMESTAMPTZ, platform.getValue(PostgresColumns.ANDROID_LAST_PING.name))

        val events = PostgresEventColumns.columnTypes
        assertEquals(PostgresDatatype.TEXT, events.getValue(PostgresEventColumns.APP_PACKAGE_NAME.name))
        assertEquals(PostgresDatatype.TIMESTAMPTZ, events.getValue(PostgresEventColumns.TIMESTAMP.name))
    }
}
