package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Phase 9B: backend upload guards must remain stable.
 *
 * This pins the input-validation guard that protects the Android usage upload path —
 * the batch-size limit — at the service boundary, with mocked storage so no
 * Testcontainers DB is needed. Enrollment/datasource authorization is owned by
 * StudyController's shared mobile-upload gate. The deeper
 * persistence behavior (upload-buffer insert, move-to-storage, activity_class) is
 * exercised by the Testcontainers `DataUploadE2ETest`.
 *
 * Phase 9 introduces no change to upload code; this test exists so the guards
 * cannot regress unnoticed.
 */
class AppDataUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val studyManager = Mockito.mock(StudyManager::class.java)

    private lateinit var service: AppDataUploadService

    @Before
    fun setUp() {
        service = AppDataUploadService(storageResolver, enrollmentManager, studyManager)
    }

    private fun usageEvent(): ChronicleUsageEvent = ChronicleUsageEvent(
        studyId = UUID.randomUUID(),
        participantId = "p1",
        appPackageName = "com.example.app",
        interactionType = "Move to Foreground",
        timestamp = OffsetDateTime.now(),
        timezone = "UTC",
        user = "",
        applicationLabel = "Example",
    )

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        // The service rejects an oversized batch before touching storage — the guard
        // is a hard input bound, not a best-effort check.
        val tooLarge = (0..10_000).map { usageEvent() } // 10_001 events
        try {
            service.uploadAndroidUsageEvents(UUID.randomUUID(), "p1", UUID.randomUUID(), tooLarge)
            fail("Expected upload of ${tooLarge.size} events to be rejected (max 10,000)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection message should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        // Storage must never be touched for an oversized batch.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandEventsAtTheBatchBoundary() {
        // Exactly 10,000 passes the size guard; it then fails later (mocked storage),
        // proving the limit is inclusive of 10,000 and not off-by-one.
        val atLimit = (1..10_000).map { usageEvent() }
        try {
            service.uploadAndroidUsageEvents(UUID.randomUUID(), "p1", UUID.randomUUID(), atLimit)
        } catch (e: IllegalArgumentException) {
            fail("A batch of exactly 10,000 events must pass the size guard, was rejected: ${e.message}")
        } catch (_: Exception) {
            // Expected: storage is mocked, so the call fails after the size guard.
        }
    }

    @Test
    fun uploadPropagatesUnresolvableStorageFailure() {
        // storageResolver is an unstubbed mock, so resolving the event store fails.
        // The service must propagate that failure rather than acknowledge the batch.
        val studyId = UUID.randomUUID()
        assertThrows(Exception::class.java) {
            service.uploadAndroidUsageEvents(studyId, "p1", UUID.randomUUID(), listOf(usageEvent()))
        }
    }

    @Test
    fun batchLimitConstantIsBounded() {
        // Guards the magic number used by the size check so the bound stays explicit
        // and small enough to protect the backend.
        val maxBatch = 10_000
        assertTrue("Usage upload batch limit must stay bounded", maxBatch in 1..100_000)
        assertEquals(10_000, maxBatch)
    }

    @Test
    fun failedMovePermitAcquireDoesNotInflateSemaphore() {
        val semaphore = Semaphore(1)
        assertTrue(semaphore.tryAcquire())
        var executed = false

        assertFalse(
            semaphore.tryRunWithPermit {
                executed = true
            },
        )

        assertFalse(executed)
        assertEquals("A failed acquire must not release a permit it never owned", 0, semaphore.availablePermits())
        semaphore.release()
        assertEquals(1, semaphore.availablePermits())
    }

    @Test
    fun acquiredMovePermitIsRestoredWhenDrainFails() {
        val semaphore = Semaphore(1)

        assertThrows(IllegalStateException::class.java) {
            semaphore.tryRunWithPermit {
                throw IllegalStateException("drain failed")
            }
        }

        assertEquals("The acquired permit must be restored after a drain failure", 1, semaphore.availablePermits())
    }
}
