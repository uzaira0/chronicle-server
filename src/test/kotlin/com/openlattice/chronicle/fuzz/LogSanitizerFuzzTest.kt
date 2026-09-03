package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.util.LogSanitizer

/**
 * Fuzz tests for LogSanitizer.
 *
 * Goals:
 * - sanitize() never returns a string containing raw newlines (\n, \r)
 * - sanitize() never returns a string containing ANSI escape sequences
 * - sanitize() never returns a string containing null bytes
 * - sanitize() never returns a string longer than maxLength
 * - sanitize() never throws an unexpected exception
 * - Unicode control characters are stripped/replaced
 */
class LogSanitizerFuzzTest {

    /**
     * Pattern that must never appear in sanitized output.
     * Raw newlines, carriage returns, null bytes, and ANSI escapes.
     */
    private val dangerousPattern = Regex("[\r\n\u0000\u001B]")

    /**
     * Unicode directional overrides and zero-width chars that should be replaced.
     */
    private val unicodeControlPattern = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")

    @FuzzTest(maxDuration = "5m")
    fun fuzzSanitize(input: String) {
        val result = LogSanitizer.sanitize(input)

        // Must never contain raw dangerous characters
        assert(!dangerousPattern.containsMatchIn(result)) {
            "sanitize() output contains dangerous characters: ${result.take(200)}"
        }

        // Must never contain Unicode control characters
        assert(!unicodeControlPattern.containsMatchIn(result)) {
            "sanitize() output contains Unicode control characters: ${result.take(200)}"
        }

        // Must respect default max length
        assert(result.length <= LogSanitizer.DEFAULT_MAX_LENGTH) {
            "sanitize() output exceeds max length: ${result.length} > ${LogSanitizer.DEFAULT_MAX_LENGTH}"
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSanitizeWithCustomLength(data: ByteArray) {
        if (data.size < 4) return
        // Use first 2 bytes as maxLength (10..2000), rest as input
        val maxLength = ((data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF))
            .coerceIn(LogSanitizer.MIN_MAX_LENGTH, 2000)
        val input = String(data, 2, data.size - 2, Charsets.UTF_8)

        val result = LogSanitizer.sanitize(input, maxLength)

        assert(!dangerousPattern.containsMatchIn(result)) {
            "sanitize(maxLength=$maxLength) output contains dangerous characters"
        }
        assert(result.length <= maxLength) {
            "sanitize() output ${result.length} exceeds maxLength $maxLength"
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSanitizeQuoted(input: String) {
        val result = LogSanitizer.sanitizeQuoted(input)

        // Must be wrapped in double quotes
        assert(result.startsWith("\"") && result.endsWith("\"")) {
            "sanitizeQuoted() output not properly quoted: ${result.take(50)}"
        }

        // Inner content must not contain raw dangerous characters
        val inner = result.substring(1, result.length - 1)
        assert(!dangerousPattern.containsMatchIn(inner)) {
            "sanitizeQuoted() inner content contains dangerous characters"
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSanitizeIp(input: String) {
        val result = LogSanitizer.sanitizeIp(input)

        // Must never contain raw dangerous characters
        assert(!dangerousPattern.containsMatchIn(result)) {
            "sanitizeIp() output contains dangerous characters: $result"
        }

        // Output must be bounded in length
        assert(result.length <= LogSanitizer.MAX_IP_OUTPUT_LENGTH) {
            "sanitizeIp() output unexpectedly long: ${result.length} > ${LogSanitizer.MAX_IP_OUTPUT_LENGTH}"
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSanitizeUri(input: String) {
        val result = LogSanitizer.sanitizeUri(input)

        // Must not contain raw newlines or null bytes
        assert(!result.contains('\n') && !result.contains('\r') && !result.contains('\u0000')) {
            "sanitizeUri() output contains raw control characters"
        }

        // Must respect default URI max length
        assert(result.length <= LogSanitizer.DEFAULT_URI_MAX_LENGTH) {
            "sanitizeUri() output exceeds default max length: ${result.length} > ${LogSanitizer.DEFAULT_URI_MAX_LENGTH}"
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzLogInjectionAttacks(data: ByteArray) {
        // Craft inputs that attempt log injection
        val attacks = listOf(
            "normal\nINFO  Fake log entry injected",
            "normal\r\nWARN  Spoofed warning",
            "value\u001B[31mRED_TEXT\u001B[0m",
            "test\u0000hidden",
            "normal\u202Eesrever",  // Right-to-left override
            "test\u200Bzero-width"
        )
        val base = String(data, Charsets.UTF_8)
        for (attack in attacks) {
            val input = base + attack
            val result = LogSanitizer.sanitize(input)
            assert(!dangerousPattern.containsMatchIn(result)) {
                "Log injection attack bypassed sanitizer"
            }
            assert(!unicodeControlPattern.containsMatchIn(result)) {
                "Unicode control injection bypassed sanitizer"
            }
        }
    }
}
