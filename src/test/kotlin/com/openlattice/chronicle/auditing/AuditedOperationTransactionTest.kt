package com.openlattice.chronicle.auditing

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.SQLException

class AuditedOperationTransactionTest {
    @Test fun commitFailureNeverPublishesAnIndependentSuccessAudit() {
        val connection = mock<Connection>()
        val manager = mock<AuditingManager>()
        whenever(connection.autoCommit).thenReturn(true)
        doThrow(SQLException("commit failed")).whenever(connection).commit()
        var wrote = false
        assertThrows(SQLException::class.java) {
            AuditedOperation(connection, { wrote = true }, { emptyList<AuditableEvent>() }, manager).execute()
        }
        assertTrue(wrote)
        val order = inOrder(manager, connection)
        order.verify(manager).recordEvents(connection, emptyList())
        order.verify(connection).commit()
        verify(connection).rollback()
        verify(connection).autoCommit = true
        verify(manager, never()).recordEvents(any<List<AuditableEvent>>())
    }
}
