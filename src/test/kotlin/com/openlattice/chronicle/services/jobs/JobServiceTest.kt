package com.openlattice.chronicle.services.jobs

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

class JobServiceTest {
    @Test
    fun `lease reconciliation logs a failed update without terminating later attempts`() {
        val storage = mock<StorageResolver>()
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.prepareStatement(org.mockito.kotlin.any())).thenReturn(statement)
        whenever(statement.executeUpdate()).thenThrow(SQLException("fixture update refused"))
        val service = JobService(mock(), storage, mock())
        service.shutdown()
        try {
            ScopedLogCapture(JobService::class.java).use { logs ->
                repeat(2) { service.tryAndAcquireTaskForExecutor() }
                assertEquals(2, logs.messages.count { it.contains("Unable to reconcile expired Chronicle job leases") })
            }
        } finally { service.shutdown() }
    }
}
