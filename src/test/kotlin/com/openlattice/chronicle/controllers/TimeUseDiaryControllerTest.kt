package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryDownloadDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class TimeUseDiaryControllerTest {

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
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testGetTimeUseDiarySettingsProjectsConfiguredStudySettings() {
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
        // response, so the web diary renders the configured instrument (R2 backwards-parity).
        val response = controller.getTimeUseDiarySettings(studyId)

        assertTrue(response.enableChangesForOhioStateUniversity)
        assertTrue(response.enableChangesForSherbrookeUniversity)
        assertTrue(response.clockFormatLocked)
        assertEquals(24, response.clockFormat)
        assertEquals("de", response.language)
    }

    @Test
    fun testGetTimeUseDiarySettingsDefaultsWhenSettingAbsent() {
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
    fun testGetStudyTUDSubmissionIdsByDateDelegatesToService() {
        val studyId = UUID.randomUUID()
        val startDate = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val endDate = OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(timeUseDiaryService.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate))
            .thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate)
        assertNotNull(result)
        verify(timeUseDiaryService).getStudyTUDSubmissionIdsByDate(studyId, startDate, endDate)
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

        val result = controller.submitTimeUseDiary(studyId, participantId, responses)

        assertSame(expectedSubmissionId, result)
        verify(timeUseDiaryService).submitTimeUseDiary(connection, studyId, participantId, responses)
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

}
