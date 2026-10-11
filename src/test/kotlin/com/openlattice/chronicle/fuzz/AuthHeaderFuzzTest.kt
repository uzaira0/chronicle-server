package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import org.springframework.mock.web.MockHttpServletRequest
import org.junit.Assert.assertTrue

/**
 * Fuzz tests for Authorization header parsing.
 *
 * Goals:
 * - Bearer token extraction never throws an unhandled exception
 * - Handles null, empty, malformed, and adversarial header values gracefully
 */
class AuthHeaderFuzzTest {

    private val resolver = ChronicleCookieOrBearerTokenResolver()

    private fun resolveHeader(headerValue: String?): String? = resolver.resolve(
        MockHttpServletRequest("GET", "/chronicle/v4/study").apply {
            if (headerValue != null) addHeader("Authorization", headerValue)
        },
    )

    @FuzzTest(maxDuration = "30s")
    fun bearerTokenExtractionDoesNotCrash(data: FuzzedDataProvider) {
        val headerValue = data.consumeRemainingAsString()
        val token = resolveHeader(headerValue)

        if (token != null) {
            assertTrue("Resolved token must come from the supplied header", headerValue.contains(token))
        }
    }

    @FuzzTest(maxDuration = "30s")
    fun nullAndEdgeCaseHeaders(data: FuzzedDataProvider) {
        // Also test with null
        resolveHeader(null)

        // Test with the fuzzed data prepended with various prefixes
        val raw = data.consumeRemainingAsString()
        val prefixes = listOf("", "Bearer ", "bearer ", "BEARER ", "Basic ", "Token ", "  Bearer  ")
        for (prefix in prefixes) {
            try {
                resolveHeader(prefix + raw)
            } catch (e: Exception) {
                throw AssertionError("Unexpected exception for header '${prefix + raw}': ${e.message}", e)
            }
        }
    }
}
