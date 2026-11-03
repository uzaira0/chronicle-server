package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.inRequestLanguage
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.survey.AppUsageSurveyDay
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.survey.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class MobileReminderControllerTest {
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val surveysManager = Mockito.mock(SurveysManager::class.java)
    private val acknowledgments = Mockito.mock(ParticipantCollectionAcknowledgmentService::class.java)
    private val controller = MobileReminderController(
        enrollmentManager,
        studyService,
        surveysManager,
        acknowledgments,
    )
    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val keyId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 7, 20)

    private fun enrolledWithDays(state: AppUsageDayState) {
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        whenever(surveysManager.getAppUsageDays(eq(studyId), eq(participantId), any())).thenReturn(
            AppUsageSurveyDays("America/Santiago", today, listOf(
                AppUsageSurveyDay(today.minusDays(2), AppUsageDayState.ANSWERED),
                AppUsageSurveyDay(today.minusDays(1), state),
            )),
        )
    }

    @Test
    fun reminderScheduleIsAReadAndNothingMintsCodes() {
        val mappings = MobileReminderController::class.java.declaredMethods
        assertEquals(emptyList<Any>(), mappings.filter { it.getAnnotation(PostMapping::class.java) != null })
        assertNotNull(mappings.single { it.name == "getReminderSchedule" }.getAnnotation(GetMapping::class.java))
    }

    @Test
    fun enrolledDeviceReceivesOnlySurveyAndActiveRecurringQuestionnaire() {
        val includedQuestionnaireId = UUID.randomUUID()
        enrolledWithDays(AppUsageDayState.ANSWERED)
        whenever(surveysManager.getStudyQuestionnaires(studyId)).thenReturn(
            listOf(
                questionnaire(includedQuestionnaireId, active = true, recurrenceRule = "FREQ=DAILY"),
                questionnaire(UUID.randomUUID(), active = false, recurrenceRule = "FREQ=DAILY"),
                questionnaire(UUID.randomUUID(), active = true, recurrenceRule = null),
            ),
        )
        val response = MockHttpServletResponse()

        val result = controller.getReminderSchedule(studyId, participantId, deviceToken(), response)

        assertEquals(ParticipationStatus.ENROLLED, result.participationStatus)
        assertEquals(listOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE), result.forms.map { it.formKind })
        assertEquals(includedQuestionnaireId, result.forms.single { it.formKind == ParticipantFormKind.QUESTIONNAIRE }.resourceId)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
    }

    @Test
    fun surveyReminderRepeatsAtNoonAndSevenInTheStudyZoneWhileADayIsOpen() {
        listOf(
            AppUsageDayState.UNANSWERED to true,
            AppUsageDayState.NEW_APPS to true,
            AppUsageDayState.ANSWERED to false,
        ).forEach { (state, open) ->
            enrolledWithDays(state)
            val survey = controller.getReminderSchedule(studyId, participantId, deviceToken(), MockHttpServletResponse())
                .forms.single { it.formKind == ParticipantFormKind.APP_USAGE }
            assertEquals("FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0", survey.recurrenceRule)
            assertEquals("America/Santiago", survey.timeZone)
            assertEquals(state.name, open, survey.unansweredDays)
        }
    }

    @Test
    fun spanishPhoneReceivesTheSurveyReminderTitleInSpanish() {
        enrolledWithDays(AppUsageDayState.UNANSWERED)
        fun title(language: String) = inRequestLanguage(language) {
            controller.getReminderSchedule(studyId, participantId, deviceToken(), MockHttpServletResponse())
                .forms.single { it.formKind == ParticipantFormKind.APP_USAGE }.title
        }
        assertEquals("Encuesta de Chronicle", title("es-CL"))
        assertEquals("Chronicle Survey", title("en-US"))
    }

    @Test
    fun aStudyWithoutTheSurveyRemindsOnlyOfQuestionnaires() {
        enrolledWithDays(AppUsageDayState.UNANSWERED)
        whenever(surveysManager.getAppUsageDays(eq(studyId), eq(participantId), any())).thenReturn(null)
        whenever(surveysManager.getStudyQuestionnaires(studyId)).thenReturn(
            listOf(questionnaire(UUID.randomUUID(), active = true, recurrenceRule = "FREQ=DAILY")),
        )

        val forms = controller.getReminderSchedule(studyId, participantId, deviceToken(), MockHttpServletResponse()).forms

        assertEquals(listOf(ParticipantFormKind.QUESTIONNAIRE), forms.map { it.formKind })
    }

    @Test
    fun unenrolledDeviceReceivesNoReminders() {
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val result = controller.getReminderSchedule(studyId, participantId, deviceToken(), MockHttpServletResponse())

        assertEquals(emptyList<Any>(), result.forms)
        verify(surveysManager, never()).getStudyQuestionnaires(any())
    }

    @Test
    fun requiredDeclineEmptiesTheReminderScheduleUntilAccepted() {
        val token = deviceToken()
        val deviceId = checkNotNull(token.randomDeviceId)
        enrolledWithDays(AppUsageDayState.UNANSWERED)
        whenever(surveysManager.getStudyQuestionnaires(studyId)).thenReturn(emptyList())
        whenever(acknowledgments.isCollectionHalted(studyId, participantId, deviceId)).thenReturn(true)

        val schedule = controller.getReminderSchedule(studyId, participantId, token, MockHttpServletResponse())

        // The real participation status is kept; only the reminder forms stop.
        assertEquals(ParticipationStatus.ENROLLED, schedule.participationStatus)
        assertEquals(emptyList<Any>(), schedule.forms)

        whenever(acknowledgments.isCollectionHalted(studyId, participantId, deviceId)).thenReturn(false)
        assertEquals(
            listOf(ParticipantFormKind.APP_USAGE),
            controller.getReminderSchedule(studyId, participantId, token, MockHttpServletResponse()).forms.map { it.formKind },
        )
    }

    @Test
    fun credentialBoundToAnotherParticipantIsRejected() {
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getReminderSchedule(
                studyId,
                participantId,
                deviceToken(boundParticipantId = "participant-2"),
                MockHttpServletResponse(),
            )
        }

        assertEquals(403, error.statusCode.value())
        verify(enrollmentManager, never()).getDeviceParticipationStatus(any(), any())
    }

    @Test
    fun statusReadReportsPauseWithItsCaptureWindowWithoutMintingCodes() {
        val pausedAt = OffsetDateTime.parse("2026-10-28T13:00:00Z")
        val published = MobileParticipationStatus(ParticipationStatus.PAUSED, pausedAt.plusMinutes(5),
            listOf(CaptureWindow(pausedAt)))
        whenever(enrollmentManager.getDeviceCollectionStatus(studyId, participantId)).thenReturn(published)

        val status = controller.getParticipationStatus(studyId, participantId, deviceToken(), MockHttpServletResponse())

        assertEquals(published, status)
    }

    @Test
    fun statusReadRejectsCredentialBoundToAnotherParticipant() {
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getParticipationStatus(
                studyId,
                participantId,
                deviceToken(boundParticipantId = "participant-2"),
                MockHttpServletResponse(),
            )
        }

        assertEquals(403, error.statusCode.value())
        verify(enrollmentManager, never()).getDeviceCollectionStatus(any(), any())
    }

    @Test
    fun researcherErasedParticipantReadsErasedOnEveryDeviceRoute() {
        // The quarantine hides the row (and completion deletes it): the researcher read throws.
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenThrow(IllegalStateException("No row returned"))
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ERASED)
        whenever(enrollmentManager.getDeviceCollectionStatus(studyId, participantId))
            .thenReturn(MobileParticipationStatus(ParticipationStatus.ERASED, OffsetDateTime.now()))

        val status = controller.getParticipationStatus(studyId, participantId, deviceToken(), MockHttpServletResponse())
        val schedule = controller.getReminderSchedule(studyId, participantId, deviceToken(), MockHttpServletResponse())

        assertEquals(ParticipationStatus.ERASED, status.participationStatus)
        assertEquals(ParticipationStatus.ERASED, schedule.participationStatus)
        assertEquals(emptyList<Any>(), schedule.forms)
    }

    @Test
    fun erasedDeviceKeySignalsErasureWithoutStatusLookup() {
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)

        val status = controller.getParticipationStatus(
            studyId,
            participantId,
            ApiKeyAuthenticationToken.erased(),
            MockHttpServletResponse(),
        )

        assertEquals(ParticipationStatus.ERASED, status.participationStatus)
        verify(enrollmentManager, never()).getDeviceCollectionStatus(any(), any())
    }

    @Test
    fun erasureAwareStatusDistinguishesBothQuarantineAndTombstoneAuthentication() {
        whenever(enrollmentManager.getDeviceCollectionStatus(studyId, participantId))
            .thenReturn(MobileParticipationStatus(ParticipationStatus.ERASED, OffsetDateTime.now()))
        listOf(deviceToken(), ApiKeyAuthenticationToken.erased()).forEach { token ->
            assertEquals(ParticipationStatus.ERASED, controller.getParticipationStatus(
                studyId, participantId, token, MockHttpServletResponse()).participationStatus)
        }
    }

    private fun deviceToken(boundParticipantId: String = participantId) = ApiKeyAuthenticationToken(
        principal = "apikey:$keyId",
        keyId = keyId,
        studyId = studyId,
        participantId = boundParticipantId,
        randomDeviceId = UUID.randomUUID(),
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
