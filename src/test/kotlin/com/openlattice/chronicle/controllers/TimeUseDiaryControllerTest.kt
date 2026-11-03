package com.openlattice.chronicle.controllers

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.StatementHolder
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.converters.TimeUseDiaryDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.TimeUseDiaryPostgresDownloadWrapper
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import java.time.LocalDate
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryDownloadDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import com.geekbeast.controllers.exceptions.ForbiddenException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.*
import java.util.function.Supplier

class TimeUseDiaryControllerTest {
    @Test
    fun missingStudyIsNotFound() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        org.junit.Assert.assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) {
            controller.getTimeUseDiarySettings(UUID.randomUUID())
        }
    }

    // A leaked login makes later filter tests in the same JVM skip their authentication checks.
    @After
    fun clearSecurityContext() = TestSecurityUtils.clearSecurityContext()

    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val timeUseDiaryService = Mockito.mock(TimeUseDiaryService::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val participantFormSubmissionReceiptService = Mockito.mock(ParticipantFormSubmissionReceiptService::class.java)
    private val controller = TimeUseDiaryController(
        authorizationManager, auditingManager, storageResolver,
        idGenerationService, timeUseDiaryService, studyService, auditService, enrollmentManager,
        participantFormSubmissionReceiptService
    )

    init {
        Mockito.`when`(enrollmentManager.isKnownParticipant(kAny(), kAnyString())).thenReturn(true)
    }

    @Test
    fun datedCapabilityRejectsHistoricalDiaryReadsAndSubmissions() {
        val study = UUID.randomUUID()
        val scope = ParticipantFormAccessScope(UUID.randomUUID(), study, "dated-diary", ParticipantFormKind.TIME_USE_DIARY,
            null, LocalDate.parse("2026-10-17"), OffsetDateTime.now().plusHours(1))
        val start = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        Mockito.`when`(studyService.getStudyId(study)).thenReturn(study)
        TestSecurityUtils.setupSecurityContext()
        TestSecurityUtils.withParticipantFormScope(scope) {
            assertThrows(ForbiddenException::class.java) {
                controller.getParticipantTUDSubmissionIdsByDate(study, scope.participantId, start, start.plusHours(1))
            }
            assertThrows(ForbiddenException::class.java) {
                controller.submitTimeUseDiary(study, scope.participantId,
                    listOf(TimeUseDiaryResponse("activity", "Activity", setOf("read"), start, start.plusHours(1))))
            }
        }
        Mockito.verifyNoInteractions(timeUseDiaryService, participantFormSubmissionReceiptService)
    }

    

    @Test
    fun testGetTimeUseDiarySettingsProjectsConfiguredStudySettings() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(
            mapOf(
                StudySettingType.TimeUseDiary to TimeUseDiarySettings(
                    enableChangesForSherbrookeUniversity = true,
                    enableChangesForOhioStateUniversity = true,
                    language = "de",
                    clockFormat = 24,
                    clockFormatLocked = true,
                )
            )
        )

        // An OSU/Sherbrooke/24h-locked study must project those flags into the participant-readable
        // response, so the web diary renders the configured instrument.
        val response = controller.getTimeUseDiarySettings(studyId)

        assertTrue(response.enableChangesForOhioStateUniversity)
        assertTrue(response.enableChangesForSherbrookeUniversity)
        assertTrue(response.clockFormatLocked)
        assertEquals(24, response.clockFormat)
        assertEquals("de", response.language)
    }

    @Test
    fun testGetTimeUseDiarySettingsDefaultsWhenSettingAbsent() {
        TestSecurityUtils.setupAuthorizedResearcher(authorizationManager)
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        // A missing setting on an anonymous read must not throw — it degrades to the seeded defaults.
        val response = controller.getTimeUseDiarySettings(studyId)

        assertFalse(response.enableChangesForOhioStateUniversity)
        assertFalse(response.enableChangesForSherbrookeUniversity)
        assertFalse(response.clockFormatLocked)
        assertEquals(12, response.clockFormat)
        assertEquals("en", response.language)
    }

    @Test
    fun testGetStudyTUDSubmissionIdsByDateUsesIanaZone() {
        val studyId = UUID.randomUUID()
        val startDate = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val endDate = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        val zone = ZoneId.of("America/Santiago")
        Mockito.`when`(timeUseDiaryService.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate, zone))
            .thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate, zone.id)
        assertNotNull(result)
        verify(timeUseDiaryService).getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate, zone)
        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate, "Invalid/Zone")
        }
    }

    @Test
    fun testGetParticipantTUDSubmissionIdsByDateDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "p-1"
        val startDate = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val endDate = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(timeUseDiaryService.getParticipantTUDSubmissionsByDate(studyId, participantId, startDate, endDate))
            .thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getParticipantTUDSubmissionIdsByDate(studyId, participantId, startDate, endDate)
        assertNotNull(result)
        verify(timeUseDiaryService).getParticipantTUDSubmissionsByDate(studyId, participantId, startDate, endDate)
    }

    @Test
    fun testGetStudyTUDSubmissionIdsByDateReturnsEmptyForNoData() {
        val studyId = UUID.randomUUID()
        val startDate = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30)
        val endDate = OffsetDateTime.now(ZoneOffset.UTC)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(timeUseDiaryService.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate))
            .thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate)
        assertEquals(0, result.size)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    // ---------------------------------------------------------------------------
    // POST submit TUD — /time-use-diary/{studyId}/participant/{participantId}
    //
    // The controller wraps the submission in an AuditedTransactionBuilder, but the
    // transaction lambda calls the MOCKED timeUseDiaryService.submitTimeUseDiary, so
    // no real JDBC is reached: storageResolver -> mocked HikariDataSource -> mocked
    // Connection (autoCommit/commit are no-op mock calls). We pin that the responses
    // are forwarded verbatim under the resolved study id and that the controller
    // returns the service-generated submission id unchanged.
    // ---------------------------------------------------------------------------
    @Test
    fun testSubmitTimeUseDiaryDelegatesToServiceWithinTransaction() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val responses = listOf(Mockito.mock(TimeUseDiaryResponse::class.java))
        val expectedSubmissionId = UUID.randomUUID()

        val connection = Mockito.mock(Connection::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)

        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(
            timeUseDiaryService.submitTimeUseDiary(connection, studyId, participantId, responses)
        ).thenReturn(expectedSubmissionId)

        TestSecurityUtils.setupSecurityContext()
        // No participant capability session is present, so this is an authenticated caller and
        // must hold study WRITE before any PHI is written.
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)

        val result = controller.submitTimeUseDiary(studyId, participantId, responses)

        assertSame(expectedSubmissionId, result)
        verify(timeUseDiaryService).submitTimeUseDiary(connection, studyId, participantId, responses)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    @Test
    fun testSubmitTimeUseDiaryDeniesAnAuthenticatedCallerWithoutStudyWrite() {
        val studyId = UUID.randomUUID()
        val responses = listOf(Mockito.mock(TimeUseDiaryResponse::class.java))
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)

        assertThrows(ForbiddenException::class.java) {
            controller.submitTimeUseDiary(studyId, "participant-1", responses)
        }
        verify(timeUseDiaryService, never())
            .submitTimeUseDiary(kAny(), kAny(), kAnyString(), kAny())
    }

    // ---------------------------------------------------------------------------
    // GET study TUD submissions (export) — ACL-gated (ensureReadAccess). Pins the
    // authorization check is invoked and the service result is returned unchanged.
    // This is the interface overload (no HttpServletResponse) that the public
    // download endpoint funnels through.
    // ---------------------------------------------------------------------------
    @Test
    fun testGetStudyTUDSubmissionsExportIsAclGatedAndDelegates() {
        val studyId = UUID.randomUUID()
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
        val dataType = TimeUseDiaryDownloadDataType.DayTime
        val expected: Iterable<List<Map<String, Any>>> = listOf(listOf(mapOf("k" to "v")))

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(
            timeUseDiaryService.getStudyTUDSubmissions(studyId, null, dataType, start, end)
        ).thenReturn(expected)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyTUDSubmissions(studyId, dataType, start, end)

        assertSame(expected, result)
        verify(timeUseDiaryService).getStudyTUDSubmissions(studyId, null, dataType, start, end)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    // ---------------------------------------------------------------------------
    // GET participants TUD submissions (export) — ACL-gated. Pins delegation with
    // the explicit participant id set, and that the result is returned unchanged.
    // ---------------------------------------------------------------------------
    @Test
    fun testGetParticipantsTudSubmissionsIsAclGatedAndDelegates() {
        val studyId = UUID.randomUUID()
        val participantIds = setOf("p-1", "p-2")
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
        val dataType = TimeUseDiaryDownloadDataType.NightTime
        val expected: Iterable<List<Map<String, Any>>> = listOf(listOf(mapOf("k" to "v")))

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(
            timeUseDiaryService.getStudyTUDSubmissions(studyId, participantIds, dataType, start, end)
        ).thenReturn(expected)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getParticipantsTudSubmissions(studyId, participantIds, dataType, start, end)

        assertSame(expected, result)
        verify(timeUseDiaryService).getStudyTUDSubmissions(studyId, participantIds, dataType, start, end)
        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    // ---------------------------------------------------------------------------
    // GET participant TUD submission ids — ACL-gated via accessCheck(READ). Pins the
    // authorization check is actually invoked (the existing participant-ids test only
    // stubs it true without asserting it ran).
    // ---------------------------------------------------------------------------
    @Test
    fun testGetParticipantTUDSubmissionIdsIsAclGated() {
        val studyId = UUID.randomUUID()
        val participantId = "p-1"
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(
            timeUseDiaryService.getParticipantTUDSubmissionsByDate(studyId, participantId, start, end)
        ).thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        controller.getParticipantTUDSubmissionIdsByDate(studyId, participantId, start, end)

        verify(authorizationManager).checkIfHasPermissions(kAny(), kAny(), kAny())
    }

    // The download routes bind the API's query names into request classes, for GET and for the browser form POST.
    @Test
    fun downloadRoutesBindTheApiQueryNamesForGetAndFormPost() {
        val studyId = UUID.randomUUID()
        val start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val end = OffsetDateTime.of(2024, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
        val dataType = TimeUseDiaryDownloadDataType.NightTime
        val participantIds = setOf("p-1", "p-2")
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(timeUseDiaryService.getStudyTUDSubmissions(kAny(), Mockito.any(), kAny(), kAny(), kAny()))
            .thenAnswer { emptyDiaryDownload() }
        TestSecurityUtils.setupSecurityContext()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(TimeUseDiaryDownloadCsvHttpMessageConverter())
            .build()
        val base = TimeUseDiaryApi.CONTROLLER + "/" + studyId
        for (method in listOf(HttpMethod.GET, HttpMethod.POST)) {
            mvc.perform(MockMvcRequestBuilders.request(method, base + TimeUseDiaryApi.DATA_PATH)
                .param(TimeUseDiaryApi.DATA_TYPE, dataType.name)
                .param(TimeUseDiaryApi.START_DATE, start.toString())
                .param(TimeUseDiaryApi.END_DATE, end.toString()))
                .andExpect(MockMvcResultMatchers.status().isOk)
            mvc.perform(MockMvcRequestBuilders.request(method, base + TimeUseDiaryApi.PARTICIPANTS_PATH + TimeUseDiaryApi.DATA_PATH)
                .param(TimeUseDiaryApi.PARTICIPANT_ID, *participantIds.toTypedArray())
                .param(TimeUseDiaryApi.DATA_TYPE, dataType.name)
                .param(TimeUseDiaryApi.START_DATE, start.toString())
                .param(TimeUseDiaryApi.END_DATE, end.toString())
                .param(TimeUseDiaryApi.FILE_NAME, "diary"))
                .andExpect(MockMvcResultMatchers.status().isOk)
                .andExpect { assertTrue(it.response.getHeader("Content-Disposition").orEmpty().contains("diary")) }
        }
        verify(timeUseDiaryService, Mockito.times(2)).getStudyTUDSubmissions(studyId, null, dataType, start, end)
        verify(timeUseDiaryService, Mockito.times(2)).getStudyTUDSubmissions(studyId, participantIds, dataType, start, end)
    }

    private fun emptyDiaryDownload(): TimeUseDiaryPostgresDownloadWrapper {
        val rows = Mockito.mock(java.sql.ResultSet::class.java)
        return TimeUseDiaryPostgresDownloadWrapper(BasePostgresIterable(Supplier {
            StatementHolder(Mockito.mock(Connection::class.java), Mockito.mock(java.sql.Statement::class.java), rows)
        }) { emptyList<Map<String, Any>>() }).withColumnAdvice(listOf("diary_column"))
    }

    @Test
    fun failedDiaryResponseNeverReachesTheAuditRecord() {
        val studyId = UUID.randomUUID()
        val sentinel = "health-response-sentinel"
        val (audit, recorded) = com.openlattice.chronicle.audit.recordingAuditService()
        try {
            val connection = Mockito.mock(Connection::class.java)
            val source = Mockito.mock(HikariDataSource::class.java)
            Mockito.`when`(source.connection).thenReturn(connection)
            Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(source)
            Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
            Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
            Mockito.`when`(timeUseDiaryService.submitTimeUseDiary(kAny(), kAny(), kAnyString(), kAny()))
                .thenAnswer { throw java.sql.SQLException("JSON data, line 1: answer $sentinel before invalid NUL", "22P05") }
            TestSecurityUtils.setupSecurityContext()
            val controller = TimeUseDiaryController(authorizationManager, auditingManager, storageResolver,
                idGenerationService, timeUseDiaryService, studyService, audit, enrollmentManager, participantFormSubmissionReceiptService)
            assertThrows(java.sql.SQLException::class.java) { controller.submitTimeUseDiary(studyId, "participant-1", emptyList()) }
            // The database row and the SIEM line are both written from this one event.
            val event = recorded.events.single()
            org.junit.Assert.assertEquals(false, event.data["success"])
            org.junit.Assert.assertFalse(event.toString().contains(sentinel))
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }

}
