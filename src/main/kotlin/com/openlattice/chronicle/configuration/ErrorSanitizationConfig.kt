/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration

/**
 * Configuration for error response sanitization.
 *
 * This configuration controls how error responses are sanitized to prevent
 * information disclosure while maintaining debugging capability.
 *
 * Security considerations:
 * - In production, sanitizeErrors should be true to prevent stack trace leakage
 * - includeStackTrace should only be true in development environments
 * - includeErrorId should typically be true for log correlation
 *
 * Configuration is loaded from error-sanitization.yaml:
 * ```yaml
 * sanitize-errors: true
 * include-stack-trace: false
 * include-error-id: true
 * log-full-errors: true
 * ```
 *
 * @author uzaira0
 */
@ReloadableConfiguration(uri = "error-sanitization.yaml")
public data class ErrorSanitizationConfig(
    /**
     * Whether to sanitize error messages.
     *
     * When true (recommended for production):
     * - 500 errors return generic messages with error ID
     * - SQL errors, file paths, and class names are scrubbed
     * - Stack traces are not included in responses
     *
     * When false (development mode):
     * - Original exception messages are included
     * - Stack traces may be included based on includeStackTrace
     */
    @param:JsonProperty("sanitize-errors")
    val sanitizeErrors: Boolean = true,

    /**
     * Whether to include stack traces in error responses.
     *
     * SECURITY: Should be false in production.
     * Stack traces can reveal:
     * - Internal code structure and file paths
     * - Library versions (potential vulnerability hints)
     * - Business logic flow
     */
    @param:JsonProperty("include-stack-trace")
    val includeStackTrace: Boolean = false,

    /**
     * Whether to include error IDs in responses.
     *
     * Error IDs enable correlation between client-reported errors
     * and server-side logs without exposing sensitive details.
     *
     * Recommended: true for all environments.
     */
    @param:JsonProperty("include-error-id")
    val includeErrorId: Boolean = true,

    /**
     * Whether to log full error details server-side.
     *
     * When true (recommended), full stack traces and error details
     * are logged server-side with the error ID for debugging.
     *
     * When false, only basic error information is logged.
     */
    @param:JsonProperty("log-full-errors")
    val logFullErrors: Boolean = true,

    /**
     * Maximum length of error messages returned to clients.
     *
     * Messages exceeding this length are truncated to prevent
     * excessive data exposure and response size issues.
     */
    @param:JsonProperty("max-message-length")
    val maxMessageLength: Int = 500,

    /**
     * List of exception class name patterns that should always
     * return generic 500 errors even if sanitization is disabled.
     *
     * Useful for ensuring database exceptions never leak SQL.
     */
    @param:JsonProperty("always-sanitize-patterns")
    val alwaysSanitizePatterns: List<String> = listOf(
        ".*SQLException.*",
        ".*JDBIException.*",
        ".*DataAccessException.*",
        ".*HibernateException.*",
        ".*PersistenceException.*"
    )
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("error-sanitization.yaml")

        /**
         * Patterns that indicate sensitive information in error messages.
         * These patterns are scrubbed from messages even when sanitization is partial.
         */
        public val SENSITIVE_PATTERNS = listOf(
            // File paths (Unix and Windows)
            Regex("""(/[a-zA-Z0-9._-]+)+\.(kt|java|class|jar|xml|yaml|yml|properties|conf|json)"""),
            Regex("""[A-Z]:\\[^\s:*?"<>|]+\.(kt|java|class|jar|xml|yaml|yml|properties|conf|json)""", RegexOption.IGNORE_CASE),
            Regex("""/home/[^\s]+"""),
            Regex("""/var/[^\s]+"""),
            Regex("""/etc/[^\s]+"""),
            Regex("""/tmp/[^\s]+"""),
            Regex("""C:\\Users\\[^\s]+""", RegexOption.IGNORE_CASE),
            Regex("""D:\\[^\s]+""", RegexOption.IGNORE_CASE),

            // SQL fragments
            Regex("""SELECT\s+\S+(?:\s+\S+)*\s+FROM\s+\w+""", RegexOption.IGNORE_CASE),
            Regex("""INSERT\s+INTO\s+\w+""", RegexOption.IGNORE_CASE),
            Regex("""UPDATE\s+\w+\s+SET""", RegexOption.IGNORE_CASE),
            Regex("""DELETE\s+FROM\s+\w+""", RegexOption.IGNORE_CASE),
            Regex("""CREATE\s+(TABLE|INDEX|VIEW)""", RegexOption.IGNORE_CASE),
            Regex("""ALTER\s+TABLE\s+\w+""", RegexOption.IGNORE_CASE),
            Regex("""DROP\s+(TABLE|INDEX|VIEW)""", RegexOption.IGNORE_CASE),
            Regex("""WHERE\s+\S+(?:\s+\S+)*?\s+(?:AND|OR)\b""", RegexOption.IGNORE_CASE),
            Regex("""JOIN\s+\w+\s+ON""", RegexOption.IGNORE_CASE),

            // Java/Kotlin class names that reveal internal structure
            Regex("""com\.openlattice\.[a-zA-Z0-9._$]+"""),
            Regex("""com\.geekbeast\.[a-zA-Z0-9._$]+"""),
            Regex("""org\.springframework\.[a-zA-Z0-9._$]+"""),
            Regex("""org\.hibernate\.[a-zA-Z0-9._$]+"""),
            Regex("""org\.postgresql\.[a-zA-Z0-9._$]+"""),
            Regex("""org\.jdbi\.[a-zA-Z0-9._$]+"""),
            Regex("""com\.hazelcast\.[a-zA-Z0-9._$]+"""),
            Regex("""com\.zaxxer\.hikari\.[a-zA-Z0-9._$]+"""),
            Regex("""io\.jsonwebtoken\.[a-zA-Z0-9._$]+"""),
            Regex("""com\.auth0\.[a-zA-Z0-9._$]+"""),

            // Package paths in stack traces
            Regex("""at\s+[a-z]+\.[a-z]+\.[a-zA-Z0-9._$]+\([^)]+\)"""),

            // Connection strings and URLs
            Regex("""jdbc:postgresql://[^\s]+"""),
            Regex("""jdbc:[a-z]+://[^\s]+"""),
            Regex("""redis://[^\s]+"""),
            Regex("""amqp://[^\s]+"""),

            // Credentials and tokens (patterns that might appear in error messages)
            Regex("""password[=:]\s*[^\s]+""", RegexOption.IGNORE_CASE),
            Regex("""secret[=:]\s*[^\s]+""", RegexOption.IGNORE_CASE),
            Regex("""token[=:]\s*[^\s]+""", RegexOption.IGNORE_CASE),
            Regex("""api[_-]?key[=:]\s*[^\s]+""", RegexOption.IGNORE_CASE),
            Regex("""Bearer\s+[A-Za-z0-9._-]+""")
        )

        /**
         * Generic replacement text for sanitized patterns.
         */
        public const val REDACTED = "[REDACTED]"
    }

    override fun getKey(): ConfigurationKey = ErrorSanitizationConfig.key

    /**
     * Checks if the given exception class should always be sanitized.
     */
    public fun shouldAlwaysSanitize(exceptionClassName: String): Boolean {
        return alwaysSanitizePatterns.any { pattern ->
            Regex(pattern).matches(exceptionClassName)
        }
    }

    /**
     * Sanitizes an error message by removing sensitive patterns.
     *
     * @param message The original error message
     * @return The sanitized message with sensitive information removed
     */
    public fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) {
            return "An error occurred"
        }

        var sanitized: String = message

        // Apply all sensitive pattern replacements
        SENSITIVE_PATTERNS.forEach { pattern ->
            sanitized = pattern.replace(sanitized, REDACTED)
        }

        // Remove consecutive redacted markers
        sanitized = sanitized.replace(Regex("""\[REDACTED](?:\s*\[REDACTED])*\s*"""), "$REDACTED ")

        // Truncate if too long
        if (sanitized.length > maxMessageLength) {
            sanitized = sanitized.substring(0, maxMessageLength - 3) + "..."
        }

        return sanitized.trim()
    }

    /**
     * Validates the configuration for security issues.
     * @return List of validation warnings/errors
     */
    public fun validate(): List<String> {
        val warnings = mutableListOf<String>()

        if (!sanitizeErrors) {
            warnings.add("ERROR_SANITIZATION: sanitize-errors is disabled - error details may be exposed")
        }

        if (includeStackTrace) {
            warnings.add("ERROR_SANITIZATION: include-stack-trace is enabled - stack traces will be exposed to clients")
        }

        if (!includeErrorId) {
            warnings.add("ERROR_SANITIZATION: include-error-id is disabled - error correlation will be difficult")
        }

        if (!logFullErrors) {
            warnings.add("ERROR_SANITIZATION: log-full-errors is disabled - debugging may be difficult")
        }

        return warnings
    }
}
