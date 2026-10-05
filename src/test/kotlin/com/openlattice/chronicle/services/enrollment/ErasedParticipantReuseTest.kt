package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class ErasedParticipantReuseTest {
    @Test
    fun erasedParticipantReadsAsUnknownWhileRegistrationStillConflicts() {
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(any())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getBoolean("erased")).thenReturn(true)
        Mockito.`when`(rows.getLong("count")).thenReturn(0L)
        val storage = Mockito.mock(StorageResolver::class.java)
        val pool = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(pool)
        Mockito.`when`(pool.connection).thenReturn(connection)
        val service = EnrollmentService(storage, Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(CandidateManager::class.java))
        val studyId = UUID.randomUUID()
        assertFalse(service.isKnownParticipant(studyId, "erased-id"))
        val failure = assertThrows(ResponseStatusException::class.java) {
            service.registerParticipant(connection, studyId, "erased-id", UUID.randomUUID(), ParticipationStatus.ENROLLED)
        }
        assertEquals(409, failure.statusCode.value())
        val deviceFailure = assertThrows(ResponseStatusException::class.java) {
            service.registerDevice(studyId, "erased-id", UUID.randomUUID(), Mockito.mock(SourceDevice::class.java))
        }
        assertEquals(409, deviceFailure.statusCode.value())
        Mockito.verify(statement, Mockito.never()).executeUpdate()
    }

    @Test
    fun completedErasurePreventsParticipantReuseBeforeAnyInsert() {
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(any())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getBoolean("erased")).thenReturn(true)
        val service = EnrollmentService(Mockito.mock(StorageResolver::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java), Mockito.mock(CandidateManager::class.java))
        val failure = assertThrows(ResponseStatusException::class.java) {
            service.registerParticipant(connection, UUID.randomUUID(), "erased-id", UUID.randomUUID(), ParticipationStatus.ENROLLED)
        }
        assertEquals(409, failure.statusCode.value())
        Mockito.verify(statement, Mockito.never()).executeUpdate()
    }
}
