package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.services.participantaccess.ParticipantSessionSecrets
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class ParticipantFormAccessAdmissionTest {
    private val storage = mock<StorageResolver>()
    private val pool = mock<HikariDataSource>()
    private val connection = mock<Connection>()
    private val statement = mock<PreparedStatement>()
    private val rows = mock<ResultSet>()
    private val audit = mock<AuditingManager>()
    private val service = participantFormAccessServiceForTest(storage, auditingManager = audit)

    init {
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.autoCommit).thenReturn(true)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(rows)
    }

    @Test
    fun garbageCookiesCostAtMostOneIndexedReadAndReleaseTheirTransaction() {
        assertFalse(service.isAdmittedSession("garbage", null, false))
        verifyNoInteractions(storage)
        val prior = RLSRequestContext.current()
        assertFalse(service.isAdmittedSession(ParticipantSessionSecrets.generateToken(), null, false))
        assertSame(prior, RLSRequestContext.current())
        val sql = argumentCaptor<String>()
        verify(connection).prepareStatement(sql.capture())
        assertTrue(sql.firstValue.contains("WHERE session_hash = ?"))
        verify(statement).executeQuery()
        verify(statement, never()).executeUpdate()
        verify(connection, never()).createStatement()
        verify(connection, never()).commit()
        val order = inOrder(connection)
        order.verify(connection).rollback()
        order.verify(connection).autoCommit = true
        order.verify(connection).close()
        verifyNoInteractions(audit)
    }

    @Test
    fun databaseFailureRemainsASqlExceptionEvenWhenCleanupFails() {
        val failure = SQLException("lookup unavailable", "08006")
        doThrow(failure).whenever(statement).executeQuery()
        doThrow(SQLException("rollback unavailable", "08006")).whenever(connection).rollback()
        assertSame(failure, assertThrows(SQLException::class.java) {
            service.isAdmittedSession(ParticipantSessionSecrets.generateToken(), null, false)
        })
        assertEquals("rollback unavailable", failure.suppressed.single().message)
        verify(connection, never()).commit()
        verify(connection, never()).autoCommit = true
        verify(connection).close()
        verifyNoInteractions(audit)
    }
}
