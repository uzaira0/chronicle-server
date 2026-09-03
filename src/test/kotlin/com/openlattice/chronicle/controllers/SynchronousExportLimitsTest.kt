package com.openlattice.chronicle.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

class SynchronousExportLimitsTest {
    private val start = OffsetDateTime.parse("2026-07-01T00:00:00Z")

    @Test
    fun `accepts a bounded synchronous export`() {
        val result = SynchronousExportLimits.validate(setOf("participant-1"), start, start.plusDays(31))
        assertEquals(start, result.first)
        assertEquals(start.plusDays(31), result.second)
    }

    @Test
    fun `rejects missing or oversized ranges`() {
        listOf(
            { SynchronousExportLimits.validate(setOf("participant-1"), null, start) },
            { SynchronousExportLimits.validate(setOf("participant-1"), start, start) },
            { SynchronousExportLimits.validate(setOf("participant-1"), start, start.plusDays(32)) },
        ).forEach { action ->
            val error = assertThrows(ResponseStatusException::class.java) { action() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }

    @Test
    fun `rejects empty or excessive participant sets`() {
        val tooMany = (1..SynchronousExportLimits.MAX_PARTICIPANTS + 1).map { "p-$it" }.toSet()
        listOf(emptySet(), tooMany).forEach { participants ->
            val error = assertThrows(ResponseStatusException::class.java) {
                SynchronousExportLimits.validate(participants, start, start.plusDays(1))
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }
}
