package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.aop.framework.ProxyFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class DataDeletionOrchestratorProxyTest {
    @Test
    fun restoredStudyErasureDelegatesThroughSpringProxy() {
        val storage = Mockito.mock(StorageResolver::class.java)
        val source = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(source)
        Mockito.`when`(source.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(any())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true, false)
        Mockito.`when`(rows.getBoolean(1)).thenReturn(true)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        val target = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
        val proxy = ProxyFactory(target).apply { isProxyTargetClass = true }.proxy as DataDeletionOrchestrator
        val operation = RestoredDeletionOperation(
            UUID.randomUUID(), UUID.randomUUID(), null, null, null, DataDeletionMode.STUDY_ERASURE,
            "CANCELLED", "researcher", UUID.randomUUID(), null, "researcher", java.time.OffsetDateTime.now(), false,
        )
        assertFalse(proxy.reconcileRestoredOperation(operation, emptyList()))
        Mockito.verify(storage).requireDeletionStorageColocated(operation.studyId)
        Mockito.verify(connection).commit()
    }
}
