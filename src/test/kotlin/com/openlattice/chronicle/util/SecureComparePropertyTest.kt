package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
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
    fun `equals is consistent with String equals`() { runBlocking {
        forAll(Arb.string(0..100), Arb.string(0..100)) { a, b ->
            SecureCompare.equals(a, b) == (a == b)
        }
    } }
}
