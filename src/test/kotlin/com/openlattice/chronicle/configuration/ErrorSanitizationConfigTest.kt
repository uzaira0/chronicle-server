package com.openlattice.chronicle.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSanitizationConfigTest {

    private val defaultConfig = ErrorSanitizationConfig()

    // --- defaults ---

    @Test
    fun testDefaultSanitizeErrorsIsTrue() {
        assertTrue(defaultConfig.sanitizeErrors)
    }

    @Test
    fun testDefaultIncludeStackTraceIsFalse() {
        assertFalse(defaultConfig.includeStackTrace)
    }

    @Test
    fun testDefaultIncludeErrorIdIsTrue() {
        assertTrue(defaultConfig.includeErrorId)
    }

    @Test
    fun testDefaultLogFullErrorsIsTrue() {
        assertTrue(defaultConfig.logFullErrors)
    }

    @Test
    fun testDefaultMaxMessageLength() {
        assertEquals(500, defaultConfig.maxMessageLength)
    }

    // --- shouldAlwaysSanitize ---

    @Test
    fun testShouldAlwaysSanitizeSqlException() {
        assertTrue(defaultConfig.shouldAlwaysSanitize("java.sql.SQLException"))
    }

    @Test
    fun testShouldAlwaysSanitizeJdbiException() {
        assertTrue(defaultConfig.shouldAlwaysSanitize("org.jdbi.JDBIException"))
    }

    @Test
    fun testShouldAlwaysSanitizeDataAccessException() {
        assertTrue(defaultConfig.shouldAlwaysSanitize("org.springframework.dao.DataAccessException"))
    }

    @Test
    fun testShouldNotSanitizeRuntimeException() {
        assertFalse(defaultConfig.shouldAlwaysSanitize("java.lang.RuntimeException"))
    }

    @Test
    fun testShouldNotSanitizeIllegalArgumentException() {
        assertFalse(defaultConfig.shouldAlwaysSanitize("java.lang.IllegalArgumentException"))
    }

    // --- sanitizeMessage ---

    @Test
    fun testSanitizeMessageRemovesSqlFragments() {
        val message = "Error: SELECT * FROM users WHERE id = 5"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("SELECT"))
        assertFalse(sanitized.contains("users"))
    }

    @Test
    fun testSanitizeMessageRemovesFilePaths() {
        val message = "Error at /home/user/project/Main.java:42"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("/home/user"))
    }

    @Test
    fun testSanitizeMessageRemovesClassNames() {
        val message = "Error in com.openlattice.chronicle.controllers.StudyController"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("com.openlattice"))
    }

    @Test
    fun testSanitizeNullMessage() {
        val sanitized = defaultConfig.sanitizeMessage(null)
        assertEquals("An error occurred", sanitized)
    }

    @Test
    fun testSanitizeBlankMessage() {
        val sanitized = defaultConfig.sanitizeMessage("  ")
        assertEquals("An error occurred", sanitized)
    }

    @Test
    fun testSanitizeEmptyMessage() {
        val sanitized = defaultConfig.sanitizeMessage("")
        assertEquals("An error occurred", sanitized)
    }

    @Test
    fun testSanitizeMessageTruncatesLongMessages() {
        val longMessage = "A".repeat(1000)
        val sanitized = defaultConfig.sanitizeMessage(longMessage)
        assertTrue(sanitized.length <= defaultConfig.maxMessageLength)
    }

    /** Text built to make each pattern backtrack still sanitizes in bounded time, and secrets in it are still redacted. */
    @Test(timeout = 10_000)
    fun testSanitizeMessageStaysBoundedOnAdversarialText() {
        val adversarial = listOf(
            "SELECT " + "a ".repeat(20_000),
            "SELECT " + "a".repeat(20_000),
            "WHERE " + "x ".repeat(20_000),
            "SELECT ".repeat(50_000),
            "WHERE ".repeat(50_000),
            "/" + "a.".repeat(20_000),
            "/a".repeat(20_000),
            "C:\\" + "a".repeat(20_000),
            "at a.b." + "c".repeat(20_000) + "(",
            "at a.b.c(".repeat(20_000),
            "[REDACTED] ".repeat(20_000) + "x",
        )
        for (text in adversarial) {
            val sanitized = defaultConfig.sanitizeMessage("password=hunter2 $text")
            assertFalse(sanitized.contains("hunter2"))
            assertTrue(sanitized.length <= defaultConfig.maxMessageLength)
        }
    }

    @Test
    fun testSanitizeMessagePreservesLegacyRedactionCoverage() {
        val examples = mapOf(
            Regex("""(/[a-zA-Z0-9._-]+)+\.(kt|java|class|jar|xml|yaml|yml|properties|conf|json)""") to listOf(
                "/opt/chronicle/Main.kt", "/srv/app/a-b_2/settings.properties", "/run/app/config.yaml",
                "/opt/app/data.json", "/srv/app/lib.jar", "/opt/app/Worker.class", "/opt/app/config.xml",
                "/opt/app/config.yml", "/opt/app/config.conf", "/opt/app/Main.java",
            ),
            Regex("""SELECT\s+\S+(?:\s+\S+)*\s+FROM\s+\w+""", RegexOption.IGNORE_CASE) to listOf(
                "SELECT secret FROM participants", "select\tsecret\nfrom participants",
                "SELECT subject, (SELECT secret FROM private_rows) FROM participants",
                "SELECT secret FROM participants UNION SELECT secret FROM archived",
                "SELECT 'participant-private', '" + "x".repeat(2_100) + "' FROM participants",
            ),
            Regex("""WHERE\s+\S+(?:\s+\S+)*?\s+(?:AND|OR)\b""", RegexOption.IGNORE_CASE) to listOf(
                "WHERE subject = 'private' AND", "where\tsecret = 1\nor",
                "WHERE note = 'WHERE private' AND",
            ),
            Regex("""at\s+[a-z]+\.[a-z]+\.[a-zA-Z0-9._$]+\([^)]+\)""") to listOf(
                "at com.example.Worker.run(Worker.kt:42)", "at com.example.Worker.run(Native Method)",
            ),
        )
        for ((legacy, messages) in examples) for (message in messages) {
            val input = "Error: $message"
            val expected = "Error: ${ErrorSanitizationConfig.REDACTED}"
            assertEquals("legacy: $input", expected, legacy.replace(input, ErrorSanitizationConfig.REDACTED))
            assertEquals(input, expected, defaultConfig.sanitizeMessage(input))
        }
    }

    @Test
    fun testSanitizeMessageCollapsesMarkersWithoutChangingLiteralControlCharacters() {
        for ((input, expected) in listOf(
            "left\u0001right" to "left\u0001right",
            "[REDACTED]\u0001[REDACTED]" to "[REDACTED] \u0001[REDACTED]",
            "before [REDACTED]\n[REDACTED] after" to "before [REDACTED] after",
        )) {
            assertEquals(input, expected, defaultConfig.sanitizeMessage(input))
        }
    }

    @Test
    fun testSanitizeMessagePreservesSafeContent() {
        val message = "Invalid study ID provided"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertEquals("Invalid study ID provided", sanitized)
        for (plain in listOf(
            "Select at least one data type", "Select from the available studies", "Choose where to save the export",
            "Somewhere the upload stopped", "Choose .csv/.json for the export",
            "Seleccione al menos un tipo de datos", "Look at the study settings (Modules)",
        )) {
            assertEquals(plain, plain, defaultConfig.sanitizeMessage(plain))
        }
    }

    @Test
    fun testSanitizeMessageRemovesJdbcUrl() {
        val message = "Connection error: jdbc:postgresql://db.example.com:5432/chronicle"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("jdbc:postgresql"))
    }

    @Test
    fun testSanitizeMessageRemovesBearerToken() {
        val message = "Auth failed with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("eyJh"))
    }

    @Test
    fun testSanitizeMessageRemovesPasswordPatterns() {
        val message = "Connection failed: password=mysecretpass123"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertFalse(sanitized.contains("mysecretpass"))
    }

    // --- validate ---

    @Test
    fun testValidateDefaultConfigNoWarnings() {
        val warnings = defaultConfig.validate()
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun testValidateDevConfigHasWarnings() {
        val devConfig = ErrorSanitizationConfig(
            sanitizeErrors = false,
            includeStackTrace = true,
            includeErrorId = false,
            logFullErrors = false
        )
        val warnings = devConfig.validate()
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.size >= 3)
    }

    @Test
    fun testValidateDisabledSanitizationWarning() {
        val config = ErrorSanitizationConfig(sanitizeErrors = false)
        val warnings = config.validate()
        assertTrue(warnings.any { it.contains("sanitize-errors") })
    }

    @Test
    fun testValidateStackTraceWarning() {
        val config = ErrorSanitizationConfig(includeStackTrace = true)
        val warnings = config.validate()
        assertTrue(warnings.any { it.contains("stack-trace") })
    }

    // --- getKey ---

    @Test
    fun testGetKeyReturnsCorrectKey() {
        val key = defaultConfig.getKey()
        assertEquals(ErrorSanitizationConfig.key, key)
    }

    // --- custom configuration ---

    @Test
    fun testCustomMaxMessageLength() {
        val config = ErrorSanitizationConfig(maxMessageLength = 100)
        val longMessage = "A".repeat(200)
        val sanitized = config.sanitizeMessage(longMessage)
        assertTrue(sanitized.length <= 100)
    }

    @Test
    fun testCustomAlwaysSanitizePatterns() {
        val config = ErrorSanitizationConfig(
            alwaysSanitizePatterns = listOf(".*CustomException.*")
        )
        assertTrue(config.shouldAlwaysSanitize("com.example.CustomException"))
        assertFalse(config.shouldAlwaysSanitize("java.sql.SQLException"))
    }
}
