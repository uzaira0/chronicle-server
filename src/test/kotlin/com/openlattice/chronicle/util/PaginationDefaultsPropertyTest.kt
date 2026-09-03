package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Property-based tests for PaginationDefaults to ensure bounds clamping prevents bulk data exfiltration.
 */
class PaginationDefaultsPropertyTest {

    @Test
    fun `clampLimit output is always between 1 and MAX_PAGE_SIZE`() { runBlocking {
        forAll(Arb.int()) { requested ->
            val result = PaginationDefaults.clampLimit(requested)
            result in 1..PaginationDefaults.MAX_PAGE_SIZE
        }
    } }

    @Test
    fun `clampLimit is idempotent - clamping twice equals clamping once`() { runBlocking {
        forAll(Arb.int()) { requested ->
            val once = PaginationDefaults.clampLimit(requested)
            val twice = PaginationDefaults.clampLimit(once)
            once == twice
        }
    } }

    @Test
    fun `clampLimit preserves values already in range`() { runBlocking {
        forAll(Arb.int(1..PaginationDefaults.MAX_PAGE_SIZE)) { requested ->
            PaginationDefaults.clampLimit(requested) == requested
        }
    } }

    @Test
    fun `clampLimit clamps values above max to MAX_PAGE_SIZE`() { runBlocking {
        forAll(Arb.int(PaginationDefaults.MAX_PAGE_SIZE + 1..Int.MAX_VALUE)) { requested ->
            PaginationDefaults.clampLimit(requested) == PaginationDefaults.MAX_PAGE_SIZE
        }
    } }

    @Test
    fun `clampLimit clamps zero and negative values to 1`() { runBlocking {
        forAll(Arb.int(Int.MIN_VALUE..0)) { requested ->
            PaginationDefaults.clampLimit(requested) == 1
        }
    } }

    @Test
    fun `clampOffset output is always non-negative`() { runBlocking {
        forAll(Arb.int()) { requested ->
            PaginationDefaults.clampOffset(requested) >= 0
        }
    } }

    @Test
    fun `clampOffset is idempotent - clamping twice equals clamping once`() { runBlocking {
        forAll(Arb.int()) { requested ->
            val once = PaginationDefaults.clampOffset(requested)
            val twice = PaginationDefaults.clampOffset(once)
            once == twice
        }
    } }

    @Test
    fun `clampOffset preserves non-negative values`() { runBlocking {
        forAll(Arb.int(0..Int.MAX_VALUE)) { requested ->
            PaginationDefaults.clampOffset(requested) == requested
        }
    } }

    @Test
    fun `clampOffset clamps negative values to 0`() { runBlocking {
        forAll(Arb.int(Int.MIN_VALUE..-1)) { requested ->
            PaginationDefaults.clampOffset(requested) == 0
        }
    } }

    @Test
    fun `clampLimit and clampOffset together produce valid pagination params`() { runBlocking {
        forAll(Arb.int(), Arb.int()) { rawLimit, rawOffset ->
            val limit = PaginationDefaults.clampLimit(rawLimit)
            val offset = PaginationDefaults.clampOffset(rawOffset)
            limit >= 1 && limit <= PaginationDefaults.MAX_PAGE_SIZE && offset >= 0
        }
    } }
}
