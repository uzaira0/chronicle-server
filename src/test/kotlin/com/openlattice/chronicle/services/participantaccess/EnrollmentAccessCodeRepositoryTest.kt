package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.util.tests.EnrollmentDisclosureActivityResultFixture
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EnrollmentAccessCodeRepositoryTest {
    private val connection = mock<Connection>()
    private val statement = mock<PreparedStatement>()
    private val studyId = UUID.randomUUID()
    private val repository = EnrollmentAccessCodeRepository(connection)

    private fun revokeSql(scope: ParticipantAccessCodeRevocationScope): String {
        val now = OffsetDateTime.parse("2026-10-16T12:00:00Z")
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeUpdate()).thenReturn(2)
        assertEquals(2, repository.revoke(studyId, "participant", now, scope))
        verify(statement).setObject(1, now)
        verify(statement).setObject(2, studyId)
        verify(statement).setString(3, "participant")
        val sql = argumentCaptor<String>()
        verify(connection).prepareStatement(sql.capture())
        return sql.firstValue
    }

    @Test fun allRevokesEveryUnrevokedParticipantCode() {
        val sql = revokeSql(ParticipantAccessCodeRevocationScope.All)
        assertTrue(sql.startsWith("UPDATE participant_form_access_codes"))
        assertTrue(sql.contains("revoked_at IS NULL"))
        assertEquals(3, sql.count { it == '?' })
        assertFalse(sql.contains("exchanged_enrollment_access_codes"))
    }

    @Test fun purgePreservesExchangedEnrollmentCodes() {
        val sql = revokeSql(ParticipantAccessCodeRevocationScope.KeepExchangedEnrollment)
        assertTrue(sql.contains("access_code_id NOT IN (SELECT access_code_id FROM exchanged_enrollment_access_codes)"))
        assertEquals(3, sql.count { it == '?' })
    }

    @Test fun missingStudyUsesTheDomainExceptionForBothReadAndLock() {
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(mock<ResultSet>())
        for (read in listOf<(UUID) -> Any>(repository::hasActiveEnrollment, repository::lockActivity)) {
            assertEquals(studyId, assertThrows(StudyNotFoundException::class.java) { read(studyId) }.studyId)
        }
    }

    @Test fun lockedActivityBindsStatusAndEveryStudyPredicate() {
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        val rows = EnrollmentDisclosureActivityResultFixture.resultSet(
            EnrollmentDisclosureActivity(hasActiveEnrollment = true, pendingParticipantIds = emptyList()),
        )
        whenever(statement.executeQuery()).thenReturn(rows)
        assertEquals(true, repository.lockActivity(studyId).hasActiveEnrollment)
        val sql = argumentCaptor<String>()
        verify(connection, times(2)).prepareStatement(sql.capture())
        assertEquals(4, sql.lastValue.count { it == '?' })
        assertFalse(sql.lastValue.contains(ParticipationStatus.ENROLLED.name))
        verify(statement).setString(2, ParticipationStatus.ENROLLED.name)
        verify(statement).setObject(3, studyId)
        verify(statement).setObject(4, studyId)
    }

    @Test fun scalarActivityOmitsThePendingParticipantArray() {
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        val rows = EnrollmentDisclosureActivityResultFixture.resultSet(
            EnrollmentDisclosureActivity(hasActiveEnrollment = true, pendingParticipantIds = emptyList()),
        )
        whenever(statement.executeQuery()).thenReturn(rows)
        assertTrue(repository.hasActiveEnrollment(studyId))
        val sql = argumentCaptor<String>()
        verify(connection, times(2)).prepareStatement(sql.capture())
        assertEquals(3, sql.lastValue.count { it == '?' })
        assertFalse(sql.lastValue.contains("ARRAY"))
        verify(statement).setString(2, ParticipationStatus.ENROLLED.name)
        verify(statement).setObject(3, studyId)
    }
}
