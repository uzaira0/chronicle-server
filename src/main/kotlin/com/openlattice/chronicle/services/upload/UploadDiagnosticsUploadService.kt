package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.ParticipantCollectionGate

import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.DeletionStudyFence
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

/** Durable, idempotent storage for redacted Android diagnostic occurrences, filtered one instant at a time. */
public open class UploadDiagnosticsUploadService(
    private val storageResolver: StorageResolver,
) {
    internal companion object {
        public const val TABLE: String = "upload_diagnostics"
        /** Ten times the phone's diagnostics batch (500 rows). */
        const val MAX_EVENTS: Int = 5_000
        private val UPSERT_SQL = """
            INSERT INTO $TABLE (
                study_id, participant_id, random_device_id, event_id, diagnostic_day,
                module_family, issue_code, occurrence_count, first_occurred_at,
                last_occurred_at, http_status, error_type, uploaded_at
            ) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
            WHERE NOT EXISTS (
                SELECT 1 FROM upload_diagnostic_erasures
                WHERE study_id = ?
                  AND participant_block_token = chronicle_participant_block_token(?::uuid, ?::text)
                  AND event_id = ?::uuid
            )
            ON CONFLICT (study_id, participant_id, random_device_id, event_id)
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
            WITH subject AS (SELECT ?::uuid AS study_id, chronicle_participant_block_token(?::uuid, ?::text) AS token)
            SELECT max(cutoff) FROM (
                SELECT COALESCE(operation.started_at, operation.completed_at) AS cutoff
                FROM data_deletion_operations operation JOIN subject USING (study_id)
                WHERE operation.participant_block_token = subject.token AND operation.status = 'COMPLETED'
                UNION ALL
                SELECT purge.cutoff FROM participant_purge_cutoffs purge JOIN subject USING (study_id)
                WHERE purge.participant_block_token = subject.token
            ) erasures
        """.trimIndent()

        /** Exception class names only (binary names allow `$` for nested classes). */
        private val ERROR_TYPE = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    }

    /**
     * Accepted IDs are exactly the events this commit stored; the rest were refused by the consent window or an
     * erasure and are discarded. Nothing is held: a refused occurrence stays refused on retry.
     */
    public fun upload(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        data: List<AndroidUploadDiagnosticEvent>,
    ): UploadReceipt {
        val submitted = data.mapTo(linkedSetOf()) { UUID.fromString(it.id) }
        if (data.isEmpty()) return UploadReceipt(emptySet(), emptySet(), 0)
        require(data.size <= MAX_EVENTS) { "Upload diagnostics batch too large" }
        require(data.map { it.id }.distinct().size == data.size) {
            "Upload diagnostics batch contains duplicate event IDs"
        }
        val stored = storageResolver.getPlatformStorage().connection.use { connection ->
            // Inside withCollectionUploadRecheck this is the pinned guard connection with
            // autocommit already off; forcing it back on here made the guard's own commit()
            // throw and every non-empty batch answer 500. Restore what we found instead.
            connection.withTransaction {
                // Serialize the cutoff read and insert against completion of a study purge.
                DeletionStudyFence.shared(connection, studyId)
                val cutoff = erasureCutoff(connection, studyId, participantId)
                // Keep the cutoff for unseen events; the upsert also fences erased IDs regardless of device time.
                ParticipantCollectionGate(connection).requireCollectionAvailable(studyId, participantId)
                val retained = if (cutoff == null) data else data.filter { !it.firstOccurredAt.isBefore(cutoff) }
                // Diagnostics are not research data: they never refresh the dashboard's last upload.
                val persisted = if (retained.isEmpty()) emptyList() else persistBatch(connection, studyId, participantId, randomDeviceId, retained)
                connection.commit()
                persisted
            }
        }
        val accepted = stored.mapTo(linkedSetOf()) { UUID.fromString(it.id) }
        return UploadReceipt(accepted, submitted - accepted, 0)
    }

    private fun erasureCutoff(connection: Connection, studyId: UUID, participantId: String): OffsetDateTime? =
        connection.prepareStatement(ERASURE_CUTOFF_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, studyId.toString())
            statement.setString(3, participantId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, OffsetDateTime::class.java) else null }
        }

    /** The events a row now holds; an erased event ID inserts nothing. */
    private fun persistBatch(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        data: List<AndroidUploadDiagnosticEvent>,
    ): List<AndroidUploadDiagnosticEvent> =
        connection.prepareStatement(UPSERT_SQL).use { statement ->
            data.forEach { event -> addBatchEntry(statement, studyId, participantId, randomDeviceId, event) }
            val counts = statement.executeBatch()
            data.filterIndexed { index, _ -> counts[index] != 0 }
        }

    private fun addBatchEntry(
        statement: PreparedStatement,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        event: AndroidUploadDiagnosticEvent,
    ) {
        bind(statement, studyId, participantId, randomDeviceId, event)
        statement.addBatch()
    }

    private fun bind(
        statement: PreparedStatement,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        event: AndroidUploadDiagnosticEvent,
    ) {
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, randomDeviceId)
        statement.setString(4, event.id)
        statement.setObject(5, event.day)
        statement.setString(6, event.moduleFamily)
        statement.setString(7, event.recognizedIssueCode().name)
        statement.setInt(8, event.count)
        statement.setObject(9, event.firstOccurredAt)
        statement.setObject(10, event.lastOccurredAt)
        val httpStatus = event.httpStatus
        if (httpStatus == null) statement.setNull(11, Types.INTEGER) else statement.setInt(11, httpStatus)
        statement.setString(12, event.errorType?.takeIf { ERROR_TYPE.matches(it) })
        statement.setObject(13, studyId)
        statement.setString(14, studyId.toString())
        statement.setString(15, participantId)
        statement.setString(16, event.id)
    }
}
