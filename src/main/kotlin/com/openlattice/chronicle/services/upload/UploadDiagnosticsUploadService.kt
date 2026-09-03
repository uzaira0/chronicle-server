package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/** Durable, idempotent storage for redacted Android upload-failure aggregates. */
public open class UploadDiagnosticsUploadService(
    private val storageResolver: StorageResolver,
) {
    internal companion object {
        public const val TABLE: String = "upload_diagnostics"
        private val logger = LoggerFactory.getLogger(UploadDiagnosticsUploadService::class.java)
        private const val RETENTION_DAYS = 30
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
        private val DELETE_EXPIRED_SQL = """
            DELETE FROM $TABLE
            WHERE study_id = ?
              AND participant_id = ?
              AND (
                  last_occurred_at < now() - INTERVAL '$RETENTION_DAYS days'
                  OR uploaded_at < now() - INTERVAL '$RETENTION_DAYS days'
              )
        """.trimIndent()
        private val DELETE_ALL_EXPIRED_SQL = """
            DELETE FROM $TABLE
            WHERE last_occurred_at < now() - INTERVAL '$RETENTION_DAYS days'
               OR uploaded_at < now() - INTERVAL '$RETENTION_DAYS days'
        """.trimIndent()
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
            connection.autoCommit = false
            try {
                deleteExpired(connection, studyId, participantId)
                persistBatch(connection, studyId, participantId, deviceId, data)
                connection.commit()
            } catch (error: SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        return data.map { it.id }
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

    /** Enforces the server-side 30-day diagnostic retention limit even after a device goes quiet. */
    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    @Suppress("TooGenericExceptionCaught")
    public open fun cleanupExpired(): Int = try {
        RLSRequestContext.withSystemContext {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(DELETE_ALL_EXPIRED_SQL).use { it.executeUpdate() }
            }
        }.also { deleted ->
            if (deleted > 0) logger.info("Deleted {} expired upload diagnostic aggregates", deleted)
        }
    } catch (error: Exception) {
        logger.error("Failed to delete expired upload diagnostic aggregates", error)
        0
    }

    private fun deleteExpired(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement(DELETE_EXPIRED_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeUpdate()
        }
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
        statement.setString(12, event.errorType)
    }
}
