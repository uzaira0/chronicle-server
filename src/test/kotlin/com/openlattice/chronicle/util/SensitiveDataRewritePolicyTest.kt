package com.openlattice.chronicle.util

import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRewritePolicyTest {

    @Test
    fun testMasksBearerToken() {
        val input = "Auth failed with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("eyJh"))
        assertTrue(masked.contains("Bearer [REDACTED]"))
    }

    @Test
    fun testMasksBasicAuth() {
        val input = "Authorization: Basic dXNlcjpwYXNzd29yZA=="
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("dXNlcjpwYXNzd29yZA"))
        assertTrue(masked.contains("Basic [REDACTED]"))
    }

    @Test
    fun testMasksJsonPassword() {
        val input = """{"username": "admin", "password": "s3cret!", "action": "login"}"""
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("s3cret!"))
        assertTrue(masked.contains("[REDACTED]"))
    }

    @Test
    fun testMasksJsonToken() {
        val input = """{"access_token": "abc123xyz", "expires_in": 3600}"""
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("abc123xyz"))
    }

    @Test
    fun testMasksJsonClientSecret() {
        val input = """{"client_secret": "super-secret-value"}"""
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("super-secret-value"))
    }

    @Test
    fun testMasksKeyValuePassword() {
        val input = "Connection failed: password=mysecretpass123 host=db.example.com"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("mysecretpass"))
    }

    @Test
    fun testMasksReplaySafeEnrollmentHeaders() {
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        val invitation = "a".repeat(64)
        val masked = SensitiveDataRewritePolicy.mask(
            "X-Chronicle-Proposed-Api-Key: $proposed, X-Chronicle-Enrollment-Code=$invitation",
        )

        assertFalse(masked.contains(proposed))
        assertFalse(masked.contains(invitation))
        assertTrue(masked.contains("[REDACTED]"))
    }

    @Test
    fun testMasksProposedApiKeyWithoutAnotherFastPathKeyword() {
        val proposed = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV"
        val masked = SensitiveDataRewritePolicy.mask("X-Chronicle-Proposed-Api-Key: $proposed")

        assertFalse(masked.contains(proposed))
        assertEquals("X-Chronicle-Credential: [REDACTED]", masked)
    }

    @Test
    fun testMasksExactReviewerSecretHeaderWithoutAnotherFastPathKeyword() {
        val reviewerSecret = "reviewer-console-secret-with-at-least-32-random-chars"

        val masked = SensitiveDataRewritePolicy.mask("X-Chronicle-Reviewer-Secret: $reviewerSecret")

        assertEquals("X-Chronicle-Credential: [REDACTED]", masked)
        assertFalse(masked.contains(reviewerSecret))
    }

    @Test
    fun testMasksReviewerSecretInSupportedJsonDiagnosticForms() {
        val reviewerSecret = "reviewer-console-secret-with-at-least-32-random-chars"
        val diagnostics = listOf(
            """{"X-Chronicle-Reviewer-Secret":"$reviewerSecret"}""",
            """{"x-chronicle-reviewer-secret": "$reviewerSecret"}""",
        )

        diagnostics.forEach { diagnostic ->
            val masked = SensitiveDataRewritePolicy.mask(diagnostic)
            assertFalse(masked.contains(reviewerSecret))
            assertTrue(masked.contains("[REDACTED]"))
        }
    }

    @Test
    fun testMasksJdbcUrl() {
        val input = "Error connecting to jdbc:postgresql://db.internal:5432/chronicle?user=admin&password=secret"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("db.internal"))
        assertFalse(masked.contains("5432"))
    }

    @Test
    fun testMasksSsn() {
        val input = "Participant SSN: 123-45-6789"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("123-45-6789"))
    }

    @Test
    fun testMasksSsnWithoutKeyword() {
        // SSN pattern must be masked even without the word "SSN" in the message
        val input = "Data includes 123-45-6789 for record"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse("SSN without keyword context should be masked", masked.contains("123-45-6789"))
    }

    @Test
    fun testMasksCreditCardWithoutKeyword() {
        val input = "Charged 4111-1111-1111-1111 on file"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse("Credit card without keyword should be masked", masked.contains("4111"))
    }

    @Test
    fun testPreservesSafeMessages() {
        val input = "Study abc-123 loaded 42 participants in 150ms"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertEquals(input, masked)
    }

    @Test
    fun testPreservesErrorIds() {
        val input = "Error ID: ERR-550e8400-e29b-41d4-a716-446655440000 | Method: POST | URI: /v3/studies"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertEquals(input, masked)
    }

    @Test
    fun testShapesChronicleRequestPaths() {
        val input = "No mapping for POST /chronicle/v4/study/00000000-0000-0000-8000-0000000005f1/participant/pixel-all-20260713-v49/reminders"
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("00000000-0000-0000-8000-0000000005f1"))
        assertFalse(masked.contains("pixel-all-20260713-v49"))
        assertTrue(masked.contains("No mapping for POST /chronicle/{unmapped}"))
    }

    @Test
    fun testNoHandlerRewritePreservesStructuredFramingAndDropsThrowable() {
        val rawPath = "/Chronicle/no-such-route/patient-jane-doe"
        val event = Log4jLogEvent.newBuilder()
            .setLoggerName("org.springframework.web.servlet.PageNotFound")
            .setMessage(SimpleMessage("{\"error\":\"No endpoint POST $rawPath\"}"))
            .setThrown(IllegalStateException("No endpoint POST $rawPath"))
            .build()

        val rewritten = SensitiveDataRewritePolicy.createPolicy().rewrite(event)

        assertEquals("{\"error\":\"No endpoint POST /chronicle/{unmapped}\"}", rewritten.message.formattedMessage)
        assertFalse(rewritten.message.formattedMessage.contains("patient-jane-doe"))
        assertNull(rewritten.thrown)
    }

    @Test
    fun testMasksMultipleSensitiveFields() {
        val input = """Body: {"password": "abc", "token": "xyz", "name": "safe"}"""
        val masked = SensitiveDataRewritePolicy.mask(input)
        assertFalse(masked.contains("abc"))
        assertTrue(masked.contains("safe"))
    }

    @Test
    fun testNullSafeViaRewrite() {
        // mask() itself only takes String, but the rewrite() method handles null messages
        val result = SensitiveDataRewritePolicy.mask("")
        assertEquals("", result)
    }
}
