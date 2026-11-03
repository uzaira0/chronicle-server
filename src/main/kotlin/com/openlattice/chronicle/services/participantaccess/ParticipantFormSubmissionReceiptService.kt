package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.storage.withTransaction
import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

/** A submission that differs from the committed one under the same receipt: a 409 the page explains, never a retry. */
public class ParticipantSubmissionConflictException(message: String) : ResponseStatusException(HttpStatus.CONFLICT, message)

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
        receiptScope: SubmissionReceiptScope,
        payload: Any,
        action: (Connection) -> Unit,
    ): IdempotentSubmissionResult<Unit> {
        val result = execute(receiptScope, payload) { connection ->
            action(connection)
            null
        }
        return IdempotentSubmissionResult(Unit, result.replayed)
    }

    public fun executeWithSubmissionId(
        receiptScope: SubmissionReceiptScope,
        payload: Any,
        action: (Connection) -> UUID,
    ): IdempotentSubmissionResult<UUID> {
        val result = execute(receiptScope, payload, action)
        return IdempotentSubmissionResult(
            result.value ?: throw ParticipantSubmissionConflictException("Completed receipt has no submission result"),
            result.replayed,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(
        receiptScope: SubmissionReceiptScope,
        payload: Any,
        action: (Connection) -> UUID?,
    ): IdempotentSubmissionResult<UUID?> {
        val scope = receiptScope.participant
        val formKind = receiptScope.formKind
        require(scope.formKind == ParticipantFormKind.PORTAL || scope.formKind == formKind) {
            "Participant form scope does not permit this submission"
        }
        require(receiptScope.resourceKey.length in 1..200) { "resourceKey must be between 1 and 200 characters" }
        val requestHash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(payload))
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction {
                try {
                    DeletionStudyFence.shared(connection, scope.studyId)
                    val result = PinnedPlatformConnection.committing(storageResolver.getPlatformStorage(), connection) {
                        val receipts = ParticipantFormReceiptRepository(connection)
                        val receiptId = receipts.reserve(receiptScope, requestHash)
                        // A committed submission replays whatever the status is now; only a new one passes the gate.
                        if (receiptId == null) receipts.replay(receiptScope, requestHash) else {
                            if (!ParticipantCollectionGate(connection).admitsForm(formKind, scope.studyId, scope.participantId)) {
                                throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
                            }
                            val submissionId = action(connection)
                            receipts.complete(receiptId, submissionId)
                            IdempotentSubmissionResult(submissionId, replayed = false)
                        }
                    }
                    ChronicleMetrics.participantFormSubmissionTotal
                        .labels(formKind.name, if (result.replayed) "replayed" else "committed").inc()
                    result
                } catch (exception: Exception) {
                    ChronicleMetrics.participantFormSubmissionTotal.labels(formKind.name, "failed").inc()
                    throw exception
                }
            }
        }
    }
}
