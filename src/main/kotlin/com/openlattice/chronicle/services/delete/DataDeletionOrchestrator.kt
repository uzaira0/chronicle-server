package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.withTransaction
import com.fasterxml.jackson.core.type.TypeReference
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditObservation
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SystemUser
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.mapstores.stats.TransactionOnlyParticipantStatsCache
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeRepository
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeRevocationScope
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.DeletionTableLockOrder
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.slf4j.LoggerFactory

public enum class DataDeletionMode {
    COLLECTED_DATA_PURGE,
    WITHDRAW_AND_ERASE,
    STUDY_ERASURE,
}

internal data class ParticipantQuarantineDecision<T>(
    val result: T,
    val createOperation: Boolean,
    val auditEvent: AuditableEvent? = null,
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
    val participantBlockToken: String?,
    val mode: DataDeletionMode,
    val status: String,
    val requestedBy: String,
    val idempotencyKey: UUID,
    val cancelledBy: String?,
    val cancelledAt: OffsetDateTime?,
    val hasSourceTombstone: Boolean,
    val startedAt: OffsetDateTime? = null,
)

private data class ParticipantQuarantineRequest(
    val studyId: UUID,
    val participantId: String,
    val participantRef: String,
    val mode: DataDeletionMode,
    val requestedBy: String,
    val idempotencyKey: UUID,
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
 * inaccessible to the application role. Physical erasure starts only when an owner runs it
 * ([eraseNow]); time alone never erases. Each asset is deleted and then independently
 * verified before a non-identifying proof tombstone is committed.
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
        internal const val DELETION_AUDIT_EVENT_ID = "deletionAuditEventId"
        private val logger = LoggerFactory.getLogger(DataDeletionOrchestrator::class.java)
        private val SQL_IDENTIFIER = Regex("^[a-z][a-z0-9_]*$")
        private const val REGISTRY_VERSION = 1
        // How long an erased device key can still learn ERASED, counted from erasure.
        private const val ERASED_DEVICE_KEY_TOMBSTONE_DAYS = 365
        // The scheduler only resumes erasures an owner already started: a dead worker's run or a failed attempt.
        private const val RESUMABLE_OPERATION_PREDICATE_SQL = """
            (${DeletionOperationRepository.INTERRUPTED_ERASURE_SQL}
             OR (operation.status = 'FAILED' AND operation.started_at IS NOT NULL AND operation.next_attempt_at <= now()))
        """
        private const val MAX_PROCESS_BATCH = 25
        private const val DELETION_LEASE_MINUTES = 30L
        private const val MAX_AUDIT_PUBLISH_BATCH = 25
        private const val AUDIT_PUBLISH_LEASE_MINUTES = 5L
        private const val MAX_STUDY_ERASURE_GENERATIONS = 1_000
        private val AUDIT_DATA_TYPE = object : TypeReference<Map<String, Any>>() {}
        private val STUDY_ERASURE_EXCLUSIONS = setOf(
            "audit",
            "audit_buffer",
            "study_settings_audit",
            "participant_collection_acknowledgment",
            "study_lifecycle_events",
            "data_deletion_operations",
            "data_deletion_steps",
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
     * Its optional audit event is enqueued after operation creation in the same transaction.
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
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")

        var insertedNewOperation: Boolean? = null
        val result = participantStatsCache.quarantineParticipant(studyId, participantId) {
            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<ParticipantQuarantineTransactionResult<T>>(connection, auditingManager)
                .transaction {
                    DeletionStudyFence.exclusive(connection, studyId)
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
                                now = now,
                            ),
                        ).also { insertedNewOperation = it.inserted }
                    } else {
                        null
                    }
                    decision.auditEvent?.let { event ->
                        enqueueDeletionAuditEvent(
                            connection = connection,
                            operationId = checkNotNull(quarantine).operationId,
                            studyId = studyId,
                            eventType = event.eventType,
                            actor = requestedBy,
                            description = event.description,
                            data = event.data,
                        )
                    }
                    ParticipantQuarantineTransactionResult(decision.result, quarantine?.operationId)
                }.audit { emptyList() }.buildAndRun()
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
        transaction: (Connection, UUID, Boolean) -> Unit,
    ): UUID {
        storageResolver.requireDeletionStorageColocated(studyId)
        val now = now()
        val result = participantStatsCache.quarantineStudy(studyId) {
            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<StudyQuarantineResult>(connection, auditingManager)
                .transaction {
                    DeletionStudyFence.exclusive(connection, studyId)
                    val quarantine = createStudyQuarantine(
                        connection = connection,
                        studyId = studyId,
                        requestedBy = requestedBy,
                        idempotencyKey = idempotencyKey,
                        now = now,
                    )
                    transaction(connection, quarantine.operationId, quarantine.inserted)
                    quarantine
                }.audit { emptyList() }.buildAndRun()
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
     * restore workflow supplies only the durable operation identity.
     */
    internal open fun reconcileRestoredOperation(operation: RestoredDeletionOperation): Boolean {
        require(operation.status != "PREVIEW") { "A restore checkpoint cannot contain an uncommitted deletion preview" }
        storageResolver.requireDeletionStorageColocated(operation.studyId)

        val reconcile = {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.withTransaction {
                    DeletionStudyFence.exclusive(connection, operation.studyId)
                    validateRestoredOperationIdentity(connection, operation)
                    if (operation.status == "CANCELLED") {
                        reconcileRestoredCancellation(connection, operation)
                    } else {
                        rearmRestoredOperation(connection, operation)
                    }
                    connection.commit()
                    operation.hasSourceTombstone
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
    internal open fun processRestoredCompletedOperation(operationId: UUID) {
        RLSRequestContext.withDeletionWorkerContext {
            publishPendingDeletionAuditEvents()
            val claim = claimOperation(DeletionOperationRepository.ERASABLE_SQL, operationId)
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
            SELECT operation_id, study_id, participant_block_token, mode, idempotency_key
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
                    // Completion clears participant_id; the block token names the subject either way.
                    check(resultSet.getString("participant_block_token") == operation.participantBlockToken) {
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

    private fun reconcileRestoredCancellation(connection: Connection, operation: RestoredDeletionOperation) {
        require(operation.mode == DataDeletionMode.STUDY_ERASURE) {
            "Only a study erasure can be cancelled"
        }
        val actor = requireNotNull(operation.cancelledBy).trim()
        require(actor.isNotEmpty()) { "Cancelled restore deletion is missing its actor" }
        val cancelledAt = requireNotNull(operation.cancelledAt) {
            "Cancelled restore deletion is missing its timestamp"
        }
        upsertRestoredOperation(connection, operation, "PREVIEW")
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
    }

    private fun rearmRestoredOperation(connection: Connection, operation: RestoredDeletionOperation) {
        deleteRestoredOperationDependents(connection, operation.operationId)
        upsertRestoredOperation(connection, operation, "PREVIEW")

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
        } else {
            val participantId = requireNotNull(operation.participantId)
            ChronicleDataAssetRegistry.assetsFor(operation.mode).forEachIndexed { ordinal, asset ->
                insertStep(
                    connection,
                    operation.operationId,
                    operation.studyId,
                    asset,
                    ordinal,
                    countParticipantRows(connection, asset, operation.studyId, participantId),
                )
            }
            revokeParticipantFormAccess(connection, operation.studyId, participantId, now(), operation.mode)
        }
        activateQuarantine(connection, operation.operationId)
        // An erasure an owner had started before the backup resumes as an interrupted run; a request stays pending.
        if (operation.hasSourceTombstone || operation.startedAt != null ||
            operation.status in setOf("ERASING", "VERIFYING", "FAILED", "COMPLETED")) {
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'FAILED', failure_code = 'RestoredFromBackup', started_at = COALESCE(started_at, now()),
                    next_attempt_at = now(), updated_at = now()
                WHERE operation_id = ? AND status = 'QUARANTINED'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operation.operationId)
                check(statement.executeUpdate() == 1) { "Restored erasure did not resume" }
            }
        }
    }

    private fun upsertRestoredOperation(
        connection: Connection,
        operation: RestoredDeletionOperation,
        status: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations (
                operation_id, study_id, participant_ref, participant_id, mode, status,
                requested_by, idempotency_key, registry_version,
                started_at, completed_at, proof_hash, failure_code, operation_attempt_count,
                next_attempt_at, participant_block_token, worker_lease_token,
                worker_lease_expires_at, cancelled_by, cancelled_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0,
                      NULL, ?, NULL, NULL, NULL, NULL)
            ON CONFLICT (operation_id) DO UPDATE SET
                study_id = EXCLUDED.study_id,
                participant_ref = EXCLUDED.participant_ref,
                participant_id = EXCLUDED.participant_id,
                mode = EXCLUDED.mode,
                status = EXCLUDED.status,
                requested_by = EXCLUDED.requested_by,
                idempotency_key = EXCLUDED.idempotency_key,
                registry_version = EXCLUDED.registry_version,
                started_at = EXCLUDED.started_at,
                completed_at = NULL,
                proof_hash = NULL,
                failure_code = NULL,
                operation_attempt_count = 0,
                next_attempt_at = NULL,
                participant_block_token = EXCLUDED.participant_block_token,
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
            statement.setObject(10, operation.startedAt)
            statement.setString(11, operation.participantBlockToken)
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
            "data_deletion_form_access_revocations",
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

    private fun createParticipantQuarantine(
        connection: Connection,
        request: ParticipantQuarantineRequest,
    ): StudyQuarantineResult {
        // Under the study fence, a repeat request for the same erasure joins the open operation.
        openParticipantOperation(connection, request)?.let { return StudyQuarantineResult(it, inserted = false) }
        val operationId = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations
                (operation_id, study_id, participant_ref, participant_id, mode, status,
                 requested_by, idempotency_key, registry_version)
            VALUES (?, ?, ?, ?, ?, 'PREVIEW', ?, ?, ?)
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

        ChronicleDataAssetRegistry.assetsFor(request.mode).forEachIndexed { ordinal, asset ->
            insertStep(
                connection,
                operationId,
                request.studyId,
                asset,
                ordinal,
                countParticipantRows(connection, asset, request.studyId, request.participantId),
            )
        }
        revokeParticipantFormAccess(connection, request.studyId, request.participantId, request.now, request.mode)
        activateQuarantine(connection, operationId)
        return StudyQuarantineResult(operationId, inserted = true)
    }

    private fun openParticipantOperation(connection: Connection, request: ParticipantQuarantineRequest): UUID? =
        connection.prepareStatement(
            """
            SELECT operation_id
            FROM data_deletion_operations
            WHERE study_id = ? AND mode = ? AND status NOT IN ('COMPLETED', 'CANCELLED')
              AND (participant_id = ? OR participant_block_token = chronicle_participant_block_token(study_id, ?))
            ORDER BY created_at, operation_id
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, request.studyId)
            statement.setString(2, request.mode.name)
            statement.setString(3, request.participantId)
            statement.setString(4, request.participantId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("operation_id", UUID::class.java) else null }
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
                    (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version)
                VALUES (?, ?, 'STUDY_ERASURE', 'PREVIEW', ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, candidateOperationId)
                statement.setObject(2, studyId)
                statement.setString(3, requestedBy)
                statement.setObject(4, effectiveIdempotencyKey)
                statement.setInt(5, REGISTRY_VERSION)
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
        activateQuarantine(connection, resolvedOperationId)
        return StudyQuarantineResult(resolvedOperationId, inserted = true)
    }

    private fun studyErasureSuccessorKey(currentKey: UUID, cancelledOperationId: UUID): UUID =
        // deterministic idempotency successor key
        // nosemgrep: chronicle-uuid-from-string
        UUID.nameUUIDFromBytes(
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
            AuditedTransactionBuilder<List<UUID>>(connection, auditingManager)
            .transaction {
                DeletionStudyFence.exclusive(connection, studyId)
                val operationIds = cancelStudyErasure(connection, studyId, actor)
                if (rejectStartedErasure) {
                    check(!hasUncancelledStudyErasure(connection, studyId)) {
                        "Study erasure has already started and can no longer be cancelled"
                    }
                }
                transaction(connection, operationIds.size)
                operationIds
            }.audit { emptyList() }.buildAndRun()
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
              AND status = 'QUARANTINED'
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

    public open fun listOperations(studyId: UUID, limit: Int, offset: Int): List<DataDeletionOperation> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            DeletionOperationRepository(connection).list(studyId, limit, offset)
        }

    public open fun getOperation(operationId: UUID): DataDeletionOperation =
        storageResolver.getPlatformStorage().connection.use { connection ->
            DeletionOperationRepository(connection).get(operationId)
        }

    /**
     * Erases one requested operation now. This explicit owner action is the only start of physical erasure;
     * a request followed by any amount of time erases nothing.
     */
    public open fun eraseNow(operationId: UUID, studyId: UUID, actor: String): DataDeletionOperation =
        RLSRequestContext.withDeletionWorkerContext {
            require(getOperation(operationId).studyId == studyId) { "Deletion operation does not belong to this study" }
            publishPendingDeletionAuditEvents()
            val claim = checkNotNull(claimOperation(DeletionOperationRepository.ERASABLE_SQL, operationId, actor)) {
                "Deletion operation is not waiting for erasure, or another erasure holds its study"
            }
            eraseAndVerify(claim)
            getOperation(operationId)
        }

    /** Resumes a bounded batch of erasures an owner already started; never starts one. */
    public open fun processDueOperations(limit: Int = MAX_PROCESS_BATCH): Int =
        RLSRequestContext.withDeletionWorkerContext {
            publishPendingDeletionAuditEvents()
            expireErasedDeviceKeyTombstones()
            processDueOperationsAsDeletionWorker(limit)
        }

    private fun expireErasedDeviceKeyTombstones() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            ApiKeyService.expireErasedDeviceKeyTombstones(connection)
        }
    }

    private fun processDueOperationsAsDeletionWorker(limit: Int): Int {
        require(limit in 1..MAX_PROCESS_BATCH)
        var processed = 0
        repeat(limit) {
            val claim = claimOperation(RESUMABLE_OPERATION_PREDICATE_SQL) ?: return processed
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

    private fun claimOperation(predicate: String, requiredOperationId: UUID? = null, startedBy: String? = null): ClaimedDeletion? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction {
                claimIn(connection, predicate, requiredOperationId, startedBy)
            }
        }

    /** Claims the first eligible operation once its study fence is held; null, rolled back, when there is none. */
    private fun claimIn(connection: Connection, predicate: String, requiredOperationId: UUID?, startedBy: String?): ClaimedDeletion? {
        val candidate = nextCandidate(connection, predicate, requiredOperationId)
        candidate?.let { DeletionStudyFence.exclusive(connection, it.studyId) }
        val operation = candidate?.let { lockedOperation(connection, predicate, it.operationId) }
        if (operation == null) {
            connection.rollback()
            return null
        }
        val leaseToken = startErasure(connection, operation, startedBy)
        connection.commit()
        return ClaimedDeletion(operation.copy(status = "ERASING", erasable = false), leaseToken)
    }

    private fun nextCandidate(
        connection: Connection,
        predicate: String,
        requiredOperationId: UUID?,
    ): DataDeletionOperation? = connection.prepareStatement(
        """
        SELECT ${DeletionOperationRepository.COLUMNS}
        FROM data_deletion_operations operation
        WHERE (?::uuid IS NULL OR operation.operation_id = ?::uuid)
          AND $predicate
        ORDER BY COALESCE(operation.worker_lease_expires_at, operation.next_attempt_at), operation.created_at
        LIMIT 1
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, requiredOperationId)
        statement.setObject(2, requiredOperationId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) null else DeletionOperationRepository(connection).read(resultSet)
        }
    }

    private fun lockedOperation(connection: Connection, predicate: String, operationId: UUID): DataDeletionOperation? =
        connection.prepareStatement(
            """
            SELECT ${DeletionOperationRepository.COLUMNS}
            FROM data_deletion_operations operation
            WHERE operation.operation_id = ?
              AND $predicate
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) null else DeletionOperationRepository(connection).read(resultSet)
            }
        }

    /** Marks [operation] erasing under a fresh lease, audits who started it and fixes a purge's cutoff. */
    private fun startErasure(connection: Connection, operation: DataDeletionOperation, startedBy: String?): UUID {
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
        startedBy?.let { actor ->
            enqueueDeletionAuditEvent(connection, operation.operationId, operation.studyId,
                AuditEventType.START_DATA_DELETION, actor, "Owner started physical erasure", emptyMap())
        }
        if (operation.mode == DataDeletionMode.COLLECTED_DATA_PURGE) {
            connection.prepareStatement(
                """
                INSERT INTO participant_purge_cutoffs (study_id, participant_block_token, cutoff)
                SELECT study_id, chronicle_participant_block_token(study_id, participant_id), started_at
                FROM data_deletion_operations WHERE operation_id = ?
                ON CONFLICT (study_id, participant_block_token) DO UPDATE
                SET cutoff = GREATEST(participant_purge_cutoffs.cutoff, EXCLUDED.cutoff)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operation.operationId)
                check(statement.executeUpdate() == 1)
            }
        }
        return leaseToken
    }

    @Suppress("TooGenericExceptionCaught")
    private fun eraseAndVerify(claim: ClaimedDeletion) {
        val operation = claim.operation
        val startedAt = System.nanoTime()
        var outcome = "completed"
        try {
            storageResolver.requireDeletionStorageColocated(operation.studyId)
            if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
                eraseStudy(claim)
                completeOperation(claim, null)
                return
            }
            val participantId = requireNotNull(operation.participantId)
            reconcileParticipantSteps(claim, participantId)
            ChronicleDataAssetRegistry.assetsFor(operation.mode).forEach { asset ->
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

    private fun nextStepOrdinal(connection: Connection, operationId: UUID): Int =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM data_deletion_steps WHERE operation_id = ?",
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unable to allocate study erasure step ordinals" }
                resultSet.getInt(1)
            }
        }

    private fun reconcileParticipantSteps(claim: ClaimedDeletion, participantId: String) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction {
                DeletionStudyFence.exclusive(connection, claim.operation.studyId)
                renewDeletionLease(connection, claim)
                val existing = connection.prepareStatement(
                    "SELECT asset_id FROM data_deletion_steps WHERE operation_id = ?",
                ).use { statement ->
                    statement.setObject(1, claim.operation.operationId)
                    statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
                }
                val nextOrdinal = nextStepOrdinal(connection, claim.operation.operationId)
                ChronicleDataAssetRegistry.assetsFor(claim.operation.mode).filterNot { it.id in existing }
                    .forEachIndexed { index, asset ->
                        insertStep(connection, claim.operation.operationId, claim.operation.studyId, asset, nextOrdinal + index,
                            countParticipantRows(connection, asset, claim.operation.studyId, participantId))
                    }
                connection.commit()
            }
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
            connection.withTransaction {
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
            connection.withTransaction {
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
                    val cutoff = purgeCutoff(connection, operation)
                    val deletedRows = deleteParticipantRows(connection, asset, operation.studyId, participantId, cutoff)
                    val residualRows = countParticipantRows(connection, asset, operation.studyId, participantId, cutoff)
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
            connection.withTransaction {
                DeletionStudyFence.exclusive(connection, operation.studyId)
                renewDeletionLease(connection, claim)
                val studyTables = if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
                    reconcileAndLockStudyInventory(connection, claim)
                } else null
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
                refreshFinalVerification(connection, claim, participantId, studyTables)
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
        studyTables: List<String>?,
    ) {
        val operation = claim.operation
        if (operation.mode == DataDeletionMode.STUDY_ERASURE) {
            val tables = requireNotNull(studyTables)
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
        val cutoff = purgeCutoff(connection, operation)
        ChronicleDataAssetRegistry.assetsFor(operation.mode).forEach { asset ->
            renewDeletionLease(connection, claim)
            val deletedRows = deleteParticipantRows(connection, asset, operation.studyId, subjectId, cutoff)
            val residualRows = countParticipantRows(connection, asset, operation.studyId, subjectId, cutoff)
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
            tombstoneDeviceKeys(connection, operation.studyId, subjectId, operation.mode)
            connection.prepareStatement("DELETE FROM api_keys WHERE study_id = ? AND participant_id = ?").use { statement ->
                statement.setObject(1, operation.studyId)
                statement.setString(2, subjectId)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Run at erasure, never at quarantine: during the quarantine the phone keeps polling with its live
     * key and learns NOT_ENROLLED. Every device key (live, revoked or expired) of the participant, or of
     * the whole study when [participantId] is null, leaves only its hash, kind and expiry in
     * erased_device_key_tombstones, so a phone offline through the quarantine still reads NOT_ENROLLED.
     * The tombstone lives [ERASED_DEVICE_KEY_TOMBSTONE_DAYS] from erasure, whatever the key's own expiry:
     * a key that expired during the quarantine or a hold still learns its terminal status.
     * The caller deletes the api_keys rows in the same transaction. Each hash is first locked, in hash
     * order, with the lock key installation holds, so no device can take over the material meanwhile.
     */
    private fun tombstoneDeviceKeys(connection: Connection, studyId: UUID, participantId: String?, mode: DataDeletionMode) {
        connection.prepareStatement(
            """
            SELECT DISTINCT key_hash FROM api_keys
            WHERE study_id = ? AND (?::text IS NULL OR participant_id = ?::text)
              AND participant_id IS NOT NULL AND random_device_id IS NOT NULL
            ORDER BY key_hash
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setString(3, participantId)
            statement.executeQuery().use { resultSet -> buildList { while (resultSet.next()) add(resultSet.getString(1)) } }
        }.forEach { keyHash ->
            connection.prepareStatement(ApiKeyService.LOCK_KEY_MATERIAL_SQL).use { statement ->
                statement.setString(1, keyHash)
                statement.execute()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at)
            SELECT DISTINCT key_hash, ?, now() + interval '$ERASED_DEVICE_KEY_TOMBSTONE_DAYS days'
            FROM api_keys
            WHERE study_id = ? AND (?::text IS NULL OR participant_id = ?::text)
              AND participant_id IS NOT NULL AND random_device_id IS NOT NULL
            ON CONFLICT (key_hash) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, mode.name)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.setString(4, participantId)
            statement.executeUpdate()
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
            val nextOrdinal = nextStepOrdinal(connection, operation.operationId)
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

        // ACCESS SHARE protects the table inventory from DDL. The exclusive study fence
        // and mutation barrier protect the erased study without blocking other studies' writes.
        discoveredTables.sortedWith(DeletionTableLockOrder.comparator).forEach { tableName ->
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
        connection.createStatement().use { it.execute("SET LOCAL statement_timeout = 0") }
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
        SELECT operation_id, study_id, mode,
               (participant_id = ? OR participant_block_token = chronicle_participant_block_token(study_id, ?)) AS same_participant
        FROM data_deletion_operations
        WHERE idempotency_key = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, participantId)
        statement.setString(2, participantId)
        statement.setObject(3, idempotencyKey)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Conflicting deletion operation disappeared" }
            check(
                resultSet.getObject("study_id", UUID::class.java) == studyId &&
                    resultSet.getBoolean("same_participant") &&
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
        mode: DataDeletionMode,
    ) {
        // A purge leaves the device enrolled; revoking its enrollment code would fail every later acknowledgment.
        val scope = if (mode == DataDeletionMode.COLLECTED_DATA_PURGE) {
            ParticipantAccessCodeRevocationScope.KeepExchangedEnrollment
        } else {
            ParticipantAccessCodeRevocationScope.All
        }
        EnrollmentAccessCodeRepository(connection).revoke(
            studyId, participantId, revokedAt, scope,
        )
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
    ) = RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
        // Existing quarantines hide form credentials even from ordinary admins. Each overlapping
        // operation must record its own claims before cancellation can restore workflow changes.
        RLSConnectionCustomizer.setDeletionWorkerContext(connection, transactionLocal = true)
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
        "jobs" -> 90
        "participant_purge_cutoffs" -> 111
        "upload_diagnostic_erasures" -> 110 // Sweep after upload_diagnostics creates its tombstones.
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
        cutoff: OffsetDateTime? = null,
    ): Long {
        return connection.participantErasureStatement(asset, studyId, participantId, ParticipantAssetOperation.COUNT, cutoff).use { statement ->
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
        cutoff: OffsetDateTime? = null,
    ): Long {
        if (cutoff != null && asset.tableName == UPLOAD_BUFFER.name) {
            BufferedUploadPurgeRepository(connection).retain(studyId, participantId, cutoff)
        }
        return connection.participantErasureStatement(asset, studyId, participantId, ParticipantAssetOperation.DELETE, cutoff).use { statement ->
            statement.executeUpdate().toLong()
        }
    }

    private fun purgeCutoff(connection: Connection, operation: DataDeletionOperation): OffsetDateTime? {
        if (operation.mode != DataDeletionMode.COLLECTED_DATA_PURGE) return null
        return connection.prepareStatement("SELECT started_at FROM data_deletion_operations WHERE operation_id = ?").use {
            it.setObject(1, operation.operationId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "Purge operation is missing" }
                checkNotNull(rows.getObject(1, OffsetDateTime::class.java)) { "Purge watermark is missing" }
            }
        }
    }

    private fun Connection.participantErasureStatement(
        asset: ParticipantDataAsset,
        studyId: UUID,
        participantId: String,
        action: ParticipantAssetOperation,
        cutoff: OffsetDateTime?,
    ): PreparedStatement {
        val captureTime = cutoff?.let { asset.captureTimeSql }
        val purgePredicate = when {
            cutoff != null && asset.tableName == UPLOAD_BUFFER.name ->
                " AND jsonb_array_length(${PostgresColumns.UPLOAD_DATA.name}) = 0"
            captureTime != null -> " AND ($captureTime IS NULL OR $captureTime < ?)"
            else -> ""
        }
        return prepareStatement(
            ChronicleDataAssetRegistry.participantStatement(asset, action) + purgePredicate,
        ).apply {
            bindErasureStudyId(studyId)
            setString(2, participantId)
            if (captureTime != null) setObject(3, cutoff)
        }
    }

    private fun countStudyRows(connection: Connection, tableName: String, studyId: UUID): Long {
        requireTrustedStudyTable(tableName)
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM $tableName WHERE $STUDY_ERASURE_PREDICATE"
        ).use { statement ->
            statement.bindErasureStudyId(studyId)
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
        if (tableName == "api_keys") tombstoneDeviceKeys(connection, studyId, null, DataDeletionMode.STUDY_ERASURE)
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
    ) {
        check(
            eventType == AuditEventType.UPDATE_PARTICIPATION_STATUS ||
                eventType == AuditEventType.CANCEL_DATA_DELETION ||
                eventType == AuditEventType.START_DATA_DELETION
        ) { "Unsupported deletion audit event type" }
        val eventId = deletionAuditEventId(operationId, eventType)
        val eventData = data + ("actor" to actor)
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_audit_outbox (
                event_id, operation_id, study_id, event_type,
                actor, description, event_data, event_timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, eventId)
            statement.setObject(2, operationId)
            statement.setObject(3, studyId)
            statement.setString(4, eventType.name)
            statement.setString(5, actor)
            statement.setString(6, description)
            statement.setString(7, mapper.writeValueAsString(eventData))
            statement.setObject(8, now())
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
                auditingManager.recordObservations(AuditObservation.CommittedEffect(
                    listOf(
                        AuditableEvent(
                            aclKey = AclKey(claim.operationId),
                            securablePrincipalId = IdConstants.CHRONICLE.id,
                            principal = SystemUser.CHRONICLE.principal,
                            eventType = claim.eventType,
                            description = claim.description,
                            study = claim.studyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = claim.data + (DELETION_AUDIT_EVENT_ID to claim.eventId.toString()),
                            timestamp = claim.eventTimestamp,
                        )
                    )
                ))
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
    // deterministic audit idempotency key
    // nosemgrep: chronicle-uuid-from-string
    ): UUID = UUID.nameUUIDFromBytes(
        "chronicle-deletion-audit:${eventType.name}:$operationId:$operationId"
            .toByteArray(StandardCharsets.UTF_8)
    )

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
