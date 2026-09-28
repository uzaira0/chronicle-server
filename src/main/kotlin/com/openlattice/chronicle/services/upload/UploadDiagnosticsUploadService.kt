package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.storage.StorageResolver
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

/** Durable, idempotent storage for redacted Android upload-failure aggregates. */
public open class UploadDiagnosticsUploadService(
    private val storageResolver: StorageResolver,
) {
    internal companion object {
        public const val TABLE: String = "upload_diagnostics"
        private val UPSERT_SQL = """
            INSERT INTO $TABLE (
                study_id, participant_id, device_id, event_id, diagnostic_day,
                module_family, issue_code, occurrence_count, first_occurred_at,
                last_occurred_at, http_status, error_type, uploaded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, device_id, event_id)
            DO UPDATE SET
                occurrence_count = GREATEST($TABLE.occurrence_count, EXCLUDED.occurrence_count),
                first_occurred_at = LEAST($TABLE.first_occurred_at, EXCLUDED.first_occurred_at),
                last_occurred_at = GREATEST($TABLE.last_occurred_at, EXCLUDED.last_occurred_at),
                http_status = EXCLUDED.http_status,
                error_type = EXCLUDED.error_type
        """.trimIndent()

        /**
         * Start of the latest completed erasure for this participant; older history stays erased.
         * Completion clears participant_id, so the operation is matched by its block token.
         */
        private val ERASURE_CUTOFF_SQL = """
            SELECT max(COALESCE(started_at, completed_at)) FROM data_deletion_operations
            WHERE study_id = ?
              AND participant_block_token = md5(?::text || ':' || ?)
              AND status = 'COMPLETED'
        """.trimIndent()

        /** Exception class names only (binary names allow `$` for nested classes). */
        private val ERROR_TYPE = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    }

    /** Returns every accepted client event ID; the client deletes only acknowledged rows. */
    public fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<AndroidUploadDiagnosticEvent>,
    ): List<String> {
        if (data.isEmpty()) return emptyList()
        require(data.size <= 500) { "Upload diagnostics batch too large" }
        require(data.map { it.id }.distinct().size == data.size) {
            "Upload diagnostics batch contains duplicate event IDs"
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            // Inside withCollectionHaltRecheck this is the pinned guard connection with
            // autocommit already off; forcing it back on here made the guard's own commit()
            // throw and every non-empty batch answer 500. Restore what we found instead.
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val cutoff = erasureCutoff(connection, studyId, participantId)
                // Events that began before a completed erasure are acknowledged but not stored, so a
                // device replaying delivered history cannot resurrect what the purge removed.
                val retained = if (cutoff == null) data else data.filter { !it.firstOccurredAt.isBefore(cutoff) }
                if (retained.isNotEmpty()) persistBatch(connection, studyId, participantId, deviceId, retained)
                connection.commit()
            } catch (error: SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
        return data.map { it.id }
    }

    private fun erasureCutoff(connection: Connection, studyId: UUID, participantId: String): OffsetDateTime? =
        connection.prepareStatement(ERASURE_CUTOFF_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, studyId.toString())
            statement.setString(3, participantId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, OffsetDateTime::class.java) else null }
        }

    private fun persistBatch(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<AndroidUploadDiagnosticEvent>,
    ) {
        connection.prepareStatement(UPSERT_SQL).use { statement ->
            data.forEach { event -> addBatchEntry(statement, studyId, participantId, deviceId, event) }
            statement.executeBatch()
        }
    }

    private fun addBatchEntry(
        statement: PreparedStatement,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        event: AndroidUploadDiagnosticEvent,
    ) {
        bind(statement, studyId, participantId, deviceId, event)
        statement.addBatch()
    }

    private fun bind(
        statement: PreparedStatement,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        event: AndroidUploadDiagnosticEvent,
    ) {
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, deviceId)
        statement.setString(4, event.id)
        statement.setObject(5, event.day)
        statement.setString(6, event.moduleFamily)
        statement.setString(7, event.issueCode)
        statement.setInt(8, event.count)
        statement.setObject(9, event.firstOccurredAt)
        statement.setObject(10, event.lastOccurredAt)
        val httpStatus = event.httpStatus
        if (httpStatus == null) statement.setNull(11, Types.INTEGER) else statement.setInt(11, httpStatus)
        statement.setString(12, event.errorType?.takeIf { ERROR_TYPE.matches(it) })
    }
}
