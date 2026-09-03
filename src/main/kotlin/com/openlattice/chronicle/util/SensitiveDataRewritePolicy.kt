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
package com.openlattice.chronicle.util

import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginFactory
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage

/**
 * Log4j2 RewritePolicy that masks sensitive data in all log messages at the framework level.
 *
 * This is a defense-in-depth measure: even if application code logs sensitive data
 * without calling ErrorSanitizationConfig.sanitizeMessage() or LogSanitizer.sanitize(),
 * this policy catches it before it reaches any appender (console, file, SIEM).
 *
 * Masks: Bearer/Basic tokens, JSON secret values, passwords, connection strings,
 * SSNs, and credit card numbers.
 */
@Plugin(name = "SensitiveDataRewritePolicy", category = "Core", elementType = "rewritePolicy", printObject = true)
public class SensitiveDataRewritePolicy private constructor() : RewritePolicy {

    override fun rewrite(event: LogEvent): LogEvent {
        val original = event.message?.formattedMessage ?: return event
        val masked = mask(original)

        // Framework no-handler exceptions repeat the raw request URI in their throwable. If the
        // message identifies that case, suppress the throwable as well as shaping the message.
        val omitRequestDerivedThrowable = NO_HANDLER_REQUEST_PATH.containsMatchIn(original)

        // Skip allocation if nothing changed
        if (masked === original && !omitRequestDerivedThrowable) return event

        val builder = Log4jLogEvent.Builder(event)
            .setMessage(SimpleMessage(masked))
        if (omitRequestDerivedThrowable) {
            // Log4j 2.25 derives the proxy from the throwable during build. Its deprecated
            // setThrownProxy method is a no-op, so clearing the throwable is sufficient.
            builder.setThrown(null)
        }
        return builder.build()
    }

    internal companion object {
        private const val REDACTED = "[REDACTED]"

        /**
         * Keyword-guarded patterns: only run when a cheap keyword check hits.
         * These all contain identifiable keywords (Bearer, password, jdbc:, etc.).
         */
        // reason: regex literal — the sensitive-key alternation must stay on one line; wrapping it risks altering the pattern's matched value
        @Suppress("MaxLineLength")
        private val KEYWORD_PATTERNS: List<Pair<Regex, String>> = listOf(
            // Authorization headers
            Regex("""Bearer\s+[A-Za-z0-9._\-/+=]+""") to "Bearer $REDACTED",
            Regex("""Basic\s+[A-Za-z0-9+/=]+""") to "Basic $REDACTED",

            // JSON key-value pairs with sensitive keys
            Regex(
                """"(?:password|passwd|secret|token|api_key|apiKey|api-key|access_token|refresh_token|client_secret|private_key|ssn|social_security|credit_card|card_number|cvv)"\s*:\s*"[^"]*"""",
                RegexOption.IGNORE_CASE
            ) to "\"[sensitive_key]\": \"$REDACTED\"",

            // Diagnostic serializers quote header names, so raw-header matching alone cannot
            // cover these JSON forms (the closing quote precedes the colon).
            Regex(
                """"X-Chronicle-(?:Proposed-Api-Key|Enrollment-Code|Reviewer-Secret)"\s*:\s*"[^"]*"""",
                RegexOption.IGNORE_CASE,
            ) to "\"X-Chronicle-Credential\": \"$REDACTED\"",

            // One-time enrollment and client-proposed mobile credentials.
            Regex(
                """X-Chronicle-(?:Proposed-Api-Key|Enrollment-Code|Reviewer-Secret)\s*[:=]\s*(?:"[^"]*"|[^,\s}]+)""",
                RegexOption.IGNORE_CASE,
            ) to "X-Chronicle-Credential: $REDACTED",

            // Key=value pairs (URL params, config, log messages). This remains after the
            // full mobile-header patterns so the embedded "api-key" substring cannot
            // partially rewrite a credential header and leave its identifying prefix.
            Regex(
                """(?:password|passwd|secret|token|api_key|apiKey|api-key|access_token|refresh_token|client_secret|private_key)[=:]\s*\S+""",
                RegexOption.IGNORE_CASE
            ) to "$REDACTED",

            // JDBC connection strings (contain host, port, credentials)
            Regex("""jdbc:[a-z]+://[^\s]+""") to "jdbc:$REDACTED",
        )

        /**
         * Numeric patterns that match by shape, not keywords.
         * These ALWAYS run (cannot be short-circuited by keyword check).
         */
        private val NUMERIC_PATTERNS: List<Pair<Regex, String>> = listOf(
            // SSN patterns (###-##-####)
            Regex("""\b\d{3}-\d{2}-\d{4}\b""") to REDACTED,

            // Credit card numbers (4 groups of digits separated by dashes/spaces)
            Regex("""\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{1,7}\b""") to REDACTED,
        )

        /** Framework no-handler messages are emitted before controller advice can sanitize them. */
        private val NO_HANDLER_REQUEST_PATH = Regex(
            """(No (?:mapping for|endpoint(?: for)?) (?:GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD) )/chronicle(?:/[^\s"'\]\[{}(),;]+)+""",
            RegexOption.IGNORE_CASE,
        )

        // Cheap keywords for fast-path short-circuit on keyword patterns only.
        private val FAST_CHECK_KEYWORDS = arrayOf(
            "Bearer", "Basic", "password", "passwd", "secret", "token",
            "api_key", "apiKey", "api-key", "access_token", "refresh_token",
            "client_secret", "private_key", "jdbc:", "X-Chronicle-Enrollment-Code",
            "X-Chronicle-Proposed-Api-Key", "X-Chronicle-Reviewer-Secret",
        )

        /**
         * Applies all masking patterns to the input string.
         * Returns the original reference if nothing matched (avoids allocation).
         */
        public fun mask(input: String): String {
            var result = input
            var changed = false

            if (NO_HANDLER_REQUEST_PATH.containsMatchIn(result)) {
                result = NO_HANDLER_REQUEST_PATH.replace(result) { match ->
                    "${match.groupValues[1]}/chronicle/{unmapped}"
                }
                changed = true
            }

            // Keyword-guarded patterns: skip if no keywords found (fast path for most log lines)
            if (FAST_CHECK_KEYWORDS.any { input.contains(it, ignoreCase = true) }) {
                for ((pattern, replacement) in KEYWORD_PATTERNS) {
                    if (pattern.containsMatchIn(result)) {
                        result = pattern.replace(result, replacement)
                        changed = true
                    }
                }
            }

            // Numeric patterns always run (SSN, credit card — no keyword to check)
            for ((pattern, replacement) in NUMERIC_PATTERNS) {
                if (pattern.containsMatchIn(result)) {
                    result = pattern.replace(result, replacement)
                    changed = true
                }
            }

            return if (changed) result else input
        }

        @JvmStatic
        @PluginFactory
        public fun createPolicy(): SensitiveDataRewritePolicy {
            return SensitiveDataRewritePolicy()
        }
    }
}
