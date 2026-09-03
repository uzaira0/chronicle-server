package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.tasks.RestoreContinuityInitializationDependencies
import com.openlattice.chronicle.tasks.RestoreContinuityInitializationTask
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class DataDeletionSpringWiringTest : ChronicleServerTests() {

    @Test
    fun `public deletion methods dispatch to the initialized Spring target`() {
        val orchestrator = testServer.context.getBean(DataDeletionOrchestrator::class.java)

        val failure = assertThrows(IllegalStateException::class.java) {
            orchestrator.getOperation(UUID.randomUUID())
        }

        assertEquals("Deletion operation not found", failure.message)
    }

    @Test
    fun `restore continuity startup gate and dependencies are wired`() {
        assertNotNull(testServer.context.getBean(RestoreContinuityReconciler::class.java))
        assertNotNull(testServer.context.getBean(RestoreContinuityInitializationDependencies::class.java))
        assertNotNull(testServer.context.getBean(RestoreContinuityInitializationTask::class.java))
    }
}
