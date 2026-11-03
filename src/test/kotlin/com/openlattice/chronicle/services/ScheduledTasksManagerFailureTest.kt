package com.openlattice.chronicle.services

import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLTransientConnectionException
import java.util.UUID

class ScheduledTasksManagerFailureTest {
    @Test
    fun aFailedFirstLoadIsCountedAndTheFilteredAppsReadReturnsUnfiltered() {
        val unavailable = mock<StorageResolver>()
        whenever(unavailable.getPlatformStorage()).thenAnswer { throw SQLTransientConnectionException("database unavailable") }
        val manager = ScheduledTasksManager(unavailable)
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("system_apps")
        val before = failures.get()
        manager.refreshSystemApps()

        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(connection.createArrayOf(any(), any())).thenReturn(mock())
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(mock<ResultSet>())
        val settingsRows = mock<ResultSet>()
        whenever(settingsRows.next()).thenReturn(true, false)
        whenever(settingsRows.getString("settings")).thenReturn("{}")
        val settingsStatement = mock<PreparedStatement>()
        whenever(settingsStatement.executeQuery()).thenReturn(settingsRows)
        whenever(connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ? FOR UPDATE"))
            .thenReturn(settingsStatement)
        val pool = mock<HikariDataSource>()
        whenever(pool.connection).thenReturn(connection)
        val storage = mock<StorageResolver>()
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        val hazelcast = mock<HazelcastInstance>()
        whenever(hazelcast.getMap<UUID, KotlinDelegatedStringSet>("FILTERED_APPS"))
            .thenReturn(mock<IMap<UUID, KotlinDelegatedStringSet>>())
        val surveys = SurveysService(hazelcast, storage, mock(), manager, mock(), mock())
        TestSecurityUtils.setupSecurityContext()
        try {
            // Upstream behaviour: the read succeeds with whatever set is loaded, here none, so nothing is excluded.
            assertEquals(emptyList<String>(), surveys.getAppsFilteredForStudyAppUsageSurvey(UUID.randomUUID()))
            assertEquals(before + 1, failures.get(), 0.0)
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun aSuccessfulEmptyRefreshIsLoadedDataAndALaterFailureKeepsTheLastSuccessfulSet() {
        val storage = mock<StorageResolver>()
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        val manager = ScheduledTasksManager(storage)
        whenever(statement.executeQuery()).thenReturn(mock<ResultSet>())
        manager.refreshSystemApps()
        assertEquals(emptySet<String>(), manager.systemAppPackageNames)

        val rows = mock<ResultSet>()
        whenever(rows.next()).thenReturn(true, false)
        whenever(rows.getString("app_package_name")).thenReturn("android.system")
        whenever(statement.executeQuery()).thenReturn(rows)
        manager.refreshSystemApps()
        assertEquals(setOf("android.system"), manager.systemAppPackageNames)

        whenever(storage.getPlatformStorage()).thenAnswer { throw SQLTransientConnectionException("database unavailable") }
        manager.refreshSystemApps()
        assertEquals(setOf("android.system"), manager.systemAppPackageNames)
    }
}
