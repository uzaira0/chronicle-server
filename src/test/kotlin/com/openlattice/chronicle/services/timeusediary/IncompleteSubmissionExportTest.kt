package com.openlattice.chronicle.services.timeusediary

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.timeusediary.TimeUseDiaryColumnTitles
import com.openlattice.chronicle.timeusediary.TimeUseDiaryDownloadDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryQuestionCodes
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.UUID

/** One participant's incomplete diary must export with blank cells, not fail the whole download. */
class IncompleteSubmissionExportTest : ServerSweepTestFixture() {
    private val start = OffsetDateTime.parse("2026-10-01T08:00:00-05:00")

    @Test
    fun anActivityWithoutAnEndTimeExportsBlankEndAndDuration() {
        val row = export(TimeUseDiaryDownloadDataType.DayTime, listOf(
            TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.PRIMARY_ACTIVITY, "activity", setOf("Home"), start, null),
        )).single()
        assertEquals("08:00", row[TimeUseDiaryColumnTitles.ACTIVITY_START_TIME])
        assertEquals("", row[TimeUseDiaryColumnTitles.ACTIVITY_END_TIME])
        assertEquals("", row[TimeUseDiaryColumnTitles.ACTIVITY_DURATION])
    }

    @Test
    fun aNightTimeDiaryWithoutAPrimaryActivityStillExports() {
        val row = export(TimeUseDiaryDownloadDataType.NightTime, listOf(
            TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.ACTIVITY_DATE, "date", setOf("2026-10-01"), null, null),
            TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.DAY_START_TIME, "start", setOf("08:00"), null, null),
            TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.DAY_END_TIME, "end", setOf("22:00"), null, null),
            TimeUseDiaryResponse(TimeUseDiaryQuestionCodes.TODAY_WAKEUP_TIME, "wake", setOf("07:00"), null, null),
        )).single()
        assertEquals("08:00", row[TimeUseDiaryColumnTitles.WAKE_UP_YESTERDAY])
        assertEquals(9L, row[TimeUseDiaryColumnTitles.SLEEP_HOURS])
    }

    private fun export(
        downloadType: TimeUseDiaryDownloadDataType,
        responses: List<TimeUseDiaryResponse>,
    ): List<Map<String, Any>> {
        val studyId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        seed(studyId, "p")
        return pool("incomplete-diary-export").use { pool ->
            pool.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO time_use_diary_submissions
                        (submission_id, study_id, participant_id, submission_date, submission)
                    VALUES (?, ?, 'p', ?, ?::jsonb)
                """.trimIndent()).use { statement ->
                    statement.setObject(1, submissionId)
                    statement.setObject(2, studyId)
                    statement.setObject(3, start)
                    statement.setString(4, ObjectMappers.getJsonMapper().writeValueAsString(responses))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            val service = TimeUseDiaryService(storage, mock(), mock())
            service.getStudyTUDSubmissions(
                studyId, null, downloadType, start.minusDays(1), start.plusDays(1),
            ).toList().single().also { rows ->
                val row = rows.single()
                assertEquals(studyId, row[TimeUseDiaryColumnTitles.STUDY_ID])
                assertEquals(submissionId, row[TimeUseDiaryColumnTitles.SUBMISSION_ID])
                assertEquals("p", row[TimeUseDiaryColumnTitles.PARTICIPANT_ID])
                assertEquals(start.toInstant(), (row[TimeUseDiaryColumnTitles.TIMESTAMP] as OffsetDateTime).toInstant())
            }
        }
    }
}
