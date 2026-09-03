package com.openlattice.chronicle.temporal.workflows

import com.openlattice.chronicle.pipeline.PipelineJobRunner
import com.openlattice.chronicle.pipeline.PipelineStepType
import com.openlattice.chronicle.temporal.activities.PipelineActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

public class PipelineWorkflowImpl : PipelineWorkflow {

    private val activities: PipelineActivities = Workflow.newActivityStub(
        PipelineActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(60))
            .setHeartbeatTimeout(Duration.ofMinutes(10))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(30))
                    .setMaximumInterval(Duration.ofMinutes(15))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(2)
                    .build()
            )
            .build()
    )

    // reason: boundary catch — records the workflow run as FAILED before rethrowing so a failed
    // pipeline run is always recorded
    @Suppress("TooGenericExceptionCaught")
    override fun executePipeline(request: PipelineRequest) {
        var totalInputRows = 0L
        var totalOutputRows = 0L
        var stepsCompleted = 0

        try {
            require(request.steps.isNotEmpty()) { "Pipeline must contain at least one step" }
            require(request.steps.all { it.order >= 0 }) { "Pipeline step order must be non-negative" }
            require(request.steps.map { it.order }.distinct().size == request.steps.size) {
                "Pipeline step order values must be unique"
            }
            PipelineJobRunner.validateTableName(request.outputTable)
            request.steps.forEach { step ->
                val stepType = PipelineStepType.valueOf(step.type)
                require(stepType != PipelineStepType.CUSTOM_SQL) {
                    "No executor for pipeline step type $stepType"
                }
                step.params["sourceTable"]?.let(PipelineJobRunner::validateTableName)
            }

            activities.updateRunStatus(request.runId, "RUNNING", 0, 0, 0, null)
            for ((index, step) in request.steps.sortedBy { it.order }.withIndex()) {
                val result = activities.executeStep(
                    studyId = request.studyId,
                    stepType = step.type,
                    stepOrder = step.order,
                    outputTable = request.outputTable,
                    params = step.params,
                )
                totalInputRows += result[0]
                totalOutputRows += result[1]
                stepsCompleted = index + 1

                activities.updateRunStatus(
                    request.runId, "RUNNING", stepsCompleted, totalInputRows, totalOutputRows, null
                )
            }

            activities.updateRunStatus(
                request.runId, "COMPLETED", stepsCompleted, totalInputRows, totalOutputRows, null
            )
        } catch (e: Exception) {
            activities.updateRunStatus(
                request.runId,
                "FAILED",
                stepsCompleted,
                totalInputRows,
                totalOutputRows,
                e.message?.take(4_096) ?: e.javaClass.simpleName,
            )
            throw e
        }

        activities.auditPipeline(
            studyId = request.studyId,
            inputRows = totalInputRows,
            outputRows = totalOutputRows,
            principalId = request.principalId,
            securablePrincipalId = request.securablePrincipalId,
        )
    }
}
