package com.openlattice.chronicle.services.delete

import com.fasterxml.jackson.core.type.TypeReference
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SystemUser
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.mapstores.stats.TransactionOnlyParticipantStatsCache
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.export.ExportFileWriter
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

public enum class DataDeletionMode {
    COLLECTED_DATA_PURGE,
    WITHDRAW_AND_ERASE,
    STUDY_ERASURE,
}

public data class DataDeletionOperation(
    val operationId: UUID,
    val studyId: UUID,
    val participantId: String?,
    val mode: DataDeletionMode,
    val status: String,
    val quarantineUntil: OffsetDateTime?,
)

internal data class ParticipantQuarantineDecision<T>(
    val result: T,
    val createOperation: Boolean,
)

internal data class ParticipantQuarantineTransactionResult<T>(
    val result: T,
    val operationId: UUID?,
)

internal data class RestoredDeletionOperation(
    val operationId: UUID,
    val studyId: UUID,
    val participantRef: String?,
    val participantId: String?,
    val mode: DataDeletionMode,
    val status: String,
    val requestedBy: String,
    val idempotencyKey: UUID,
    val quarantineUntil: OffsetDateTime?,
    val cancelledBy: String?,
    val cancelledAt: OffsetDateTime?,
    val hasSourceTombstone: Boolean,
)

internal data class RestoredRetentionHold(
    val holdId: UUID,
    val operationId: UUID,
    val studyId: UUID,
    val reason: String,
    val createdBy: String,
    val createdAt: OffsetDateTime,
    val reviewAt: OffsetDateTime,
    val releasedBy: String?,
    val releasedAt: OffsetDateTime?,
    val releaseReason: String?,
)

private data class ParticipantQuarantineRequest(
    val studyId: UUID,
    val participantId: String,
    val participantRef: String,
    val mode: DataDeletionMode,
    val requestedBy: String,
    val idempotencyKey: UUID,
    val quarantineUntil: OffsetDateTime,
    val now: OffsetDateTime,
)

private data class ClaimedDeletion(
    val operation: DataDeletionOperation,
    val leaseToken: UUID,
)

private data class ClaimedDeletionAuditEvent(
    val eventId: UUID,
    val operationId: UUID,
    val studyId: UUID,
    val eventType: AuditEventType,
    val description: String,
    val data: Map<String, Any>,
    val eventTimestamp: OffsetDateTime,
    val leaseToken: UUID,
)

private class DeletionLeaseLostException(operationId: UUID) :
    IllegalStateException("Deletion worker lease was lost for operation $operationId")

internal class DeletionRetryStatePersistenceException(
    operationId: UUID,
    persistenceFailure: Exception,
    deletionFailure: Exception,
) : IllegalStateException(
    "Unable to persist retry state for deletion operation $operationId",
    persistenceFailure,
) {
    init {
        addSuppressed(deletionFailure)
    }
}

/**
 * Durable, resumable owner of Chronicle erasure.
 *
 * All participant assets come from [ChronicleDataAssetRegistry]. Queuing immediately
 * revokes form access and V50's restrictive RLS policy makes the subject's rows
 * inaccessible to the application role. Physical erasure starts only after seven days
 * and only when no explicit retention hold is active. Each asset is deleted and then
 * independently verified before a non-identifying proof tombstone is committed.
 */
public open class DataDeletionOrchestrator(
    private val storageResolver: StorageResolver,
    override val auditingManager: AuditingManager,
    private val participantStatsCache: ParticipantStatsCache,
    private val clock: Clock = Clock.systemUTC(),
) : AuditingComponent {
    internal constructor(
        storageResolver: StorageResolver,
        auditingManager: AuditingManager,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        storageResolver,
        auditingManager,
        TransactionOnlyParticipantStatsCache,
        clock,
    )

    internal companion object {
        private val logger = LoggerFactory.getLogger(DataDeletionOrchestrator::class.java)
        private val SQL_IDENTIFIER = Regex("^[a-z][a-z0-9_]*$")
        private const val REGISTRY_VERSION = 1
        private const val QUARANTINE_DAYS = 7L
        private const val MAX_PROCESS_BATCH = 25
        private const val DELETION_LEASE_MINUTES = 30L
        private const val MAX_AUDIT_PUBLISH_BATCH = 25
        private const val AUDIT_PUBLISH_LEASE_MINUTES = 5L
        private const val MAX_STUDY_ERASURE_GENERATIONS = 1_000
        private val AUDIT_DATA_TYPE = object : TypeReference<Map<String, Any>>() {}
        private val STUDY_ERASURE_EXCLUSIONS = setOf(
            "audit",
            "audit_buffer",
            "audit_logs",
            "study_settings_audit",
            "participant_collection_acknowledgment",
            "study_lifecycle_events",
            "data_deletion_operations",
            "data_deletion_steps",
            "retention_holds",
            "data_deletion_tombstones",
            "data_deletion_form_access_revocations",
            "data_deletion_audit_outbox",
        )
    }

    private val mapper = ObjectMappers.newJsonMapper()

    public open fun quarantineParticipant(
        studyId: UUID,
        participantId: String,
        mode: DataDeletionMode,
        requestedBy: String,
        idempotencyKey: UUID,
    ): UUID = requireNotNull(
        quarantineParticipantAtomically(
            studyId = studyId,
            participantId = participantId,
            mode = mode,
            requestedBy = requestedBy,
            idempotencyKey = idempotencyKey,
        ) { ParticipantQuarantineDecision(Unit, createOperation = true) }.operationId,
    ) { "Participant quarantine operation was not created" }

    /**
     * Commits caller-owned authorization/status changes and the participant deletion ledger
     * in one database transaction. The callback runs before operation creation and must use
     * only the supplied connection; a false decision commits no deletion operation.
     */
    internal open fun <T> quarantineParticipantAtomically(
        studyId: UUID,
        participantId: String,
        mode: DataDeletionMode,
        requestedBy: String,
        idempotencyKey: UUID,
        transaction: (Connection) -> ParticipantQuarantineDecision<T>,
    ): ParticipantQuarantineTransactionResult<T> {
        require(mode != DataDeletionMode.STUDY_ERASURE) { "Participant deletion cannot use STUDY_ERASURE" }
        require(participantId.isNotBlank()) { "participantId must not be blank" }
        storageResolver.requireDeletionStorageColocated(studyId)
        val now = now()
        val quarantineUntil = now.plusDays(QUARANTINE_DAYS)
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")

        var insertedNewOperation: Boolean? = null
        val result = participantStatsCache.quarantineParticipant(studyId, participantId) {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.autoCommit = false
                try {
                    acquireDeletionStudyLock(connection, studyId)
                    val decision = transaction(connection)
                    val quarantine = if (decision.createOperation) {
                        createParticipantQuarantine(
                            connection = connection,
                            request = ParticipantQuarantineRequest(
                                studyId = studyId,
                                participantId = participantId,
                                participantRef = participantRef,
                                mode = mode,
                                requestedBy = requestedBy,
                                idempotencyKey = idempotencyKey,
                                quarantineUntil = quarantineUntil,
                                now = now,
                            ),
                        ).also { insertedNewOperation = it.inserted }
                    } else {
                        null
                    }
                    connection.commit()
                    ParticipantQuarantineTransactionResult(decision.result, quarantine?.operationId)
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        }
        insertedNewOperation?.let { inserted ->
            ChronicleMetrics.dataDeletionOperationsTotal.labels(
                mode.name,
                if (inserted) "quarantined" else "idempotent_replay",
            ).inc()
        }
        return result
    }

    public open fun quarantineStudy(
        studyId: UUID,
        requestedBy: String,
        idempotencyKey: UUID,
        requestedDeleteAfter: OffsetDateTime? = null,
    ): UUID = quarantineStudyAtomically(
        studyId = studyId,
        requestedBy = requestedBy,
        idempotencyKey = idempotencyKey,
        requestedDeleteAfter = requestedDeleteAfter,
    ) { _, _, _ -> }

    /**
     * Commits the study quarantine and its caller-owned lifecycle changes together.
     *
     * The callback receives whether this request inserted the operation. It must use the
     * supplied connection and must not commit it. Any callback, audit, or commit failure
     * rolls the deletion ledger and lifecycle state back together.
     */
    public open fun quarantineStudyAtomically(
        studyId: UUID,
        requestedBy: String,
        idempotencyKey: UUID,
        requestedDeleteAfter: OffsetDateTime? = null,
        transaction: (Connection, UUID, Boolean) -> Unit,
    ): UUID {
        storageResolver.requireDeletionStorageColocated(studyId)
        val now = now()
        val quarantineUntil = maxOf(now.plusDays(QUARANTINE_DAYS), requestedDeleteAfter ?: now)
        val result = participantStatsCache.quarantineStudy(studyId) {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.autoCommit = false
                try {
                    acquireDeletionStudyLock(connection, studyId)
                    val quarantine = createStudyQuarantine(
                        connection = connection,
                        studyId = studyId,
                        requestedBy = requestedBy,
                        idempotencyKey = idempotencyKey,
                        quarantineUntil = quarantineUntil,
                        now = now,
                    )
                    transaction(connection, quarantine.operationId, quarantine.inserted)
                    connection.commit()
                    quarantine
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        }
        ChronicleMetrics.dataDeletionOperationsTotal.labels(
            DataDeletionMode.STUDY_ERASURE.name,
            if (result.inserted) "quarantined" else "idempotent_replay",
        ).inc()
        return result.operationId
    }

    private data class StudyQuarantineResult(
        val operationId: UUID,
        val inserted: Boolean,
    )

    /**
     * Re-arms an operation captured immediately before an older database backup was restored.
     * Physical erasure still belongs to this orchestrator and its canonical asset registry; the
     * restore workflow supplies only the durable operation identity and current hold state.
     */
    internal fun reconcileRestoredOperation(
        operation: RestoredDeletionOperation,
        holds: List<RestoredRetentionHold>,
    ): Boolean {
        require(holds.all { it.operationId == operation.operationId && it.studyId == operation.studyId }) {
            "Restore retention hold belongs to a different deletion operation"
        }
        require(operation.status != "PREVIEW") { "A restore checkpoint cannot contain an uncommitted deletion preview" }
        storageResolver.requireDeletionStorageColocated(operation.studyId)

        val reconcile = {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.autoCommit = false
                try {
                    acquireDeletionStudyLock(connection, operation.studyId)
                    validateRestoredOperationIdentity(connection, operation)
                    if (operation.status == "CANCELLED") {
                        reconcileRestoredCancellation(connection, operation, holds)
                    } else {
                        rearmRestoredOperation(connection, operation, holds)
                    }
                    connection.commit()
                    operation.hasSourceTombstone
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        }

        return if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
            participantStatsCache.quarantineStudy(operation.studyId, reconcile)
        } else {
            val participantId = requireNotNull(operation.participantId) {
                "Participant deletion checkpoint is missing participantId"
            }
            participantStatsCache.quarantineParticipant(operation.studyId, participantId, reconcile)
        }
    }

    /** A source tombstone is replayable only after the restored rows have been erased again. */
    internal fun processRestoredCompletedOperation(operationId: UUID) {
        RLSRequestContext.withDeletionWorkerContext {
            publishPendingDeletionAuditEvents()
            val claim = claimDueOperation(operationId)
                ?: error("Restored completed deletion operation is not claimable")
            eraseAndVerify(claim)
        }
        check(getOperation(operationId).status == "COMPLETED") {
            "Restored completed deletion operation did not reach COMPLETED"
        }
    }

    private fun validateRestoredOperationIdentity(
        connection: Connection,
        operation: RestoredDeletionOperation,
    ) {
        connection.prepareStatement(
            """
            SELECT operation_id, study_id, participant_id, mode, idempotency_key
            FROM data_deletion_operations
            WHERE operation_id = ? OR idempotency_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operation.operationId)
            statement.setObject(2, operation.idempotencyKey)
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    check(resultSet.getObject("operation_id", UUID::class.java) == operation.operationId) {
                        "Restore deletion idempotency key belongs to another operation"
                    }
                    check(resultSet.getObject("study_id", UUID::class.java) == operation.studyId) {
                        "Restore deletion operation belongs to another study"
                    }
                    check(resultSet.getString("participant_id") == operation.participantId) {
                        "Restore deletion operation belongs to another participant"
                    }
                    check(resultSet.getString("mode") == operation.mode.name) {
                        "Restore deletion operation has a conflicting mode"
                    }
                    check(resultSet.getObject("idempotency_key", UUID::class.java) == operation.idempotencyKey) {
                        "Restore deletion operation has a conflicting idempotency key"
                    }
                }
            }
        }
    }

    private fun reconcileRestoredCancellation(
        connection: Connection,
        operation: RestoredDeletionOperation,
        holds: List<RestoredRetentionHold>,
    ) {
        require(operation.mode == DataDeletionMode.STUDY_ERASURE) {
            "Only a study erasure can be cancelled"
        }
        val actor = requireNotNull(operation.cancelledBy).trim()
        require(actor.isNotEmpty()) { "Cancelled restore deletion is missing its actor" }
        val cancelledAt = requireNotNull(operation.cancelledAt) {
            "Cancelled restore deletion is missing its timestamp"
        }
        upsertRestoredOperation(connection, operation, "PREVIEW", operation.quarantineUntil)
        connection.prepareStatement(
            """
            UPDATE data_deletion_operations
            SET status = 'CANCELLED', cancelled_by = ?, cancelled_at = ?, updated_at = now()
            WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, actor)
            statement.setObject(2, cancelledAt)
            statement.setObject(3, operation.operationId)
            check(statement.executeUpdate() == 1)
        }
        restoreCancelledStudyFormAccess(connection, operation.studyId)
        deleteRestoredOperationDependents(connection, operation.operationId)
        restoreRetentionHolds(connection, holds)
    }

    private fun rearmRestoredOperation(
        connection: Connection,
        operation: RestoredDeletionOperation,
        holds: List<RestoredRetentionHold>,
    ) {
        deleteRestoredOperationDependents(connection, operation.operationId)
        val quarantineUntil = when {
            operation.hasSourceTombstone || operation.status in setOf("READY", "ERASING", "VERIFYING", "FAILED", "COMPLETED") ->
                now()
            else -> operation.quarantineUntil ?: now()
        }
        upsertRestoredOperation(connection, operation, "PREVIEW", quarantineUntil)

        if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
            discoverStudyTables(connection).forEachIndexed { ordinal, tableName ->
                insertStudyStep(
                    connection,
                    operation.operationId,
                    operation.studyId,
                    tableName,
                    ordinal,
                    countStudyRows(connection, tableName, operation.studyId),
                )
            }
            revokeStudyFormAccess(connection, operation.operationId, operation.studyId, now())
            revokeStudyExports(connection, operation.operationId, operation.studyId)
        } else {
            val participantId = requireNotNull(operation.participantId)
            ChronicleDataAssetRegistry.participantAssets.forEachIndexed { ordinal, asset ->
                insertStep(
                    connection,
                    operation.operationId,
                    operation.studyId,
                    asset,
                    ordinal,
                    countParticipantRows(connection, asset, operation.studyId, participantId),
                )
            }
            revokeParticipantFormAccess(connection, operation.studyId, participantId, now())
            revokeParticipantExports(connection, operation.operationId, operation.studyId, participantId)
        }
        activateQuarantine(connection, operation.operationId)
        restoreRetentionHolds(connection, holds)
        if (holds.any { it.releasedAt == null }) {
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'HELD', updated_at = now()
                WHERE operation_id = ? AND status = 'QUARANTINED'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operation.operationId)
                check(statement.executeUpdate() == 1) {
                    "Restored active hold did not suspend its deletion operation"
                }
            }
        }
    }

    private fun upsertRestoredOperation(
        connection: Connection,
        operation: RestoredDeletionOperation,
        status: String,
        quarantineUntil: OffsetDateTime?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations (
                operation_id, study_id, participant_ref, participant_id, mode, status,
                requested_by, idempotency_key, registry_version, quarantine_until,
                started_at, completed_at, proof_hash, failure_code, operation_attempt_count,
                next_attempt_at, participant_block_token, worker_lease_token,
                worker_lease_expires_at, cancelled_by, cancelled_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, 0,
                      NULL, NULL, NULL, NULL, NULL, NULL)
            ON CONFLICT (operation_id) DO UPDATE SET
                study_id = EXCLUDED.study_id,
                participant_ref = EXCLUDED.participant_ref,
                participant_id = EXCLUDED.participant_id,
                mode = EXCLUDED.mode,
                status = EXCLUDED.status,
                requested_by = EXCLUDED.requested_by,
                idempotency_key = EXCLUDED.idempotency_key,
                registry_version = EXCLUDED.registry_version,
                quarantine_until = EXCLUDED.quarantine_until,
                started_at = NULL,
                completed_at = NULL,
                proof_hash = NULL,
                failure_code = NULL,
                operation_attempt_count = 0,
                next_attempt_at = NULL,
                participant_block_token = NULL,
                worker_lease_token = NULL,
                worker_lease_expires_at = NULL,
                cancelled_by = NULL,
                cancelled_at = NULL,
                updated_at = now()
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operation.operationId)
            statement.setObject(2, operation.studyId)
            statement.setString(
                3,
                operation.participantRef ?: operation.participantId?.let {
                    LogSanitizer.stableFingerprint(it, "participant")
                },
            )
            statement.setString(4, operation.participantId)
            statement.setString(5, operation.mode.name)
            statement.setString(6, status)
            statement.setString(7, operation.requestedBy)
            statement.setObject(8, operation.idempotencyKey)
            statement.setInt(9, REGISTRY_VERSION)
            statement.setObject(10, quarantineUntil)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun deleteRestoredOperationDependents(
        connection: Connection,
        operationId: UUID,
    ) {
        // Study-deletion schedules deliberately remain linked to their original operation;
        // only derived execution rows are rebuilt from the current canonical registry.
        listOf(
            "data_deletion_audit_outbox",
            "retention_holds",
            "data_deletion_form_access_revocations",
            "export_job_revocations",
            "data_deletion_steps",
            "data_deletion_tombstones",
        ).forEach { tableName ->
            check(SQL_IDENTIFIER.matches(tableName))
            connection.prepareStatement("DELETE FROM $tableName WHERE operation_id = ?").use { statement ->
                statement.setObject(1, operationId)
                statement.executeUpdate()
            }
        }
    }

    private fun restoreRetentionHolds(connection: Connection, holds: List<RestoredRetentionHold>) {
        holds.forEach { hold ->
            connection.prepareStatement(
                """
                INSERT INTO retention_holds (
                    hold_id, operation_id, study_id, reason, created_by, created_at,
                    review_at, released_by, released_at, release_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, hold.holdId)
                statement.setObject(2, hold.operationId)
                statement.setObject(3, hold.studyId)
                statement.setString(4, hold.reason)
                statement.setString(5, hold.createdBy)
                statement.setObject(6, hold.createdAt)
                statement.setObject(7, hold.reviewAt)
                statement.setString(8, hold.releasedBy)
                statement.setObject(9, hold.releasedAt)
                statement.setString(10, hold.releaseReason)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    private fun createParticipantQuarantine(
        connection: Connection,
        request: ParticipantQuarantineRequest,
    ): StudyQuarantineResult {
        val operationId = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations
                (operation_id, study_id, participant_ref, participant_id, mode, status,
                 requested_by, idempotency_key, registry_version, quarantine_until)
            VALUES (?, ?, ?, ?, ?, 'PREVIEW', ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setObject(2, request.studyId)
            statement.setString(3, request.participantRef)
            statement.setString(4, request.participantId)
            statement.setString(5, request.mode.name)
            statement.setString(6, request.requestedBy)
            statement.setObject(7, request.idempotencyKey)
            statement.setInt(8, REGISTRY_VERSION)
            statement.setObject(9, request.quarantineUntil)
            statement.executeUpdate() == 1
        }
        if (!inserted) {
            return StudyQuarantineResult(
                resolveMatchingOperation(
                    connection,
                    request.idempotencyKey,
                    request.studyId,
                    request.participantId,
                    request.mode,
                ),
                inserted = false,
            )
        }

        ChronicleDataAssetRegistry.participantAssets.forEachIndexed { ordinal, asset ->
            insertStep(
                connection,
                operationId,
                request.studyId,
                asset,
                ordinal,
                countParticipantRows(connection, asset, request.studyId, request.participantId),
            )
        }
        revokeParticipantFormAccess(connection, request.studyId, request.participantId, request.now)
        revokeParticipantExports(connection, operationId, request.studyId, request.participantId)
        activateQuarantine(connection, operationId)
        return StudyQuarantineResult(operationId, inserted = true)
    }

    private data class ExistingStudyOperation(
        val operationId: UUID,
        val status: String,
    )

    private fun createStudyQuarantine(
        connection: Connection,
        studyId: UUID,
        requestedBy: String,
        idempotencyKey: UUID,
        quarantineUntil: OffsetDateTime,
        now: OffsetDateTime,
    ): StudyQuarantineResult {
        var effectiveIdempotencyKey = idempotencyKey
        var operationId: UUID? = null
        var generation = 0
        while (operationId == null && generation < MAX_STUDY_ERASURE_GENERATIONS) {
            generation += 1
            val candidateOperationId = UUID.randomUUID()
            val inserted = connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations
                    (operation_id, study_id, mode, status, requested_by, idempotency_key,
                     registry_version, quarantine_until)
                VALUES (?, ?, 'STUDY_ERASURE', 'PREVIEW', ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, candidateOperationId)
                statement.setObject(2, studyId)
                statement.setString(3, requestedBy)
                statement.setObject(4, effectiveIdempotencyKey)
                statement.setInt(5, REGISTRY_VERSION)
                statement.setObject(6, quarantineUntil)
                statement.executeUpdate() == 1
            }
            if (inserted) {
                operationId = candidateOperationId
                break
            }

            val existing = resolveMatchingStudyOperation(connection, effectiveIdempotencyKey, studyId)
            if (existing.status != "CANCELLED") {
                return StudyQuarantineResult(existing.operationId, inserted = false)
            }

            // The base key is stable across uncertain retries, but an explicitly cancelled
            // operation must not reserve that logical request forever. Follow a deterministic
            // successor chain derived from immutable cancelled operation IDs. A retry resolves
            // the same live successor; another cancellation advances exactly one generation.
            effectiveIdempotencyKey = studyErasureSuccessorKey(
                effectiveIdempotencyKey,
                existing.operationId,
            )
        }
        val resolvedOperationId = checkNotNull(operationId) {
            "Study erasure idempotency history exceeded $MAX_STUDY_ERASURE_GENERATIONS generations"
        }

        discoverStudyTables(connection).forEachIndexed { ordinal, tableName ->
            insertStudyStep(
                connection,
                resolvedOperationId,
                studyId,
                tableName,
                ordinal,
                countStudyRows(connection, tableName, studyId),
            )
        }
        revokeStudyFormAccess(connection, resolvedOperationId, studyId, now)
        revokeStudyExports(connection, resolvedOperationId, studyId)
        activateQuarantine(connection, resolvedOperationId)
        return StudyQuarantineResult(resolvedOperationId, inserted = true)
    }

    private fun studyErasureSuccessorKey(currentKey: UUID, cancelledOperationId: UUID): UUID =
        UUID.nameUUIDFromBytes( // nosemgrep: chronicle-uuid-from-string -- deterministic idempotency successor key
            "study-erasure-successor:$currentKey:$cancelledOperationId"
                .toByteArray(StandardCharsets.UTF_8)
        )

    public open fun cancelStudyErasure(studyId: UUID, cancelledBy: String): Int =
        cancelStudyErasureTransaction(
            studyId = studyId,
            cancelledBy = cancelledBy,
            rejectStartedErasure = false,
        ) { _, _ -> }

    /**
     * Cancels every not-yet-started study erasure and commits caller-owned lifecycle changes
     * in the same transaction. A started or completed erasure makes cancellation fail closed.
     */
    public open fun cancelStudyErasureAtomically(
        studyId: UUID,
        cancelledBy: String,
        transaction: (Connection, Int) -> Unit,
    ): Int = cancelStudyErasureTransaction(
        studyId = studyId,
        cancelledBy = cancelledBy,
        rejectStartedErasure = true,
        transaction = transaction,
    )

    private fun cancelStudyErasureTransaction(
        studyId: UUID,
        cancelledBy: String,
        rejectStartedErasure: Boolean,
        transaction: (Connection, Int) -> Unit,
    ): Int {
        val actor = cancelledBy.trim()
        require(actor.isNotEmpty()) { "cancelledBy is required" }
        require(actor.length <= 255) { "cancelledBy exceeds 255 characters" }
        val cancelledOperationIds = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireDeletionStudyLock(connection, studyId)
                val operationIds = cancelStudyErasure(connection, studyId, actor)
                if (rejectStartedErasure) {
                    check(!hasUncancelledStudyErasure(connection, studyId)) {
                        "Study erasure has already started and can no longer be cancelled"
                    }
                }
                transaction(connection, operationIds.size)
                connection.commit()
                operationIds
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        if (cancelledOperationIds.isNotEmpty()) {
            ChronicleMetrics.dataDeletionOperationsTotal
                .labels(DataDeletionMode.STUDY_ERASURE.name, "cancelled")
                .inc(cancelledOperationIds.size.toDouble())
            publishPendingDeletionAuditEvents()
        }
        return cancelledOperationIds.size
    }

    private fun cancelStudyErasure(connection: Connection, studyId: UUID, actor: String): List<UUID> {
        val operationIds = connection.prepareStatement(
            """
            UPDATE data_deletion_operations
            SET status = 'CANCELLED', updated_at = now(), failure_code = 'Cancelled',
                next_attempt_at = NULL, worker_lease_token = NULL,
                worker_lease_expires_at = NULL,
                cancelled_by = ?, cancelled_at = now()
            WHERE study_id = ? AND mode = 'STUDY_ERASURE'
              AND status IN ('QUARANTINED', 'HELD', 'READY')
              AND started_at IS NULL
              AND operation_attempt_count = 0
            RETURNING operation_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, actor)
            statement.setObject(2, studyId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getObject("operation_id", UUID::class.java))
                    }
                }
            }
        }
        if (operationIds.isEmpty()) return operationIds

        restoreCancelledStudyFormAccess(connection, studyId)
        connection.prepareStatement(
            """
            DELETE FROM export_job_revocations AS revocation
            USING data_deletion_operations AS operation
            WHERE revocation.operation_id = operation.operation_id
              AND operation.study_id = ?
              AND operation.mode = 'STUDY_ERASURE'
              AND operation.status = 'CANCELLED'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeUpdate()
        }
        operationIds.forEach { operationId ->
            enqueueDeletionAuditEvent(
                connection = connection,
                operationId = operationId,
                studyId = studyId,
                eventType = AuditEventType.CANCEL_DATA_DELETION,
                actor = actor,
                description = "Cancelled study erasure before physical deletion",
                data = mapOf("cancelledBy" to actor),
            )
        }
        return operationIds
    }

    private fun hasUncancelledStudyErasure(connection: Connection, studyId: UUID): Boolean =
        connection.prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1
                FROM data_deletion_operations
                WHERE study_id = ? AND mode = 'STUDY_ERASURE' AND status <> 'CANCELLED'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unable to verify study-erasure cancellation" }
                resultSet.getBoolean(1)
            }
        }

    public open fun getOperation(operationId: UUID): DataDeletionOperation =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT operation_id, study_id, participant_id, mode, status, quarantine_until
                FROM data_deletion_operations
                WHERE operation_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Deletion operation not found" }
                    DataDeletionOperation(
                        operationId = resultSet.getObject("operation_id", UUID::class.java),
                        studyId = resultSet.getObject("study_id", UUID::class.java),
                        participantId = resultSet.getString("participant_id"),
                        mode = DataDeletionMode.valueOf(resultSet.getString("mode")),
                        status = resultSet.getString("status"),
                        quarantineUntil = resultSet.getObject("quarantine_until", OffsetDateTime::class.java),
                    )
                }
            }
        }

    public open fun placeHold(
        operationId: UUID,
        studyId: UUID,
        reason: String,
        createdBy: String,
        reviewAt: OffsetDateTime,
    ): UUID {
        val normalizedReason = reason.trim()
        val actor = createdBy.trim()
        require(normalizedReason.length in 10..2_000) {
            "A specific hold reason between 10 and 2000 characters is required"
        }
        require(actor.isNotEmpty()) { "createdBy is required" }
        require(actor.length <= 255) { "createdBy exceeds 255 characters" }
        require(reviewAt.isAfter(now())) { "Hold review time must be in the future" }
        var placedNewHold = false
        val resolvedHoldId = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireDeletionStudyLock(connection, studyId)
                val existingHold = connection.prepareStatement(
                    """
                    SELECT hold_id,
                           reason = ? AND created_by = ? AND review_at = ? AS exact_replay
                    FROM retention_holds
                    WHERE operation_id = ? AND study_id = ? AND released_at IS NULL
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, normalizedReason)
                    statement.setString(2, actor)
                    statement.setObject(3, reviewAt)
                    statement.setObject(4, operationId)
                    statement.setObject(5, studyId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            null
                        } else {
                            resultSet.getObject("hold_id", UUID::class.java) to
                                resultSet.getBoolean("exact_replay")
                        }
                    }
                }

                val holdId = if (existingHold != null) {
                    check(existingHold.second) {
                        "A different active retention hold already exists"
                    }
                    existingHold.first
                } else {
                    UUID.randomUUID().also { newHoldId ->
                        connection.prepareStatement(
                            """
                            INSERT INTO retention_holds
                                (hold_id, operation_id, study_id, reason, created_by, review_at)
                            SELECT ?, operation_id, study_id, ?, ?, ?
                            FROM data_deletion_operations
                            WHERE operation_id = ? AND study_id = ?
                              AND status IN ('QUARANTINED', 'READY', 'FAILED')
                            """.trimIndent()
                        ).use { statement ->
                            statement.setObject(1, newHoldId)
                            statement.setString(2, normalizedReason)
                            statement.setString(3, actor)
                            statement.setObject(4, reviewAt)
                            statement.setObject(5, operationId)
                            statement.setObject(6, studyId)
                            check(statement.executeUpdate() == 1) { "Deletion operation is not holdable" }
                        }
                        placedNewHold = true
                    }
                }
                enqueueDeletionAuditEvent(
                    connection = connection,
                    operationId = operationId,
                    studyId = studyId,
                    holdId = holdId,
                    eventType = AuditEventType.PLACE_RETENTION_HOLD,
                    actor = actor,
                    description = "Placed explicit retention hold; review at $reviewAt",
                    data = mapOf(
                        "holdId" to holdId.toString(),
                        "reviewAt" to reviewAt.toString(),
                        "createdBy" to actor,
                    ),
                )
                connection.commit()
                holdId
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        if (placedNewHold) {
            ChronicleMetrics.dataDeletionRetentionHoldsTotal.labels("placed").inc()
        }
        publishPendingDeletionAuditEvents()
        return resolvedHoldId
    }

    public open fun releaseHold(
        operationId: UUID,
        holdId: UUID,
        studyId: UUID,
        releasedBy: String,
        releaseReason: String,
    ) {
        val normalizedReason = releaseReason.trim()
        val actor = releasedBy.trim()
        require(normalizedReason.length in 10..2_000) {
            "A specific release reason between 10 and 2000 characters is required"
        }
        require(actor.isNotEmpty()) { "releasedBy is required" }
        require(actor.length <= 255) { "releasedBy exceeds 255 characters" }
        var releasedNow = false
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                acquireDeletionStudyLock(connection, studyId)
                val persistedRelease = connection.prepareStatement(
                    """
                    SELECT released_at IS NOT NULL AS released,
                           released_by = ? AND release_reason = ? AS exact_replay
                    FROM retention_holds
                    WHERE hold_id = ? AND operation_id = ? AND study_id = ?
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, actor)
                    statement.setString(2, normalizedReason)
                    statement.setObject(3, holdId)
                    statement.setObject(4, operationId)
                    statement.setObject(5, studyId)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Retention hold not found" }
                        resultSet.getBoolean("released") to resultSet.getBoolean("exact_replay")
                    }
                }
                if (persistedRelease.first) {
                    check(persistedRelease.second) {
                        "Retention hold was already released with different attribution"
                    }
                } else {
                    connection.prepareStatement(
                        """
                        UPDATE retention_holds
                        SET released_by = ?, released_at = now(), release_reason = ?
                        WHERE hold_id = ? AND operation_id = ? AND study_id = ? AND released_at IS NULL
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, actor)
                        statement.setString(2, normalizedReason)
                        statement.setObject(3, holdId)
                        statement.setObject(4, operationId)
                        statement.setObject(5, studyId)
                        check(statement.executeUpdate() == 1) { "Active retention hold not found" }
                    }
                    releasedNow = true
                }
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET status = CASE
                            WHEN started_at IS NOT NULL OR operation_attempt_count > 0 THEN 'FAILED'
                            WHEN quarantine_until <= now() THEN 'READY'
                            ELSE 'QUARANTINED'
                        END,
                        failure_code = CASE
                            WHEN started_at IS NOT NULL OR operation_attempt_count > 0
                                THEN COALESCE(failure_code, 'LegacyHeldOperation')
                            ELSE failure_code
                        END,
                        next_attempt_at = CASE
                            WHEN started_at IS NOT NULL OR operation_attempt_count > 0 THEN now()
                            ELSE next_attempt_at
                        END,
                        updated_at = now()
                    WHERE operation_id = ?
                      AND status = 'HELD'
                      AND NOT EXISTS (
                          SELECT 1 FROM retention_holds
                          WHERE operation_id = ? AND released_at IS NULL
                      )
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setObject(2, operationId)
                    statement.executeUpdate()
                }
                enqueueDeletionAuditEvent(
                    connection = connection,
                    operationId = operationId,
                    studyId = studyId,
                    holdId = holdId,
                    eventType = AuditEventType.RELEASE_RETENTION_HOLD,
                    actor = actor,
                    description = "Released explicit retention hold",
                    data = mapOf(
                        "holdId" to holdId.toString(),
                        "releasedBy" to actor,
                    ),
                )
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        if (releasedNow) {
            ChronicleMetrics.dataDeletionRetentionHoldsTotal.labels("released").inc()
        }
        publishPendingDeletionAuditEvents()
    }

    /** Process a bounded batch. Interrupted ERASING/VERIFYING operations are resumable. */
    public open fun processDueOperations(limit: Int = MAX_PROCESS_BATCH): Int =
        RLSRequestContext.withDeletionWorkerContext {
            publishPendingDeletionAuditEvents()
            processDueOperationsAsDeletionWorker(limit)
        }

    private fun processDueOperationsAsDeletionWorker(limit: Int): Int {
        require(limit in 1..MAX_PROCESS_BATCH)
        var processed = 0
        repeat(limit) {
            val claim = claimDueOperation() ?: return processed
            try {
                eraseAndVerify(claim)
                processed += 1
            } catch (exception: DeletionLeaseLostException) {
                logger.warn(
                    "Stopped stale deletion worker for operation {}",
                    claim.operation.operationId,
                    exception,
                )
            } catch (exception: DeletionRetryStatePersistenceException) {
                // Without a durable next_attempt_at value, this operation remains immediately
                // claimable. Stop the batch instead of creating a tight retry loop.
                throw exception
            } catch (_: Exception) {
                // eraseAndVerify records the redacted failure and schedules a later retry. Keep
                // draining the bounded batch so one poison operation cannot starve unrelated work.
            }
        }
        return processed
    }

    private fun claimDueOperation(requiredOperationId: UUID? = null): ClaimedDeletion? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                val candidate = connection.prepareStatement(
                    """
                    SELECT operation_id, study_id, participant_id, mode, status, quarantine_until
                    FROM data_deletion_operations operation
                    WHERE (?::uuid IS NULL OR operation.operation_id = ?::uuid)
                      AND (
                        (
                            operation.status IN ('QUARANTINED', 'READY', 'FAILED')
                            AND COALESCE(operation.next_attempt_at, operation.quarantine_until) <= now()
                        )
                        OR
                        (
                            operation.status IN ('ERASING', 'VERIFYING')
                            AND operation.worker_lease_expires_at <= now()
                        )
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM retention_holds hold
                          WHERE hold.operation_id = operation.operation_id AND hold.released_at IS NULL
                      )
                    ORDER BY
                        CASE
                            WHEN operation.status IN ('ERASING', 'VERIFYING')
                                THEN operation.worker_lease_expires_at
                            ELSE COALESCE(operation.next_attempt_at, operation.quarantine_until)
                        END,
                        operation.created_at
                    LIMIT 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, requiredOperationId)
                    statement.setObject(2, requiredOperationId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) null else DataDeletionOperation(
                            operationId = resultSet.getObject("operation_id", UUID::class.java),
                            studyId = resultSet.getObject("study_id", UUID::class.java),
                            participantId = resultSet.getString("participant_id"),
                            mode = DataDeletionMode.valueOf(resultSet.getString("mode")),
                            status = resultSet.getString("status"),
                            quarantineUntil = resultSet.getObject("quarantine_until", OffsetDateTime::class.java),
                        )
                    }
                }
                if (candidate == null) {
                    connection.rollback()
                    return@use null
                }
                acquireDeletionStudyLock(connection, candidate.studyId)

                val operation = connection.prepareStatement(
                    """
                    SELECT operation_id, study_id, participant_id, mode, status, quarantine_until
                    FROM data_deletion_operations operation
                    WHERE operation.operation_id = ?
                      AND (
                        (
                            operation.status IN ('QUARANTINED', 'READY', 'FAILED')
                            AND COALESCE(operation.next_attempt_at, operation.quarantine_until) <= now()
                        )
                        OR
                        (
                            operation.status IN ('ERASING', 'VERIFYING')
                            AND operation.worker_lease_expires_at <= now()
                        )
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM retention_holds hold
                          WHERE hold.operation_id = operation.operation_id AND hold.released_at IS NULL
                      )
                    FOR UPDATE SKIP LOCKED
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, candidate.operationId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) null else DataDeletionOperation(
                            operationId = resultSet.getObject("operation_id", UUID::class.java),
                            studyId = resultSet.getObject("study_id", UUID::class.java),
                            participantId = resultSet.getString("participant_id"),
                            mode = DataDeletionMode.valueOf(resultSet.getString("mode")),
                            status = resultSet.getString("status"),
                            quarantineUntil = resultSet.getObject("quarantine_until", OffsetDateTime::class.java),
                        )
                    }
                }
                if (operation == null) {
                    connection.rollback()
                    return@use null
                }

                val leaseToken = UUID.randomUUID()
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET status = 'ERASING', started_at = COALESCE(started_at, now()), updated_at = now(),
                        failure_code = NULL, next_attempt_at = NULL,
                        operation_attempt_count = operation_attempt_count + 1,
                        worker_lease_token = ?,
                        worker_lease_expires_at = now() + (? * interval '1 minute')
                    WHERE operation_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, leaseToken)
                    statement.setLong(2, DELETION_LEASE_MINUTES)
                    statement.setObject(3, operation.operationId)
                    check(statement.executeUpdate() == 1)
                }
                connection.commit()
                ClaimedDeletion(operation.copy(status = "ERASING"), leaseToken)
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun eraseAndVerify(claim: ClaimedDeletion) {
        val operation = claim.operation
        val startedAt = System.nanoTime()
        var outcome = "completed"
        try {
            storageResolver.requireDeletionStorageColocated(operation.studyId)
            eraseExportArtifacts(claim)
            if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
                eraseStudy(claim)
                completeOperation(claim, null)
                return
            }
            val participantId = requireNotNull(operation.participantId)
            ChronicleDataAssetRegistry.participantAssets.forEach { asset ->
                eraseAsset(claim, participantId, asset)
            }
            completeOperation(claim, participantId)
        } catch (exception: Exception) {
            if (exception is DeletionLeaseLostException) {
                outcome = "lease_lost"
                throw exception
            }
            outcome = "failed"
            try {
                markFailed(claim, exception)
            } catch (leaseLost: DeletionLeaseLostException) {
                exception.addSuppressed(leaseLost)
                throw leaseLost
            } catch (persistenceFailure: Exception) {
                throw DeletionRetryStatePersistenceException(
                    operation.operationId,
                    persistenceFailure,
                    exception,
                )
            }
            throw exception
        } finally {
            ChronicleMetrics.dataDeletionOperationsTotal.labels(operation.mode.name, outcome).inc()
            ChronicleMetrics.dataDeletionOperationDurationSeconds
                .labels(operation.mode.name, outcome)
                .observe((System.nanoTime() - startedAt) / 1_000_000_000.0)
        }
    }

    private fun eraseStudy(claim: ClaimedDeletion) {
        val operation = claim.operation
        val steps = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT asset_id
                FROM data_deletion_steps
                WHERE operation_id = ?
                ORDER BY ordinal
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, operation.operationId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString("asset_id"))
                    }
                }
            }
        }
        steps.forEach { assetId ->
            check(assetId.startsWith("study-table:")) { "Invalid study erasure asset" }
            val tableName = assetId.removePrefix("study-table:")
            requireTrustedStudyTable(tableName)
            eraseStudyTable(claim, assetId, tableName)
        }
    }

    private fun eraseStudyTable(
        claim: ClaimedDeletion,
        assetId: String,
        tableName: String,
    ) {
        val operation = claim.operation
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                renewDeletionLease(connection, claim)
                val alreadyVerified = connection.prepareStatement(
                    "SELECT status FROM data_deletion_steps WHERE operation_id = ? AND asset_id = ? FOR UPDATE"
                ).use { statement ->
                    statement.setObject(1, operation.operationId)
                    statement.setString(2, assetId)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Missing deletion step for $assetId" }
                        resultSet.getString(1) == "VERIFIED"
                    }
                }
                if (!alreadyVerified) {
                    connection.prepareStatement(
                        """
                        UPDATE data_deletion_steps
                        SET status = 'RUNNING', attempt_count = attempt_count + 1,
                            last_attempt_at = now(), error_code = NULL
                        WHERE operation_id = ? AND asset_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, operation.operationId)
                        statement.setString(2, assetId)
                        check(statement.executeUpdate() == 1)
                    }
                    val deletedRows = deleteStudyRows(connection, tableName, operation.studyId)
                    val residualRows = countStudyRowsForErasure(connection, tableName, operation.studyId)
                    check(residualRows == 0L) { "Residual rows remain for study asset $tableName" }
                    connection.prepareStatement(
                        """
                        UPDATE data_deletion_steps
                        SET status = 'VERIFIED', deleted_rows = COALESCE(deleted_rows, 0) + ?,
                            residual_rows = ?, verified_at = now()
                        WHERE operation_id = ? AND asset_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, deletedRows)
                        statement.setLong(2, residualRows)
                        statement.setObject(3, operation.operationId)
                        statement.setString(4, assetId)
                        check(statement.executeUpdate() == 1)
                    }
                }
                renewDeletionLease(connection, claim)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun eraseAsset(
        claim: ClaimedDeletion,
        participantId: String,
        asset: ParticipantDataAsset,
    ) {
        val operation = claim.operation
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                renewDeletionLease(connection, claim)
                val alreadyVerified = connection.prepareStatement(
                    "SELECT status FROM data_deletion_steps WHERE operation_id = ? AND asset_id = ? FOR UPDATE"
                ).use { statement ->
                    statement.setObject(1, operation.operationId)
                    statement.setString(2, asset.id)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Missing deletion step for ${asset.id}" }
                        resultSet.getString(1) == "VERIFIED"
                    }
                }
                if (!alreadyVerified) {
                    connection.prepareStatement(
                        """
                        UPDATE data_deletion_steps
                        SET status = 'RUNNING', attempt_count = attempt_count + 1, last_attempt_at = now(),
                            error_code = NULL
                        WHERE operation_id = ? AND asset_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, operation.operationId)
                        statement.setString(2, asset.id)
                        check(statement.executeUpdate() == 1)
                    }
                    val deletedRows = deleteParticipantRows(connection, asset, operation.studyId, participantId)
                    val residualRows = countParticipantRows(connection, asset, operation.studyId, participantId)
                    check(residualRows == 0L) { "Residual rows remain for registered asset ${asset.id}" }
                    connection.prepareStatement(
                        """
                        UPDATE data_deletion_steps
                        SET status = 'VERIFIED', deleted_rows = COALESCE(deleted_rows, 0) + ?,
                            residual_rows = ?, verified_at = now()
                        WHERE operation_id = ? AND asset_id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, deletedRows)
                        statement.setLong(2, residualRows)
                        statement.setObject(3, operation.operationId)
                        statement.setString(4, asset.id)
                        check(statement.executeUpdate() == 1)
                    }
                }
                renewDeletionLease(connection, claim)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun deleteEnrollment(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement(
            "DELETE FROM study_participants WHERE study_id = ? AND participant_id = ?"
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeUpdate()
        }
    }

    private fun completeOperation(claim: ClaimedDeletion, participantId: String?) {
        val operation = claim.operation
        storageResolver.requireDeletionStorageColocated(operation.studyId)
        storageResolver.getPlatformStorage().connection.use { connection ->
            var exportStorageLock: AutoCloseable? = null
            connection.autoCommit = false
            try {
                acquireDeletionStudyLock(connection, operation.studyId)
                exportStorageLock = ExportFileWriter.acquireStudyExportLock(operation.studyId)
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET status = 'VERIFYING', updated_at = now(),
                        worker_lease_expires_at = now() + (? * interval '1 minute')
                    WHERE operation_id = ? AND status IN ('ERASING', 'VERIFYING')
                      AND worker_lease_token = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, DELETION_LEASE_MINUTES)
                    statement.setObject(2, operation.operationId)
                    statement.setObject(3, claim.leaseToken)
                    check(statement.executeUpdate() == 1) { "Deletion operation is not finalizable" }
                }
                sweepRevokedExportArtifacts(connection, claim)
                refreshFinalVerification(connection, claim, participantId)
                if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
                    requireStudyInventoryUnchanged(
                        connection,
                        operation.operationId,
                        operation.studyId,
                    )
                }
                val proofMaterial = connection.prepareStatement(
                    """
                    SELECT asset_id, status, expected_rows, deleted_rows, residual_rows
                    FROM data_deletion_steps
                    WHERE operation_id = ?
                    ORDER BY ordinal
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, operation.operationId)
                    statement.executeQuery().use { resultSet ->
                        buildString {
                            append(operation.operationId).append('|')
                            append(operation.studyId).append('|')
                            append(operation.mode.name).append('|').append(REGISTRY_VERSION)
                            while (resultSet.next()) {
                                val residualRows = resultSet.getObject("residual_rows")
                                    ?.let { (it as Number).toLong() }
                                check(
                                    resultSet.getString("status") == "VERIFIED"
                                        && residualRows != null
                                        && residualRows == 0L
                                ) {
                                    "Deletion verification is incomplete"
                                }
                                append('|').append(resultSet.getString("asset_id"))
                                    .append(':').append(resultSet.getLong("expected_rows"))
                                    .append(':').append(resultSet.getLong("deleted_rows"))
                                    .append(':').append(residualRows)
                            }
                        }
                    }
                }
                val proofHash = sha256(proofMaterial)
                val completedAt = now()
                connection.prepareStatement(
                    """
                    INSERT INTO data_deletion_tombstones
                        (operation_id, study_ref, participant_ref, mode, registry_version, completed_at, proof_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (operation_id) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, operation.operationId)
                    statement.setString(2, LogSanitizer.stableFingerprint(operation.studyId.toString(), "study"))
                    statement.setString(3, participantId?.let {
                        LogSanitizer.stableFingerprint(it, "participant")
                    })
                    statement.setString(4, operation.mode.name)
                    statement.setInt(5, REGISTRY_VERSION)
                    statement.setObject(6, completedAt)
                    statement.setString(7, proofHash)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET status = 'COMPLETED', participant_id = NULL, proof_hash = ?,
                        completed_at = ?, updated_at = now(), failure_code = NULL,
                        worker_lease_token = NULL, worker_lease_expires_at = NULL
                    WHERE operation_id = ? AND worker_lease_token = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, proofHash)
                    statement.setObject(2, completedAt)
                    statement.setObject(3, operation.operationId)
                    statement.setObject(4, claim.leaseToken)
                    check(statement.executeUpdate() == 1)
                }
                connection.commit()
                logger.info(
                    "data_deletion outcome=completed operation_id={} mode={}",
                    operation.operationId,
                    operation.mode.name,
                )
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                try {
                    exportStorageLock?.close()
                } catch (lockFailure: Exception) {
                    logger.error(
                        "Failed to release export storage lock for deletion operation {}",
                        operation.operationId,
                        lockFailure,
                    )
                }
                connection.autoCommit = true
            }
        }
    }

    /**
     * Re-run the physical sweep and live counts immediately before the proof
     * transaction commits. The V68 mutation barrier prevents inserts/updates
     * while an operation is ERASING/VERIFYING, so these observations cannot be
     * invalidated behind the tombstone.
     */
    private fun refreshFinalVerification(
        connection: Connection,
        claim: ClaimedDeletion,
        participantId: String?,
    ) {
        val operation = claim.operation
        if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
            val tables = reconcileAndLockStudyInventory(connection, claim)
            tables.forEach { tableName ->
                renewDeletionLease(connection, claim)
                val assetId = "study-table:$tableName"
                val deletedRows = deleteStudyRows(connection, tableName, operation.studyId)
                val residualRows = countStudyRowsForErasure(connection, tableName, operation.studyId)
                check(residualRows == 0L) { "Residual rows remain for study asset $tableName" }
                recordFinalVerification(
                    connection,
                    operation.operationId,
                    assetId,
                    deletedRows,
                    residualRows,
                )
            }
            renewDeletionLease(connection, claim)
            return
        }

        val subjectId = requireNotNull(participantId)
        ChronicleDataAssetRegistry.participantAssets.forEach { asset ->
            renewDeletionLease(connection, claim)
            val deletedRows = deleteParticipantRows(connection, asset, operation.studyId, subjectId)
            val residualRows = countParticipantRows(connection, asset, operation.studyId, subjectId)
            check(residualRows == 0L) { "Residual rows remain for registered asset ${asset.id}" }
            recordFinalVerification(
                connection,
                operation.operationId,
                asset.id,
                deletedRows,
                residualRows,
            )
        }
        renewDeletionLease(connection, claim)
        if (operation.mode == DataDeletionMode.WITHDRAW_AND_ERASE) {
            deleteEnrollment(connection, operation.studyId, subjectId)
        }
    }

    private fun reconcileAndLockStudyInventory(
        connection: Connection,
        claim: ClaimedDeletion,
    ): List<String> {
        val operation = claim.operation
        val discoveredTables = discoverStudyTablesForErasure(connection, operation.studyId)
        val recordedTables = readStudyStepTables(connection, operation.operationId)
        val disappearedTables = recordedTables - discoveredTables.toSet()
        check(disappearedTables.isEmpty()) {
            "Study erasure inventory lost registered tables: ${disappearedTables.sorted().joinToString(",")}"
        }

        val missingTables = discoveredTables.filterNot(recordedTables::contains)
        if (missingTables.isNotEmpty()) {
            val nextOrdinal = connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM data_deletion_steps WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operation.operationId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Unable to allocate study erasure step ordinals" }
                    resultSet.getInt(1)
                }
            }
            missingTables.forEachIndexed { index, tableName ->
                insertStudyStep(
                    connection,
                    operation.operationId,
                    operation.studyId,
                    tableName,
                    nextOrdinal + index,
                    countStudyRowsForErasure(connection, tableName, operation.studyId),
                )
            }
        }

        // Block INSERT/UPDATE/DELETE writers for every verified table until the
        // proof and COMPLETED state commit. Lock identifiers in one global order
        // to keep concurrent erasures deadlock-free; deletion order remains the
        // registry/FK-aware order returned by discoverStudyTables().
        discoveredTables.sorted().forEach { tableName ->
            requireTrustedStudyTable(tableName)
            lockStudyTableForErasure(connection, tableName, operation.studyId)
        }

        val lockedInventory = discoverStudyTablesForErasure(connection, operation.studyId)
        check(lockedInventory == discoveredTables) {
            "Study erasure inventory changed while final verification locks were acquired"
        }
        check(readStudyStepTables(connection, operation.operationId) == discoveredTables.toSet()) {
            "Study erasure steps do not exactly match the locked table inventory"
        }
        return discoveredTables
    }

    private fun readStudyStepTables(connection: Connection, operationId: UUID): Set<String> =
        connection.prepareStatement(
            """
            SELECT asset_id
            FROM data_deletion_steps
            WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) {
                        val assetId = resultSet.getString("asset_id")
                        check(assetId.startsWith("study-table:")) { "Invalid study erasure asset" }
                        val tableName = assetId.removePrefix("study-table:")
                        requireTrustedStudyTable(tableName)
                        add(tableName)
                    }
                }
            }
        }

    private fun requireStudyInventoryUnchanged(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
    ) {
        val currentTables = discoverStudyTablesForErasure(connection, studyId).toSet()
        check(readStudyStepTables(connection, operationId) == currentTables) {
            "Study erasure inventory changed before proof creation"
        }
    }

    private fun recordFinalVerification(
        connection: Connection,
        operationId: UUID,
        assetId: String,
        deletedRows: Long,
        residualRows: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE data_deletion_steps
            SET status = 'VERIFIED',
                deleted_rows = COALESCE(deleted_rows, 0) + ?,
                residual_rows = ?,
                verified_at = now()
            WHERE operation_id = ? AND asset_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, deletedRows)
            statement.setLong(2, residualRows)
            statement.setObject(3, operationId)
            statement.setString(4, assetId)
            check(statement.executeUpdate() == 1) { "Missing deletion step for $assetId" }
        }
    }

    private fun markFailed(claim: ClaimedDeletion, exception: Exception) {
        val operationId = claim.operation.operationId
        val failureCode = exception::class.simpleName?.take(120) ?: "DeletionFailure"
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'FAILED', failure_code = ?, updated_at = now(),
                    next_attempt_at = now() + interval '1 hour',
                    worker_lease_token = NULL, worker_lease_expires_at = NULL
                WHERE operation_id = ? AND worker_lease_token = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, failureCode)
                statement.setObject(2, operationId)
                statement.setObject(3, claim.leaseToken)
                if (statement.executeUpdate() != 1) {
                    throw DeletionLeaseLostException(operationId)
                }
            }
        }
        logger.error(
            "data_deletion outcome=failed operation_id={} failure_code={} retry_delay=PT1H",
            operationId,
            failureCode,
            exception,
        )
    }

    private fun renewDeletionLease(connection: Connection, claim: ClaimedDeletion) {
        connection.prepareStatement(
            """
            UPDATE data_deletion_operations
            SET worker_lease_expires_at = now() + (? * interval '1 minute'),
                updated_at = now()
            WHERE operation_id = ?
              AND worker_lease_token = ?
              AND status IN ('ERASING', 'VERIFYING')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, DELETION_LEASE_MINUTES)
            statement.setObject(2, claim.operation.operationId)
            statement.setObject(3, claim.leaseToken)
            if (statement.executeUpdate() != 1) {
                throw DeletionLeaseLostException(claim.operation.operationId)
            }
        }
    }

    private fun acquireDeletionStudyLock(connection: Connection, studyId: UUID) {
        connection.prepareStatement(
            """
            SELECT pg_advisory_xact_lock(
                hashtextextended('chronicle-deletion:' || ?::text, 0)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Deletion study lock was not acquired" }
            }
        }
    }

    private fun activateQuarantine(connection: Connection, operationId: UUID) {
        connection.prepareStatement(
            """
            UPDATE data_deletion_operations
            SET status = 'QUARANTINED', updated_at = now()
            WHERE operation_id = ? AND status = 'PREVIEW'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operationId)
            check(statement.executeUpdate() == 1) { "Deletion preview was not activated" }
        }
    }

    private fun insertStep(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        asset: ParticipantDataAsset,
        ordinal: Int,
        expectedRows: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_steps
                (operation_id, study_id, asset_id, ordinal, status, expected_rows)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setObject(2, studyId)
            statement.setString(3, asset.id)
            statement.setInt(4, ordinal)
            statement.setLong(5, expectedRows)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun insertStudyStep(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        tableName: String,
        ordinal: Int,
        expectedRows: Long,
    ) {
        requireTrustedStudyTable(tableName)
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_steps
                (operation_id, study_id, asset_id, ordinal, status, expected_rows)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setObject(2, studyId)
            statement.setString(3, "study-table:$tableName")
            statement.setInt(4, ordinal)
            statement.setLong(5, expectedRows)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun resolveMatchingOperation(
        connection: Connection,
        idempotencyKey: UUID,
        studyId: UUID,
        participantId: String,
        mode: DataDeletionMode,
    ): UUID = connection.prepareStatement(
        """
        SELECT operation_id, study_id, participant_id, mode
        FROM data_deletion_operations
        WHERE idempotency_key = ?
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, idempotencyKey)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Conflicting deletion operation disappeared" }
            check(
                resultSet.getObject("study_id", UUID::class.java) == studyId &&
                    resultSet.getString("participant_id") == participantId &&
                    resultSet.getString("mode") == mode.name
            ) { "Idempotency key belongs to a different deletion request" }
            resultSet.getObject("operation_id", UUID::class.java)
        }
    }

    private fun resolveMatchingStudyOperation(
        connection: Connection,
        idempotencyKey: UUID,
        studyId: UUID,
    ): ExistingStudyOperation = connection.prepareStatement(
        """
        SELECT operation_id, study_id, mode, status
        FROM data_deletion_operations
        WHERE idempotency_key = ?
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, idempotencyKey)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Conflicting deletion operation disappeared" }
            check(
                resultSet.getObject("study_id", UUID::class.java) == studyId &&
                    resultSet.getString("mode") == DataDeletionMode.STUDY_ERASURE.name
            ) { "Idempotency key belongs to a different deletion request" }
            ExistingStudyOperation(
                operationId = resultSet.getObject("operation_id", UUID::class.java),
                status = resultSet.getString("status"),
            )
        }
    }

    private fun revokeParticipantFormAccess(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        revokedAt: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            UPDATE participant_form_access_codes
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE study_id = ? AND participant_id = ? AND revoked_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, revokedAt)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE participant_form_sessions
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE study_id = ? AND participant_id = ? AND revoked_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, revokedAt)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.executeUpdate()
        }
    }

    private fun revokeStudyFormAccess(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        revokedAt: OffsetDateTime,
    ) {
        recordStudyFormAccessRevocations(
            connection,
            operationId,
            studyId,
            "ACCESS_CODE",
            "participant_form_access_codes",
            "access_code_id",
            revokedAt,
        )
        connection.prepareStatement(
            """
            UPDATE participant_form_access_codes
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE study_id = ? AND revoked_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, revokedAt)
            statement.setObject(2, studyId)
            statement.executeUpdate()
        }
        recordStudyFormAccessRevocations(
            connection,
            operationId,
            studyId,
            "SESSION",
            "participant_form_sessions",
            "session_id",
            revokedAt,
        )
        connection.prepareStatement(
            """
            UPDATE participant_form_sessions
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE study_id = ? AND revoked_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, revokedAt)
            statement.setObject(2, studyId)
            statement.executeUpdate()
        }
    }

    private fun recordStudyFormAccessRevocations(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        resourceKind: String,
        sourceTable: String,
        resourceIdColumn: String,
        revokedAt: OffsetDateTime,
    ) {
        require(resourceKind == "ACCESS_CODE" || resourceKind == "SESSION")
        require(
            (sourceTable == "participant_form_access_codes" && resourceIdColumn == "access_code_id") ||
                (sourceTable == "participant_form_sessions" && resourceIdColumn == "session_id")
        )
        connection.prepareStatement(
            """
            WITH baseline AS (
                SELECT DISTINCT ON (resource_id) resource_id, original_revoked_at
                FROM data_deletion_form_access_revocations
                WHERE resource_kind = ?
                ORDER BY resource_id, created_at, operation_id
            )
            INSERT INTO data_deletion_form_access_revocations (
                operation_id, study_id, resource_kind, resource_id,
                original_revoked_at, revoked_at
            )
            SELECT ?, ?, ?, source.$resourceIdColumn,
                   CASE
                       WHEN baseline.resource_id IS NOT NULL THEN baseline.original_revoked_at
                       ELSE source.revoked_at
                   END,
                   COALESCE(source.revoked_at, ?)
            FROM $sourceTable AS source
            LEFT JOIN baseline ON baseline.resource_id = source.$resourceIdColumn
            WHERE source.study_id = ?
            ON CONFLICT (operation_id, resource_kind, resource_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, resourceKind)
            statement.setObject(2, operationId)
            statement.setObject(3, studyId)
            statement.setString(4, resourceKind)
            statement.setObject(5, revokedAt)
            statement.setObject(6, studyId)
            statement.executeUpdate()
        }
    }

    private fun restoreCancelledStudyFormAccess(connection: Connection, studyId: UUID) {
        restoreCancelledStudyFormAccessKind(
            connection,
            studyId,
            "ACCESS_CODE",
            "participant_form_access_codes",
            "access_code_id",
        )
        restoreCancelledStudyFormAccessKind(
            connection,
            studyId,
            "SESSION",
            "participant_form_sessions",
            "session_id",
        )
        connection.prepareStatement(
            """
            DELETE FROM data_deletion_form_access_revocations AS claim
            USING data_deletion_operations AS operation
            WHERE claim.operation_id = operation.operation_id
              AND claim.study_id = ?
              AND operation.mode = 'STUDY_ERASURE'
              AND operation.status = 'CANCELLED'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeUpdate()
        }
    }

    private fun restoreCancelledStudyFormAccessKind(
        connection: Connection,
        studyId: UUID,
        resourceKind: String,
        targetTable: String,
        resourceIdColumn: String,
    ) {
        require(resourceKind == "ACCESS_CODE" || resourceKind == "SESSION")
        require(
            (targetTable == "participant_form_access_codes" && resourceIdColumn == "access_code_id") ||
                (targetTable == "participant_form_sessions" && resourceIdColumn == "session_id")
        )
        connection.prepareStatement(
            """
            WITH restorable AS (
                SELECT claim.resource_id,
                       max(claim.original_revoked_at) AS original_revoked_at,
                       min(claim.revoked_at) AS workflow_revoked_at
                FROM data_deletion_form_access_revocations AS claim
                JOIN data_deletion_operations AS operation
                  ON operation.operation_id = claim.operation_id
                 AND operation.study_id = claim.study_id
                WHERE claim.study_id = ?
                  AND claim.resource_kind = ?
                  AND operation.mode = 'STUDY_ERASURE'
                  AND operation.status = 'CANCELLED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM data_deletion_form_access_revocations AS other_claim
                      JOIN data_deletion_operations AS other_operation
                        ON other_operation.operation_id = other_claim.operation_id
                       AND other_operation.study_id = other_claim.study_id
                      WHERE other_claim.resource_kind = claim.resource_kind
                        AND other_claim.resource_id = claim.resource_id
                        AND other_operation.status <> 'CANCELLED'
                  )
                GROUP BY claim.resource_id
            )
            UPDATE $targetTable AS target
            SET revoked_at = restorable.original_revoked_at
            FROM restorable
            WHERE target.$resourceIdColumn = restorable.resource_id
              AND target.study_id = ?
              AND target.revoked_at IS NOT DISTINCT FROM restorable.workflow_revoked_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, resourceKind)
            statement.setObject(3, studyId)
            statement.executeUpdate()
        }
    }

    private fun revokeParticipantExports(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        participantId: String,
    ) {
        connection.prepareStatement(
            """
            WITH candidates AS (
                SELECT job.export_id, job.study_id
                FROM export_jobs AS job
                WHERE job.study_id = ?
                  AND CASE
                      WHEN NOT jsonb_exists(job.request, 'participantIds') THEN TRUE
                      WHEN jsonb_typeof(job.request -> 'participantIds') <> 'array' THEN TRUE
                      ELSE jsonb_array_length(job.request -> 'participantIds') = 0
                           OR jsonb_exists(job.request -> 'participantIds', ?)
                  END
                FOR UPDATE OF job
            )
            INSERT INTO export_job_revocations (export_id, operation_id, study_id)
            SELECT export_id, ?, study_id
            FROM candidates
            ON CONFLICT (export_id, operation_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setObject(3, operationId)
            statement.executeUpdate()
        }
    }

    private fun revokeStudyExports(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
    ) {
        connection.prepareStatement(
            """
            WITH candidates AS (
                SELECT job.export_id, job.study_id
                FROM export_jobs AS job
                WHERE job.study_id = ?
                FOR UPDATE OF job
            )
            INSERT INTO export_job_revocations (export_id, operation_id, study_id)
            SELECT export_id, ?, study_id
            FROM candidates
            ON CONFLICT (export_id, operation_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setObject(2, operationId)
            statement.executeUpdate()
        }
    }

    private fun eraseExportArtifacts(claim: ClaimedDeletion) {
        val operation = claim.operation
        storageResolver.getPlatformStorage().connection.use { connection ->
            var exportStorageLock: AutoCloseable? = null
            connection.autoCommit = false
            try {
                acquireDeletionStudyLock(connection, operation.studyId)
                exportStorageLock = ExportFileWriter.acquireStudyExportLock(operation.studyId)
                sweepRevokedExportArtifacts(connection, claim)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                try {
                    exportStorageLock?.close()
                } catch (lockFailure: Exception) {
                    logger.error(
                        "Failed to release export storage lock for deletion operation {}",
                        operation.operationId,
                        lockFailure,
                    )
                }
                connection.autoCommit = true
            }
        }
    }

    /**
     * Caller must hold the exclusive per-study deletion advisory lock in this
     * transaction. Re-running this immediately before completion closes the
     * insert-after-initial-sweep window created by long erasure operations.
     */
    private fun sweepRevokedExportArtifacts(connection: Connection, claim: ClaimedDeletion) {
        val operation = claim.operation
        renewDeletionLease(connection, claim)
        if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
            revokeStudyExports(connection, operation.operationId, operation.studyId)
        } else {
            revokeParticipantExports(
                connection,
                operation.operationId,
                operation.studyId,
                requireNotNull(operation.participantId),
            )
        }

        val artifacts = connection.prepareStatement(
            """
            SELECT job.export_id, job.file_path
            FROM export_jobs AS job
            JOIN export_job_revocations AS revocation
              ON revocation.export_id = job.export_id
            WHERE revocation.operation_id = ?
            FOR UPDATE OF job
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operation.operationId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            resultSet.getObject("export_id", UUID::class.java) to
                                resultSet.getString("file_path"),
                        )
                    }
                }
            }
        }

        artifacts.forEach { (exportId, filePath) ->
            ExportFileWriter.deleteExportArtifactsForErasure(exportId, filePath)
        }
        connection.prepareStatement(
            """
            DELETE FROM export_capacity_reservations AS reservation
            USING export_job_revocations AS revocation
            WHERE revocation.operation_id = ?
              AND revocation.export_id = reservation.export_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operation.operationId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE export_jobs AS job
            SET status = 'FAILED',
                completed_at = now(),
                download_token = NULL,
                error_message = 'Export revoked by verified data erasure',
                file_path = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            FROM export_job_revocations AS revocation
            WHERE revocation.operation_id = ?
              AND revocation.export_id = job.export_id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operation.operationId)
            statement.executeUpdate()
        }
        renewDeletionLease(connection, claim)
    }

    private fun discoverStudyTables(connection: Connection): List<String> =
        connection.prepareStatement(
            """
            SELECT columns.table_name
            FROM information_schema.columns columns
            JOIN information_schema.tables tables
              ON tables.table_schema = columns.table_schema
             AND tables.table_name = columns.table_name
            WHERE columns.table_schema = current_schema()
              AND columns.column_name = 'study_id'
              AND tables.table_type = 'BASE TABLE'
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val tableName = resultSet.getString("table_name")
                        if (tableName !in STUDY_ERASURE_EXCLUSIONS) {
                            check(SQL_IDENTIFIER.matches(tableName)) {
                                "Study erasure found an untrusted study-scoped table identifier"
                            }
                            add(tableName)
                        }
                    }
                }.distinct().sortedWith(
                    compareBy<String> { studyTablePriority(it) }.thenBy { it }
                )
            }
        }

    private fun discoverStudyTablesForErasure(
        connection: Connection,
        studyId: UUID,
    ): List<String> =
        connection.prepareStatement(
            "SELECT table_name FROM chronicle_discover_study_erasure_tables(?)"
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val tableName = resultSet.getString("table_name")
                        check(SQL_IDENTIFIER.matches(tableName)) {
                            "Study erasure found an untrusted study-scoped table identifier"
                        }
                        add(tableName)
                    }
                }.distinct().sortedWith(
                    compareBy<String> { studyTablePriority(it) }.thenBy { it }
                )
            }
        }

    private fun lockStudyTableForErasure(
        connection: Connection,
        tableName: String,
        studyId: UUID,
    ) {
        connection.prepareStatement(
            "SELECT chronicle_lock_study_table_for_erasure(?, ?)"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setObject(2, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Study table lock was not acquired for $tableName" }
            }
        }
    }

    private fun studyTablePriority(tableName: String): Int = when (tableName) {
        "pipeline_runs" -> 80
        "jobs" -> 90
        "participant_form_submission_receipts" -> 470
        "participant_form_sessions" -> 480
        "participant_form_access_codes" -> 490
        "questionnaires" -> 800
        "organization_studies" -> 900
        "study_participants" -> 910
        "study_limits" -> 920
        "studies" -> 1_000
        else -> 100
    }

    private fun requireTrustedStudyTable(tableName: String) {
        require(SQL_IDENTIFIER.matches(tableName) && tableName !in STUDY_ERASURE_EXCLUSIONS) {
            "Untrusted or retained study table"
        }
    }

    private fun countParticipantRows(
        connection: Connection,
        asset: ParticipantDataAsset,
        studyId: UUID,
        participantId: String,
    ): Long {
        val participantPredicate = when (asset.participantScope) {
            ParticipantScope.SCALAR_COLUMN -> "participant_id = ?"
            ParticipantScope.TEXT_ARRAY_COLUMN -> "? = ANY(participant_ids)"
        }
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM ${asset.tableName} WHERE study_id::text = ? AND $participantPredicate"
        ).use { statement ->
            statement.setString(1, studyId.toString())
            statement.setString(2, participantId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "No count returned for registered asset ${asset.id}" }
                resultSet.getLong(1)
            }
        }
    }

    private fun deleteParticipantRows(
        connection: Connection,
        asset: ParticipantDataAsset,
        studyId: UUID,
        participantId: String,
    ): Long {
        val participantPredicate = when (asset.participantScope) {
            ParticipantScope.SCALAR_COLUMN -> "participant_id = ?"
            ParticipantScope.TEXT_ARRAY_COLUMN -> "? = ANY(participant_ids)"
        }
        return connection.prepareStatement(
            "DELETE FROM ${asset.tableName} WHERE study_id::text = ? AND $participantPredicate"
        ).use { statement ->
            statement.setString(1, studyId.toString())
            statement.setString(2, participantId)
            statement.executeUpdate().toLong()
        }
    }

    private fun countStudyRows(connection: Connection, tableName: String, studyId: UUID): Long {
        requireTrustedStudyTable(tableName)
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM $tableName WHERE study_id::text = ?"
        ).use { statement ->
            statement.setString(1, studyId.toString())
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "No count returned for study asset $tableName" }
                resultSet.getLong(1)
            }
        }
    }

    private fun countStudyRowsForErasure(
        connection: Connection,
        tableName: String,
        studyId: UUID,
    ): Long {
        requireTrustedStudyTable(tableName)
        return connection.prepareStatement(
            "SELECT chronicle_count_study_rows_for_erasure(?, ?)"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setObject(2, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "No verification count returned for study asset $tableName" }
                resultSet.getLong(1)
            }
        }
    }

    private fun deleteStudyRows(connection: Connection, tableName: String, studyId: UUID): Long {
        requireTrustedStudyTable(tableName)
        return connection.prepareStatement(
            "SELECT chronicle_delete_study_rows(?, ?)"
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setObject(2, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "No deletion count returned for study asset $tableName" }
                resultSet.getLong(1)
            }
        }
    }

    private fun enqueueDeletionAuditEvent(
        connection: Connection,
        operationId: UUID,
        studyId: UUID,
        eventType: AuditEventType,
        actor: String,
        description: String,
        data: Map<String, Any>,
        holdId: UUID? = null,
    ) {
        check(
            eventType == AuditEventType.CANCEL_DATA_DELETION ||
                eventType == AuditEventType.PLACE_RETENTION_HOLD ||
                eventType == AuditEventType.RELEASE_RETENTION_HOLD
        ) { "Unsupported deletion audit event type" }
        val eventId = deletionAuditEventId(operationId, eventType, holdId)
        val eventData = data + ("actor" to actor)
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_audit_outbox (
                event_id, operation_id, study_id, hold_id, event_type,
                actor, description, event_data, event_timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, operationId)
            statement.setObject(3, studyId)
            statement.setObject(4, holdId)
            statement.setString(5, eventType.name)
            statement.setString(6, actor)
            statement.setString(7, description)
            statement.setString(8, mapper.writeValueAsString(eventData))
            statement.setObject(9, now())
            statement.executeUpdate()
        }
    }

    /**
     * Projects durable deletion-accountability events into Chronicle's append-only audit table.
     *
     * A failed or uncertain projection never changes the already committed domain result.
     * The exact original event timestamp and payload are retained for replay, so the vanilla
     * Postgres auditing manager's ON CONFLICT behavior safely deduplicates a crash between
     * audit insertion and the published_at update.
     */
    @Suppress("TooGenericExceptionCaught")
    public open fun publishPendingDeletionAuditEvents(limit: Int = MAX_AUDIT_PUBLISH_BATCH): Int {
        require(limit in 1..MAX_AUDIT_PUBLISH_BATCH)
        var published = 0
        repeat(limit) {
            val claim = try {
                claimPendingDeletionAuditEvent()
            } catch (exception: Exception) {
                logger.error("Unable to claim a durable deletion audit event", exception)
                return published
            } ?: return published

            try {
                auditingManager.recordEvents(
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(claim.operationId),
                            securablePrincipalId = IdConstants.CHRONICLE.id,
                            principal = SystemUser.CHRONICLE.principal,
                            eventType = claim.eventType,
                            description = claim.description,
                            study = claim.studyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = claim.data,
                            timestamp = claim.eventTimestamp,
                        )
                    )
                )
            } catch (exception: Exception) {
                rescheduleDeletionAuditEvent(claim, exception)
                logger.warn(
                    "Deletion audit event {} remains durable for retry after publication failed",
                    claim.eventId,
                    exception,
                )
                return published
            }

            try {
                if (markDeletionAuditEventPublished(claim)) {
                    published += 1
                } else {
                    logger.warn(
                        "Deletion audit event {} was published after its local lease expired; replay will deduplicate it",
                        claim.eventId,
                    )
                }
            } catch (exception: Exception) {
                logger.warn(
                    "Deletion audit event {} was published but its local receipt could not be persisted; replay will deduplicate it",
                    claim.eventId,
                    exception,
                )
                return published
            }
        }
        return published
    }

    private fun claimPendingDeletionAuditEvent(): ClaimedDeletionAuditEvent? {
        val leaseToken = UUID.randomUUID()
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                WITH candidate AS (
                    SELECT event_id
                    FROM data_deletion_audit_outbox
                    WHERE published_at IS NULL
                      AND available_at <= now()
                      AND (lease_token IS NULL OR lease_expires_at <= now())
                    ORDER BY available_at, event_timestamp
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE data_deletion_audit_outbox AS event
                SET lease_token = ?,
                    lease_expires_at = now() + (? * interval '1 minute'),
                    publish_attempt_count = publish_attempt_count + 1,
                    last_error_code = NULL
                FROM candidate
                WHERE event.event_id = candidate.event_id
                RETURNING event.event_id, event.operation_id, event.study_id,
                          event.event_type, event.description, event.event_data,
                          event.event_timestamp
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, leaseToken)
                statement.setLong(2, AUDIT_PUBLISH_LEASE_MINUTES)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        ClaimedDeletionAuditEvent(
                            eventId = resultSet.getObject("event_id", UUID::class.java),
                            operationId = resultSet.getObject("operation_id", UUID::class.java),
                            studyId = resultSet.getObject("study_id", UUID::class.java),
                            eventType = AuditEventType.valueOf(resultSet.getString("event_type")),
                            description = resultSet.getString("description"),
                            data = mapper.readValue(resultSet.getString("event_data"), AUDIT_DATA_TYPE),
                            eventTimestamp = resultSet.getObject("event_timestamp", OffsetDateTime::class.java),
                            leaseToken = leaseToken,
                        )
                    }
                }
            }
        }
    }

    private fun markDeletionAuditEventPublished(claim: ClaimedDeletionAuditEvent): Boolean =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_audit_outbox
                SET published_at = now(),
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    last_error_code = NULL
                WHERE event_id = ? AND lease_token = ? AND published_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, claim.eventId)
                statement.setObject(2, claim.leaseToken)
                statement.executeUpdate() == 1
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun rescheduleDeletionAuditEvent(
        claim: ClaimedDeletionAuditEvent,
        failure: Exception,
    ) {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_audit_outbox
                    SET available_at = now() + interval '1 minute',
                        lease_token = NULL,
                        lease_expires_at = NULL,
                        last_error_code = ?
                    WHERE event_id = ? AND lease_token = ? AND published_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, failure.javaClass.simpleName.take(128))
                    statement.setObject(2, claim.eventId)
                    statement.setObject(3, claim.leaseToken)
                    statement.executeUpdate()
                }
            }
        } catch (persistenceFailure: Exception) {
            logger.error(
                "Unable to reschedule durable deletion audit event {}; its lease will expire for retry",
                claim.eventId,
                persistenceFailure,
            )
        }
    }

    private fun deletionAuditEventId(
        operationId: UUID,
        eventType: AuditEventType,
        holdId: UUID?,
    ): UUID = UUID.nameUUIDFromBytes( // nosemgrep: chronicle-uuid-from-string -- deterministic audit idempotency key
        "chronicle-deletion-audit:${eventType.name}:$operationId:${holdId ?: operationId}"
            .toByteArray(StandardCharsets.UTF_8)
    )

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
