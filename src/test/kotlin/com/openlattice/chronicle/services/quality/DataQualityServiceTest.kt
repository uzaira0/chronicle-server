package com.openlattice.chronicle.services.quality

import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.util.*

class DataQualityServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var studyService: StudyService
    private lateinit var service: DataQualityService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        studyService = Mockito.mock(StudyService::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service = DataQualityService(storageResolver, studyService)
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- getDataQualityDashboard tests ---

    @Test
    fun testGetDataQualityDashboardNoParticipants() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(emptyMap())
        `when`(mockRs.next()).thenReturn(false) // no alerts

        val dashboard = service.getDataQualityDashboard(studyId)

        assertEquals(studyId, dashboard.studyId)
        assertEquals(0, dashboard.totalParticipants)
        assertEquals(0, dashboard.activeParticipants)
        assertEquals(0, dashboard.belowThreshold)
        assertEquals(0.0, dashboard.overallCompleteness, 0.001)
        assertTrue(dashboard.participantScores.isEmpty())
    }

    @Test
    fun testGetDataQualityDashboardWithActiveParticipant() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        val today = LocalDate.now()
        val stats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            androidUniqueDates = (0..13).map { today.minusDays(it.toLong()) }.toSet()
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(mapOf("p1" to stats))
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        assertEquals(1, dashboard.totalParticipants)
        assertTrue(dashboard.activeParticipants > 0)
        assertTrue(dashboard.overallCompleteness > 0)
    }

    @Test
    fun testGetDataQualityDashboardWithMultipleParticipants() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        val today = LocalDate.now()
        val activeStats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            androidUniqueDates = (0..13).map { today.minusDays(it.toLong()) }.toSet()
        )
        val inactiveStats = ParticipantStats(
            studyId = studyId,
            participantId = "p2",
            androidUniqueDates = emptySet()
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(
            mapOf("p1" to activeStats, "p2" to inactiveStats)
        )
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        assertEquals(2, dashboard.totalParticipants)
        assertEquals(2, dashboard.participantScores.size)
    }

    @Test
    fun testGetDataQualityDashboardWithLowQualityParticipant() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        val today = LocalDate.now()
        // Only 2 days of data out of expected 10 — should be below threshold
        val stats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            androidUniqueDates = setOf(today, today.minusDays(1))
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(mapOf("p1" to stats))
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        assertTrue("Expected at least one participant below threshold", dashboard.belowThreshold > 0)
    }

    @Test
    fun testGetDataQualityDashboardUsesDefaultConfig() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId, settings = null)

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(emptyMap())
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        // Default config: 5 days/week, 50% threshold, 14-day window
        assertEquals(5, dashboard.config.expectedDaysPerWeek)
        assertEquals(50, dashboard.config.alertThresholdPercent)
        assertEquals(14, dashboard.config.evaluationWindowDays)
    }

    // --- generateAlerts tests ---

    @Test
    fun testGenerateAlertsWithNoParticipants() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(emptyMap())

        service.generateAlerts(studyId)

        // Verify that study data was fetched even with no participants
        verify(studyService).getStudyParticipantStats(studyId)
    }

    @Test
    fun testGenerateAlertsCreatesAlertForLowQuality() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)

        val today = LocalDate.now()
        val stats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            androidUniqueDates = setOf(today.minusDays(10)) // only 1 day in 14-day window
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(mapOf("p1" to stats))
        `when`(mockPs.executeBatch()).thenReturn(intArrayOf(1))

        service.generateAlerts(studyId)

        // Verify batch was called
        verify(mockPs).executeBatch()
    }

    // --- cleanupOldAlerts tests ---

    @Test
    fun testCleanupOldAlertsExecutesDelete() {
        `when`(mockPs.executeUpdate()).thenReturn(5)

        service.cleanupOldAlerts()

        verify(mockPs).executeUpdate()
    }

    @Test
    fun testCleanupOldAlertsNoDeletesNeeded() {
        `when`(mockPs.executeUpdate()).thenReturn(0)

        service.cleanupOldAlerts()

        verify(mockPs).executeUpdate()
    }

    // --- Quality score calculation tests ---

    @Test
    fun testDashboardScoreWithIosData() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)
        val today = LocalDate.now()

        val stats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            iosUniqueDates = (0..6).map { today.minusDays(it.toLong()) }.toSet()
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(mapOf("p1" to stats))
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        val score = dashboard.participantScores.first()
        assertTrue(score.iosScore > 0)
        assertTrue(score.iosDaysInWindow > 0)
    }

    @Test
    fun testDashboardScoreWithTudData() {
        val studyId = UUID.randomUUID()
        val study = createStudy(studyId)
        val today = LocalDate.now()

        val stats = ParticipantStats(
            studyId = studyId,
            participantId = "p1",
            tudUniqueDates = (0..4).map { today.minusDays(it.toLong()) }.toSet()
        )

        `when`(studyService.getStudy(studyId)).thenReturn(study)
        `when`(studyService.getStudyParticipantStats(studyId)).thenReturn(mapOf("p1" to stats))
        `when`(mockRs.next()).thenReturn(false)

        val dashboard = service.getDataQualityDashboard(studyId)

        val score = dashboard.participantScores.first()
        assertTrue(score.tudScore > 0)
        assertTrue(score.tudDaysInWindow > 0)
    }

    private fun createStudy(studyId: UUID, settings: StudySettings? = null): Study {
        return Study(
            studyId = studyId,
            title = "Test Study",
            contact = "test@test.com",
            settings = settings ?: Study.initialSettings("Test Study")
        )
    }
}
