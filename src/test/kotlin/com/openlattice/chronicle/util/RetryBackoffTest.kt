package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {
    @Test fun delaysSpreadUniformlyUpToTheDoublingCap() {
        for ((priorAttempts, cap) in listOf(0 to 1L, 1 to 2L, 2 to 4L, 4 to 16L, 5 to 30L, 9 to 30L)) {
            val draws = List(2_000) { RetryBackoff.delaySeconds(priorAttempts) }
            assertTrue(draws.all { it in 1L..cap })
            assertEquals("every value in [1, $cap] is drawn", (1L..cap).toSet(), draws.toSet())
        }
    }
}
