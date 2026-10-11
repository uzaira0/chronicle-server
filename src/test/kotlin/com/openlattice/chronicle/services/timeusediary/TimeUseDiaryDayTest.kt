package com.openlattice.chronicle.services.timeusediary

import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class TimeUseDiaryDayTest {
    // 20:00 in Chicago on Oct 1 is 01:00Z on Oct 2.
    private val eveningSubmission = OffsetDateTime.parse("2026-10-02T01:00:00Z")

    @Test
    fun `groups a submission on the local day of the requested range`() {
        val chicagoRangeStart = OffsetDateTime.parse("2026-10-01T00:00:00-05:00")
        assertEquals(LocalDate.parse("2026-10-01"), tudSubmissionDay(eveningSubmission, chicagoRangeStart.offset))
    }

    @Test fun `Santiago grouping follows the changed offset across September DST`() {
        val zone = java.time.ZoneId.of("America/Santiago")
        assertEquals(LocalDate.parse("2026-09-05"), tudSubmissionDay(OffsetDateTime.parse("2026-09-06T03:30:00Z"), zone))
        assertEquals(LocalDate.parse("2026-09-07"), tudSubmissionDay(OffsetDateTime.parse("2026-09-07T03:30:00Z"), zone))
    }

    @Test
    fun `counts the diary day the participant answered for`() {
        val responses = listOf(
            TimeUseDiaryResponse("activityDate", "activityDate", setOf("2026-10-01"), null, null),
        )
        assertEquals(LocalDate.parse("2026-10-01"), tudDiaryDay(responses, eveningSubmission))
    }

    @Test
    fun `falls back to the submission day without an activityDate answer`() {
        assertEquals(LocalDate.parse("2026-10-02"), tudDiaryDay(emptyList(), eveningSubmission))
    }
}
