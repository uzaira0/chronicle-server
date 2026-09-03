package com.openlattice.chronicle.deletion

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRE_SUBMISSIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime

public open class DeleteParticipantQuestionnaireSubmissionDataRunner :
    AbstractChronicleDeleteJobRunner<DeleteParticipantQuestionnaireSubmissionData>() {
    internal companion object {
        private val logger =
            LoggerFactory.getLogger(DeleteParticipantQuestionnaireSubmissionDataRunner::class.java)!!

        private val DELETE_PARTICIPANT_QUESTIONNAIRE_SUBMISSION_DATA_SQL = """
            DELETE FROM ${QUESTIONNAIRE_SUBMISSIONS.name}
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ANY(?)
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        job.definition as DeleteParticipantQuestionnaireSubmissionData

        val deletedRows = deleteQuestionnaireSubmissionData(connection, job.definition)

        job.deletedRows = deletedRows
        job.updatedAt = OffsetDateTime.now()
        job.completedAt = job.updatedAt
        job.status = JobStatus.FINISHED

        updateFinishedDeleteJob(connection, job)

        return listOf(
            AuditableEvent(
                AclKey(job.definition.studyId),
                job.securablePrincipalId,
                job.principal,
                eventType = AuditEventType.BACKGROUND_QUESTIONNAIRE_SUBMISSION_DATA_DELETION,
                data = mapOf("definition" to job.definition),
                study = job.definition.studyId
            )
        )
    }

    private fun deleteQuestionnaireSubmissionData(
        connection: Connection,
        jobDefinition: DeleteParticipantQuestionnaireSubmissionData
    ): Long {
        logger.info(
            "Deleting questionnaire submission data with studyId = {} for participantRefs = {}",
            jobDefinition.studyId,
            LogSanitizer.stableFingerprints(jobDefinition.participantIds, "participant")
        )
        return connection.prepareStatement(DELETE_PARTICIPANT_QUESTIONNAIRE_SUBMISSION_DATA_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId)
            val pgParticipantIds = PostgresArrays.createTextArray(ps.connection, jobDefinition.participantIds)
            ps.setObject(2, pgParticipantIds)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteParticipantQuestionnaireSubmissionData> = DeleteParticipantQuestionnaireSubmissionData::class.java
}
