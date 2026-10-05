package com.openlattice.chronicle.configuration

import org.junit.Assert.assertTrue
import org.junit.Test

class ResearcherWriteCorsTest {
    @Test fun browserCanSendCreateAndEditPreconditionsAndReadSnapshotRevision() {
        val config = CorsConfiguration()
        assertTrue(config.allowedHeaders.containsAll(listOf("Idempotency-Key", "If-Match")))
        assertTrue(config.exposedHeaders.contains("ETag"))
    }
}
