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
 * Utility object for constant-time string comparisons to prevent timing attacks.
 *
 * Timing attacks exploit the fact that standard string comparison (equals, ==)
 * returns early when it finds the first mismatched character. An attacker can
 * measure response times to determine how many characters of a secret match,
 * allowing them to brute-force secrets one character at a time.
 *
 * This utility uses [MessageDigest.isEqual] which performs constant-time
 * comparison regardless of where (or if) the strings differ.
 *
 * Usage:
 * ```kotlin
 * // For API key validation
 * if (SecureCompare.equals(providedApiKey, storedApiKey)) {
 *     // Authenticated
 * }
 *
 * // For token validation
 * if (SecureCompare.equals(providedToken, expectedToken)) {
 *     // Valid token
 * }
 * ```
 *
 * @see MessageDigest.isEqual
 */
public object SecureCompare {

    /**
     * Performs a constant-time comparison of two strings.
     *
     * This method takes the same amount of time regardless of:
     * - How many characters match
     * - Where the first mismatch occurs
     * - The length of the strings (after conversion to bytes)
     *
     * Both strings are converted to UTF-8 byte arrays and compared using
     * [MessageDigest.isEqual], which is guaranteed to be constant-time.
     *
     * @param a The first string to compare (e.g., user-provided API key)
     * @param b The second string to compare (e.g., stored API key)
     * @return true if the strings are equal, false otherwise
     *
     * @throws NullPointerException if either string is null. Check for null values before comparing.
     */
    public fun equals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    /**
     * Performs a constant-time comparison of two byte arrays.
     *
     * Directly delegates to [MessageDigest.isEqual] for byte array comparisons.
     * Use this when you already have byte arrays (e.g., hashed values).
     *
     * @param a The first byte array to compare
     * @param b The second byte array to compare
     * @return true if the byte arrays are equal, false otherwise
     *
     * @throws NullPointerException if either array is null
     */
    public fun equals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

}
