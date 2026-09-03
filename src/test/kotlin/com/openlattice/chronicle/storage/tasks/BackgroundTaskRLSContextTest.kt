package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BackgroundTaskRLSContextTest {
    private class ProbeComplete : RuntimeException()

    @After
    fun tearDown() {
        RLSRequestContext.clear()
    }

    @Test
    fun `system context restores an enclosing request context`() {
        val requestContext = RLSConnectionContext(
            principalId = "request-user",
            authorizedStudyIds = setOf(UUID.randomUUID()),
            isAdmin = false,
        )
        RLSRequestContext.set(requestContext)

        RLSRequestContext.withSystemContext {
            val current = requireNotNull(RLSRequestContext.current())
            assertEquals("chronicle-background", current.principalId)
            assertTrue(current.isAdmin)
            assertTrue(current.authorizedStudyIds.isEmpty())
        }

        assertEquals(requestContext, RLSRequestContext.current())
    }

    @Test
    fun `system context clears after an exception`() {
        assertThrows(ProbeComplete::class.java) {
            RLSRequestContext.withSystemContext {
                throw ProbeComplete()
            }
        }

        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `deletion worker context restores an enclosing request context`() {
        val requestContext = RLSConnectionContext(
            principalId = "request-user",
            authorizedStudyIds = setOf(UUID.randomUUID()),
            isAdmin = false,
        )
        RLSRequestContext.set(requestContext)

        RLSRequestContext.withDeletionWorkerContext {
            assertDeletionWorkerContext(RLSRequestContext.current())
        }

        assertEquals(requestContext, RLSRequestContext.current())
    }

    @Test
    fun `deletion worker context clears after an exception`() {
        assertThrows(ProbeComplete::class.java) {
            RLSRequestContext.withDeletionWorkerContext {
                throw ProbeComplete()
            }
        }

        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `export worker is restricted to exactly one study and restores its caller`() {
        val outer = RLSConnectionContext(
            principalId = "request-user",
            authorizedStudyIds = setOf(UUID.randomUUID()),
            isAdmin = true,
        )
        val exportStudy = UUID.randomUUID()
        RLSRequestContext.set(outer)

        RLSRequestContext.withExportWorkerContext("export-owner", exportStudy) {
            val current = requireNotNull(RLSRequestContext.current())
            assertEquals("export-owner", current.principalId)
            assertEquals(setOf(exportStudy), current.authorizedStudyIds)
            assertTrue(!current.isAdmin)
        }

        assertEquals(outer, RLSRequestContext.current())
    }

    @Test
    fun `export worker context clears after an exception`() {
        assertThrows(ProbeComplete::class.java) {
            RLSRequestContext.withExportWorkerContext("export-owner", UUID.randomUUID()) {
                throw ProbeComplete()
            }
        }

        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `deletion orchestrator establishes worker context before borrowing storage`() {
        val seen = AtomicReference<RLSConnectionContext?>()
        val storageResolver = mock<StorageResolver>()
        whenever(storageResolver.getPlatformStorage()).thenAnswer {
            seen.set(RLSRequestContext.current())
            throw ProbeComplete()
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, mock<AuditingManager>())

        assertThrows(ProbeComplete::class.java) {
            orchestrator.processDueOperations(limit = 1)
        }

        assertDeletionWorkerContext(seen.get())
        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `ios mover establishes system context inside its executor thread`() {
        val seen = AtomicReference<RLSConnectionContext?>()
        val dependencies = probingDependencies(seen)
        val mover = object : MoveToIosEventStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }

        mover.runTask()

        assertSystemContext(seen.get())
    }

    @Test
    fun `android sensor mover establishes system context inside its executor thread`() {
        val seen = AtomicReference<RLSConnectionContext?>()
        val dependencies = probingDependencies(seen)
        val mover = object : MoveAndroidSensorDataToStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }

        assertThrows(IllegalStateException::class.java) {
            mover.runTask()
        }

        assertSystemContext(seen.get())
    }

    private fun probingDependencies(
        seen: AtomicReference<RLSConnectionContext?>,
    ): MoveToEventStorageTaskDependencies {
        val storageResolver = mock<StorageResolver>()
        whenever(storageResolver.getPlatformStorage()).thenAnswer {
            seen.set(RLSRequestContext.current())
            throw ProbeComplete()
        }
        return MoveToEventStorageTaskDependencies(storageResolver, mock<StudyManager>())
    }

    private fun assertSystemContext(context: RLSConnectionContext?) {
        val actual = requireNotNull(context)
        assertEquals("chronicle-background", actual.principalId)
        assertTrue(actual.isAdmin)
        assertTrue(actual.authorizedStudyIds.isEmpty())
    }

    private fun assertDeletionWorkerContext(context: RLSConnectionContext?) {
        val actual = requireNotNull(context)
        assertEquals("chronicle-deletion-worker", actual.principalId)
        assertTrue(actual.isAdmin)
        assertTrue(actual.authorizedStudyIds.isEmpty())
    }
}
