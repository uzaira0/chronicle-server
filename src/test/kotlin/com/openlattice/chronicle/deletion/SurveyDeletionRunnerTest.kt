package com.openlattice.chronicle.deletion

import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.ChronicleJobDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

class SurveyDeletionRunnerTest {
    @Test
    fun `survey deletion runners persist completion and preserve audit scope only after deletion succeeds`() {
        val studyId = UUID.randomUUID()
        val participantIds = setOf("participant-1")
        val cases: List<Triple<AbstractChronicleDeleteJobRunner<*>, ChronicleJobDefinition, AuditEventType>> = listOf(
            Triple(DeleteParticipantAppUsageSurveyDataRunner(), DeleteParticipantAppUsageSurveyData(studyId, participantIds),
                AuditEventType.BACKGROUND_APP_USAGE_SURVEY_DATA_DELETION),
            Triple(DeleteStudyAppUsageSurveyDataRunner(), DeleteStudyAppUsageSurveyData(studyId),
                AuditEventType.BACKGROUND_APP_USAGE_SURVEY_DATA_DELETION),
            Triple(DeleteParticipantTUDSubmissionDataRunner(), DeleteParticipantTUDSubmissionData(studyId, participantIds),
                AuditEventType.BACKGROUND_TUD_DATA_DELETION),
            Triple(DeleteStudyTUDSubmissionDataRunner(), DeleteStudyTUDSubmissionData(studyId),
                AuditEventType.BACKGROUND_TUD_DATA_DELETION),
        )
        for ((runner, definition, eventType) in cases) {
            val connection = mock<Connection>()
            val deletion = mock<PreparedStatement>()
            val completion = mock<PreparedStatement>()
            whenever(connection.prepareStatement(any())).thenAnswer { invocation ->
                if (invocation.getArgument<String>(0).trimStart().startsWith("DELETE")) deletion else completion
            }
            whenever(deletion.connection).thenReturn(connection)
            whenever(connection.createArrayOf(any(), any())).thenReturn(mock<java.sql.Array>())
            whenever(deletion.executeUpdate()).thenReturn(3)
            val job = ChronicleJob(
                id = UUID.randomUUID(), securablePrincipalId = UUID.randomUUID(), principal = mock<Principal>(),
                definition = definition,
            )

            val event = runner.run(connection, job).single()

            assertEquals(JobStatus.FINISHED, job.status)
            assertEquals(3L, job.deletedRows)
            assertEquals(job.updatedAt, job.completedAt)
            verify(completion).setObject(1, job.updatedAt)
            verify(completion).setObject(2, job.completedAt)
            verify(completion).setLong(3, job.deletedRows)
            verify(completion).setObject(4, job.id)
            verify(completion).executeUpdate()
            assertEquals(AclKey(studyId), event.aclKey)
            assertEquals(studyId, event.study)
            assertEquals(eventType, event.eventType)
            assertEquals(job.securablePrincipalId, event.securablePrincipalId)
            assertSame(job.principal, event.principal)
            assertEquals(mapOf("definition" to definition), event.data)

            Mockito.clearInvocations(completion)
            whenever(deletion.executeUpdate()).thenThrow(SQLException("delete failed"))
            val failedJob = job.copy(status = JobStatus.PENDING, deletedRows = 0, completedAt = OffsetDateTime.MAX)
            assertThrows(SQLException::class.java) { runner.run(connection, failedJob) }
            assertEquals(JobStatus.RUNNING, failedJob.status)
            assertEquals(0L, failedJob.deletedRows)
            assertEquals(OffsetDateTime.MAX, failedJob.completedAt)
            Mockito.verifyNoInteractions(completion)
        }
    }
}
