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

import java.security.MessageDigest

/**
 * Utility object for sanitizing user input before logging.
 *
 * Log injection attacks allow attackers to:
 * - Insert fake log entries to hide malicious activity
 * - Confuse log analysis and forensics
 * - Inject ANSI escape sequences to exploit terminal emulators
 * - Create misleading audit trails
 *
 * This sanitizer prevents these attacks by:
 * 1. Escaping/replacing newlines and carriage returns
 * 2. Removing or escaping control characters
 * 3. Truncating excessively long strings
 * 4. Encoding potentially dangerous characters
 *
 * Usage:
 *   logger.info("User input: ${LogSanitizer.sanitize(userInput)}")
 *   logger.warn("Parameter: ${LogSanitizer.sanitize(param, maxLength = 100)}")
 */
@Suppress("TooManyFunctions")
public object LogSanitizer {

    /**
     * Default maximum length for sanitized strings.
     * Strings longer than this will be truncated with "...[truncated]" suffix.
     */
    public const val DEFAULT_MAX_LENGTH: Int = 1000

    /**
     * Minimum allowed max length to prevent misconfiguration.
     */
    public const val MIN_MAX_LENGTH: Int = 10

    /**
     * Default maximum length for sanitized URIs.
     * Used by [sanitizeUri] when no explicit maxLength is provided.
     */
    public const val DEFAULT_URI_MAX_LENGTH: Int = 500

    /**
     * Maximum possible output length for [sanitizeIp].
     * Valid IPs are at most 45 chars (IPv6). Invalid IPs produce
     * "[invalid-ip:" + sanitize(ip, 20) + "]" which is at most 34 chars.
     * This constant provides a safe upper bound for both cases.
     */
    public const val MAX_IP_OUTPUT_LENGTH: Int = 45

    /**
     * Truncation suffix appended to strings that exceed max length.
     */
    private const val TRUNCATION_SUFFIX = "...[truncated]"

    /**
     * Replacement string for newlines.
     */
    private const val NEWLINE_REPLACEMENT = "\\n"

    /**
     * Replacement string for carriage returns.
     */
    private const val CR_REPLACEMENT = "\\r"

    /**
     * Replacement string for tabs.
     */
    private const val TAB_REPLACEMENT = "\\t"

    /**
     * Control character replacements (0x00-0x1F except common ones).
     */
    private val controlCharPattern = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

    /**
     * ANSI escape sequence pattern (prevents terminal exploitation).
     */
    private val ansiEscapePattern = Regex("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007")

    /**
     * Unicode control characters that can be used for log spoofing:
     * - U+200B Zero Width Space (invisible text injection)
     * - U+200C-U+200F Zero Width Non-Joiner through Right-to-Left Mark
     * - U+202A-U+202E Directional formatting (RLO can reverse displayed text)
     * - U+2066-U+2069 Directional isolates
     * - U+FEFF Byte Order Mark (invisible when not at start of file)
     */
    private val unicodeControlPattern = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")

    private const val DEFAULT_FINGERPRINT_PREFIX = "id"
    private const val FINGERPRINT_HEX_LENGTH = 12
    private val uuidPathSegmentPattern = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val studyIdParentSegments = setOf("study", "survey", "time-use-diary")
    private val staticPlatformSegments = setOf("availability", "data", "sensors", "status", "upload")

    /**
     * Sanitizes a string for safe logging.
     *
     * @param input The potentially untrusted input string
     * @param maxLength Maximum allowed length (default: 1000). Values below MIN_MAX_LENGTH
     *                  will be coerced to MIN_MAX_LENGTH.
     * @return A sanitized string safe for logging
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitize(input: String?, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        if (input == null) {
            return "[null]"
        }

        if (input.isEmpty()) {
            return "[empty]"
        }

        val effectiveMaxLength = maxLength.coerceAtLeast(MIN_MAX_LENGTH)

        var sanitized = input

        // 1. Remove ANSI escape sequences (prevents terminal exploitation)
        sanitized = ansiEscapePattern.replace(sanitized, "[ESC]")

        // 1b. Remove Unicode directional overrides and zero-width chars (log spoofing)
        sanitized = unicodeControlPattern.replace(sanitized, "[UCTL]")

        // 2. Escape newlines and carriage returns (primary log injection vector)
        sanitized = sanitized
            .replace("\r\n", "$CR_REPLACEMENT$NEWLINE_REPLACEMENT")
            .replace("\n", NEWLINE_REPLACEMENT)
            .replace("\r", CR_REPLACEMENT)

        // 3. Escape tabs (can misalign log entries)
        sanitized = sanitized.replace("\t", TAB_REPLACEMENT)

        // 4. Replace other control characters with placeholder
        sanitized = controlCharPattern.replace(sanitized) { match ->
            "[0x${match.value[0].code.toString(16).uppercase().padStart(2, '0')}]"
        }

        // 5. Truncate if too long
        if (sanitized.length > effectiveMaxLength) {
            sanitized = if (effectiveMaxLength > TRUNCATION_SUFFIX.length) {
                sanitized.substring(0, effectiveMaxLength - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
            } else {
                sanitized.substring(0, effectiveMaxLength)
            }
        }

        return sanitized
    }

    /**
     * Sanitizes a string and wraps it in quotes for clear log delineation.
     * Useful when logging user-provided values that might contain spaces.
     *
     * @param input The potentially untrusted input string
     * @param maxLength Maximum allowed length (default: 1000)
     * @return A sanitized string wrapped in double quotes
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizeQuoted(input: String?, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        return "\"${sanitize(input, maxLength)}\""
    }

    /**
     * Returns a stable, one-way identifier for logs. Use this for participant IDs, device IDs,
     * external message IDs, and other values that are useful for correlation but should not be
     * written to application logs in plaintext.
     */
    @JvmStatic
    @JvmOverloads
    public fun stableFingerprint(input: String?, prefix: String = DEFAULT_FINGERPRINT_PREFIX): String {
        if (input == null) {
            return "${sanitize(prefix, 32)}:[null]"
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(FINGERPRINT_HEX_LENGTH)
        return "${sanitize(prefix, 32)}:$digest"
    }

    @JvmStatic
    @JvmOverloads
    public fun stableFingerprints(
        inputs: Collection<String>?,
        prefix: String = DEFAULT_FINGERPRINT_PREFIX,
        maxItems: Int = 50
    ): String {
        if (inputs == null) {
            return "[null]"
        }
        if (inputs.isEmpty()) {
            return "[empty]"
        }

        val fingerprints = inputs.take(maxItems).joinToString(", ") { input ->
            stableFingerprint(input, prefix)
        }
        val suffix = if (inputs.size > maxItems) ", ...[${inputs.size - maxItems} more items]" else ""
        return "[$fingerprints$suffix]"
    }

    /**
     * Sanitizes a map of key-value pairs for logging.
     * Useful for logging request parameters or headers.
     *
     * @param map The map to sanitize
     * @param maxValueLength Maximum length for each value
     * @param maxEntries Maximum number of entries to include
     * @return A sanitized string representation of the map
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizeMap(
        map: Map<String, Any?>?,
        maxValueLength: Int = DEFAULT_MAX_LENGTH,
        maxEntries: Int = 50
    ): String {
        if (map == null) {
            return "[null]"
        }

        if (map.isEmpty()) {
            return "[empty]"
        }

        val entries = map.entries.take(maxEntries).joinToString(", ") { (key, value) ->
            "${sanitize(key, 100)}=${sanitize(value?.toString(), maxValueLength)}"
        }

        val suffix = if (map.size > maxEntries) ", ...[${map.size - maxEntries} more entries]" else ""

        return "{$entries$suffix}"
    }

    /**
     * Sanitizes a collection for logging.
     *
     * @param collection The collection to sanitize
     * @param maxItemLength Maximum length for each item
     * @param maxItems Maximum number of items to include
     * @return A sanitized string representation of the collection
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizeCollection(
        collection: Collection<Any?>?,
        maxItemLength: Int = DEFAULT_MAX_LENGTH,
        maxItems: Int = 50
    ): String {
        if (collection == null) {
            return "[null]"
        }

        if (collection.isEmpty()) {
            return "[empty]"
        }

        val items = collection.take(maxItems).joinToString(", ") { item ->
            sanitize(item?.toString(), maxItemLength)
        }

        val suffix = if (collection.size > maxItems) ", ...[${collection.size - maxItems} more items]" else ""

        return "[$items$suffix]"
    }

    /**
     * Sanitizes an IP address for logging.
     * Validates the format and prevents log injection through IP fields.
     *
     * @param ip The IP address string
     * @return A sanitized IP address or "[invalid-ip]" marker
     */
    @JvmStatic
    public fun sanitizeIp(ip: String?): String {
        if (ip == null) {
            return "[null-ip]"
        }

        // Basic validation - only allow expected characters
        val ipPattern = Regex("^[0-9a-fA-F.:]+$")
        return if (ipPattern.matches(ip) && ip.length <= 45) {
            ip
        } else {
            "[invalid-ip:${sanitize(ip, 20)}]"
        }
    }

    /**
     * Sanitizes a URI/URL path for logging.
     * Allows standard URL characters but escapes dangerous ones.
     *
     * @param uri The URI string
     * @param maxLength Maximum allowed length
     * @return A sanitized URI string
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizeUri(uri: String?, maxLength: Int = DEFAULT_URI_MAX_LENGTH): String {
        if (uri == null) {
            return "[null-uri]"
        }

        // For URIs, we still need to prevent log injection but allow URL-safe characters
        var sanitized = uri

        // Remove ANSI escapes and control characters
        sanitized = ansiEscapePattern.replace(sanitized, "[ESC]")
        sanitized = controlCharPattern.replace(sanitized, "[CTRL]")

        // Escape newlines (primary injection vector)
        sanitized = sanitized
            .replace("\r\n", "[CRLF]")
            .replace("\n", "[LF]")
            .replace("\r", "[CR]")

        // Truncate if needed
        if (sanitized.length > maxLength) {
            sanitized = if (maxLength > TRUNCATION_SUFFIX.length) {
                sanitized.substring(0, maxLength - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
            } else {
                sanitized.substring(0, maxLength)
            }
        }

        return sanitized
    }

    /**
     * Converts a request URI into a low-cardinality, log-safe route shape.
     *
     * [sanitizeUri] prevents log injection. This method additionally removes query strings and
     * replaces sensitive or high-cardinality route values with parameter names so logs, MDC fields,
     * and metrics do not store participant IDs, study UUIDs, source-device IDs, export IDs, or
     * webhook IDs in plaintext.
     *
     * NOTE ON THE OUTPUT SHAPE: the `{studyId}` / `{participantId}` / `{uuid}` segments this
     * returns are REDACTIONS of real values that were present in the request, not an
     * unsubstituted Spring mapping template. `/chronicle/v3/compliance/study/<a-real-uuid>`
     * deliberately becomes `/chronicle/v3/compliance/study/{studyId}`. This has been misread
     * as a routing bug — the identifier was removed on purpose and must stay removed; nothing
     * here reads `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`.
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizeRequestPath(uri: String?, maxLength: Int = DEFAULT_URI_MAX_LENGTH): String {
        if (uri == null) {
            return "[null-uri]"
        }

        val pathOnly = uri.substringBefore('?')
        if (pathOnly.isEmpty()) {
            return "[empty-uri]"
        }

        val parts = pathOnly.split('/')
        val shaped = mutableListOf<String>()

        parts.forEachIndexed { index, rawSegment ->
            if (index == 0 && rawSegment.isEmpty()) {
                shaped += ""
                return@forEachIndexed
            }

            shaped += requestPathSegmentReplacement(parts, index, rawSegment)
        }

        return sanitizeUri(shaped.joinToString("/"), maxLength)
    }

    private fun requestPathSegmentReplacement(
        parts: List<String>,
        index: Int,
        rawSegment: String
    ): String {
        val previous = parts.getOrNull(index - 1)?.lowercase()
        val previousPreviousPrevious = parts.getOrNull(index - 3)?.lowercase()
        val segment = rawSegment.lowercase()
        return when {
            rawSegment.isEmpty() -> ""
            previous in studyIdParentSegments -> "{studyId}"
            previous == "participant" -> "{participantId}"
            previous == "questionnaire" -> "{questionnaireId}"
            previous in setOf("exports", "export") -> "{exportId}"
            previous == "webhooks" -> "{webhookId}"
            isSourceDeviceSegment(previous, previousPreviousPrevious, segment) -> "{sourceDeviceId}"
            uuidPathSegmentPattern.matches(rawSegment) -> "{uuid}"
            else -> sanitize(rawSegment, 120)
        }
    }

    private fun isSourceDeviceSegment(
        previous: String?,
        previousPreviousPrevious: String?,
        segment: String
    ): Boolean {
        return previous in setOf("android", "ios") &&
                previousPreviousPrevious == "participant" &&
                segment !in staticPlatformSegments
    }
}
