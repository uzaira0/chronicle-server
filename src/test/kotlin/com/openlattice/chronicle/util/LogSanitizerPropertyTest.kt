package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Property-based tests for LogSanitizer to ensure log injection prevention.
 */
class LogSanitizerPropertyTest {

    @Test
    fun `sanitized output never contains raw newlines`() { runBlocking {
        forAll(Arb.string(0..500)) { input ->
            val sanitized = LogSanitizer.sanitize(input)
            !sanitized.contains('\n') && !sanitized.contains('\r')
        }
    } }

    @Test
    fun `sanitized output never contains raw tabs`() { runBlocking {
        forAll(Arb.string(0..500)) { input ->
            val sanitized = LogSanitizer.sanitize(input)
            !sanitized.contains('\t')
        }
    } }

    @Test
    fun `sanitized output never contains ANSI escape sequences`() { runBlocking {
        forAll(Arb.string(0..200)) { input ->
            val withAnsi = "\u001B[31m${input}\u001B[0m"
            val sanitized = LogSanitizer.sanitize(withAnsi)
            !sanitized.contains("\u001B[")
        }
    } }

    @Test
    fun `sanitize is idempotent - sanitize(sanitize(x)) equals sanitize(x)`() { runBlocking {
        forAll(Arb.string(0..300)) { input ->
            val once = LogSanitizer.sanitize(input)
            val twice = LogSanitizer.sanitize(once)
            once == twice
        }
    } }

    @Test
    fun `sanitized output respects max length`() { runBlocking {
        forAll(Arb.string(0..2000), Arb.int(10..500)) { input, maxLen ->
            val sanitized = LogSanitizer.sanitize(input, maxLen)
            sanitized.length <= maxLen
        }
    } }

    @Test
    fun `sanitized output for max length below minimum is coerced to MIN_MAX_LENGTH`() { runBlocking {
        forAll(Arb.string(0..100), Arb.int(-100..9)) { input, maxLen ->
            val sanitized = LogSanitizer.sanitize(input, maxLen)
            sanitized.length <= LogSanitizer.MIN_MAX_LENGTH
        }
    } }

    @Test
    fun `null input always returns bracket-null marker`() {
        assertEquals("[null]", LogSanitizer.sanitize(null))
    }

    @Test
    fun `empty input always returns bracket-empty marker`() {
        assertEquals("[empty]", LogSanitizer.sanitize(""))
    }

    @Test
    fun `sanitizeQuoted wraps sanitized output in quotes`() { runBlocking {
        forAll(Arb.string(0..100)) { input ->
            val quoted = LogSanitizer.sanitizeQuoted(input)
            quoted.startsWith("\"") && quoted.endsWith("\"")
        }
    } }

    @Test
    fun `sanitized output never contains unicode directional overrides`() { runBlocking {
        val bidiChars = listOf('\u200B', '\u200E', '\u200F', '\u202A', '\u202E', '\u2066', '\u2069', '\uFEFF')
        forAll(Arb.string(0..100), Arb.element(bidiChars)) { base, bidi ->
            val input = "$base$bidi"
            val sanitized = LogSanitizer.sanitize(input)
            bidiChars.none { sanitized.contains(it) }
        }
    } }

    @Test
    fun `sanitizeIp rejects strings with newlines or special chars`() { runBlocking {
        forAll(Arb.string(1..20)) { input ->
            val injected = "$input\nfake-log-entry"
            val sanitized = LogSanitizer.sanitizeIp(injected)
            !sanitized.contains('\n')
        }
    } }

    @Test
    fun `sanitizeMap returns bracket-null for null maps`() {
        assertEquals("[null]", LogSanitizer.sanitizeMap(null))
    }

    @Test
    fun `sanitizeMap returns bracket-empty for empty maps`() {
        assertEquals("[empty]", LogSanitizer.sanitizeMap(emptyMap()))
    }

    @Test
    fun `sanitizeCollection returns bracket-null for null collections`() {
        assertEquals("[null]", LogSanitizer.sanitizeCollection(null))
    }

    @Test
    fun `sanitizeCollection returns bracket-empty for empty collections`() {
        assertEquals("[empty]", LogSanitizer.sanitizeCollection(emptyList()))
    }

    @Test
    fun `sanitizeUri never contains raw newlines`() { runBlocking {
        forAll(Arb.string(0..200)) { input ->
            val sanitized = LogSanitizer.sanitizeUri(input)
            !sanitized.contains('\n') && !sanitized.contains('\r')
        }
    } }
}
