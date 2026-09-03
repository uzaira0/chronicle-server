package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

/**
 * Pins the input guard + idempotency-key computation on the envelope-encrypted ingestion
 * path (HIPAA-2028 W2). The blind BYTEA insert itself needs a real DB and is covered by
 * [com.openlattice.chronicle.storage.rls.EncryptedPayloadsRlsTest]; here — with mocked
 * storage — we assert an oversized batch is rejected BEFORE any storage is touched, the
 * 10,000 limit is inclusive, an empty batch is a no-op, and content_hash is a
 * deterministic, order-sensitive SHA-256 (the dedup key).
 */
class EncryptedPayloadUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private lateinit var service: EncryptedPayloadUploadService

    private fun envelope() = EncryptedEnvelope(
        keyId = "key-1",
        payloadType = EncryptedPayloadType.SENSOR,
        encryptedKey = "QUJD",
        iv = "REVG",
        ciphertext = "R0hJ",
        sampleCount = 1,
    )

    @Before
    fun setUp() {
        service = EncryptedPayloadUploadService(storageResolver)
    }

    @Test
    fun emptyBatchIsANoOpAndTouchesNoStorage() {
        assertEquals(0, service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), emptyList()))
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        val tooLarge = List(10_001) { envelope() }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), tooLarge)
            fail("Expected upload of ${tooLarge.size} envelopes to be rejected (max 10,000)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        // The guard runs before any storage access — storage must never be touched.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandAtTheBatchBoundary() {
        val atLimit = List(10_000) { envelope() }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), atLimit)
        } catch (e: IllegalArgumentException) {
            fail("A batch of exactly 10,000 envelopes must pass the size guard, was rejected: ${e.message}")
        } catch (_: Exception) {
            // Expected: storage is mocked, so the call fails after the size guard passes.
        }
    }

    @Test
    fun contentHashIsDeterministicOrderSensitiveSha256() {
        val a = byteArrayOf(1, 1)
        val b = byteArrayOf(2, 2)
        val c = byteArrayOf(3, 3)
        val h1 = EncryptedPayloadUploadService.contentHash(a, b, c)
        val h2 = EncryptedPayloadUploadService.contentHash(a, b, c)
        assertEquals("SHA-256 digest width", 32, h1.size)
        assertArrayEquals(h1, h2)
        // Field order is part of the hash, so a permutation must differ.
        assertFalse(h1.contentEquals(EncryptedPayloadUploadService.contentHash(b, a, c)))
    }
}
