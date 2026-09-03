package com.openlattice.chronicle.pipeline

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.pipeline.steps.AggregationStep
import com.openlattice.chronicle.pipeline.steps.DeidentificationStep
import com.openlattice.chronicle.pipeline.steps.FeatureExtractionStep
import com.openlattice.chronicle.pipeline.steps.PipelineStepExecutor
import com.openlattice.chronicle.pipeline.steps.TimeBucketingStep
import com.openlattice.chronicle.services.jobs.AbstractChronicleJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PIPELINE_RUNS
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class PipelineJobRunner(
    private val storageResolver: StorageResolver,
) : AbstractChronicleJobRunner<PipelineJobDefinition>() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(PipelineJobRunner::class.java)
        private const val EXECUTION_AVAILABLE = false
        internal const val EXECUTION_UNAVAILABLE_MESSAGE =
            "Pipeline execution is unavailable because the configured transformations do not match the production event schema"

        private val VALID_TABLE_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$")

        internal fun validateTableName(name: String) {
            require(VALID_TABLE_NAME.matches(name)) {
                "Invalid table name: '$name'. Table names must contain only letters, digits, and underscores."
            }
        }

        private val stepExecutors: Map<PipelineStepType, PipelineStepExecutor> = mapOf(
            PipelineStepType.DEIDENTIFICATION to DeidentificationStep(),
            PipelineStepType.AGGREGATION to AggregationStep(),
            PipelineStepType.FEATURE_EXTRACTION to FeatureExtractionStep(),
            PipelineStepType.TIME_BUCKETING to TimeBucketingStep(),
        )

        internal fun validateConfig(config: PipelineConfig) {
            require(config.steps.isNotEmpty()) { "Pipeline must contain at least one step" }
            require(config.steps.all { it.order >= 0 }) { "Pipeline step order must be non-negative" }
            require(config.steps.map { it.order }.distinct().size == config.steps.size) {
                "Pipeline step order values must be unique"
            }
            require(isValidBucketMinutes(config.timeBucketMinutes)) {
                "Pipeline timeBucketMinutes must be a positive divisor of 60"
            }
            validateTableName(config.outputTable)

            config.steps.forEach { step ->
                require(step.type in stepExecutors) { "No executor for pipeline step type ${step.type}" }
                step.params["sourceTable"]?.let(::validateTableName)
                step.params["bucketMinutes"]?.let { value ->
                    require(value.toIntOrNull()?.let(::isValidBucketMinutes) == true) {
                        "Pipeline bucketMinutes must be a positive divisor of 60"
                    }
                }
            }

            requireExecutionAvailable()
        }

        internal fun isValidBucketMinutes(value: Int): Boolean = value in 1..60 && 60 % value == 0

        internal fun requireExecutionAvailable() {
            if (!EXECUTION_AVAILABLE) {
                throw UnsupportedOperationException(EXECUTION_UNAVAILABLE_MESSAGE)
            }
        }

        private val UPDATE_PIPELINE_RUN_SQL = """
            UPDATE ${PIPELINE_RUNS.name}
            SET status = ?, steps_completed = ?, input_rows = ?, output_rows = ?,
                completed_at = ?, error_message = ?
            WHERE run_id = ?
        """.trimIndent()
    }

    // reason: boundary catch records the run as FAILED before rethrowing so a failed pipeline is
    // never silently dropped; nesting is the connection.use/step-loop scaffolding
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        val definition = job.definition as PipelineJobDefinition
        val studyId = definition.studyId
        val config = definition.config
        val steps = config.steps.sortedBy { it.order }
        val runId = getRunIdForJob(connection, job.id)

        var totalInputRows = 0L
        var totalOutputRows = 0L
        var stepsCompleted = 0

        try {
            validateConfig(config)
            updateRunStatus(
                connection,
                runId,
                PipelineRunStatus.RUNNING,
                0,
                0,
                0,
                OffsetDateTime.MAX,
                null,
            )

            val (_, eventHds) = storageResolver.getDefaultEventStorage()
            eventHds.connection.use { eventConnection ->
                val previousAutoCommit = eventConnection.autoCommit
                try {
                    eventConnection.autoCommit = false
                    for ((index, step) in steps.withIndex()) {
                        val executor = checkNotNull(stepExecutors[step.type]) {
                            "No executor for pipeline step type ${step.type}"
                        }

                        logger.info(
                            "Executing pipeline step {}/{}: {} for study {}",
                            index + 1,
                            steps.size,
                            step.type,
                            studyId,
                        )
                        val effectiveStep = if (
                            step.type == PipelineStepType.TIME_BUCKETING &&
                            "bucketMinutes" !in step.params
                        ) {
                            step.copy(
                                params = step.params + ("bucketMinutes" to config.timeBucketMinutes.toString()),
                            )
                        } else {
                            step
                        }
                        val result = executor.execute(eventConnection, studyId, effectiveStep, config.outputTable)
                        totalInputRows += result.inputRows
                        totalOutputRows += result.outputRows
                        stepsCompleted = index + 1

                        updateRunStatus(
                            connection,
                            runId,
                            PipelineRunStatus.RUNNING,
                            stepsCompleted,
                            totalInputRows,
                            totalOutputRows,
                            OffsetDateTime.MAX,
                            null,
                        )
                    }
                    eventConnection.commit()
                } catch (ex: Exception) {
                    eventConnection.rollback()
                    throw ex
                } finally {
                    eventConnection.autoCommit = previousAutoCommit
                }
            }

            updateRunStatus(
                connection,
                runId,
                PipelineRunStatus.COMPLETED,
                stepsCompleted,
                totalInputRows,
                totalOutputRows,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
            )
            logger.info("Pipeline completed for study {}: {} input rows, {} output rows", studyId, totalInputRows, totalOutputRows)
        } catch (e: Exception) {
            logger.error("Pipeline failed for study {}", studyId, e)
            updateRunStatus(
                connection,
                runId,
                PipelineRunStatus.FAILED,
                stepsCompleted,
                totalInputRows,
                totalOutputRows,
                OffsetDateTime.now(ZoneOffset.UTC),
                e.message?.take(4_096) ?: e.javaClass.simpleName,
            )
            job.updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
            job.completedAt = job.updatedAt
            job.status = JobStatus.CANCELED
            return emptyList()
        }

        job.updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
        job.completedAt = job.updatedAt
        job.status = JobStatus.FINISHED

        return listOf(
            AuditableEvent(
                AclKey(studyId),
                job.securablePrincipalId,
                job.principal,
                eventType = AuditEventType.TRIGGER_PIPELINE,
                data = mapOf("config" to config, "inputRows" to totalInputRows, "outputRows" to totalOutputRows),
                study = studyId
            )
        )
    }

    private fun getRunIdForJob(connection: Connection, jobId: UUID): UUID {
        connection.prepareStatement("SELECT run_id FROM ${PIPELINE_RUNS.name} WHERE job_id = ?").use { ps ->
            ps.setObject(1, jobId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "No pipeline run is mapped to job $jobId" }
                val runId = rs.getObject("run_id", UUID::class.java)
                check(!rs.next()) { "Multiple pipeline runs are mapped to job $jobId" }
                return runId
            }
        }
    }

    // reason: parameters map 1:1 to the pipeline_runs UPDATE columns; bundling into an object
    // would only obscure the direct column binding of this private status writer
    @Suppress("LongParameterList")
    private fun updateRunStatus(
        connection: Connection,
        runId: UUID,
        status: PipelineRunStatus,
        stepsCompleted: Int,
        inputRows: Long,
        outputRows: Long,
        completedAt: OffsetDateTime,
        errorMessage: String?,
    ) {
        connection.prepareStatement(UPDATE_PIPELINE_RUN_SQL).use { ps ->
            ps.setString(1, status.name)
            ps.setInt(2, stepsCompleted)
            ps.setLong(3, inputRows)
            ps.setLong(4, outputRows)
            ps.setObject(5, completedAt)
            ps.setString(6, errorMessage)
            ps.setObject(7, runId)
            check(ps.executeUpdate() == 1) { "Pipeline run $runId was not updated" }
        }
    }

    override fun accepts(): Class<PipelineJobDefinition> = PipelineJobDefinition::class.java
}
