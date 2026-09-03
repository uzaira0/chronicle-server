package com.openlattice.chronicle.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, example-based tests for [SecureCompare] pinning the exact true/false
 * outcome of every comparison path and null/blank guard so PIT mutants are killed.
 */
class SecureCompareMutationTest {

    // ---- equals(String, String) ----

    @Test
    fun `equals returns true for identical strings`() {
        assertTrue(SecureCompare.equals("secret-token", "secret-token"))
    }

    @Test
    fun `equals returns false for differing strings`() {
        assertFalse(SecureCompare.equals("secret-token", "secret-tokex"))
    }

    @Test
    fun `equals returns false for different lengths`() {
        assertFalse(SecureCompare.equals("abc", "abcd"))
    }

    @Test
    fun `equals returns true for two empty strings`() {
        assertTrue(SecureCompare.equals("", ""))
    }

    @Test
    fun `equals on null throws NPE per contract`() {
        @Suppress("ImplicitNullableNothingType")
        val nullStr: String? = null
        assertThrows(NullPointerException::class.java) { SecureCompare.equals(nullStr!!, "x") }
    }

    // ---- equals(ByteArray, ByteArray) ----

    @Test
    fun `equals byte arrays true and false`() {
        assertTrue(SecureCompare.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(SecureCompare.equals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    // ---- equalsNullSafe(String?, String?) ----

    @Test
    fun `equalsNullSafe string true for equal non-null`() {
        assertTrue(SecureCompare.equalsNullSafe("k", "k"))
    }

    @Test
    fun `equalsNullSafe string false for unequal non-null`() {
        assertFalse(SecureCompare.equalsNullSafe("k", "j"))
    }

    @Test
    fun `equalsNullSafe string false when first is null`() {
        assertFalse(SecureCompare.equalsNullSafe(null, "k"))
    }

    @Test
    fun `equalsNullSafe string false when second is null`() {
        assertFalse(SecureCompare.equalsNullSafe("k", null))
    }

    @Test
    fun `equalsNullSafe string false when both null`() {
        assertFalse(SecureCompare.equalsNullSafe(null as String?, null as String?))
    }

    // ---- equalsNullSafe(ByteArray?, ByteArray?) ----

    @Test
    fun `equalsNullSafe byte arrays covers null and value branches`() {
        assertTrue(SecureCompare.equalsNullSafe(byteArrayOf(9), byteArrayOf(9)))
        assertFalse(SecureCompare.equalsNullSafe(byteArrayOf(9), byteArrayOf(8)))
        assertFalse(SecureCompare.equalsNullSafe(null, byteArrayOf(9)))
        assertFalse(SecureCompare.equalsNullSafe(byteArrayOf(9), null))
    }

    // ---- validateApiKey ----

    @Test
    fun `validateApiKey true for equal non-blank keys`() {
        assertTrue(SecureCompare.validateApiKey("api-key-123", "api-key-123"))
    }

    @Test
    fun `validateApiKey false for differing keys`() {
        assertFalse(SecureCompare.validateApiKey("api-key-123", "api-key-999"))
    }

    @Test
    fun `validateApiKey false when provided is null or blank`() {
        assertFalse(SecureCompare.validateApiKey(null, "stored"))
        assertFalse(SecureCompare.validateApiKey("   ", "stored"))
    }

    @Test
    fun `validateApiKey false when stored is null or blank`() {
        assertFalse(SecureCompare.validateApiKey("provided", null))
        assertFalse(SecureCompare.validateApiKey("provided", "   "))
    }

    // ---- validateToken ----

    @Test
    fun `validateToken true for equal non-blank tokens`() {
        assertTrue(SecureCompare.validateToken("tok", "tok"))
    }

    @Test
    fun `validateToken false for differing tokens`() {
        assertFalse(SecureCompare.validateToken("tok", "tos"))
    }

    @Test
    fun `validateToken false when provided is null or blank`() {
        assertFalse(SecureCompare.validateToken(null, "expected"))
        assertFalse(SecureCompare.validateToken("  ", "expected"))
    }

    @Test
    fun `validateToken false when expected is null or blank`() {
        assertFalse(SecureCompare.validateToken("provided", null))
        assertFalse(SecureCompare.validateToken("provided", "  "))
    }
}
