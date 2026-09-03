package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.access.AccessDeniedException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Mock-based unit tests for [EnrollmentService]. Mocks StorageResolver + JDBC so the
 * count/lookup/insert paths run without Postgres (the integration tests are excluded
 * from PIT's minion). Verifies argument binding, both branches of the count > 0
 * conditionals, and the check(rs.next()) failure paths so PIT mutants are killed.
 */
class EnrollmentServiceMutationTest {

    private val storageResolver: StorageResolver = mock()
    private val idGenerationService: HazelcastIdGenerationService = mock()
    private val candidateManager: CandidateManager = mock()

    private val service = EnrollmentService(storageResolver, idGenerationService, candidateManager)

    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"

    private fun androidDevice(
        deviceId: String = "android-device-1",
        fcm: String = "fcm-token"
    ) = AndroidDevice(
        device = "Pixel",
        model = "Pixel 8",
        codename = "shiba",
        brand = "Google",
        osVersion = "14",
        sdkVersion = "34",
        product = "shiba",
        deviceId = deviceId,
        additionalInfo = emptyMap(),
        fcmRegistrationToken = fcm
    )

    /** A mocked Connection + the PreparedStatement it returns, so callers can verify on the ps. */
    private class Wired(val connection: Connection, val ps: PreparedStatement, val rs: ResultSet)

    private fun wirePlatformStorage(): Wired {
        val rs: ResultSet = mock()
        val ps: PreparedStatement = mock()
        whenever(ps.executeQuery()).doReturn(rs)
        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(ps)
        val hds: HikariDataSource = mock()
        whenever(hds.connection).doReturn(connection)
        whenever(storageResolver.getPlatformStorage()).doReturn(hds)
        return Wired(connection, ps, rs)
    }

    // ---- isKnownParticipant ----

    @Test
    fun `isKnownParticipant true when count positive and binds study and participant`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(1L)

        assertTrue(service.isKnownParticipant(studyId, participantId))

        verify(w.ps).setObject(1, studyId)
        verify(w.ps).setString(2, participantId)
    }

    @Test
    fun `isKnownParticipant false when count zero`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(0L)

        assertFalse(service.isKnownParticipant(studyId, participantId))
    }

    @Test
    fun `isKnownParticipant throws when no count row returned`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(IllegalStateException::class.java) {
            service.isKnownParticipant(studyId, participantId)
        }
    }

    // ---- isKnownDatasource ----

    @Test
    fun `isKnownDatasource true when count positive and binds study participant device`() {
        val deviceId = UUID.randomUUID()
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(2L)

        assertTrue(service.isKnownDatasource(studyId, participantId, deviceId))

        verify(w.ps).setObject(1, studyId)
        verify(w.ps).setString(2, participantId)
        verify(w.ps).setObject(3, deviceId)
    }

    @Test
    fun `isKnownDatasource false when count zero`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(0L)

        assertFalse(service.isKnownDatasource(studyId, participantId, UUID.randomUUID()))
    }

    @Test
    fun `isKnownDatasource throws when no count row returned`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(IllegalStateException::class.java) {
            service.isKnownDatasource(studyId, participantId, UUID.randomUUID())
        }
    }

    // ---- studyExists ----

    @Test
    fun `studyExists true when count positive and binds study id`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(1L)

        assertTrue(service.studyExists(studyId))
        verify(w.ps).setObject(1, studyId)
    }

    @Test
    fun `studyExists false when count zero`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(0L)

        assertFalse(service.studyExists(studyId))
    }

    @Test
    fun `studyExists throws when no count row returned`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(IllegalStateException::class.java) { service.studyExists(studyId) }
    }

    // ---- getParticipationStatus ----

    @Test
    fun `getParticipationStatus returns status and binds parameters`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getString("participation_status")).doReturn(ParticipationStatus.ENROLLED.name)

        assertEquals(ParticipationStatus.ENROLLED, service.getParticipationStatus(studyId, participantId))
        verify(w.ps).setObject(1, studyId)
        verify(w.ps).setString(2, participantId)
    }

    @Test
    fun `getParticipationStatus throws when no row returned`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(IllegalStateException::class.java) {
            service.getParticipationStatus(studyId, participantId)
        }
    }

    // ---- getParticipant ----

    @Test
    fun `getParticipant assembles participant from row plus candidate manager`() {
        val candidateId = UUID.randomUUID()
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getString("participation_status")).doReturn(ParticipationStatus.ENROLLED.name)
        whenever(w.rs.getObject("candidate_id", UUID::class.java)).doReturn(candidateId)
        val candidate = Candidate(id = candidateId)
        whenever(candidateManager.getCandidate(candidateId)).doReturn(candidate)

        val participant = service.getParticipant(studyId, participantId)

        assertEquals(participantId, participant.participantId)
        assertEquals(candidate, participant.candidate)
        assertEquals(ParticipationStatus.ENROLLED, participant.participationStatus)
        verify(w.ps).setObject(1, studyId)
        verify(w.ps).setString(2, participantId)
        verify(candidateManager).getCandidate(candidateId)
    }

    @Test
    fun `getParticipant throws when no row returned`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(IllegalStateException::class.java) {
            service.getParticipant(studyId, participantId)
        }
    }

    // ---- getOrganizationIdForStudy ----

    @Test
    fun `getOrganizationIdForStudy returns the organization id and binds study id`() {
        val orgId = UUID.randomUUID()
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getObject("organization_id", UUID::class.java)).doReturn(orgId)

        assertEquals(orgId, service.getOrganizationIdForStudy(studyId))
        verify(w.ps).setObject(1, studyId)
    }

    @Test
    fun `getOrganizationIdForStudy throws ResourceNotFound when no row`() {
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(false)

        assertThrows(com.geekbeast.controllers.exceptions.ResourceNotFoundException::class.java) {
            service.getOrganizationIdForStudy(studyId)
        }
    }

    // ---- registerParticipant ----

    @Test
    fun `registerParticipant binds all four parameters and executes update`() {
        val candidateId = UUID.randomUUID()
        val ps: PreparedStatement = mock()
        whenever(ps.executeUpdate()).doReturn(1)
        val connection: Connection = mock()
        whenever(connection.prepareStatement(any())).doReturn(ps)

        service.registerParticipant(connection, studyId, participantId, candidateId, ParticipationStatus.ENROLLED)

        verify(ps).setObject(1, studyId)
        verify(ps).setString(2, participantId)
        verify(ps).setObject(3, candidateId)
        verify(ps).setString(4, ParticipationStatus.ENROLLED.name)
        verify(ps).executeUpdate()
    }

    // ---- registerDevice / registerDeviceOrGetId ----

    @Test
    fun `registerDevice rejects unknown participant before touching device storage`() {
        // isKnownParticipant -> false (count == 0)
        val w = wirePlatformStorage()
        whenever(w.rs.next()).doReturn(true)
        whenever(w.rs.getLong("count")).doReturn(0L)

        assertThrows(AccessDeniedException::class.java) {
            service.registerDevice(studyId, participantId, UUID.randomUUID(), androidDevice())
        }
    }

    @Test
    fun `registerDevice for known participant discards push token and returns the returned id`() {
        val deviceId = UUID.randomUUID()
        val returnedDeviceId = UUID.randomUUID()

        // First lookup: isKnownParticipant count query -> count = 1
        val knownRs: ResultSet = mock()
        whenever(knownRs.next()).doReturn(true)
        whenever(knownRs.getLong("count")).doReturn(1L)
        val knownPs: PreparedStatement = mock()
        whenever(knownPs.executeQuery()).doReturn(knownRs)
        val knownConn: Connection = mock()
        whenever(knownConn.prepareStatement(any())).doReturn(knownPs)
        val knownHds: HikariDataSource = mock()
        whenever(knownHds.connection).doReturn(knownConn)

        // Second lookup: INSERT_DEVICE RETURNING device_id
        val insertRs: ResultSet = mock()
        whenever(insertRs.next()).doReturn(true)
        whenever(insertRs.getObject("device_id", UUID::class.java)).doReturn(returnedDeviceId)
        val insertPs: PreparedStatement = mock()
        whenever(insertPs.executeQuery()).doReturn(insertRs)
        val insertConn: Connection = mock()
        whenever(insertConn.prepareStatement(any())).doReturn(insertPs)
        val insertHds: HikariDataSource = mock()
        whenever(insertHds.connection).doReturn(insertConn)

        whenever(storageResolver.getPlatformStorage()).doReturn(knownHds, insertHds)

        val result = service.registerDevice(studyId, participantId, deviceId, androidDevice(fcm = "fcm-xyz"))

        assertEquals(returnedDeviceId, result)
        // device insert bound study, deviceId, participant, type=Android, jsonb, disabled push token
        verify(insertPs).setObject(1, studyId)
        verify(insertPs).setObject(2, deviceId)
        verify(insertPs).setString(3, participantId)
        verify(insertPs).setString(eq(4), eq("Android"))
        verify(insertPs).setString(eq(6), eq(""))
        verify(insertPs, times(1)).executeQuery()
    }

    @Test
    fun `registerDeviceOrGetId throws when INSERT RETURNING produces no row`() {
        // isKnownParticipant -> true
        val knownRs: ResultSet = mock()
        whenever(knownRs.next()).doReturn(true)
        whenever(knownRs.getLong("count")).doReturn(1L)
        val knownPs: PreparedStatement = mock()
        whenever(knownPs.executeQuery()).doReturn(knownRs)
        val knownConn: Connection = mock()
        whenever(knownConn.prepareStatement(any())).doReturn(knownPs)
        val knownHds: HikariDataSource = mock()
        whenever(knownHds.connection).doReturn(knownConn)

        // INSERT RETURNING -> empty
        val insertRs: ResultSet = mock()
        whenever(insertRs.next()).doReturn(false)
        val insertPs: PreparedStatement = mock()
        whenever(insertPs.executeQuery()).doReturn(insertRs)
        val insertConn: Connection = mock()
        whenever(insertConn.prepareStatement(any())).doReturn(insertPs)
        val insertHds: HikariDataSource = mock()
        whenever(insertHds.connection).doReturn(insertConn)

        whenever(storageResolver.getPlatformStorage()).doReturn(knownHds, insertHds)

        assertThrows(IllegalStateException::class.java) {
            service.registerDevice(studyId, participantId, UUID.randomUUID(), androidDevice())
        }
    }
}
