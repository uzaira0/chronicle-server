package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

/**
 * Fuzz tests for Authorization header parsing.
 *
 * Goals:
 * - Bearer token extraction never throws an unhandled exception
 * - Handles null, empty, malformed, and adversarial header values gracefully
 */
class AuthHeaderFuzzTest {

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }

    /**
     * Simulates the common pattern of extracting a Bearer token from an Authorization header.
     * Returns null if the header is not a valid Bearer token.
     */
    private fun extractBearerToken(headerValue: String?): String? {
        if (headerValue == null) return null
        val trimmed = headerValue.trim()
        if (!trimmed.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        val token = trimmed.substring(BEARER_PREFIX.length).trim()
        return token.ifEmpty { null }
    }

    @FuzzTest(maxDuration = "30s")
    fun bearerTokenExtractionDoesNotCrash(data: FuzzedDataProvider) {
        val headerValue = data.consumeRemainingAsString()
        val token = extractBearerToken(headerValue)

        // If extraction succeeded, the result must be non-empty
        if (token != null) {
            assert(token.isNotEmpty()) {
                "Extracted an empty bearer token from: $headerValue"
            }
        }
    }

    @FuzzTest(maxDuration = "30s")
    fun nullAndEdgeCaseHeaders(data: FuzzedDataProvider) {
        // Also test with null
        extractBearerToken(null)

        // Test with the fuzzed data prepended with various prefixes
        val raw = data.consumeRemainingAsString()
        val prefixes = listOf("", "Bearer ", "bearer ", "BEARER ", "Basic ", "Token ", "  Bearer  ")
        for (prefix in prefixes) {
            try {
                extractBearerToken(prefix + raw)
            } catch (e: Exception) {
                throw AssertionError("Unexpected exception for header '${prefix + raw}': ${e.message}", e)
            }
        }
    }
}
