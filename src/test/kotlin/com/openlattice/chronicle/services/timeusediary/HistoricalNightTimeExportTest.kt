package com.openlattice.chronicle.services.timeusediary

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.timeusediary.TimeUseDiaryColumnTitles
import com.openlattice.chronicle.timeusediary.TimeUseDiaryDownloadDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryQuestionCodes
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.UUID

class HistoricalNightTimeExportTest : ServerSweepTestFixture() {
    @Test
    fun historicalSleepIsAnchoredToDiaryDayAcrossLaterDownloadsAndDstDates() {
        val studyId = UUID.randomUUID()
        seed(studyId, "p")
        pool("historical-night-time-export").use { pool ->
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            val service = TimeUseDiaryService(storage, mock(), mock())
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
                    val submissionId = UUID.randomUUID()
                    insertSubmission(pool, studyId, submissionId, downloadedAt, responses)
                    val exported = service.getStudyTUDSubmissions(
                        studyId, null, TimeUseDiaryDownloadDataType.NightTime,
                        downloadedAt.minusMinutes(1), downloadedAt.plusMinutes(1),
                    ).toList().single().single()
                    assertEquals(9L, exported[TimeUseDiaryColumnTitles.SLEEP_HOURS])
                    assertEquals(studyId, exported[TimeUseDiaryColumnTitles.STUDY_ID])
                    assertEquals(submissionId, exported[TimeUseDiaryColumnTitles.SUBMISSION_ID])
                    assertEquals("p", exported[TimeUseDiaryColumnTitles.PARTICIPANT_ID])
                    assertEquals(downloadedAt.toInstant(),
                        (exported[TimeUseDiaryColumnTitles.TIMESTAMP] as OffsetDateTime).toInstant())
                }
            }
        }
    }

    private fun insertSubmission(
        pool: HikariDataSource,
        studyId: UUID,
        submissionId: UUID,
        submittedAt: OffsetDateTime,
        responses: List<TimeUseDiaryResponse>,
    ) {
        pool.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO time_use_diary_submissions
                    (submission_id, study_id, participant_id, submission_date, submission)
                VALUES (?, ?, 'p', ?, ?::jsonb)
            """.trimIndent()).use { statement ->
                statement.setObject(1, submissionId)
                statement.setObject(2, studyId)
                statement.setObject(3, submittedAt)
                statement.setString(4, ObjectMappers.getJsonMapper().writeValueAsString(responses))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }
}
