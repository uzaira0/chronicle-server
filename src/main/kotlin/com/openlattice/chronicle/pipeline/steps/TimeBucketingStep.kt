package com.openlattice.chronicle.pipeline.steps

import com.openlattice.chronicle.pipeline.PipelineStep
import com.openlattice.chronicle.pipeline.PipelineJobRunner
import com.openlattice.chronicle.pipeline.PipelineStepType
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.*

public open class TimeBucketingStep : PipelineStepExecutor {
    override val stepType: PipelineStepType = PipelineStepType.TIME_BUCKETING

    internal companion object {
        private val logger = LoggerFactory.getLogger(TimeBucketingStep::class.java)

        private val TIME_BUCKET_SQL = """
            INSERT INTO %s (study_id, participant_id, event_type, sample_date, duration_seconds)
            SELECT
                study_id,
                participant_id,
                event_type,
                date_trunc('hour', sample_date) + (EXTRACT(MINUTE FROM sample_date)::int / %d * %d) * interval '1 minute' AS sample_date,
                SUM(duration_seconds) AS duration_seconds
            FROM %s
            WHERE study_id = ?
            GROUP BY study_id, participant_id, event_type,
                date_trunc('hour', sample_date) + (EXTRACT(MINUTE FROM sample_date)::int / %d * %d) * interval '1 minute'
            ON CONFLICT DO NOTHING
        """.trimIndent()
    }

    override fun execute(
        connection: Connection,
        studyId: UUID,
        step: PipelineStep,
        outputTable: String,
    ): StepResult {
        val bucketMinutes = step.params["bucketMinutes"]?.toIntOrNull() ?: 60
        require(PipelineJobRunner.isValidBucketMinutes(bucketMinutes)) {
            "Pipeline bucketMinutes must be a positive divisor of 60"
        }
        val sourceTable = step.params["sourceTable"] ?: outputTable
        val inputCount = countRows(connection, sourceTable, studyId)

        val outputCount = connection.prepareStatement(
            String.format(TIME_BUCKET_SQL, outputTable, bucketMinutes, bucketMinutes, sourceTable, bucketMinutes, bucketMinutes)
        ).use { ps ->
            ps.setObject(1, studyId)
            ps.executeUpdate().toLong()
        }

        logger.info("TimeBucketing step ({}min) for study {}: {} input, {} output", bucketMinutes, studyId, inputCount, outputCount)
        return StepResult(inputRows = inputCount, outputRows = outputCount)
    }
}
