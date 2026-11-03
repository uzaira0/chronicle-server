package com.openlattice.chronicle.controllers

import java.time.LocalDate
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.study.AppUsageSurveySetting
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.services.surveys.AppUsageDayRules
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import org.mockito.kotlin.argumentCaptor
import java.time.ZoneId
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.SurveySettings
import kotlin.reflect.full.memberProperties
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.geekbeast.controllers.exceptions.ForbiddenException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.After
import com.openlattice.chronicle.data.FileType
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class SurveyControllerTest {
    @Test
    fun replacingFilteredAppsRequiresARevisionBeforeWriting() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes()
        val refused = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.setAppsFilteredForStudyAppUsageSurvey(UUID.randomUUID(), setOf("org.example"))
        }
        assertEquals(428, refused.statusCode.value())
        Mockito.verifyNoInteractions(surveysService)
    }
    @Test
    fun missingStudyIsNotFound() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) {
            controller.getStudyQuestionnaires(UUID.randomUUID())
        }
    }

    @Test
    fun omittedUsageThresholdsReadTheirOwnSettings() {
        val studyId = UUID.randomUUID()
        val participantId = "threshold-participant"
        val start = OffsetDateTime.parse("2026-10-01T00:00:00Z")
        val end = start.plusDays(1)
        val setting = SurveySettings(appUsageThresholdInSeconds = 180, deviceUsageThresholdInSeconds = 900)
        val usage = mapOf("short" to 60.0, "medium" to 600.0, "long" to 1200.0)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(mapOf(StudySettingType.Survey to setting))
        Mockito.`when`(surveysService.getDeviceUsageData(studyId, participantId, start, end))
            .thenReturn(DeviceUsage(usage.values.sum(), usage, usage.mapValues { "tools" }))
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(surveysService.getAppUsageDays(kAny(), kAnyString(), kAny()))
            .thenReturn(AppUsageSurveyDays("UTC", LocalDate.of(2026, 10, 2), emptyList()))
        TestSecurityUtils.setupSecurityContext()

        val devicePackages = controller.getDeviceUsageSurveyData(studyId, participantId, start, end, null).usageByPackage.keys
        controller.getAppUsageSurveyDays(studyId, participantId)
        val rules = argumentCaptor<AppUsageDayRules>()
        verify(surveysService).getAppUsageDays(kAny(), kAnyString(), rules.capture())

        assertEquals(setOf("deviceUsageThresholdInSeconds", "appUsageThresholdInSeconds"),
            SurveySettings::class.memberProperties.filter { it.name.endsWith("ThresholdInSeconds") }.map { it.name }.toSet())
        assertEquals(usage.filterValues { it > setting.deviceUsageThresholdInSeconds }.keys, devicePackages)
        assertEquals(setting.appUsageThresholdInSeconds, rules.firstValue.thresholdSeconds)
    }

    /**
     * The researcher's "hide these apps from the daily survey" list: only a study writer may change it, every
     * change (and every failed change) leaves a SETTINGS_CHANGE row with the number of apps and the operation.
     */
    @Test
    fun surveyAppFilterChangesNeedWriteAccessAndAreAuditedWhetherTheyStoreOrFail() {
        val studyId = UUID.randomUUID()
        val audit = com.openlattice.chronicle.audit.RecordingAuditingManager()
        val audited = SurveyController(surveysService, studyService, downloadService, idGenerationService,
            authorizationManager, auditingManager, AuditService(audit), enrollmentManager, participantFormSubmissionReceiptService)
        val apps = setOf("com.android.systemui", "com.whatsapp")
        val writes = listOf<Pair<String?, () -> Any>>(
            null to { audited.setAppsFilteredForStudyAppUsageSurvey(studyId, apps, "\"0\"") },
            "filter" to { audited.filterAppForStudyAppUsageSurvey(studyId, apps) },
            "allow" to { audited.allowAppForStudyAppUsageSurvey(studyId, apps) },
        )
        TestSecurityUtils.setupSecurityContext(admin = false)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
        writes.forEach { (_, write) -> assertThrows(ForbiddenException::class.java) { write() } }
        Mockito.verifyNoInteractions(surveysService)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        for ((operation, write) in writes) {
            audit.observations.clear()
            write()
            val event = audit.events.single()
            assertEquals(com.openlattice.chronicle.auditing.AuditEventType.SETTINGS_CHANGE, event.eventType)
            assertEquals(studyId, event.study)
            assertEquals(true, event.data["success"])
            assertEquals(2, event.data["appCount"])
            assertEquals(operation, event.data["operation"])
        }
        verify(surveysService).setAppsFilteredForStudyAppUsageSurvey(studyId, apps, 0L)
        verify(surveysService).filterAppForStudyAppUsageSurvey(studyId, apps)
        verify(surveysService).allowAppForStudyAppUsageSurvey(studyId, apps)

        audit.observations.clear()
        Mockito.doThrow(IllegalStateException("db down")).`when`(surveysService).filterAppForStudyAppUsageSurvey(studyId, apps)
        assertThrows(IllegalStateException::class.java) { audited.filterAppForStudyAppUsageSurvey(studyId, apps) }
        assertEquals(false, audit.events.single().data["success"])
    }

    /** A participant the survey no longer admits (withdrawn, declined, single-user study) gets a refusal, not an empty list. */
    @Test
    fun surveyDaysForAParticipantTheSurveyNoLongerAdmitsAreRefusedAndNotAudited() {
        val studyId = UUID.randomUUID()
        val audit = com.openlattice.chronicle.audit.RecordingAuditingManager()
        val audited = SurveyController(surveysService, studyService, downloadService, idGenerationService,
            authorizationManager, auditingManager, AuditService(audit), enrollmentManager, participantFormSubmissionReceiptService)
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownParticipant(studyId, "withdrawn-participant")).thenReturn(true)
        Mockito.`when`(surveysService.getAppUsageDays(kAny(), kAnyString(), kAny())).thenReturn(null)

        val refused = assertThrows(com.openlattice.chronicle.exceptions.CollectionUnavailableException::class.java) {
            audited.getAppUsageSurveyDays(studyId, "withdrawn-participant")
        }

        assertEquals(com.openlattice.chronicle.exceptions.CollectionUnavailableException.Scope.PARTICIPANT, refused.scope)
        assertEquals(emptyList<Any>(), audit.events)
    }

    @Test
    fun surveyForAParticipantNeverRegisteredInTheStudyIsNotFound() {
        val studyId = UUID.randomUUID()
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownParticipant(studyId, "typo-participant")).thenReturn(false)

        val missing = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getAppUsageSurveyDays(studyId, "typo-participant")
        }

        assertEquals(404, missing.statusCode.value())
        assertEquals(com.openlattice.chronicle.i18n.Messages.get("error.enrollment.participantNotRegistered"), missing.reason)
        Mockito.verifyNoInteractions(surveysService)
    }

    @After
    fun clearSecurityContext() = TestSecurityUtils.clearSecurityContext()


    private val surveysService = Mockito.mock(SurveysService::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val downloadService = Mockito.mock(DataDownloadService::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val participantFormSubmissionReceiptService = Mockito.mock(ParticipantFormSubmissionReceiptService::class.java)
    private val controller = SurveyController(
        surveysService, studyService, downloadService,
        idGenerationService, authorizationManager, auditingManager, auditService, enrollmentManager,
        participantFormSubmissionReceiptService
    )

    private val day = LocalDate.parse("2026-10-17")
    private val answers = listOf(AppUsageAnswer("pkg.a", OffsetDateTime.parse("2026-10-17T00:00:00-03:00"), listOf("Child")))

    init {
        Mockito.`when`(enrollmentManager.isKnownParticipant(kAny(), kAnyString())).thenReturn(true)
    }

    @Test
    fun aSurveySessionReachesOnlyItsOwnParticipant() {
        val study = UUID.randomUUID()
        val scope = ParticipantFormAccessScope(UUID.randomUUID(), study, "own-participant", ParticipantFormKind.APP_USAGE,
            null, null, OffsetDateTime.now().plusHours(1))
        Mockito.`when`(studyService.getStudyId(study)).thenReturn(study)
        TestSecurityUtils.setupSecurityContext()
        TestSecurityUtils.withParticipantFormScope(scope) {
            listOf<() -> Any>(
                { controller.getAppUsageSurveyDays(study, "other-participant") },
                { controller.getAppUsageSurveyData(study, "other-participant", day) },
                { controller.submitAppUsageSurvey(study, "other-participant", day, answers) },
            ).forEach { call -> assertThrows(ForbiddenException::class.java) { call() } }
        }
        Mockito.verifyNoInteractions(participantFormSubmissionReceiptService, surveysService)
    }

    @Test
    fun testGetAppUsageFrequencyDefaultsToDailyWhenAbsentOrModular() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        // No DataCollection setting (or a non-ChronicleDataCollectionSettings shape) → default DAILY.
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        val response = controller.getAppUsageFrequency(studyId)

        assertEquals(AppUsageFrequency.DAILY, response.appUsageFrequency)
    }

    @Test
    fun appUsageSurveyFrequencyUsesItsOwnStudySetting() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        val id = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(id)).thenReturn(id)
        Mockito.`when`(studyService.getStudySettings(id)).thenReturn(mapOf(
            StudySettingType.AppUsageSurvey to AppUsageSurveySetting(AppUsageFrequency.HOURLY),
        ))
        assertEquals(AppUsageFrequency.HOURLY, controller.getAppUsageFrequency(id).appUsageFrequency)
    }

    @Test
    fun testGetStudyQuestionnairesDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(surveysService.getStudyQuestionnaires(studyId)).thenReturn(emptyList())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyQuestionnaires(studyId)
        assertNotNull(result)
        verify(surveysService).getStudyQuestionnaires(studyId)
    }

    @Test
    fun testGetQuestionnaireDelegatesToService() {
        val studyId = UUID.randomUUID()
        val questionnaireId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val questionnaire = Mockito.mock(Questionnaire::class.java)
        Mockito.`when`(surveysService.getQuestionnaire(studyId, questionnaireId)).thenReturn(questionnaire)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getQuestionnaire(studyId, questionnaireId)
        assertNotNull(result)
        verify(surveysService).getQuestionnaire(studyId, questionnaireId)
    }

    @Test
    fun testGetAppsFilteredForStudyDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(surveysService.getFilteredAppsSnapshot(studyId)).thenReturn(
            com.openlattice.chronicle.services.surveys.FilteredAppsSnapshot(emptyList(), 0L),
        )

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getAppsFilteredForStudyAppUsageSurvey(studyId)
        assertNotNull(result)
    }

    @Test
    fun researcherReadsADayUnderTheStudyZoneAndThreshold() {
        val studyId = UUID.randomUUID()
        val expected = AppUsageDaySurvey(day, "America/Santiago", AppUsageDayState.UNANSWERED, emptyList())
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(mapOf(
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(timeZone = "America/Santiago"),
            StudySettingType.Survey to SurveySettings(appUsageThresholdInSeconds = 60),
            StudySettingType.AppUsageSurvey to AppUsageSurveySetting(AppUsageFrequency.HOURLY),
        ))
        val rules = AppUsageDayRules(ZoneId.of("America/Santiago"), 60, AppUsageFrequency.HOURLY)
        Mockito.`when`(surveysService.getAppUsageDay(studyId, "participant-1", day, rules)).thenReturn(expected)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        TestSecurityUtils.setupSecurityContext()

        assertSame(expected, controller.getAppUsageSurveyData(studyId, "participant-1", day))
    }

    // ---------------------------------------------------------------------------
    // GET device-usage survey data — /survey/{studyId}/participant/{participantId}/device
    //
    // Researchers need study READ. The controller keeps only packages
    // whose usage exceeds the threshold (it removes packages with usage <= threshold).
    // ---------------------------------------------------------------------------
    @Test
    fun testGetDeviceUsageSurveyDataDelegatesAndAppliesThresholdFilter() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)

        val deviceUsage = DeviceUsage(
            totalTime = 105.0,
            usageByPackage = mapOf("keep.me" to 100.0, "drop.me" to 5.0),
            categoryByPackage = mapOf("keep.me" to "social", "drop.me" to "tools"),
        )

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(surveysService.getDeviceUsageData(studyId, participantId, start, end))
            .thenReturn(deviceUsage)

        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)

        val result = controller.getDeviceUsageSurveyData(studyId, participantId, start, end, 10)

        verify(surveysService).getDeviceUsageData(studyId, participantId, start, end)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
        // only the above-threshold package survives
        assertEquals(setOf("keep.me"), result.usageByPackage.keys)
        assertEquals(setOf("keep.me"), result.categoryByPackage.keys)
        assertEquals(100.0, result.usageByPackage["keep.me"]!!, 0.0)
    }

    // ---------------------------------------------------------------------------
    // POST app-usage survey. A participant reaches this through a capability session
    // (ParticipantFormAccessFilter scope); an authenticated caller with no scope must hold
    // study WRITE, because authentication alone is not authorization to write another
    // study's PHI. Pins that the exact response list is forwarded under the resolved
    // real study id once write access is granted.
    // ---------------------------------------------------------------------------
    @Test
    fun testSubmitAppUsageSurveyDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)

        controller.submitAppUsageSurvey(studyId, "participant-1", day, answers)

        verify(surveysService).submitAppUsageDay(studyId, "participant-1", day, answers,
            AppUsageDayRules(ZoneId.of("UTC"), SurveySettings().appUsageThresholdInSeconds, AppUsageFrequency.DAILY))
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    @Test
    fun testSubmitAppUsageSurveyDeniesAnAuthenticatedCallerWithoutStudyWrite() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)

        // A researcher holding only READ must not post responses for a known participant.
        assertThrows(ForbiddenException::class.java) {
            controller.submitAppUsageSurvey(studyId, "participant-1", day, answers)
        }
        verify(surveysService, never()).submitAppUsageDay(kAny(), kAnyString(), kAny(), kAny(), kAny())
    }

    // ---------------------------------------------------------------------------
    // POST questionnaire responses. Same rule as the app-usage submission: a capability
    // session or, for an authenticated caller, study WRITE. Pins that the response list is
    // forwarded with the resolved study id and questionnaire id and that the controller
    // returns the service-backed OK.
    // ---------------------------------------------------------------------------
    @Test
    fun testSubmitQuestionnaireResponsesDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val questionnaireId = UUID.randomUUID()
        val responses = listOf(Mockito.mock(QuestionnaireResponse::class.java))

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)

        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)

        val result = controller.submitQuestionnaireResponses(studyId, participantId, questionnaireId, responses)

        assertNotNull(result)
        verify(surveysService).submitQuestionnaireResponses(studyId, participantId, questionnaireId, responses)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    @Test
    fun testSubmitQuestionnaireResponsesDeniesAnAuthenticatedCallerWithoutStudyWrite() {
        val studyId = UUID.randomUUID()
        val questionnaireId = UUID.randomUUID()
        val responses = listOf(Mockito.mock(QuestionnaireResponse::class.java))
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)

        assertThrows(ForbiddenException::class.java) {
            controller.submitQuestionnaireResponses(studyId, "participant-1", questionnaireId, responses)
        }
        verify(surveysService, never())
            .submitQuestionnaireResponses(kAny(), kAnyString(), kAny(), kAny())
    }

    // ---------------------------------------------------------------------------
    // GET questionnaire responses download — ACL-gated (ensureReadAccess). Pins the
    // authorization check is invoked and the download service result is returned
    // unchanged.
    // ---------------------------------------------------------------------------
    @Test
    fun testGetQuestionnaireResponsesDownloadIsAclGatedAndDelegates() {
        val studyId = UUID.randomUUID()
        val questionnaireId = UUID.randomUUID()
        val expected: Iterable<Map<String, Any>> = listOf(mapOf("q" to "a"))

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(downloadService.getQuestionnaireResponses(studyId, questionnaireId)).thenReturn(expected)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getQuestionnaireResponses(
            studyId, questionnaireId, FileType.csv
        )

        assertSame(expected, result)
        verify(downloadService).getQuestionnaireResponses(studyId, questionnaireId)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    @Test
    fun testUsageReadWithoutCapabilityRequiresStudyRead() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        TestSecurityUtils.setupSecurityContext()
        assertThrows(ForbiddenException::class.java) { controller.getAppUsageSurveyData(studyId, "participant-1", day) }
        assertThrows(ForbiddenException::class.java) { controller.getAppUsageSurveyDays(studyId, "participant-1") }
        Mockito.verifyNoInteractions(surveysService)
    }

}
