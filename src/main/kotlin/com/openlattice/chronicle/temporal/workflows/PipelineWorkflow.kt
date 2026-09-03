package com.openlattice.chronicle.temporal.workflows

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * Temporal workflow for data pipeline execution.
 *
 * Each pipeline step runs as a separate Temporal activity, giving:
 * - Per-step retry (a failed aggregation step doesn't re-run deidentification)
 * - Per-step visibility in Temporal UI
 * - Guaranteed completion through server restarts
 */
@WorkflowInterface
public interface PipelineWorkflow {

    @WorkflowMethod
    public fun executePipeline(request: PipelineRequest)
}

public data class PipelineRequest(
    val runId: UUID,
    val studyId: UUID,
    val outputTable: String,
    val steps: List<PipelineStepRequest>,
    val principalId: String,
    val securablePrincipalId: UUID,
)

public data class PipelineStepRequest(
    val type: String,
    val order: Int,
    val params: Map<String, String>,
)
