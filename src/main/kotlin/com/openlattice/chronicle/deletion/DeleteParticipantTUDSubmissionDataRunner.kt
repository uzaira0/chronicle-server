package com.openlattice.chronicle.deletion

import com.geekbeast.postgres.PostgresArrays
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.TIME_USE_DIARY_SUBMISSIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import org.slf4j.LoggerFactory
import com.openlattice.chronicle.util.LogSanitizer
import java.sql.Connection

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteParticipantTUDSubmissionDataRunner : AbstractChronicleDeleteJobRunner<DeleteParticipantTUDSubmissionData>() {
    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteParticipantTUDSubmissionDataRunner::class.java)!!

        private val DELETE_PARTICIPANT_TUD_DATA_SQL = """
            DELETE FROM ${TIME_USE_DIARY_SUBMISSIONS.name}
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ANY(?)
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        job.definition as DeleteParticipantTUDSubmissionData

        val deletedRows = deleteTUDSubmissionData(connection, job.definition)
        return finishDeleteJob(
            connection, job, deletedRows, job.definition.studyId,
            AuditEventType.BACKGROUND_TUD_DATA_DELETION,
        )
    }

    private fun deleteTUDSubmissionData(connection: Connection, jobDefinition: DeleteParticipantTUDSubmissionData): Long {
        logger.info(
            "Deleting TUD submission data with studyId = {} for participantRefs = {}",
            jobDefinition.studyId,
            LogSanitizer.stableFingerprints(jobDefinition.participantIds, "participant")
        )
        return connection.prepareStatement(DELETE_PARTICIPANT_TUD_DATA_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId)
            val pgParticipantIds = PostgresArrays.createTextArray(ps.connection, jobDefinition.participantIds)
            ps.setArray(2, pgParticipantIds)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteParticipantTUDSubmissionData> = DeleteParticipantTUDSubmissionData::class.java
}
