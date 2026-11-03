package com.openlattice.chronicle.auditing

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock

class AuditingComponentFailureTest {
    @Test
    fun bestEffortPropagatesErrorsFromConstructionAndPersistence() {
        val failure = AssertionError("audit failure")
        val component = object : AuditingComponent {
            override val auditingManager = mock<AuditingManager>()
            override fun recordEvent(event: AuditableEvent): Int = throw failure
        }
        assertSame(failure, assertThrows(AssertionError::class.java) {
            component.recordEventBestEffort { throw failure }
        })
        assertSame(failure, assertThrows(AssertionError::class.java) {
            component.recordEventBestEffort { mock<AuditableEvent>() }
        })
    }
}
