package com.openlattice.chronicle.services.delete

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.filters.ConcurrentEditFilter
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.surveys.QuestionnaireRepository
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class QuestionnaireReminderPatchTest : ServerSweepTestFixture() {
    private val mapper = JacksonSecurityConfig().secureObjectMapper()

    @Test fun realDeserializerAndTransactionPreserveClearAndSetReminders() {
        val study = UUID.randomUUID()
        val questionnaireId = UUID.randomUUID()
        seed(study, "reminder-patch")
        val original = Questionnaire(null, "Reminder", null, questions = listOf(Question("Mood")),
            recurrenceRule = "FREQ=DAILY")
        pool("reminder-patch").use { hds ->
            hds.connection.use { QuestionnaireRepository(it, mapper).insert(study, questionnaireId, original) }
            val storage = resolver(hds)
            val surveys = SurveysService(Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS), storage,
                Mockito.mock(EnrollmentManager::class.java), Mockito.mock(ScheduledTasksManager::class.java),
                Mockito.mock(AuditingManager::class.java), Mockito.mock(HazelcastIdGenerationService::class.java))
            val filter = ConcurrentEditFilter(storage, Mockito.mock(StudyAuthorizationService::class.java))
            val path = "/chronicle/v3/survey/$study/questionnaire/$questionnaireId"
            TestSecurityUtils.setupSecurityContext()
            try {
                val newRule = "FREQ=WEEKLY;BYDAY=MO"
                val cases = listOf("" to original.recurrenceRule, ",\"recurrenceRule\":null" to null,
                    ",\"recurrenceRule\":\"$newRule\"" to newRule)
                for ((field, expected) in cases) {
                    patchReminder(filter, path, field) { update ->
                        surveys.updateQuestionnaire(study, questionnaireId, update)
                    }
                    assertStoredReminder(hds, study, questionnaireId, expected)
                }
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
    }

    private fun patchReminder(filter: ConcurrentEditFilter, path: String, field: String, update: (QuestionnaireUpdate) -> Unit) {
        val head = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("HEAD", path), head, FilterChain { _, _ -> })
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("PATCH", path).apply { addHeader("If-Match", requireNotNull(head.getHeader("ETag"))) }
        filter.doFilter(request, response, FilterChain { _, output ->
            update(mapper.readValue("""{"title":"Reminder"$field}""", QuestionnaireUpdate::class.java))
            output.writer.write("saved"); output.flushBuffer()
        })
        assertEquals(200, response.status)
    }

    private fun assertStoredReminder(hds: HikariDataSource, study: UUID, questionnaire: UUID, expected: String?) {
        hds.connection.use { connection ->
            val json = requireNotNull(QuestionnaireRepository(connection, mapper).loadSnapshot(study, questionnaire))
            assertEquals(expected, mapper.readTree(json).get("recurrenceRule").takeUnless { it.isNull }?.asText())
        }
    }
}
