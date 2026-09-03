package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

/**
 * Pins the input guard on the iOS SensorKit ingestion path. The jsonb upload-buffer
 * write needs a real DB and is covered by the Testcontainers e2e suite; here we
 * assert with mocked collaborators that an oversized batch is rejected before any
 * storage is touched, and that exactly 10,000 passes the guard. Before this test the
 * service had no coverage.
 */
class SensorDataUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private lateinit var service: SensorDataUploadService

    @Before
    fun setUp() {
        service = SensorDataUploadService(storageResolver, studyService)
    }

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        val sample = Mockito.mock(SensorDataSample::class.java)
        val tooLarge = List(10_001) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), tooLarge)
            fail("Expected upload of ${tooLarge.size} samples to be rejected (max 10,000)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        // Neither collaborator should be touched once the guard trips.
        Mockito.verifyNoInteractions(storageResolver)
        Mockito.verifyNoInteractions(studyService)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandSamplesAtTheBatchBoundary() {
        val sample = Mockito.mock(SensorDataSample::class.java)
        val atLimit = List(10_000) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), atLimit)
        } catch (e: IllegalArgumentException) {
            fail("A batch of exactly 10,000 samples must pass the size guard, was rejected: ${e.message}")
        } catch (_: Exception) {
            // Expected: storage is mocked, so the call fails after the size guard passes.
        }
    }
}
