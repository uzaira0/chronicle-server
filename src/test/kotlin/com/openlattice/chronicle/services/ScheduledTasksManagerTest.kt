package com.openlattice.chronicle.services

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.SQLException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

class ScheduledTasksManagerTest {
    @Test
    fun `a failed refresh preserves the cache and leaves the next refresh runnable`() {
        val storage = mock<StorageResolver>()
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val rows = mock<ResultSet>()
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(pool.connection).thenReturn(connection).thenThrow(SQLException("fixture connection refused", "08006"))
        whenever(connection.prepareStatement(org.mockito.kotlin.any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(rows)
        whenever(rows.next()).thenReturn(true, false)
        whenever(rows.getString(APP_PACKAGE_NAME.name)).thenReturn("android.system.fixture")
        val tasks = ScheduledTasksManager(storage)
        tasks.refreshSystemApps()
        assertEquals(setOf("android.system.fixture"), tasks.systemAppPackageNames)
        ScopedLogCapture(ScheduledTasksManager::class.java).use { logs ->
            repeat(2) { tasks.refreshSystemApps() }
            assertEquals(setOf("android.system.fixture"), tasks.systemAppPackageNames)
            assertEquals(2, logs.messages.count { it.contains("Failed to refresh system apps cache") })
            assertTrue(logs.messages.any { it.contains("sqlState=08006") })
        }
    }
}
