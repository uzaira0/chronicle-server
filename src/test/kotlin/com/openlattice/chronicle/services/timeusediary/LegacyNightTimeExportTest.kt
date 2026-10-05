package com.openlattice.chronicle.services.timeusediary

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.timeusediary.TimeUseDiaryColumnTitles
import com.openlattice.chronicle.timeusediary.TimeUseDiaryQuestionCodes
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class LegacyNightTimeExportTest {
    @Test
    fun historicalLegacySleepIsAnchoredToDiaryDayAcrossLaterDownloadsAndDstDates() {
        val service = Mockito.mock(TimeUseDiaryService::class.java, Mockito.CALLS_REAL_METHODS)
        val method = TimeUseDiaryService::class.java.getDeclaredMethod("getNightTimeDataColumnMapping", ResultSet::class.java)
            .apply { isAccessible = true }
        for (day in listOf("2020-10-01", "2020-03-08", "2020-11-01")) {
            val start = OffsetDateTime.parse("${day}T08:00:00-05:00")
            val responses = listOf(
                TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.PRIMARY_ACTIVITY, "activity", setOf("Home"), start, start.plusHours(1)),
                TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.ACTIVITY_DATE, "date", setOf(day), null, null),
                TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.DAY_START_TIME, "start", setOf("08:00"), null, null),
                TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.DAY_END_TIME, "end", setOf("22:00"), null, null),
                TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.TODAY_WAKEUP_TIME, "wake", setOf("07:00"), null, null),
            )
            for (downloadedAt in listOf(start.plusDays(2), start.plusDays(7))) {
                val rows = Mockito.mock(ResultSet::class.java)
                Mockito.`when`(rows.getString("participant_id")).thenReturn("p")
                Mockito.`when`(rows.getObject("study_id")).thenReturn(UUID.randomUUID())
                Mockito.`when`(rows.getObject("submission_id")).thenReturn(UUID.randomUUID())
                Mockito.`when`(rows.getObject("submission_date", OffsetDateTime::class.java)).thenReturn(downloadedAt)
                Mockito.`when`(rows.getString("submission")).thenReturn(ObjectMappers.getJsonMapper().writeValueAsString(responses))
                @Suppress("UNCHECKED_CAST")
                val exported = method.invoke(service, rows) as List<Map<String, Any>>
                assertEquals(9L, exported.single()[TimeUseDiaryColumnTitles.SLEEP_HOURS])
            }
        }
    }
}
