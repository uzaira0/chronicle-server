package com.openlattice.chronicle.pipeline.steps

import com.openlattice.chronicle.pipeline.PipelineStep
import com.openlattice.chronicle.pipeline.PipelineStepType
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.*

public open class DeidentificationStep : PipelineStepExecutor {
    override val stepType: PipelineStepType = PipelineStepType.DEIDENTIFICATION

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeidentificationStep::class.java)

        private val DEIDENTIFY_SQL = """
            INSERT INTO %s (study_id, participant_id, event_type, sample_date, duration_seconds)
            SELECT
                study_id,
                encode(sha256(participant_id::bytea), 'hex') AS participant_id,
                COALESCE(app_package_name, interaction_type, 'unknown') AS event_type,
                date_trunc('day', event_timestamp) AS sample_date,
                COALESCE(EXTRACT(EPOCH FROM (end_timestamp - event_timestamp)), 0)::bigint AS duration_seconds
            FROM chronicle_usage_events
            WHERE study_id = ?
            ON CONFLICT DO NOTHING
        """.trimIndent()
    }

    override fun execute(
        connection: Connection,
        studyId: UUID,
        step: PipelineStep,
        outputTable: String,
    ): StepResult {
        val inputCount = countRows(connection, "chronicle_usage_events", studyId)

        val outputCount = connection.prepareStatement(
            String.format(DEIDENTIFY_SQL, outputTable)
        ).use { ps ->
            ps.setObject(1, studyId)
            ps.executeUpdate().toLong()
        }

        logger.info("Deidentification step for study {}: {} input rows, {} output rows", studyId, inputCount, outputCount)
        return StepResult(inputRows = inputCount, outputRows = outputCount)
    }
}
