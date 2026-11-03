package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

public data class SubmissionReceiptScope(
    val participant: ParticipantFormAccessScope,
    val formKind: ParticipantFormKind,
    val resourceKey: String,
    val idempotencyKey: UUID,
) {
    internal val receiptResourceKey: String
        get() = if (formKind == ParticipantFormKind.QUESTIONNAIRE) {
            "$resourceKey:${requireNotNull(participant.logicalDate) { "Questionnaire occurrence date is required" }}"
        } else resourceKey
}

private enum class SubmissionReceiptState { PROCESSING, COMPLETED }

/** Receipt identity is the completed participant resource, independent of browser nonce and issuing link. */
internal class ParticipantFormReceiptRepository(private val connection: Connection) {
    fun reserve(scope: SubmissionReceiptScope, requestHash: ByteArray): UUID? {
        val receiptId = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO participant_form_submission_receipts
                (receipt_id, access_code_id, study_id, participant_id, form_kind,
                 resource_key, idempotency_key, request_hash, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (study_id, participant_id, form_kind, resource_key) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, receiptId)
            statement.setObject(2, scope.participant.accessCodeId)
            statement.setObject(3, scope.participant.studyId)
            statement.setString(4, scope.participant.participantId)
            statement.setString(5, scope.formKind.name)
            statement.setString(6, scope.receiptResourceKey)
            statement.setObject(7, scope.idempotencyKey)
            statement.setBytes(8, requestHash)
            statement.setString(9, SubmissionReceiptState.PROCESSING.name)
            statement.executeUpdate() == 1
        }
        return receiptId.takeIf { inserted }
    }

    fun complete(receiptId: UUID, submissionId: UUID?) {
        connection.prepareStatement(
            """
            UPDATE participant_form_submission_receipts
            SET status = ?, submission_id = ?, completed_at = now()
            WHERE receipt_id = ? AND status = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, SubmissionReceiptState.COMPLETED.name)
            statement.setObject(2, submissionId)
            statement.setObject(3, receiptId)
            statement.setString(4, SubmissionReceiptState.PROCESSING.name)
            check(statement.executeUpdate() == 1) { "Participant receipt completion failed" }
        }
    }

    fun replay(scope: SubmissionReceiptScope, requestHash: ByteArray): IdempotentSubmissionResult<UUID?> =
        connection.prepareStatement(
            """
            SELECT request_hash, status, submission_id
            FROM participant_form_submission_receipts
            WHERE study_id = ? AND participant_id = ? AND form_kind = ? AND resource_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, scope.participant.studyId)
            statement.setString(2, scope.participant.participantId)
            statement.setString(3, scope.formKind.name)
            statement.setString(4, scope.receiptResourceKey)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Conflicting participant receipt disappeared" }
                if (!MessageDigest.isEqual(requestHash, resultSet.getBytes("request_hash"))) {
                    throw ParticipantSubmissionConflictException("This form resource already has a different completed submission")
                }
                if (resultSet.getString("status") != SubmissionReceiptState.COMPLETED.name) {
                    throw ParticipantSubmissionConflictException("Matching participant submission is still processing")
                }
                IdempotentSubmissionResult(resultSet.getObject("submission_id", UUID::class.java), replayed = true)
            }
        }
}
