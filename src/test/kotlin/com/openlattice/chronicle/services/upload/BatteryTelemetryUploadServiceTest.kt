package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

/**
 * Pins the input guards on the battery_telemetry ingestion path (the newest
 * collection module). Two guards matter and are asserted with mocked storage:
 *  - an empty batch short-circuits to 0 WITHOUT opening a DB connection, and
 *  - an oversized batch (> 10,000) is rejected before any storage is touched.
 * The per-row insert + ON CONFLICT idempotency need a real DB and are covered by
 * the Testcontainers e2e suite. Before this test the service had no coverage.
 */
class BatteryTelemetryUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private lateinit var service: BatteryTelemetryUploadService

    @Before
    fun setUp() {
        service = BatteryTelemetryUploadService(storageResolver)
    }

    @Test
    fun emptyBatchReturnsZeroWithoutTouchingStorage() {
        val result = service.upload(UUID.randomUUID(), "p1", emptyList())
        assertEquals("An empty battery batch must be a no-op returning 0", 0, result)
        // No DB connection should be opened for an empty batch.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        val sample = Mockito.mock(BatterySample::class.java)
        val tooLarge = List(10_001) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", tooLarge)
            fail("Expected upload of ${tooLarge.size} samples to be rejected (max 10,000)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandSamplesAtTheBatchBoundary() {
        val sample = Mockito.mock(BatterySample::class.java)
        val atLimit = List(10_000) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", atLimit)
        } catch (e: IllegalArgumentException) {
            fail("A batch of exactly 10,000 samples must pass the size guard, was rejected: ${e.message}")
        } catch (_: Exception) {
            // Expected: storage is mocked, so the call fails after the size guard passes.
        }
    }
}
