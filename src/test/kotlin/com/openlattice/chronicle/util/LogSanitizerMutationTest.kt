package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, example-based tests for [LogSanitizer] pinning exact outputs,
 * truncation boundaries, and branch behaviour so PIT mutants are killed.
 */
class LogSanitizerMutationTest {

    @Test
    fun `sanitize null returns null marker`() = assertEquals("[null]", LogSanitizer.sanitize(null))

    @Test
    fun `sanitize empty returns empty marker`() = assertEquals("[empty]", LogSanitizer.sanitize(""))

    @Test
    fun `sanitize plain string is unchanged`() = assertEquals("hello-world", LogSanitizer.sanitize("hello-world"))

    @Test
    fun `sanitize escapes newlines and carriage returns`() {
        assertEquals("a\\r\\nb", LogSanitizer.sanitize("a\r\nb"))
        assertEquals("a\\nb", LogSanitizer.sanitize("a\nb"))
        assertEquals("a\\rb", LogSanitizer.sanitize("a\rb"))
    }

    @Test
    fun `sanitize escapes tabs`() = assertEquals("a\\tb", LogSanitizer.sanitize("a\tb"))

    @Test
    fun `sanitize replaces control characters with hex placeholder`() {
        assertEquals("[0x01]", LogSanitizer.sanitize("\u0001"))
        assertEquals("[0x7F]", LogSanitizer.sanitize("\u007F"))
    }

    @Test
    fun `sanitize strips ansi escape sequences`() {
        assertEquals("[ESC]red", LogSanitizer.sanitize("\u001B[31mred"))
    }

    @Test
    fun `sanitize strips unicode directional override`() {
        // U+202E Right-to-Left Override
        assertEquals("[UCTL]x", LogSanitizer.sanitize("\u202Ex"))
    }

    @Test
    fun `sanitize truncates with suffix when over max length`() {
        val input = "x".repeat(2000)
        val out = LogSanitizer.sanitize(input, maxLength = 50)
        assertEquals(50, out.length)
        assertTrue(out.endsWith("...[truncated]"))
    }

    @Test
    fun `sanitize at exactly max length is not truncated`() {
        val input = "x".repeat(50)
        val out = LogSanitizer.sanitize(input, maxLength = 50)
        assertEquals(input, out)
    }

    @Test
    fun `sanitize one over max length is truncated`() {
        val input = "x".repeat(51)
        val out = LogSanitizer.sanitize(input, maxLength = 50)
        assertEquals(50, out.length)
        assertTrue(out.endsWith("...[truncated]"))
    }

    @Test
    fun `sanitize coerces tiny max length up to MIN and truncates without suffix when suffix too long`() {
        // MIN_MAX_LENGTH = 10, which is < TRUNCATION_SUFFIX length (14),
        // so the else-branch (plain substring, no suffix) is taken.
        val out = LogSanitizer.sanitize("abcdefghijklmnop", maxLength = 1)
        assertEquals(10, out.length)
        assertEquals("abcdefghij", out)
    }

    @Test
    fun `sanitizeQuoted wraps in double quotes`() = assertEquals("\"hi\"", LogSanitizer.sanitizeQuoted("hi"))

    @Test
    fun `sanitizeMap null and empty markers`() {
        assertEquals("[null]", LogSanitizer.sanitizeMap(null))
        assertEquals("[empty]", LogSanitizer.sanitizeMap(emptyMap()))
    }

    @Test
    fun `sanitizeMap renders sanitized entries`() {
        assertEquals("{k=v}", LogSanitizer.sanitizeMap(mapOf("k" to "v")))
    }

    @Test
    fun `sanitizeMap handles null value`() {
        assertEquals("{k=[null]}", LogSanitizer.sanitizeMap(mapOf("k" to null)))
    }

    @Test
    fun `sanitizeMap truncates entries beyond maxEntries with count suffix`() {
        val map = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val out = LogSanitizer.sanitizeMap(map, maxEntries = 2)
        assertEquals("{a=1, b=2, ...[1 more entries]}", out)
    }

    @Test
    fun `sanitizeMap at exactly maxEntries has no suffix`() {
        val map = linkedMapOf("a" to "1", "b" to "2")
        val out = LogSanitizer.sanitizeMap(map, maxEntries = 2)
        assertEquals("{a=1, b=2}", out)
    }

    @Test
    fun `sanitizeCollection null and empty markers`() {
        assertEquals("[null]", LogSanitizer.sanitizeCollection(null))
        assertEquals("[empty]", LogSanitizer.sanitizeCollection(emptyList()))
    }

    @Test
    fun `sanitizeCollection renders sanitized items`() {
        assertEquals("[a, b]", LogSanitizer.sanitizeCollection(listOf("a", "b")))
    }

    @Test
    fun `sanitizeCollection truncates items beyond maxItems with count suffix`() {
        val out = LogSanitizer.sanitizeCollection(listOf("a", "b", "c", "d"), maxItems = 2)
        assertEquals("[a, b, ...[2 more items]]", out)
    }

    @Test
    fun `sanitizeCollection at exactly maxItems has no suffix`() {
        val out = LogSanitizer.sanitizeCollection(listOf("a", "b"), maxItems = 2)
        assertEquals("[a, b]", out)
    }

    @Test
    fun `sanitizeIp null marker`() = assertEquals("[null-ip]", LogSanitizer.sanitizeIp(null))

    @Test
    fun `sanitizeIp valid ipv4 unchanged`() = assertEquals("192.168.1.1", LogSanitizer.sanitizeIp("192.168.1.1"))

    @Test
    fun `sanitizeIp valid ipv6 unchanged`() {
        assertEquals("2001:db8::1", LogSanitizer.sanitizeIp("2001:db8::1"))
    }

    @Test
    fun `sanitizeIp at exactly 45 chars is accepted`() {
        val ip45 = "f".repeat(45) // all valid hex chars, length == 45 boundary
        assertEquals(ip45, LogSanitizer.sanitizeIp(ip45))
    }

    @Test
    fun `sanitizeIp over 45 chars is marked invalid`() {
        val ip46 = "f".repeat(46)
        val out = LogSanitizer.sanitizeIp(ip46)
        assertTrue(out.startsWith("[invalid-ip:"))
        assertTrue(out.endsWith("]"))
    }

    @Test
    fun `sanitizeIp with invalid characters is marked invalid`() {
        val out = LogSanitizer.sanitizeIp("10.0.0.1; rm -rf")
        assertTrue(out.startsWith("[invalid-ip:"))
    }

    @Test
    fun `sanitizeUri null marker`() = assertEquals("[null-uri]", LogSanitizer.sanitizeUri(null))

    @Test
    fun `sanitizeUri plain path unchanged`() = assertEquals("/api/data?x=1", LogSanitizer.sanitizeUri("/api/data?x=1"))

    @Test
    fun `sanitizeUri escapes newline injection`() {
        assertEquals("/a[CRLF]b", LogSanitizer.sanitizeUri("/a\r\nb"))
        assertEquals("/a[LF]b", LogSanitizer.sanitizeUri("/a\nb"))
        assertEquals("/a[CR]b", LogSanitizer.sanitizeUri("/a\rb"))
    }

    @Test
    fun `sanitizeUri strips control chars and ansi`() {
        assertEquals("a[CTRL]b", LogSanitizer.sanitizeUri("a\u0001b"))
        assertEquals("[ESC]x", LogSanitizer.sanitizeUri("\u001B[0mx"))
    }

    @Test
    fun `sanitizeUri truncates with suffix beyond max length`() {
        val input = "/" + "x".repeat(600)
        val out = LogSanitizer.sanitizeUri(input, maxLength = 100)
        assertEquals(100, out.length)
        assertTrue(out.endsWith("...[truncated]"))
    }

    @Test
    fun `sanitizeUri at exactly max length is not truncated`() {
        val input = "x".repeat(100)
        assertEquals(input, LogSanitizer.sanitizeUri(input, maxLength = 100))
    }

    @Test
    fun `sanitizeUri truncates without suffix when max length below suffix length`() {
        val out = LogSanitizer.sanitizeUri("abcdefghij", maxLength = 5)
        assertEquals("abcde", out)
    }

    @Test
    fun `sanitizeRequestPath redacts study participant and mobile device path values`() {
        val path = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                "/participant/u15-device-owner/android/iphone-identifier/upload?token=secret"

        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/android/{sourceDeviceId}/upload",
            LogSanitizer.sanitizeRequestPath(path)
        )
    }

    @Test
    fun `sanitizeRequestPath keeps static platform routes and redacts query strings`() {
        val path = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000/android/sensors/availability" +
                "?participantId=u15"

        assertEquals(
            "/chronicle/v3/study/{studyId}/android/sensors/availability",
            LogSanitizer.sanitizeRequestPath(path)
        )
    }

    @Test
    fun `sanitizeRequestPath redacts export webhook and questionnaire identifiers`() {
        assertEquals(
            "/chronicle/v3/study/{studyId}/exports/{exportId}/download",
            LogSanitizer.sanitizeRequestPath(
                "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                        "/exports/11111111-2222-3333-4444-555555555555/download"
            )
        )
        assertEquals(
            "/chronicle/v3/study/{studyId}/webhooks/{webhookId}",
            LogSanitizer.sanitizeRequestPath(
                "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                        "/webhooks/11111111-2222-3333-4444-555555555555"
            )
        )
        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/questionnaire/{questionnaireId}/submit",
            LogSanitizer.sanitizeRequestPath(
                "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                        "/participant/u15/questionnaire/11111111-2222-3333-4444-555555555555/submit"
            )
        )
    }

    @Test
    fun `stableFingerprints null collection returns null marker`() {
        assertEquals("[null]", LogSanitizer.stableFingerprints(null))
    }

    @Test
    fun `stableFingerprints empty collection returns empty marker`() {
        assertEquals("[empty]", LogSanitizer.stableFingerprints(emptyList()))
    }

    @Test
    fun `stableFingerprints single item wraps one fingerprint in brackets`() {
        val single = LogSanitizer.stableFingerprints(listOf("participant-1"))
        assertEquals("[${LogSanitizer.stableFingerprint("participant-1")}]", single)
    }

    @Test
    fun `stableFingerprints joins items comma-separated preserving order and prefix`() {
        val joined = LogSanitizer.stableFingerprints(listOf("a", "b"), prefix = "dev")
        assertEquals(
            "[${LogSanitizer.stableFingerprint("a", "dev")}, ${LogSanitizer.stableFingerprint("b", "dev")}]",
            joined
        )
    }

    @Test
    fun `stableFingerprints at maxItems boundary has no truncation suffix`() {
        val out = LogSanitizer.stableFingerprints(listOf("a", "b", "c"), maxItems = 3)
        assertFalse("no suffix expected at exactly maxItems: $out", out.contains("more items"))
        assertEquals(3, out.split(", ").size)
    }

    @Test
    fun `stableFingerprints past maxItems truncates and reports exact remainder`() {
        val out = LogSanitizer.stableFingerprints(listOf("a", "b", "c", "d", "e"), maxItems = 2)
        assertTrue("expected truncation suffix in: $out", out.endsWith(", ...[3 more items]]"))
        // Exactly the first two items are fingerprinted.
        assertTrue(out.startsWith("[${LogSanitizer.stableFingerprint("a")}, ${LogSanitizer.stableFingerprint("b")},"))
        // Raw values must never appear.
        listOf("a", "b", "c", "d", "e").forEach { raw ->
            assertFalse(out.contains(", $raw,"))
        }
    }

    @Test
    fun `stableFingerprint is deterministic, hex-bounded, and never echoes input`() {
        val one = LogSanitizer.stableFingerprint("participant-secret")
        assertEquals(one, LogSanitizer.stableFingerprint("participant-secret"))
        // "<prefix>:<12 hex chars>"
        val parts = one.split(":")
        assertEquals(2, parts.size)
        assertEquals("id", parts[0])
        assertEquals(12, parts[1].length)
        assertTrue(parts[1].matches(Regex("[0-9a-f]{12}")))
        assertFalse(one.contains("participant-secret"))
        // Different inputs produce different digests.
        assertFalse(one == LogSanitizer.stableFingerprint("participant-other"))
    }
}
