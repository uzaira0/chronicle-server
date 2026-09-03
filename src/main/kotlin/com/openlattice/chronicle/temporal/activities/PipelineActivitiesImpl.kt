package com.openlattice.chronicle.temporal.activities

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.pipeline.PipelineJobRunner
import com.openlattice.chronicle.pipeline.PipelineRunStatus
import com.openlattice.chronicle.pipeline.PipelineStepType
import com.openlattice.chronicle.pipeline.steps.AggregationStep
import com.openlattice.chronicle.pipeline.steps.DeidentificationStep
import com.openlattice.chronicle.pipeline.steps.FeatureExtractionStep
import com.openlattice.chronicle.pipeline.steps.PipelineStepExecutor
import com.openlattice.chronicle.pipeline.steps.TimeBucketingStep
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PIPELINE_RUNS
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
public open class PipelineActivitiesImpl(
    private val storageResolver: StorageResolver,
    private val auditingManager: AuditingManager,
) : PipelineActivities {

    private val logger = LoggerFactory.getLogger(PipelineActivitiesImpl::class.java)

    override fun executeStep(
        studyId: UUID,
        stepType: String,
        stepOrder: Int,
        outputTable: String,
        params: Map<String, String>,
    ): LongArray {
        val type = PipelineStepType.valueOf(stepType)
        val executor = STEP_EXECUTORS[type]
            ?: throw IllegalArgumentException("No executor for step type: $stepType")
        PipelineJobRunner.validateTableName(outputTable)
        params["sourceTable"]?.let(PipelineJobRunner::validateTableName)
        params["bucketMinutes"]?.let { value ->
            val bucketMinutes = value.toIntOrNull()
            require(bucketMinutes != null && PipelineJobRunner.isValidBucketMinutes(bucketMinutes)) {
                "Pipeline bucketMinutes must be a positive divisor of 60"
            }
        }
        PipelineJobRunner.requireExecutionAvailable()

        val step = com.openlattice.chronicle.pipeline.PipelineStep(
            type = type,
            order = stepOrder,
            params = params,
        )

        logger.info("Executing pipeline step {} for study {}", stepType, studyId)
        val (_, eventHds) = storageResolver.getDefaultEventStorage()
        eventHds.connection.use { connection ->
            val result = executor.execute(connection, studyId, step, outputTable)
            return longArrayOf(result.inputRows, result.outputRows)
        }
    }

    override fun updateRunStatus(
        runId: UUID,
        status: String,
        stepsCompleted: Int,
        inputRows: Long,
        outputRows: Long,
        errorMessage: String?,
    ) {
        val runStatus = PipelineRunStatus.valueOf(status)
        val completedAt = when (runStatus) {
            PipelineRunStatus.COMPLETED,
            PipelineRunStatus.FAILED -> OffsetDateTime.now(ZoneOffset.UTC)
            PipelineRunStatus.PENDING,
            PipelineRunStatus.RUNNING -> OffsetDateTime.MAX
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(UPDATE_PIPELINE_RUN_SQL).use { ps ->
                ps.setString(1, runStatus.name)
                ps.setInt(2, stepsCompleted)
                ps.setLong(3, inputRows)
                ps.setLong(4, outputRows)
                ps.setObject(5, completedAt)
                ps.setString(6, errorMessage)
                ps.setObject(7, runId)
                check(ps.executeUpdate() == 1) { "Pipeline run $runId was not updated" }
            }
        }
    }

    override fun auditPipeline(
        studyId: UUID,
        inputRows: Long,
        outputRows: Long,
        principalId: String,
        securablePrincipalId: UUID,
    ) {
        val event = AuditableEvent(
            AclKey(studyId),
            securablePrincipalId,
            Principal(PrincipalType.USER, principalId),
            AuditEventType.TRIGGER_PIPELINE,
            "Pipeline completed: $inputRows input rows, $outputRows output rows",
            studyId,
        )
        auditingManager.recordEvents(listOf(event))
    }

    internal companion object {
        private val STEP_EXECUTORS: Map<PipelineStepType, PipelineStepExecutor> = mapOf(
            PipelineStepType.DEIDENTIFICATION to DeidentificationStep(),
            PipelineStepType.AGGREGATION to AggregationStep(),
            PipelineStepType.FEATURE_EXTRACTION to FeatureExtractionStep(),
            PipelineStepType.TIME_BUCKETING to TimeBucketingStep(),
        )

        private val UPDATE_PIPELINE_RUN_SQL = """
            UPDATE ${PIPELINE_RUNS.name}
            SET status = ?, steps_completed = ?, input_rows = ?, output_rows = ?,
                completed_at = ?, error_message = ?
            WHERE run_id = ?
        """.trimIndent()
    }
}
