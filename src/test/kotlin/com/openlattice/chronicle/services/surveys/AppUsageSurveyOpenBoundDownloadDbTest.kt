package com.openlattice.chronicle.services.surveys

import com.geekbeast.postgres.PostgresArrays
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.storage.StorageResolver
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Open survey-day bounds bind SQL NULLs against the migrated Postgres schema. */
class AppUsageSurveyOpenBoundDownloadDbTest : ServerSweepTestFixture() {
    private val zone = ZoneId.of("America/Santiago")
    private fun day(date: Int) = LocalDate.of(2026, 4, date)
    private fun start(date: Int): OffsetDateTime = day(date).atStartOfDay(zone).toOffsetDateTime()

    private fun insertAnsweredDays(connection: Connection, study: UUID, participant: String) {
        connection.prepareStatement("""
            INSERT INTO app_usage_survey (study_id, participant_id, submission_date, application_label,
                app_package_name, event_timestamp, timezone, users, survey_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { statement ->
            // 3 April, the 4 April fall-back day (25 hours) and 5 April.
            for (date in 3..5) {
                statement.setObject(1, study)
                statement.setString(2, participant)
                statement.setObject(3, start(date + 2))
                statement.setString(4, "maps")
                statement.setString(5, "a.maps")
                statement.setObject(6, start(date).plusHours(10))
                statement.setString(7, zone.id)
                statement.setArray(8, PostgresArrays.createTextArray(connection, listOf("Child")))
                statement.setObject(9, day(date))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    @Test fun openBoundsReturnEveryAnsweredDayWithoutASqlError() {
        val study = UUID.randomUUID()
        val participant = "survey-open-bounds"
        seed(study, participant)
        pool("survey-open-bounds").use { pool ->
            pool.connection.use { insertAnsweredDays(it, study, participant) }
            val storage = mock<StorageResolver>().also {
                whenever(it.getPlatformStorage()).thenReturn(pool)
                whenever(it.getPlatformReadStorage()).thenReturn(pool)
            }
            // SynchronousExportLimits.validate turns blank modal dates into MIN / MAX.
            fun days(from: OffsetDateTime, to: OffsetDateTime, participants: Set<String> = setOf(participant)) = DataDownloadService(storage)
                .getParticipantsAppUsageSurveyData(study, participants, from, to, zone)
                .map { it.getValue("survey_date") as String }.sorted()

            val allDays = listOf("2026-04-03", "2026-04-04", "2026-04-05")
            assertEquals(allDays, days(OffsetDateTime.MIN, OffsetDateTime.MAX))
            assertEquals(allDays.drop(1), days(start(4), OffsetDateTime.MAX))
            assertEquals(allDays.dropLast(1), days(OffsetDateTime.MIN, start(5)))
            // The whole study (no participant filter) takes the same open-bound path.
            assertEquals(allDays, days(OffsetDateTime.MIN, OffsetDateTime.MAX, emptySet()))
        }
    }
}
