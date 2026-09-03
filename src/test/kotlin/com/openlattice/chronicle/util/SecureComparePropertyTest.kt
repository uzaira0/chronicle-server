package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Property-based tests for SecureCompare timing-safe comparison utility.
 */
class SecureComparePropertyTest {

    @Test
    fun `equals is reflexive - every string equals itself`() { runBlocking {
        forAll(Arb.string(0..200)) { s ->
            SecureCompare.equals(s, s)
        }
    } }

    @Test
    fun `equals is symmetric - a eq b implies b eq a`() { runBlocking {
        forAll(Arb.string(0..100), Arb.string(0..100)) { a, b ->
            SecureCompare.equals(a, b) == SecureCompare.equals(b, a)
        }
    } }

    @Test
    fun `different strings never match`() { runBlocking {
        forAll(Arb.string(1..100), Arb.string(1..100)) { a, b ->
            if (a == b) true  // skip equal pairs
            else !SecureCompare.equals(a, b)
        }
    } }

    @Test
    fun `byte array equals is reflexive`() { runBlocking {
        forAll(Arb.byteArray(Arb.int(0..200), Arb.byte())) { bytes ->
            SecureCompare.equals(bytes, bytes)
        }
    } }

    @Test
    fun `byte array equals is symmetric`() { runBlocking {
        forAll(
            Arb.byteArray(Arb.int(0..50), Arb.byte()),
            Arb.byteArray(Arb.int(0..50), Arb.byte())
        ) { a, b ->
            SecureCompare.equals(a, b) == SecureCompare.equals(b, a)
        }
    } }

    @Test
    fun `equalsNullSafe returns false when either argument is null`() { runBlocking {
        forAll(Arb.string(0..100)) { s ->
            !SecureCompare.equalsNullSafe(s, null) &&
                !SecureCompare.equalsNullSafe(null, s) &&
                !SecureCompare.equalsNullSafe(null as String?, null as String?)
        }
    } }

    @Test
    fun `equalsNullSafe agrees with equals for non-null inputs`() { runBlocking {
        forAll(Arb.string(0..100), Arb.string(0..100)) { a, b ->
            SecureCompare.equalsNullSafe(a, b) == SecureCompare.equals(a, b)
        }
    } }

    @Test
    fun `validateApiKey returns false for null or blank keys`() { runBlocking {
        forAll(Arb.string(1..50)) { stored ->
            !SecureCompare.validateApiKey(null, stored) &&
                !SecureCompare.validateApiKey("", stored) &&
                !SecureCompare.validateApiKey("  ", stored) &&
                !SecureCompare.validateApiKey(stored, null) &&
                !SecureCompare.validateApiKey(stored, "") &&
                !SecureCompare.validateApiKey(stored, "  ")
        }
    } }

    @Test
    fun `validateApiKey returns true when keys match`() { runBlocking {
        forAll(Arb.string(1..100).filter { it.isNotBlank() }) { key ->
            SecureCompare.validateApiKey(key, key)
        }
    } }

    @Test
    fun `validateToken returns false for null or blank tokens`() { runBlocking {
        forAll(Arb.string(1..50)) { token ->
            !SecureCompare.validateToken(null, token) &&
                !SecureCompare.validateToken(token, null) &&
                !SecureCompare.validateToken("", token) &&
                !SecureCompare.validateToken(token, "")
        }
    } }

    @Test
    fun `validateToken returns true when tokens match`() { runBlocking {
        forAll(Arb.string(1..100).filter { it.isNotBlank() }) { token ->
            SecureCompare.validateToken(token, token)
        }
    } }

    @Test
    fun `equals is consistent with String equals`() { runBlocking {
        forAll(Arb.string(0..100), Arb.string(0..100)) { a, b ->
            SecureCompare.equals(a, b) == (a == b)
        }
    } }
}
