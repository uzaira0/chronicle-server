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

    @Test
    fun testSanitizeMessagePreservesSafeContent() {
        val message = "Invalid study ID provided"
        val sanitized = defaultConfig.sanitizeMessage(message)
        assertEquals("Invalid study ID provided", sanitized)
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
