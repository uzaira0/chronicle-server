package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteStudyAppUsageSurveyDataRunner : AbstractChronicleDeleteJobRunner<DeleteStudyAppUsageSurveyData>() {
    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteStudyAppUsageSurveyDataRunner::class.java)!!

        private val DELETE_STUDY_APP_USAGE_SURVEY_DATA_SQL = """
            DELETE FROM ${APP_USAGE_SURVEY.name}
            WHERE ${STUDY_ID.name} = ?
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        job.definition as DeleteStudyAppUsageSurveyData

        val deletedRows = deleteAppUsageSurveyData(connection, job.definition)
        return finishDeleteJob(
            connection, job, deletedRows, job.definition.studyId,
            AuditEventType.BACKGROUND_APP_USAGE_SURVEY_DATA_DELETION,
        )
    }

    private fun deleteAppUsageSurveyData(connection: Connection, jobDefinition: DeleteStudyAppUsageSurveyData): Long {
        logger.info("Deleting app usage survey data with studyId = {}", jobDefinition.studyId, )
        return connection.prepareStatement(DELETE_STUDY_APP_USAGE_SURVEY_DATA_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteStudyAppUsageSurveyData> = DeleteStudyAppUsageSurveyData::class.java
}
