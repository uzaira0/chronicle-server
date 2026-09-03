package com.openlattice.chronicle.tasks

import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class RestoreContinuityInitializationTaskTest {
    @Test
    fun `initializer is blocking retryable and invokes reconciliation`() {
        val reconciler = Mockito.mock(RestoreContinuityReconciler::class.java)
        val task = RestoreContinuityInitializationTask()

        task.initialize(RestoreContinuityInitializationDependencies(reconciler))

        Mockito.verify(reconciler).reconcile()
        assertEquals("RESTORE_CONTINUITY_RECONCILIATION", task.name)
        assertEquals(0L, task.getInitialDelay())
        assertFalse("a failed distributed future must not poison every restart", task.isRunOnceAcrossCluster())
        assertTrue(
            task.after().contains(
                PostConstructInitializerTaskDependencies.PostConstructInitializerTask::class.java,
            ),
        )
    }

    @Test
    fun `reconciliation failure propagates and blocks startup`() {
        val reconciler = Mockito.mock(RestoreContinuityReconciler::class.java)
        val failure = IllegalStateException("checkpoint mismatch")
        Mockito.doThrow(failure).`when`(reconciler).reconcile()

        val thrown = assertThrows(IllegalStateException::class.java) {
            RestoreContinuityInitializationTask().initialize(
                RestoreContinuityInitializationDependencies(reconciler),
            )
        }

        assertEquals(failure, thrown)
    }
}
