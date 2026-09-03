package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PaginationDefaultsTest {

    @Test
    fun testDefaultPageSize() {
        assertEquals(100, PaginationDefaults.DEFAULT_PAGE_SIZE)
    }

    @Test
    fun testMaxPageSize() {
        assertEquals(500, PaginationDefaults.MAX_PAGE_SIZE)
    }

    @Test
    fun testClampLimitWithinRange() {
        assertEquals(100, PaginationDefaults.clampLimit(100))
    }

    @Test
    fun testClampLimitBelowMinimumReturnsOne() {
        assertEquals(1, PaginationDefaults.clampLimit(0))
    }

    @Test
    fun testClampLimitNegativeReturnsOne() {
        assertEquals(1, PaginationDefaults.clampLimit(-5))
    }

    @Test
    fun testClampLimitAboveMaximumReturnMax() {
        assertEquals(500, PaginationDefaults.clampLimit(1000))
    }

    @Test
    fun testClampLimitExactMaximum() {
        assertEquals(500, PaginationDefaults.clampLimit(500))
    }

    @Test
    fun testClampLimitExactMinimum() {
        assertEquals(1, PaginationDefaults.clampLimit(1))
    }

    @Test
    fun testClampOffsetZero() {
        assertEquals(0, PaginationDefaults.clampOffset(0))
    }

    @Test
    fun testClampOffsetNegativeReturnsZero() {
        assertEquals(0, PaginationDefaults.clampOffset(-1))
    }

    @Test
    fun testClampOffsetPositive() {
        assertEquals(100, PaginationDefaults.clampOffset(100))
    }

    @Test
    fun testClampOffsetLargeNegativeReturnsZero() {
        assertEquals(0, PaginationDefaults.clampOffset(Int.MIN_VALUE))
    }

    @Test
    fun testDefaultOffset() {
        assertEquals(0, PaginationDefaults.DEFAULT_OFFSET)
    }
}
