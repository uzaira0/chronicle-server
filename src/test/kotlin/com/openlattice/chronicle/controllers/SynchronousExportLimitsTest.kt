package com.openlattice.chronicle.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

class SynchronousExportLimitsTest {
    private val start = OffsetDateTime.parse("2026-07-01T00:00:00Z")
    private val end = start.plusDays(400)

    @Test
    fun `accepts a 400 day synchronous export`() {
        val result = SynchronousExportLimits.validate(setOf("participant-1"), start, end)
        assertEquals(start, result.start)
        assertEquals(end, result.end)
    }

    @Test
    fun `accepts independently omitted bounds`() {
        for ((lower, upper) in listOf(null to null, start to null, null to end)) {
            val result = SynchronousExportLimits.validate(setOf("participant-1"), lower, upper)
            assertEquals(lower ?: OffsetDateTime.MIN, result.start)
            assertEquals(upper ?: OffsetDateTime.MAX, result.end)
        }
    }

    @Test
    fun `rejects nonpositive ranges`() {
        listOf(
            { SynchronousExportLimits.validate(setOf("participant-1"), start, start) },
            { SynchronousExportLimits.validate(setOf("participant-1"), start, start.minusDays(1)) },
        ).forEach { action ->
            val error = assertThrows(ResponseStatusException::class.java) { action() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }

    @Test
    fun `rejects an empty participant set but never a large one`() {
        val error = assertThrows(ResponseStatusException::class.java) {
            SynchronousExportLimits.validate(emptySet(), start, start.plusDays(1))
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        val everyone = (1..10_000).map { "p-$it" }.toSet()
        assertEquals(start, SynchronousExportLimits.validate(everyone, start, start.plusDays(1)).start)
    }
}
