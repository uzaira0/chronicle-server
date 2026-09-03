package com.openlattice.chronicle.pipeline.steps

import com.openlattice.chronicle.pipeline.PipelineStep
import com.openlattice.chronicle.pipeline.PipelineStepType
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.*

public open class AggregationStep : PipelineStepExecutor {
    override val stepType: PipelineStepType = PipelineStepType.AGGREGATION

    internal companion object {
        private val logger = LoggerFactory.getLogger(AggregationStep::class.java)

        private val AGGREGATE_SQL = """
            INSERT INTO %s (study_id, participant_id, event_type, sample_date, duration_seconds)
            SELECT
                study_id,
                participant_id,
                event_type,
                sample_date,
                SUM(duration_seconds) AS duration_seconds
            FROM %s
            WHERE study_id = ?
            GROUP BY study_id, participant_id, event_type, sample_date
            ON CONFLICT DO NOTHING
        """.trimIndent()
    }

    override fun execute(
        connection: Connection,
        studyId: UUID,
        step: PipelineStep,
        outputTable: String,
    ): StepResult {
        val sourceTable = step.params["sourceTable"] ?: outputTable
        val inputCount = countRows(connection, sourceTable, studyId)

        val outputCount = connection.prepareStatement(
            String.format(AGGREGATE_SQL, outputTable, sourceTable)
        ).use { ps ->
            ps.setObject(1, studyId)
            ps.executeUpdate().toLong()
        }

        logger.info("Aggregation step for study {}: {} input rows, {} output rows", studyId, inputCount, outputCount)
        return StepResult(inputRows = inputCount, outputRows = outputCount)
    }
}
