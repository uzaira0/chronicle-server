package com.openlattice.chronicle.services.dashboard

import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

class DashboardServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var service: DashboardService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service = DashboardService(storageResolver)
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- getStats tests ---

    @Test
    fun testGetStatsReturnsDefaultsWhenNoData() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        val result = service.getStats(studyId)

        assertEquals(studyId, result.studyId)
        assertEquals(0, result.activeParticipants24h)
        assertEquals(0L, result.dataSubmissions24h)
        assertEquals(0, result.totalParticipants)
        assertNull(result.lastDataReceived)
        assertTrue(result.submissionsByType.isEmpty())
    }

    @Test
    fun testGetStatsReturnsDataFromDb() {
        val studyId = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getInt("active_participants_24h")).thenReturn(5)
        `when`(mockRs.getLong("data_submissions_24h")).thenReturn(100L)
        `when`(mockRs.getInt("total_participants")).thenReturn(20)
        `when`(mockRs.getObject("last_data_received", OffsetDateTime::class.java)).thenReturn(now)
        `when`(mockRs.getString("submissions_by_type")).thenReturn("""{"usage_events": 50, "sensor_data": 50}""")
        `when`(mockRs.getObject("updated_at", OffsetDateTime::class.java)).thenReturn(now)

        val result = service.getStats(studyId)

        assertEquals(studyId, result.studyId)
        assertEquals(5, result.activeParticipants24h)
        assertEquals(100L, result.dataSubmissions24h)
        assertEquals(20, result.totalParticipants)
    }

    @Test
    fun testGetStatsHandlesInvalidSubmissionsByTypeJson() {
        val studyId = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(studyId)
        `when`(mockRs.getInt("active_participants_24h")).thenReturn(0)
        `when`(mockRs.getLong("data_submissions_24h")).thenReturn(0L)
        `when`(mockRs.getInt("total_participants")).thenReturn(0)
        `when`(mockRs.getObject("last_data_received", OffsetDateTime::class.java)).thenReturn(null)
        `when`(mockRs.getString("submissions_by_type")).thenReturn("invalid-json")
        `when`(mockRs.getObject("updated_at", OffsetDateTime::class.java)).thenReturn(now)

        val result = service.getStats(studyId)

        assertTrue(result.submissionsByType.isEmpty())
    }

    @Test
    fun testGetStatsSetsCorrectParameter() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.getStats(studyId)

        verify(mockPs).setObject(1, studyId)
    }

    // --- getRecentEvents tests ---

    @Test
    fun testGetRecentEventsReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.getRecentEvents(UUID.randomUUID(), 50, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun testGetRecentEventsUsesDefaultSinceDate() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.getRecentEvents(studyId, 50, null)

        verify(mockPs).setObject(1, studyId)
        // Second param is the since date (defaults to 24h ago)
        verify(mockPs).setObject(kEq(2), Mockito.any(OffsetDateTime::class.java))
    }

    @Test
    fun testGetRecentEventsUsesProvidedSinceDate() {
        val studyId = UUID.randomUUID()
        val since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        `when`(mockRs.next()).thenReturn(false)

        service.getRecentEvents(studyId, 25, since)

        verify(mockPs).setObject(2, since)
    }

    @Test
    fun testGetRecentEventsCoercesLimitToMax1000() {
        `when`(mockRs.next()).thenReturn(false)

        service.getRecentEvents(UUID.randomUUID(), 5000, null)

        verify(mockPs).setInt(3, 1000)
    }

    @Test
    fun testGetRecentEventsCoercesLimitToMin1() {
        `when`(mockRs.next()).thenReturn(false)

        service.getRecentEvents(UUID.randomUUID(), 0, null)

        verify(mockPs).setInt(3, 1)
    }

    @Test
    fun testGetRecentEventsNormalLimit() {
        `when`(mockRs.next()).thenReturn(false)

        service.getRecentEvents(UUID.randomUUID(), 50, null)

        verify(mockPs).setInt(3, 50)
    }

    // --- publishEvent tests ---

    @Test
    fun testPublishEventDoesNotThrow() {
        service.publishEvent(UUID.randomUUID(), "enrollment", "p1", mapOf("action" to "enroll"))

        verify(mockPs).executeUpdate()
    }

    @Test
    fun testPublishEventHandlesException() {
        `when`(mockPs.executeUpdate()).thenThrow(RuntimeException("DB error"))

        // Should not throw — catches exception internally
        service.publishEvent(UUID.randomUUID(), "enrollment", "p1", mapOf("action" to "enroll"))
    }

    @Test
    fun testPublishEventWithNullParticipant() {
        service.publishEvent(UUID.randomUUID(), "study_updated", null, emptyMap())

        verify(mockPs).setString(4, null)
    }

    // --- refreshStats tests ---

    @Test
    fun testRefreshStatsDoesNotThrow() {
        service.refreshStats(UUID.randomUUID())

        verify(mockPs).executeUpdate()
    }

    @Test
    fun testRefreshStatsHandlesException() {
        `when`(mockPs.executeUpdate()).thenThrow(RuntimeException("DB error"))

        service.refreshStats(UUID.randomUUID())
        // No exception
    }

    @Test
    fun testRefreshStatsSetsStudyIdFiveTimes() {
        val studyId = UUID.randomUUID()

        service.refreshStats(studyId)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setObject(2, studyId)
        verify(mockPs).setObject(3, studyId)
        verify(mockPs).setObject(4, studyId)
        verify(mockPs).setObject(5, studyId)
    }
}
