package com.openlattice.chronicle.auditing

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

class PostgresAuditingManagerTest {
    @Test fun deniedAttemptsCommitIndependentlyWhileSuccessJoinsTheReceipt() {
        val resolver = mock<StorageResolver>()
        val platform = mock<HikariDataSource>()
        val audit = mock<HikariDataSource>()
        val owner = mock<Connection>()
        val independent = mock<Connection>()
        val staged = mock<PreparedStatement>()
        val delivered = mock<PreparedStatement>()
        whenever(resolver.getPlatformStorage()).thenReturn(platform)
        whenever(resolver.getAuditStorage()).thenReturn(PostgresFlavor.VANILLA to audit)
        whenever(audit.connection).thenReturn(independent)
        whenever(owner.prepareStatement(any())).thenReturn(staged)
        whenever(independent.prepareStatement(any())).thenReturn(delivered)
        whenever(staged.executeBatch()).thenReturn(intArrayOf(0))
        whenever(delivered.executeBatch()).thenReturn(intArrayOf(1))
        val manager = PostgresAuditingManager(resolver)
        fun event(type: AuditEventType) = AuditableEvent(AclKey(UUID.randomUUID()), UUID.randomUUID(),
            Principal(PrincipalType.USER, "subject"), type)
        PinnedPlatformConnection.pinning(platform, owner) {
            assertEquals(2, manager.recordEvents(listOf(event(AuditEventType.UPDATE_STUDY), event(AuditEventType.ACCESS_DENIED))))
        }
        verify(independent).commit()
        verify(owner, never()).commit()
        verify(owner).prepareStatement(argThat { contains("audit_buffer") })
        verify(independent).prepareStatement(argThat { !contains("audit_buffer") })
    }
}
