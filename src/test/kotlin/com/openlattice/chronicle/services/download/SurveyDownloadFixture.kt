package com.openlattice.chronicle.services.download

import java.sql.Connection
import java.util.UUID

internal object SurveyDownloadFixture {
    const val ROW_COUNT = 2 * DataDownloadService.FETCH_SIZE + 1
    fun insertRows(connection: Connection, study: UUID, participant: String, count: Int) {
        connection.prepareStatement("""
            INSERT INTO app_usage_survey (study_id, participant_id, application_label,
                app_package_name, event_timestamp, submission_date, timezone, users, survey_date)
            SELECT ?, ?, 'test', 'test.app', now() + n * interval '1 ms', now(), 'UTC', '{}'::text[],
                (now() AT TIME ZONE 'UTC')::date
            FROM generate_series(1, ?) AS n
        """.trimIndent()).use {
            it.setObject(1, study); it.setString(2, participant); it.setInt(3, count); it.executeUpdate()
        }
    }
}
