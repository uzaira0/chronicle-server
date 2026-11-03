package com.openlattice.chronicle.services.delete

import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A requested erasure; QUARANTINED means requested at [requestedAt], data blocked, nothing erased yet.
 * [erasable] is the server's answer to whether an owner may run it now, so clients never restate the rule.
 */
public data class DataDeletionOperation(
    val operationId: UUID,
    val studyId: UUID,
    val participantId: String?,
    val mode: DataDeletionMode,
    val status: String,
    val requestedAt: OffsetDateTime,
    val erasable: Boolean,
)

/** Reads the same operation projection for researcher controls and the deletion worker. */
internal class DeletionOperationRepository(private val connection: Connection) {
    companion object {
        /** A started erasure whose worker died; it resumes from where it stopped. */
        const val INTERRUPTED_ERASURE_SQL =
            "(operation.status IN ('ERASING', 'VERIFYING') AND operation.worker_lease_expires_at <= now())"
        /** The one rule for which operations an owner may erase now. */
        const val ERASABLE_SQL = "(operation.status IN ('QUARANTINED', 'FAILED') OR $INTERRUPTED_ERASURE_SQL)"
        const val COLUMNS = "operation.operation_id, operation.study_id, operation.participant_id, operation.mode, " +
            "operation.status, operation.created_at, $ERASABLE_SQL AS erasable"
        private const val SELECT_OPERATION = "SELECT $COLUMNS FROM data_deletion_operations operation"
        private val LIST_OPERATIONS =
            "$SELECT_OPERATION WHERE operation.study_id = ? ORDER BY operation.created_at DESC, operation.operation_id LIMIT ? OFFSET ?"
        private val GET_OPERATION = "$SELECT_OPERATION WHERE operation.operation_id = ?"
    }

    fun list(studyId: UUID, limit: Int, offset: Int): List<DataDeletionOperation> =
        connection.prepareStatement(LIST_OPERATIONS).use { statement ->
            statement.setObject(1, studyId)
            statement.setInt(2, limit)
            statement.setInt(3, offset)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }

    fun get(operationId: UUID): DataDeletionOperation =
        connection.prepareStatement(GET_OPERATION).use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw NoSuchElementException("Deletion operation not found")
                read(rows)
            }
        }

    fun read(rows: ResultSet): DataDeletionOperation = DataDeletionOperation(
        operationId = rows.getObject("operation_id", UUID::class.java),
        studyId = rows.getObject("study_id", UUID::class.java),
        participantId = rows.getString("participant_id"),
        mode = DataDeletionMode.valueOf(rows.getString("mode")),
        status = rows.getString("status"),
        requestedAt = rows.getObject("created_at", OffsetDateTime::class.java),
        erasable = rows.getBoolean("erasable"),
    )
}
