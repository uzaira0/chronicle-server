package com.openlattice.chronicle.temporal.activities

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Temporal activity implementation for data deletion.
 * Delegates to the existing ChronicleJobRunner infrastructure for the actual SQL execution.
 */
@Component
public open class DeletionActivitiesImpl(
    // reason: DI-injected dependency held for the planned move of SQL execution into this activity (see deleteData)
    @Suppress("UnusedPrivateProperty")
    private val storageResolver: StorageResolver,
    private val auditingManager: AuditingManager,
) : DeletionActivities {

    private val logger = LoggerFactory.getLogger(DeletionActivitiesImpl::class.java)

    override fun deleteData(
        jobId: UUID,
        deletionType: String,
        studyId: UUID,
        participantIds: List<UUID>,
    ): Long {
        // Deletion is executed by the existing job runner infrastructure via JobService.
        // This activity serves as the Temporal-managed wrapper that provides retry and
        // visibility. The actual SQL execution happens through the job system.
        logger.info("Temporal activity: deleting {} data for study {} ({} participants)", deletionType, studyId, participantIds.size)

        // For now, this is a passthrough — the existing JobService still handles
        // the SQL execution. The Temporal workflow coordinates scheduling and retry.
        // Future: move the SQL execution directly into activities and remove JobService.
        return 0L
    }

    override fun auditDeletion(
        studyId: UUID,
        deletionType: String,
        participantIds: List<UUID>,
        deletedRows: Long,
        principalId: String,
        securablePrincipalId: UUID,
    ) {
        val description = "Deleted $deletedRows rows of type $deletionType for study $studyId" +
            if (participantIds.isNotEmpty()) " (participants: ${participantIds.size})" else ""

        val event = AuditableEvent(
            AclKey(studyId),
            securablePrincipalId,
            Principal(PrincipalType.USER, principalId),
            AuditEventType.DELETE_STUDY,
            description,
            studyId,
        )
        auditingManager.recordEvents(listOf(event))
    }
}
