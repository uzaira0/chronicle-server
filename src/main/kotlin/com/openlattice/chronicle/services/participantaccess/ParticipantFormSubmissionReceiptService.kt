package com.openlattice.chronicle.services.participantaccess

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@ResponseStatus(HttpStatus.CONFLICT)
public class ParticipantSubmissionConflictException(message: String) : RuntimeException(message)

public data class IdempotentSubmissionResult<T>(
    val value: T,
    val replayed: Boolean,
)

/**
 * Atomically stores a participant submission and its idempotency receipt on one JDBC
 * transaction. A concurrent duplicate blocks on the unique key and then replays the committed
 * result; a failed/crashed transaction rolls both the receipt and the data back.
 */
public class ParticipantFormSubmissionReceiptService(
    private val storageResolver: StorageResolver,
    private val objectMapper: ObjectMapper,
) {
    public fun executeWithoutResult(
        scope: ParticipantFormAccessScope,
        formKind: ParticipantFormKind,
        resourceKey: String,
        idempotencyKey: UUID,
        payload: Any,
        action: (Connection) -> Unit,
    ): IdempotentSubmissionResult<Unit> {
        val result = execute(scope, formKind, resourceKey, idempotencyKey, payload) { connection ->
            action(connection)
            null
        }
        return IdempotentSubmissionResult(Unit, result.replayed)
    }

    public fun executeWithSubmissionId(
        scope: ParticipantFormAccessScope,
        formKind: ParticipantFormKind,
        resourceKey: String,
        idempotencyKey: UUID,
        payload: Any,
        action: (Connection) -> UUID,
    ): IdempotentSubmissionResult<UUID> {
        val result = execute(scope, formKind, resourceKey, idempotencyKey, payload, action)
        return IdempotentSubmissionResult(
            result.value ?: throw ParticipantSubmissionConflictException("Completed receipt has no submission result"),
            result.replayed,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(
        scope: ParticipantFormAccessScope,
        formKind: ParticipantFormKind,
        resourceKey: String,
        idempotencyKey: UUID,
        payload: Any,
        action: (Connection) -> UUID?,
    ): IdempotentSubmissionResult<UUID?> {
        require(scope.formKind == ParticipantFormKind.PORTAL || scope.formKind == formKind) {
            "Participant form scope does not permit this submission"
        }
        require(resourceKey.length in 1..200) { "resourceKey must be between 1 and 200 characters" }
        val requestHash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(payload))
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                val receiptId = UUID.randomUUID()
                val inserted = connection.prepareStatement(
                    """
                    INSERT INTO participant_form_submission_receipts
                        (receipt_id, access_code_id, study_id, participant_id, form_kind,
                         resource_key, idempotency_key, request_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING')
                    ON CONFLICT (access_code_id, form_kind, resource_key, idempotency_key) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, receiptId)
                    statement.setObject(2, scope.accessCodeId)
                    statement.setObject(3, scope.studyId)
                    statement.setString(4, scope.participantId)
                    statement.setString(5, formKind.name)
                    statement.setString(6, resourceKey)
                    statement.setObject(7, idempotencyKey)
                    statement.setBytes(8, requestHash)
                    statement.executeUpdate() == 1
                }

                val result = if (inserted) {
                    val submissionId = action(connection)
                    connection.prepareStatement(
                        """
                        UPDATE participant_form_submission_receipts
                        SET status = 'COMPLETED', submission_id = ?, completed_at = now()
                        WHERE receipt_id = ? AND status = 'PROCESSING'
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, submissionId)
                        statement.setObject(2, receiptId)
                        check(statement.executeUpdate() == 1) { "Participant receipt completion failed" }
                    }
                    IdempotentSubmissionResult(submissionId, replayed = false)
                } else {
                    loadCommittedReceipt(connection, scope, formKind, resourceKey, idempotencyKey, requestHash)
                }
                connection.commit()
                ChronicleMetrics.participantFormSubmissionTotal
                    .labels(formKind.name, if (result.replayed) "replayed" else "committed")
                    .inc()
                result
            } catch (exception: Exception) {
                connection.rollback()
                ChronicleMetrics.participantFormSubmissionTotal.labels(formKind.name, "failed").inc()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun loadCommittedReceipt(
        connection: Connection,
        scope: ParticipantFormAccessScope,
        formKind: ParticipantFormKind,
        resourceKey: String,
        idempotencyKey: UUID,
        requestHash: ByteArray,
    ): IdempotentSubmissionResult<UUID?> {
        return connection.prepareStatement(
            """
            SELECT request_hash, status, submission_id
            FROM participant_form_submission_receipts
            WHERE access_code_id = ? AND form_kind = ? AND resource_key = ? AND idempotency_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, scope.accessCodeId)
            statement.setString(2, formKind.name)
            statement.setString(3, resourceKey)
            statement.setObject(4, idempotencyKey)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Conflicting participant receipt disappeared" }
                if (!MessageDigest.isEqual(requestHash, resultSet.getBytes("request_hash"))) {
                    throw ParticipantSubmissionConflictException("Idempotency key was reused with a different payload")
                }
                if (resultSet.getString("status") != "COMPLETED") {
                    throw ParticipantSubmissionConflictException("Matching participant submission is still processing")
                }
                IdempotentSubmissionResult(
                    resultSet.getObject("submission_id", UUID::class.java),
                    replayed = true,
                )
            }
        }
    }
}
