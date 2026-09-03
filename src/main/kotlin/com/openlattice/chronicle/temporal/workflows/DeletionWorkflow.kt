package com.openlattice.chronicle.temporal.workflows

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * Temporal workflow for data deletion operations.
 *
 * Handles both study-level and participant-level deletions with:
 * - Guaranteed completion (survives server restarts)
 * - Per-deletion-type retry
 * - Audit trail for HIPAA compliance
 */
@WorkflowInterface
public interface DeletionWorkflow {

    @WorkflowMethod
    public fun deleteData(request: DeletionRequest)
}

public data class DeletionRequest(
    val jobId: UUID,
    val studyId: UUID,
    val participantIds: List<UUID>,
    val deletionTypes: List<String>,
    val principalId: String,
    val securablePrincipalId: UUID,
)
