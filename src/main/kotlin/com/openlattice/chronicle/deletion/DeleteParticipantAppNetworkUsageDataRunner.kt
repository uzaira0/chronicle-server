package com.openlattice.chronicle.deletion

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_NETWORK_USAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import org.slf4j.LoggerFactory
import com.openlattice.chronicle.util.LogSanitizer
import java.sql.Connection
import java.time.OffsetDateTime

public open class DeleteParticipantAppNetworkUsageDataRunner : AbstractChronicleDeleteJobRunner<DeleteParticipantAppNetworkUsageData>() {
    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteParticipantAppNetworkUsageDataRunner::class.java)!!

        private val DELETE_PARTICIPANT_APP_NETWORK_USAGE_SQL = """
            DELETE FROM ${APP_NETWORK_USAGE.name}
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ANY(?)
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        job.definition as DeleteParticipantAppNetworkUsageData

        val deletedRows = deleteAppNetworkUsageData(connection, job.definition)

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
                eventType = AuditEventType.BACKGROUND_APP_NETWORK_USAGE_DATA_DELETION,
                data = mapOf("definition" to job.definition),
                study = job.definition.studyId
            )
        )
    }

    private fun deleteAppNetworkUsageData(connection: Connection, jobDefinition: DeleteParticipantAppNetworkUsageData): Long {
        logger.info(
            "Deleting app network usage data with studyId = {} for participantRefs = {}",
            jobDefinition.studyId,
            LogSanitizer.stableFingerprints(jobDefinition.participantIds, "participant")
        )
        return connection.prepareStatement(DELETE_PARTICIPANT_APP_NETWORK_USAGE_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId)
            val pgParticipantIds = PostgresArrays.createTextArray(ps.connection, jobDefinition.participantIds)
            ps.setObject(2, pgParticipantIds)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteParticipantAppNetworkUsageData> = DeleteParticipantAppNetworkUsageData::class.java
}
