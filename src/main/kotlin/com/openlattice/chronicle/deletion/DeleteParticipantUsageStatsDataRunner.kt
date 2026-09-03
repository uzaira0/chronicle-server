package com.openlattice.chronicle.deletion

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.PostgresDataTables.Companion.CHRONICLE_USAGE_STATS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import com.openlattice.chronicle.util.LogSanitizer
import java.sql.Connection
import java.time.OffsetDateTime

public open class DeleteParticipantUsageStatsDataRunner(
    private val storageResolver: StorageResolver
) : AbstractChronicleDeleteJobRunner<DeleteParticipantUsageStatsData>() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteParticipantUsageStatsDataRunner::class.java)!!

        private val DELETE_PARTICIPANT_USAGE_STATS_SQL = """
            DELETE FROM ${CHRONICLE_USAGE_STATS.name}
            WHERE ${STUDY_ID.name} = ?
            AND ${PARTICIPANT_ID.name} = ANY(?)
        """.trimIndent()
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        val (_, eventHds) = storageResolver.getDefaultEventStorage()

        job.definition as DeleteParticipantUsageStatsData

        val deletedRows = eventHds.connection.use { eventConnection ->
            deleteUsageStats(eventConnection, job.definition)
        }

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
                eventType = AuditEventType.BACKGROUND_USAGE_STATS_DATA_DELETION,
                data = mapOf( "definition" to job.definition),
                study = job.definition.studyId
            )
        )
    }

    private fun deleteUsageStats(connection: Connection, jobDefinition: DeleteParticipantUsageStatsData): Long {
        logger.info(
            "Deleting usage stats with studyId = {} for participantRefs = {}",
            jobDefinition.studyId,
            LogSanitizer.stableFingerprints(jobDefinition.participantIds, "participant")
        )
        return connection.prepareStatement(DELETE_PARTICIPANT_USAGE_STATS_SQL).use { ps ->
            ps.setObject(1, jobDefinition.studyId.toString())
            val pgParticipantIds = PostgresArrays.createTextArray(ps.connection, jobDefinition.participantIds)
            ps.setArray(2, pgParticipantIds)
            ps.executeUpdate().toLong()
        }
    }

    override fun accepts(): Class<DeleteParticipantUsageStatsData> = DeleteParticipantUsageStatsData::class.java
}
