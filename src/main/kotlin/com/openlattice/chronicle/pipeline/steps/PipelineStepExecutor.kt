package com.openlattice.chronicle.pipeline.steps

import com.openlattice.chronicle.pipeline.PipelineStep
import java.sql.Connection
import java.util.*

public interface PipelineStepExecutor {
    public val stepType: com.openlattice.chronicle.pipeline.PipelineStepType

    public fun execute(
        connection: Connection,
        studyId: UUID,
        step: PipelineStep,
        outputTable: String,
    ): StepResult
}

public data class StepResult(
    val inputRows: Long = 0,
    val outputRows: Long = 0,
)

public fun countRows(connection: Connection, tableName: String, studyId: UUID): Long {
    return connection.prepareStatement(
        "SELECT COUNT(*) FROM $tableName WHERE study_id = ?"
    ).use { ps ->
        ps.setObject(1, studyId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getLong(1) else 0L
    }
}
