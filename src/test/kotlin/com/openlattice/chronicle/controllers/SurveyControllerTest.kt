package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.survey.AppUsage
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class SurveyControllerTest {

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

    init {
        Mockito.`when`(enrollmentManager.isKnownParticipant(kAny(), kAnyString())).thenReturn(true)
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testGetAppUsageFrequencyReadsHourlyFromDataCollectionSettings() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(
            mapOf(StudySettingType.DataCollection to ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY))
        )

        // A HOURLY-configured study must report HOURLY so the web survey renders the hourly
        // variant rather than always the daily one (R1 backwards-parity). The reader must read it
        // off ChronicleDataCollectionSettings (the type the PUT writer persists).
        val response = controller.getAppUsageFrequency(studyId)

        assertEquals(AppUsageFrequency.HOURLY, response.appUsageFrequency)
    }

    @Test
    fun testGetAppUsageFrequencyDefaultsToDailyWhenAbsentOrModular() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        // No DataCollection setting (or a non-ChronicleDataCollectionSettings shape) → default DAILY.
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        val response = controller.getAppUsageFrequency(studyId)

        assertEquals(AppUsageFrequency.DAILY, response.appUsageFrequency)
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
        Mockito.`when`(surveysService.getAppsFilteredForStudyAppUsageSurvey(studyId)).thenReturn(emptyList())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getAppsFilteredForStudyAppUsageSurvey(studyId)
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // GET app-usage survey data — /survey/{studyId}/participant/{participantId}/app-usage
    //
    // Not ACL-gated: this endpoint is participant-facing (the participant reviews
    // their own usage), so it never calls authorizationManager.checkIfHasPermissions.
    // The controller does NOT return the service result unchanged — it filters out
    // packages whose aggregate usage is at/below the threshold. We pin both the
    // delegation (getAndroidAppUsageData + computeAggregateUsage) and the actual
    // threshold-filtered outcome, passing an explicit threshold so getStudySettings
    // is never reached.
    // ---------------------------------------------------------------------------
    @Test
    fun testGetAppUsageSurveyDataDelegatesAndAppliesThresholdFilter() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)

        val keep = AppUsage("keep.me", "Keep", start, 1, listOf(), "UTC", Optional.empty())
        val drop = AppUsage("drop.me", "Drop", start, 1, listOf(), "UTC", Optional.empty())
        val appUsageData = listOf(keep, drop)

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(surveysService.getAndroidAppUsageData(studyId, participantId, start, end))
            .thenReturn(appUsageData)
        // keep.me is above threshold (100 > 10); drop.me is below (5 <= 10) and must be filtered out.
        Mockito.`when`(surveysService.computeAggregateUsage(start, appUsageData))
            .thenReturn(mapOf("keep.me" to 100.0, "drop.me" to 5.0))

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getAppUsageSurveyData(studyId, participantId, start, end, 10)

        // delegation
        verify(surveysService).getAndroidAppUsageData(studyId, participantId, start, end)
        verify(surveysService).computeAggregateUsage(start, appUsageData)
        // not acl-gated
        verify(authorizationManager, never()).checkIfHasPermissions(kAny(), kAny(), kAny())
        // threshold-filtered outcome: only the above-threshold package survives
        assertEquals(1, result.size)
        assertTrue(result.contains(keep))
        assertFalse(result.contains(drop))
    }

    // ---------------------------------------------------------------------------
    // GET device-usage survey data — /survey/{studyId}/participant/{participantId}/device
    //
    // Also participant-facing (not ACL-gated). The controller keeps only packages
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

        val result = controller.getDeviceUsageSurveyData(studyId, participantId, start, end, 10)

        verify(surveysService).getDeviceUsageData(studyId, participantId, start, end)
        verify(authorizationManager, never()).checkIfHasPermissions(kAny(), kAny(), kAny())
        // only the above-threshold package survives
        assertEquals(setOf("keep.me"), result.usageByPackage.keys)
        assertEquals(setOf("keep.me"), result.categoryByPackage.keys)
        assertEquals(100.0, result.usageByPackage["keep.me"]!!, 0.0)
    }

    // ---------------------------------------------------------------------------
    // POST app-usage survey — participant submission. Not ACL-gated. Pins that the
    // exact response list is forwarded to surveysService.submitAppUsageSurvey under
    // the resolved real study id.
    // ---------------------------------------------------------------------------
    @Test
    fun testSubmitAppUsageSurveyDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val responses = listOf(
            AppUsage(
                "pkg.a", "A",
                OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                1, listOf(), "UTC", Optional.empty()
            )
        )

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)

        TestSecurityUtils.setupSecurityContext()

        controller.submitAppUsageSurvey(studyId, participantId, responses)

        verify(surveysService).submitAppUsageSurvey(studyId, participantId, responses)
        verify(authorizationManager, never()).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    // ---------------------------------------------------------------------------
    // POST questionnaire responses — participant submission, not ACL-gated. Pins
    // that the response list is forwarded with the resolved study id and the
    // questionnaire id, and that the controller returns the service-backed OK.
    // ---------------------------------------------------------------------------
    @Test
    fun testSubmitQuestionnaireResponsesDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val questionnaireId = UUID.randomUUID()
        val responses = listOf(Mockito.mock(QuestionnaireResponse::class.java))

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.submitQuestionnaireResponses(studyId, participantId, questionnaireId, responses)

        assertNotNull(result)
        verify(surveysService).submitQuestionnaireResponses(studyId, participantId, questionnaireId, responses)
        verify(authorizationManager, never()).checkIfHasPermissions(kAny(), kAny(), kAny())
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
            studyId, questionnaireId, com.openlattice.chronicle.data.FileType.csv
        )

        assertSame(expected, result)
        verify(downloadService).getQuestionnaireResponses(studyId, questionnaireId)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

}
