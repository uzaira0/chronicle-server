package com.openlattice.chronicle.temporal.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import java.util.UUID

/**
 * Activities for executing data pipeline steps.
 * Each step runs as a separate activity for independent retry and visibility.
 */
@ActivityInterface
public interface PipelineActivities {

    /**
     * Executes a single pipeline step. Returns input/output row counts as a pair.
     */
    @ActivityMethod
    public fun executeStep(
        studyId: UUID,
        stepType: String,
        stepOrder: Int,
        outputTable: String,
        params: Map<String, String>,
    ): LongArray

    /**
     * Updates the pipeline run status in the database.
     */
    @ActivityMethod
    public fun updateRunStatus(
        runId: UUID,
        status: String,
        stepsCompleted: Int,
        inputRows: Long,
        outputRows: Long,
        errorMessage: String?,
    )

    /**
     * Records an audit event for the pipeline execution.
     */
    @ActivityMethod
    public fun auditPipeline(
        studyId: UUID,
        inputRows: Long,
        outputRows: Long,
        principalId: String,
        securablePrincipalId: UUID,
    )
}
