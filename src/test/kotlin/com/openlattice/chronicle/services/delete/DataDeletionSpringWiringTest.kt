package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.upgrades.RuntimeDatabaseValidationService
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class DataDeletionSpringWiringTest : ChronicleServerTests() {

    @Test
    fun `public deletion methods dispatch to the initialized Spring target`() {
        val orchestrator = testServer.context.getBean(DataDeletionOrchestrator::class.java)

        val failure = assertThrows(NoSuchElementException::class.java) {
            orchestrator.getOperation(UUID.randomUUID())
        }

        assertEquals("Deletion operation not found", failure.message)
    }

    @Test
    fun `backend validates its runtime login without an owner reconciliation bean`() {
        assertTrue(testServer.context.getBeansOfType(RestoreContinuityReconciler::class.java).isEmpty())
        testServer.context.getBean(RuntimeDatabaseValidationService::class.java).runUpgrade()
    }
}
