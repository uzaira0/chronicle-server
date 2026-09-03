package com.openlattice.chronicle.validation

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.domain
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.email
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.springframework.web.server.ResponseStatusException

/**
 * Property-based tests for the PII-rejecting ParticipantIdValidator in the validation package.
 */
class ParticipantIdValidatorPropertyTest {

    @Test
    fun `alphanumeric IDs within length limit never throw`() { runBlocking {
        forAll(Arb.string(1..200, Codepoint.alphanumeric())) { id ->
            try {
                validateParticipantIdNotPii(id)
                true
            } catch (_: ResponseStatusException) {
                false
            }
        }
    } }

    @Test
    fun `IDs exceeding 255 characters are always rejected`() { runBlocking {
        forAll(Arb.string(256..400, Codepoint.alphanumeric())) { id ->
            try {
                validateParticipantIdNotPii(id)
                false
            } catch (e: ResponseStatusException) {
                e.reason?.contains("maximum length") == true
            }
        }
    } }

    @Test
    fun `email addresses are always rejected`() { runBlocking {
        forAll(
            Arb.string(3..20, Codepoint.az()),
            Arb.string(3..10, Codepoint.az()),
            Arb.element("com", "org", "edu", "net")
        ) { local, domain, tld ->
            val email = "$local@$domain.$tld"
            try {
                validateParticipantIdNotPii(email)
                false
            } catch (e: ResponseStatusException) {
                e.reason?.contains("email") == true
            }
        }
    } }

    @Test
    fun `SSN patterns are always rejected`() { runBlocking {
        forAll(
            Arb.int(100..999),
            Arb.int(10..99),
            Arb.int(1000..9999)
        ) { area, group, serial ->
            val ssn = "$area-${group}-$serial"
            try {
                validateParticipantIdNotPii(ssn)
                false
            } catch (e: ResponseStatusException) {
                e.reason?.contains("SSN") == true
            }
        }
    } }

    @Test
    fun `phone numbers are always rejected`() { runBlocking {
        forAll(Arb.long(1_000_000_000L..9_999_999_999L)) { number ->
            val phone = "+$number"
            try {
                validateParticipantIdNotPii(phone)
                false
            } catch (e: ResponseStatusException) {
                e.reason?.contains("phone") == true
            }
        }
    } }

    @Test
    fun `full names (First Last) are always rejected`() { runBlocking {
        val firstNames = Arb.element("John", "Alice", "Robert", "Maria", "David", "Sarah")
        val lastNames = Arb.element("Smith", "Johnson", "Williams", "Brown", "Jones", "Davis")
        forAll(firstNames, lastNames) { first, last ->
            val name = "$first $last"
            try {
                validateParticipantIdNotPii(name)
                false
            } catch (e: ResponseStatusException) {
                e.reason?.contains("name") == true
            }
        }
    } }

    @Test
    fun `full names separated by any whitespace are rejected`() {
        listOf("John\tSmith", "John\nSmith", "John   Smith").forEach { name ->
            try {
                validateParticipantIdNotPii(name)
                throw AssertionError("Expected name-like participant ID to be rejected: $name")
            } catch (e: ResponseStatusException) {
                assert(e.reason?.contains("name") == true)
            }
        }
    }

    @Test
    fun `validation is stable - validating twice produces same result`() { runBlocking {
        forAll(Arb.string(1..100, Codepoint.alphanumeric())) { id ->
            val result1 = runCatching { validateParticipantIdNotPii(id) }
            val result2 = runCatching { validateParticipantIdNotPii(id) }
            result1.isSuccess == result2.isSuccess
        }
    } }
}
