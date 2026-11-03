package com.openlattice.chronicle.services.surveys

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresDatatype
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRES
import com.openlattice.chronicle.survey.Questionnaire
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** Questionnaire reads and inserts on the caller's transaction. */
internal class QuestionnaireRepository(
    private val connection: Connection,
    private val mapper: ObjectMapper = ObjectMappers.getJsonMapper(),
) {
    fun insert(studyId: UUID, questionnaireId: UUID, questionnaire: Questionnaire) {
        val columns = QUESTIONNAIRES.columns.joinToString { it.name }
        val parameters = QUESTIONNAIRES.columns.joinToString {
            if (it.datatype == PostgresDatatype.JSONB) "?::jsonb" else "?"
        }
        connection.prepareStatement("INSERT INTO ${QUESTIONNAIRES.name} ($columns) VALUES ($parameters)").use { statement ->
            statement.setObject(1, studyId)
            statement.setObject(2, questionnaireId)
            statement.setString(3, questionnaire.title)
            statement.setString(4, questionnaire.description)
            statement.setString(5, mapper.writeValueAsString(questionnaire.questions))
            statement.setBoolean(6, questionnaire.active)
            statement.setObject(7, OffsetDateTime.now())
            statement.setString(8, questionnaire.recurrenceRule)
            statement.executeUpdate()
        }
    }

    fun loadSnapshot(studyId: UUID, questionnaireId: UUID): String? =
        connection.prepareStatement(
            "SELECT jsonb_build_object('title', title, 'description', description, 'questions', questions, " +
                "'active', active, 'recurrenceRule', recurrence_rule)::text " +
                "FROM ${QUESTIONNAIRES.name} WHERE study_id = ? AND questionnaire_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setObject(2, questionnaireId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
}
