package com.openlattice.chronicle.auditing

import com.openlattice.chronicle.ScopedLogCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AuditingComponentBestEffortTest {
    @Test
    fun bestEffortReportsCreationAndPersistenceFailuresWithoutLoggingPrivateContent() {
        val manager = mock<AuditingManager>()
        val component = object : AuditingComponent { override val auditingManager = manager }
        val event = mock<AuditableEvent>()
        val sentinel = "private-audit-sentinel"
        ScopedLogCapture(AuditingComponent::class.java).use { logs ->
            assertFalse(component.recordEventBestEffort { throw IllegalStateException(sentinel) })
            whenever(manager.recordEvents(listOf(event))).thenThrow(IllegalStateException(sentinel))
            assertFalse(component.recordEventBestEffort { event })
            assertEquals(2, logs.messages.size)
            assertTrue(logs.messages.all { it.contains("Failed to record best-effort audit event") })
            assertFalse(logs.messages.any { it.contains(sentinel) })
            doReturn(1).whenever(manager).recordEvents(listOf(event))
            assertTrue(component.recordEventBestEffort { event })
            assertEquals(2, logs.messages.size)
        }
    }
}
