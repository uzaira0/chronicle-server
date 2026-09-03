package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

private fun Arb.Companion.codepoints(chars: List<Char>): Arb<Codepoint> =
    Arb.element(chars).map { Codepoint(it.code) }

/**
 * Property-based tests for the format-checking ParticipantIdValidator in the util package.
 */
class ParticipantIdValidatorPropertyTest {

    @Test
    fun `valid IDs with alphanumeric chars, dots, dashes, underscores always pass`() { runBlocking {
        val validChars = Arb.codepoints(
            ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '-', '_')
        )
        forAll<String>(Arb.string(1..100, validChars)) { id ->
            try {
                validateParticipantId(id)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    } }

    @Test
    fun `empty strings are always rejected`() {
        try {
            validateParticipantId("")
            assert(false) { "Should have thrown" }
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `strings longer than 255 chars are always rejected`() { runBlocking {
        forAll(Arb.string(256..400, Codepoint.alphanumeric())) { id ->
            try {
                validateParticipantId(id)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    } }

    @Test
    fun `IDs containing spaces are always rejected`() { runBlocking {
        forAll(
            Arb.string(1..20, Codepoint.alphanumeric()),
            Arb.string(1..20, Codepoint.alphanumeric())
        ) { left, right ->
            val idWithSpace = "$left $right"
            try {
                validateParticipantId(idWithSpace)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    } }

    @Test
    fun `IDs with special characters are always rejected`() { runBlocking {
        val badChars = Arb.element('!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '/', '\\', '\'', '"', ';')
        forAll(Arb.string(1..10, Codepoint.alphanumeric()), badChars) { prefix, badChar ->
            val id = "$prefix$badChar"
            try {
                validateParticipantId(id)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    } }

    @Test
    fun `validation is idempotent - valid IDs pass on repeated calls`() { runBlocking {
        forAll(Arb.string(1..50, Codepoint.alphanumeric())) { id ->
            val r1 = runCatching { validateParticipantId(id) }
            val r2 = runCatching { validateParticipantId(id) }
            r1.isSuccess == r2.isSuccess
        }
    } }
}
