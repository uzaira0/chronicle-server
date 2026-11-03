package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.pods.servlet.controllerValidator
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * Element constraints such as `List<@Size(max = 255) String>` are enforced on the real routes, including the rows of a
 * list body: an over-length value is a 400, and a value at the cap is accepted.
 */
class InputLengthCapsTest {
    private val studyId = UUID.randomUUID()
    private val participant = "participant-1"
    private val mapper = ObjectMappers.getJsonMapper()

    @After
    fun clearSecurityContext() = TestSecurityUtils.clearSecurityContext()

    private fun answers(user: String) = mapper.writeValueAsString(listOf(mapOf(
        "appPackageName" to "com.example.app", "blockStart" to "2026-10-17T00:00:00-03:00", "users" to listOf(user))))

    private fun responses(value: String) = mapper.writeValueAsString(listOf(mapOf(
        "questionTitle" to "How was your day?", "value" to setOf(value))))

    private fun diary(value: String) = mapper.writeValueAsString(listOf(mapOf(
        "code" to "primaryActivity", "question" to "Primary activity", "response" to setOf(value))))

    private fun questionnaire(choice: String) = mapper.writeValueAsString(mapOf(
        "title" to "Daily check-in", "questions" to listOf(mapOf("title" to "Mood", "choices" to setOf(choice)))))

    private fun MockMvc.send(path: String, body: String): MockHttpServletResponse =
        perform(post(SurveyApi.SERVICE + path).contextPath(SurveyApi.SERVICE)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response

    private fun <T> withMvc(action: (MockMvc) -> T): T {
        val fixture = InputLengthCapsFixture()
        Mockito.`when`(fixture.studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(fixture.studyService.getStudySettings(studyId)).thenReturn(emptyMap())
        Mockito.`when`(fixture.enrollmentManager.isKnownParticipant(studyId, participant)).thenReturn(true)
        Mockito.`when`(fixture.authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(fixture.surveysService.createQuestionnaire(kAny(), kAny())).thenReturn(UUID.randomUUID())
        TestSecurityUtils.setupSecurityContext()
        return action(MockMvcBuilders.standaloneSetup(fixture.surveyController, fixture.timeUseDiaryController)
            .setControllerAdvice(fixture.exceptionHandler)
            .setValidator(controllerValidator().apply { afterPropertiesSet() })
            .build())
    }

    private fun assertRefused(response: MockHttpServletResponse, field: String) {
        assertEquals(response.contentAsString, 400, response.status)
        val details = mapper.readTree(response.contentAsString).path("details").map { it.asText() }
        assertTrue(response.contentAsString, details.any { it.startsWith(field) })
    }

    @Test
    fun appUsageSurveyUserValueIsCappedAt255() = withMvc { mvc ->
        val path = SurveyApi.CONTROLLER + "/$studyId/participant/$participant/app-usage?date=2026-10-17"
        assertRefused(mvc.send(path, answers("u".repeat(256))), "[0].users[0]")
        assertEquals(200, mvc.send(path, answers("u".repeat(255))).status)
    }

    @Test
    fun questionnaireResponseValueIsCappedAt10000() = withMvc { mvc ->
        val path = SurveyApi.CONTROLLER + "/$studyId/participant/$participant/questionnaire/${UUID.randomUUID()}"
        assertRefused(mvc.send(path, responses("v".repeat(10_001))), "[0].value[]")
        assertEquals(200, mvc.send(path, responses("v".repeat(10_000))).status)
    }

    @Test
    fun questionChoiceIsCappedAt1000() = withMvc { mvc ->
        val path = SurveyApi.CONTROLLER + "/$studyId/questionnaire"
        assertRefused(mvc.send(path, questionnaire("c".repeat(1001))), "questions[0].choices[]")
        assertEquals(200, mvc.send(path, questionnaire("c".repeat(1000))).status)
    }

    @Test
    fun timeUseDiaryResponseValueIsCappedAt1000() = withMvc { mvc ->
        assertRefused(mvc.send(TimeUseDiaryApi.CONTROLLER + "/$studyId/participant/$participant", diary("r".repeat(1001))),
            "[0].response[]")
    }
}

/** The real survey and diary controllers over mocked services. */
private class InputLengthCapsFixture {
    val studyService: StudyService = Mockito.mock(StudyService::class.java)
    val enrollmentManager: EnrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    val authorizationManager: AuthorizationManager = Mockito.mock(AuthorizationManager::class.java)
    val surveysService: SurveysService = Mockito.mock(SurveysService::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)
    private val receipts = Mockito.mock(ParticipantFormSubmissionReceiptService::class.java)
    private val ids = Mockito.mock(HazelcastIdGenerationService::class.java)

    val surveyController = SurveyController(
        surveysService, studyService, Mockito.mock(DataDownloadService::class.java), ids,
        authorizationManager, auditingManager, auditService, enrollmentManager, receipts,
    )

    val timeUseDiaryController = TimeUseDiaryController(
        authorizationManager, auditingManager, Mockito.mock(StorageResolver::class.java), ids,
        Mockito.mock(TimeUseDiaryService::class.java), studyService, auditService, enrollmentManager, receipts,
    )

    val exceptionHandler = ChronicleServerExceptionHandler(auditingManager, ErrorSanitizationConfig())
}
