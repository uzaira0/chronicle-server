package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.util.RedirectValidator

/**
 * Fuzz tests for RedirectValidator.
 *
 * Goals:
 * - isValidRedirect never throws an unexpected exception
 * - No input bypasses the dangerous-pattern blocklist
 * - Protocol-relative URLs (//evil.com) are always rejected
 * - javascript:, data:, vbscript: schemes are always rejected
 * - Newline injection is always caught
 * - Double-encoding bypasses are detected
 */
class RedirectValidatorFuzzTest {

    private val validator = RedirectValidator(
        allowedDomains = setOf("trusted.example.com", "sso.example.edu"),
        allowRelativePaths = true,
        strictHostMatching = true
    )

    private val strictValidator = RedirectValidator(
        allowedDomains = emptySet(),
        allowRelativePaths = false,
        strictHostMatching = true
    )

    private fun mockRequest(
        serverName: String = "app.example.com",
        serverPort: Int = 443,
        scheme: String = "https"
    ) = FuzzTestConstants.mockHttpServletRequest(serverName, serverPort, scheme)

    @FuzzTest(maxDuration = "5m")
    fun fuzzIsValidRedirect(input: String) {
        val request = mockRequest()
        // Must never throw — only return true/false
        val result = validator.isValidRedirect(request, input)

        if (result) {
            val lower = input.lowercase()
            // If accepted, verify it does not contain known-dangerous schemes
            assert(!lower.startsWith("javascript:")) {
                "Accepted javascript: URL: $input"
            }
            assert(!lower.startsWith("data:")) {
                "Accepted data: URL: $input"
            }
            assert(!lower.startsWith("vbscript:")) {
                "Accepted vbscript: URL: $input"
            }
            // Must not contain raw newlines
            assert(!input.contains('\n') && !input.contains('\r')) {
                "Accepted URL with newline injection: $input"
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzProtocolRelativeBypass(data: ByteArray) {
        val request = mockRequest()
        // Generate inputs that try to look like relative paths but redirect externally
        val bypasses = listOf(
            "//evil.com",
            "///evil.com",
            "/\\evil.com",
            "\\/evil.com",
            "/%2fevil.com",
            "/%5cevil.com",
            "/\u0000//evil.com",
            "/%00//evil.com"
        )
        val prefix = String(data, Charsets.UTF_8).take(20)
        for (bypass in bypasses) {
            val input = prefix + bypass
            val result = validator.isValidRedirect(request, input)
            // Protocol-relative URLs to evil.com must be rejected
            // (Unless the prefix happens to form a valid same-origin URL)
            if (result && input.contains("evil.com")) {
                // Verify it's actually same-origin or allowed domain
                assert(
                    input.contains("app.example.com") ||
                    input.contains("trusted.example.com") ||
                    input.contains("sso.example.edu")
                ) {
                    "Protocol-relative bypass accepted: $input"
                }
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzDoubleEncodingBypass(data: ByteArray) {
        val request = mockRequest()
        // Attempt double/triple encoding to bypass validation
        val encodings = listOf(
            "%252F%252F",     // double-encoded //
            "%25252F%25252F", // triple-encoded //
            "%252F%255C",     // double-encoded /\
            "%2525",          // double-encoded %
            "%00",            // null byte
            "%250a",          // double-encoded newline
            "%250d"           // double-encoded carriage return
        )
        val base = String(data, Charsets.UTF_8).take(30)
        for (encoding in encodings) {
            val input = "https://evil.com${encoding}$base"
            val result = validator.isValidRedirect(request, input)
            if (result) {
                // If accepted, it must resolve to an allowed domain
                assert(false) {
                    "Double-encoding bypass accepted for evil.com: $input"
                }
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzStrictNoRelativePaths(input: String) {
        val request = mockRequest()
        val result = strictValidator.isValidRedirect(request, input)

        if (result) {
            // Strict validator with no allowed domains and no relative paths
            // should only accept same-origin absolute URLs
            assert(input.lowercase().startsWith("http://") || input.lowercase().startsWith("https://")) {
                "Strict validator accepted non-absolute URL: $input"
            }
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzGetSafeRedirectUrl(input: String) {
        val request = mockRequest()
        val result = validator.getSafeRedirectUrl(request, input, "/dashboard")

        // Must always return either the validated URL or the fallback
        assert(result == input || result == "/dashboard") {
            "getSafeRedirectUrl returned unexpected value: $result"
        }
    }
}
