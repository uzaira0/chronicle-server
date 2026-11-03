package com.openlattice.chronicle.auditing

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    @Test fun aDifferentConnectionCannotBorrowAnotherTransactionsCommitNotification() {
        val fixture = Fixture()
        val unrelated = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(unrelated.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeBatch()).thenReturn(intArrayOf(1))
        PinnedPlatformConnection.pinning(fixture.platform, fixture.owner) {
            assertThrows(IllegalStateException::class.java) {
                fixture.manager.recordEvents(unrelated, listOf(event(AuditEventType.UPDATE_STUDY)))
            }
        }
        verify(unrelated, never()).prepareStatement(any())
    }

    @Test fun borrowedAndUnpinnedSuccessesReachSiemOnlyAfterTheOwnerCommits() {
        com.openlattice.chronicle.ScopedLogCapture("AUDIT").use { capture ->
            for (borrowed in listOf(false, true)) {
                val fixture = Fixture()
                org.mockito.kotlin.doThrow(java.sql.SQLException("commit failed")).whenever(fixture.owner).commit()
                capture.messages.clear()
                assertThrows(java.sql.SQLException::class.java) {
                    if (borrowed) PinnedPlatformConnection.committing(fixture.platform, fixture.owner) {
                        val proxy = PinnedPlatformConnection.resolve(fixture.platform).connection
                        fixture.manager.recordEvents(proxy, listOf(event(AuditEventType.UPDATE_QUESTIONNAIRE)))
                        assertEquals(0, capture.messages.size)
                    } else AuditedOperation(fixture.owner, { Unit },
                        { listOf(event(AuditEventType.UPDATE_STUDY)) }, fixture.manager).execute()
                }
                assertEquals(0, capture.messages.size)
            }
            val fixture = Fixture()
            AuditedOperation(fixture.owner, { Unit }, { listOf(event(AuditEventType.UPDATE_STUDY)) }, fixture.manager).execute()
            assertEquals(1, capture.messages.size)
        }
    }

    @Test fun successWithoutTransactionOwnerFailsBeforeOpeningAnIndependentConnection() {
        val fixture = Fixture()
        assertThrows(IllegalStateException::class.java) {
            fixture.manager.recordEvents(listOf(event(AuditEventType.UPDATE_STUDY)))
        }
        verify(fixture.audit, never()).connection
    }

    @Test fun independentReadObservationsCannotClaimMutationSuccess() {
        val fixture = Fixture()
        val read = event(AuditEventType.DOWNLOAD_PARTICIPANTS_DATA)
        assertThrows(IllegalStateException::class.java) { fixture.manager.recordEvents(listOf(read)) }
        assertEquals(1, fixture.manager.recordObservations(AuditObservation.Read(listOf(read))))
        assertThrows(IllegalArgumentException::class.java) {
            AuditObservation.Read(listOf(event(AuditEventType.UPDATE_STUDY)))
        }
        verify(fixture.independent).commit()
    }

    @Test fun deniedAttemptsCommitIndependentlyWhileSuccessJoinsTheReceipt() {
        val fixture = Fixture()
        PinnedPlatformConnection.pinning(fixture.platform, fixture.owner) {
            assertEquals(2, fixture.manager.recordEvents(listOf(
                event(AuditEventType.UPDATE_STUDY), event(AuditEventType.ACCESS_DENIED),
            )))
        }
        verify(fixture.independent).commit()
        verify(fixture.owner, never()).commit()
        verify(fixture.owner).prepareStatement(argThat { contains("audit_buffer") })
        verify(fixture.independent).prepareStatement(argThat { !contains("audit_buffer") })
    }

    private fun event(type: AuditEventType) = AuditableEvent(AclKey(UUID.randomUUID()), UUID.randomUUID(),
        Principal(PrincipalType.USER, "subject"), type)

    private class Fixture {
        val resolver = mock<StorageResolver>()
        val platform = mock<HikariDataSource>()
        val audit = mock<HikariDataSource>()
        val owner = mock<Connection>()
        val independent = mock<Connection>()
        private val staged = mock<PreparedStatement>()
        private val delivered = mock<PreparedStatement>()
        init {
            whenever(resolver.getPlatformStorage()).thenReturn(platform)
            whenever(resolver.getAuditStorage()).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, audit))
            whenever(audit.connection).thenReturn(independent)
            whenever(owner.prepareStatement(any())).thenReturn(staged)
            whenever(independent.prepareStatement(any())).thenReturn(delivered)
            whenever(staged.executeBatch()).thenReturn(intArrayOf(0))
            whenever(delivered.executeBatch()).thenReturn(intArrayOf(1))
        }
        val manager = PostgresAuditingManager(resolver)
    }
}
