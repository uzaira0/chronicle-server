package com.openlattice.chronicle.deletion

import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.services.delete.ChronicleDataAssetRegistry
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime

public open class DeleteParticipantRegisteredAssetDataRunner :
    AbstractChronicleDeleteJobRunner<DeleteParticipantRegisteredAssetData>() {

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        val definition = job.definition as DeleteParticipantRegisteredAssetData
        val asset = ChronicleDataAssetRegistry.participantAsset(definition.assetId)
        check(!asset.handledByDedicatedJob) { "Asset must use its dedicated deletion job" }

        logger.info(
            "Deleting registered asset {} in study {} for participantRefs {}",
            asset.id,
            definition.studyId,
            LogSanitizer.stableFingerprints(definition.participantIds, "participant"),
        )
        val sql = "DELETE FROM ${asset.tableName} WHERE study_id = ? AND participant_id = ANY(?)"
        val deletedRows = connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, definition.studyId)
            statement.setArray(
                2,
                PostgresArrays.createTextArray(connection, definition.participantIds),
            )
            statement.executeUpdate().toLong()
        }

        job.deletedRows = deletedRows
        job.updatedAt = OffsetDateTime.now()
        job.completedAt = job.updatedAt
        job.status = JobStatus.FINISHED
        updateFinishedDeleteJob(connection, job)

        return listOf(
            AuditableEvent(
                aclKey = AclKey(definition.studyId),
                securablePrincipalId = job.securablePrincipalId,
                principal = job.principal,
                eventType = AuditEventType.BACKGROUND_REGISTERED_ASSET_DELETION,
                study = definition.studyId,
                data = mapOf("assetId" to asset.id, "deletedRows" to deletedRows),
            )
        )
    }

    override fun accepts(): Class<DeleteParticipantRegisteredAssetData> =
        DeleteParticipantRegisteredAssetData::class.java

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteParticipantRegisteredAssetDataRunner::class.java)
    }
}
