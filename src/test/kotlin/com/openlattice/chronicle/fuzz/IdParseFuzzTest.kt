package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import java.util.UUID

/**
 * Fuzz tests for UUID parsing.
 *
 * Goals:
 * - UUID.fromString never throws anything other than IllegalArgumentException
 * - No crash on arbitrary string input
 */
class IdParseFuzzTest {

    @FuzzTest(maxDuration = "30s")
    fun uuidParserDoesNotCrash(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()
        try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            // expected for non-UUID strings
        }
    }
}
