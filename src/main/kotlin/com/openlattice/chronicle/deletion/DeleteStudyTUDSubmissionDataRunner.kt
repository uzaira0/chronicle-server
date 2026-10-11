package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.TIME_USE_DIARY_SUBMISSIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteStudyTUDSubmissionDataRunner : AbstractChronicleDeleteJobRunner<DeleteStudyTUDSubmissionData>() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteStudyTUDSubmissionDataRunner::class.java)!!

        private val DELETE_STUDY_TUD_DATA_SQL = """
            DELETE FROM ${TIME_USE_DIARY_SUBMISSIONS.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {

        job.definition as DeleteStudyTUDSubmissionData

        val deletedRows = deleteTUDSubmissionData(connection, job.definition)
        return finishDeleteJob(
            connection, job, deletedRows, job.definition.studyId,
            AuditEventType.BACKGROUND_TUD_DATA_DELETION,
        )
    }

    private fun deleteTUDSubmissionData(connection: Connection, jobDefinition: DeleteStudyTUDSubmissionData): Long {
        logger.info("Deleting TUD submission data with studyId = {}", jobDefinition.studyId, )
        return connection.prepareStatement(DELETE_STUDY_TUD_DATA_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteStudyTUDSubmissionData> = DeleteStudyTUDSubmissionData::class.java
}
