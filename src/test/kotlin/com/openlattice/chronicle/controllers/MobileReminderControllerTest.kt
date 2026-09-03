package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeCommand
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.survey.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class MobileReminderControllerTest {
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val surveysManager = Mockito.mock(SurveysManager::class.java)
    private val accessService = Mockito.mock(ParticipantFormAccessService::class.java)
    private val controller = MobileReminderController(
        enrollmentManager,
        studyService,
        surveysManager,
        accessService,
    )
    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val keyId = UUID.randomUUID()
    private val expiresAt = OffsetDateTime.of(2026, 7, 20, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun reminderManifestUsesStateChangingHttpSemantics() {
        val endpoint = MobileReminderController::class.java.declaredMethods
            .single { method -> method.name == "getReminderConfiguration" }

        val postMapping = endpoint.getAnnotation(PostMapping::class.java)
        assertNotNull(postMapping)
        assertNull(endpoint.getAnnotation(GetMapping::class.java))
        assertEquals(
            setOf(
                "/v4/study/{studyId}/participant/{participantId}/reminders",
                "/chronicle/v4/study/{studyId}/participant/{participantId}/reminders",
            ),
            postMapping.path.toSet(),
        )
    }

    @Test
    fun enrolledDeviceReceivesOnlySurveyAndActiveRecurringQuestionnaire() {
        val includedQuestionnaireId = UUID.randomUUID()
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        whenever(studyService.isNotificationsEnabled(studyId)).thenReturn(true)
        whenever(surveysManager.getStudyQuestionnaires(studyId)).thenReturn(
            listOf(
                questionnaire(includedQuestionnaireId, active = true, recurrenceRule = "FREQ=DAILY"),
                questionnaire(UUID.randomUUID(), active = false, recurrenceRule = "FREQ=DAILY"),
                questionnaire(UUID.randomUUID(), active = true, recurrenceRule = null),
            ),
        )
        whenever(accessService.createAccessCodes(any())).thenAnswer { invocation ->
            invocation.getArgument<List<ParticipantAccessCodeCommand>>(0).map { command ->
                ParticipantFormAccessCodeResponse(
                    accessCode = "code-${command.formKind.name.lowercase()}",
                    expiresAt = expiresAt,
                    formKind = command.formKind,
                    resourceId = command.resourceId,
                )
            }
        }
        val response = MockHttpServletResponse()

        val result = controller.getReminderConfiguration(
            studyId,
            participantId,
            deviceToken(),
            response,
        )

        assertEquals(ParticipationStatus.ENROLLED, result.participationStatus)
        assertEquals(listOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE), result.forms.map { it.formKind })
        assertEquals(includedQuestionnaireId, result.forms.single { it.formKind == ParticipantFormKind.QUESTIONNAIRE }.resourceId)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
        val commands = argumentCaptor<List<ParticipantAccessCodeCommand>>()
        verify(accessService).createAccessCodes(commands.capture())
        assertEquals(2, commands.firstValue.size)
        assertEquals(
            setOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE),
            commands.firstValue.map { it.formKind }.toSet(),
        )
        assertEquals(setOf(ParticipantAccessCodeIssuerType.DEVICE), commands.firstValue.map { it.issuerType }.toSet())
    }

    @Test
    fun unenrolledDeviceReceivesNoReminderCodes() {
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val result = controller.getReminderConfiguration(
            studyId,
            participantId,
            deviceToken(),
            MockHttpServletResponse(),
        )

        assertEquals(emptyList<Any>(), result.forms)
        verify(accessService, never()).createAccessCodes(any())
        verify(surveysManager, never()).getStudyQuestionnaires(any())
    }

    @Test
    fun credentialBoundToAnotherParticipantIsRejected() {
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getReminderConfiguration(
                studyId,
                participantId,
                deviceToken(boundParticipantId = "participant-2"),
                MockHttpServletResponse(),
            )
        }

        assertEquals(403, error.statusCode.value())
        verify(enrollmentManager, never()).getParticipationStatus(any(), any())
    }

    private fun deviceToken(boundParticipantId: String = participantId) = ApiKeyAuthenticationToken(
        principal = "apikey:$keyId",
        keyId = keyId,
        studyId = studyId,
        participantId = boundParticipantId,
        deviceId = UUID.randomUUID(),
        scope = ApiKeyScope.READ_ONLY,
        authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
    )

    private fun questionnaire(id: UUID, active: Boolean, recurrenceRule: String?) = Questionnaire(
        id = id,
        title = "Weekly check-in",
        dateCreated = null,
        active = active,
        questions = listOf(Question("How was your week?")),
        recurrenceRule = recurrenceRule,
    )
}
