package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.controllers.kAnyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.security.access.AccessDeniedException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.*

class EnrollmentServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var idGenerationService: HazelcastIdGenerationService
    private lateinit var candidateManager: CandidateManager
    private lateinit var service: EnrollmentService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        candidateManager = Mockito.mock(CandidateManager::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)

        service = EnrollmentService(storageResolver, idGenerationService, candidateManager)
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- registerParticipant tests ---

    @Test
    fun testRegisterParticipantExecutesInsert() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val candidateId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.registerParticipant(
            mockConnection, studyId, participantId, candidateId, ParticipationStatus.ENROLLED
        )

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setString(2, participantId)
        verify(mockPs).setObject(3, candidateId)
        verify(mockPs).setString(4, ParticipationStatus.ENROLLED.name)
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testRegisterParticipantWithUnknownStatus() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-2"
        val candidateId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.registerParticipant(
            mockConnection, studyId, participantId, candidateId, ParticipationStatus.UNKNOWN
        )

        verify(mockPs).setString(4, ParticipationStatus.UNKNOWN.name)
    }

    @Test
    fun testRegisterParticipantWithNotEnrolledStatus() {
        val studyId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.registerParticipant(
            mockConnection, studyId, "p3", UUID.randomUUID(), ParticipationStatus.NOT_ENROLLED
        )

        verify(mockPs).setString(4, ParticipationStatus.NOT_ENROLLED.name)
    }

    @Test
    fun testRegisterParticipantMapsUniqueViolationToConflictState() {
        val duplicate = SQLException("duplicate database detail", "23505")
        `when`(mockPs.executeUpdate()).thenThrow(duplicate)

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            service.registerParticipant(
                mockConnection,
                UUID.randomUUID(),
                "participant-duplicate",
                UUID.randomUUID(),
                ParticipationStatus.ENROLLED
            )
        }

        assertEquals("Participant is already registered for this study", thrown.message)
        assertEquals(duplicate, thrown.cause)
    }

    @Test
    fun testRegisterParticipantPreservesOtherSqlFailures() {
        val databaseFailure = SQLException("database unavailable", "08006")
        `when`(mockPs.executeUpdate()).thenThrow(databaseFailure)

        val thrown = org.junit.Assert.assertThrows(SQLException::class.java) {
            service.registerParticipant(
                mockConnection,
                UUID.randomUUID(),
                "participant-new",
                UUID.randomUUID(),
                ParticipationStatus.ENROLLED
            )
        }

        assertEquals(databaseFailure, thrown)
    }

    // --- isKnownParticipant tests ---

    @Test
    fun testIsKnownParticipantReturnsTrue() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        val result = service.isKnownParticipant(UUID.randomUUID(), "p1")

        assertTrue(result)
    }

    @Test
    fun testIsKnownParticipantReturnsFalse() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(0L)

        val result = service.isKnownParticipant(UUID.randomUUID(), "p1")

        assertFalse(result)
    }

    @Test(expected = IllegalStateException::class)
    fun testIsKnownParticipantThrowsWhenNoCountReturned() {
        `when`(mockRs.next()).thenReturn(false)

        service.isKnownParticipant(UUID.randomUUID(), "p1")
    }

    // --- isKnownDatasource tests ---

    @Test
    fun testIsKnownDatasourceReturnsTrue() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        val result = service.isKnownDatasource(UUID.randomUUID(), "p1", UUID.randomUUID())

        assertTrue(result)
    }

    @Test
    fun testIsKnownDatasourceReturnsFalse() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(0L)

        val result = service.isKnownDatasource(UUID.randomUUID(), "p1", UUID.randomUUID())

        assertFalse(result)
    }

    @Test(expected = IllegalStateException::class)
    fun testIsKnownDatasourceThrowsWhenNoCountReturned() {
        `when`(mockRs.next()).thenReturn(false)

        service.isKnownDatasource(UUID.randomUUID(), "p1", UUID.randomUUID())
    }

    // --- studyExists tests ---

    @Test
    fun testStudyExistsReturnsTrue() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        assertTrue(service.studyExists(UUID.randomUUID()))
    }

    @Test
    fun testStudyExistsReturnsFalse() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(0L)

        assertFalse(service.studyExists(UUID.randomUUID()))
    }

    @Test(expected = IllegalStateException::class)
    fun testStudyExistsThrowsWhenNoCountReturned() {
        `when`(mockRs.next()).thenReturn(false)

        service.studyExists(UUID.randomUUID())
    }

    // --- getOrganizationIdForStudy tests ---

    @Test
    fun testGetOrganizationIdForStudyReturnsId() {
        val orgId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject(kAnyString(), kEq(UUID::class.java))).thenReturn(orgId)

        val result = service.getOrganizationIdForStudy(UUID.randomUUID())

        assertEquals(orgId, result)
    }

    @Test(expected = com.geekbeast.controllers.exceptions.ResourceNotFoundException::class)
    fun testGetOrganizationIdForStudyThrowsWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        service.getOrganizationIdForStudy(UUID.randomUUID())
    }

    // --- registerDevice tests ---

    @Test(expected = AccessDeniedException::class)
    fun testRegisterDeviceThrowsForUnknownParticipant() {
        // isKnownParticipant returns false
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(0L)

        val device = AndroidDevice("dev", "model", "code", "brand", "11", "30", "product", "did-1", fcmRegistrationToken = "tok")
        service.registerDevice(UUID.randomUUID(), "unknown-p", UUID.randomUUID(), device)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun testRegisterDeviceThrowsForUnsupportedDeviceType() {
        // isKnownParticipant returns true
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        val unsupportedDevice = Mockito.mock(SourceDevice::class.java)
        service.registerDevice(UUID.randomUUID(), "p1", UUID.randomUUID(), unsupportedDevice)
    }

    @Test
    fun testRegisterDeviceWithAndroidDeviceReturnsNewId() {
        val newDeviceId: UUID = UUID.randomUUID()

        // isKnownParticipant check — returns count > 0
        val countRs = Mockito.mock(ResultSet::class.java)
        `when`(countRs.next()).thenReturn(true)
        `when`(countRs.getLong("count")).thenReturn(1L)

        // INSERT ... RETURNING device_id
        val insertRs = Mockito.mock(ResultSet::class.java)
        `when`(insertRs.next()).thenReturn(true)
        `when`(insertRs.getObject(kAnyString(), kEq(UUID::class.java))).thenReturn(newDeviceId)

        val insertPs = Mockito.mock(PreparedStatement::class.java)
        `when`(insertPs.executeQuery()).thenReturn(insertRs)

        // Two prepareStatement calls: first for COUNT, second for INSERT
        val countPs = Mockito.mock(PreparedStatement::class.java)
        `when`(countPs.executeQuery()).thenReturn(countRs)

        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(countPs)
            .thenReturn(insertPs)

        val device = AndroidDevice("dev", "model", "code", "brand", "11", "30", "product", "did-1", fcmRegistrationToken = "fcm-token-123")

        val result = service.registerDevice(UUID.randomUUID(), "p1", UUID.randomUUID(), device)

        assertEquals(newDeviceId, result)
    }

    @Test
    fun testRegisterDeviceWithIosDeviceReturnsNewId() {
        val newDeviceId: UUID = UUID.randomUUID()

        val countRs = Mockito.mock(ResultSet::class.java)
        `when`(countRs.next()).thenReturn(true)
        `when`(countRs.getLong("count")).thenReturn(1L)

        // INSERT ... RETURNING device_id
        val insertRs = Mockito.mock(ResultSet::class.java)
        `when`(insertRs.next()).thenReturn(true)
        `when`(insertRs.getObject(kAnyString(), kEq(UUID::class.java))).thenReturn(newDeviceId)

        val insertPs = Mockito.mock(PreparedStatement::class.java)
        `when`(insertPs.executeQuery()).thenReturn(insertRs)

        val countPs = Mockito.mock(PreparedStatement::class.java)
        `when`(countPs.executeQuery()).thenReturn(countRs)

        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(countPs)
            .thenReturn(insertPs)

        val device = IOSDevice("iPhone", "iOS", "iPhone13,2", "iPhone", "15.0", "dev-id-1", apnDeviceToken = "apn-token-456")

        val result = service.registerDevice(UUID.randomUUID(), "p1", UUID.randomUUID(), device)

        assertEquals(newDeviceId, result)
    }

    // --- getParticipationStatus tests ---

    @Test
    fun testGetParticipationStatusReturnsEnrolled() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(ParticipationStatus.ENROLLED.name)

        val result = service.getParticipationStatus(UUID.randomUUID(), "p1")

        assertEquals(ParticipationStatus.ENROLLED, result)
    }

    @Test(expected = IllegalStateException::class)
    fun testGetParticipationStatusThrowsWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        service.getParticipationStatus(UUID.randomUUID(), "p1")
    }

    // --- getParticipant tests ---

    @Test(expected = IllegalStateException::class)
    fun testGetParticipantThrowsWhenNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        service.getParticipant(UUID.randomUUID(), "nonexistent")
    }

    // --- Multiple UUID tests ---

    @Test
    fun testStudyExistsWithSpecificStudyId() {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        assertTrue(service.studyExists(studyId))

        verify(mockPs).setObject(1, studyId)
    }

    @Test
    fun testIsKnownParticipantSetsCorrectParameters() {
        val studyId = UUID.randomUUID()
        val participantId = "test-participant"
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(1L)

        service.isKnownParticipant(studyId, participantId)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setString(2, participantId)
    }

    @Test
    fun testIsKnownDatasourceSetsCorrectParameters() {
        val studyId = UUID.randomUUID()
        val participantId = "test-p"
        val deviceId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(0L)

        service.isKnownDatasource(studyId, participantId, deviceId)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setString(2, participantId)
        verify(mockPs).setObject(3, deviceId)
    }

    @Test
    fun testRegisterParticipantUsesProvidedConnection() {
        val conn = Mockito.mock(Connection::class.java)
        val ps = Mockito.mock(PreparedStatement::class.java)
        `when`(conn.prepareStatement(kAnyString())).thenReturn(ps)
        `when`(ps.executeUpdate()).thenReturn(1)

        service.registerParticipant(
            conn, UUID.randomUUID(), "p-ext", UUID.randomUUID(), ParticipationStatus.ENROLLED
        )

        // Verify the provided connection was used (not storageResolver)
        verify(conn).prepareStatement(kAnyString())
    }

    @Test
    fun testGetOrganizationIdSetsCorrectStudyIdParameter() {
        val studyId = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getObject(kAnyString(), kEq(UUID::class.java))).thenReturn(orgId)

        service.getOrganizationIdForStudy(studyId)

        verify(mockPs).setObject(1, studyId)
    }

    @Test
    fun testStudyExistsMultipleCount() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getLong("count")).thenReturn(5L)

        assertTrue(service.studyExists(UUID.randomUUID()))
    }
}
