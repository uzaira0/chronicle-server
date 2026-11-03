package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.clearContext
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.setDeletionWorkerContext
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.sqlCause
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

internal data class RestoreContinuityResult(
    val checkpointId: UUID,
    val withdrawalReceiptCount: Long,
    val replayedCompletedDeletionCount: Long,
    val alreadyProtectedDeletionCount: Long,
)


/**
 * Consumes the owner-only checkpoint left by the guarded self-host restore command.
 *
 * The checkpoint is deliberately outside `public`, so replacing an older public schema
 * cannot erase post-backup withdrawals. Reconciliation is idempotent: exact immutable
 * receipts are replayed, credentials and participation are contained, deletion operations
 * are re-armed through [DataDeletionOrchestrator], and source tombstones are replaced only
 * after a fresh physical erasure proof. Any mismatch leaves the checkpoint in place and
 * aborts startup.
 */
// The reconciliation owner intentionally keeps validation, replay, and verification
// under one recovery lock so a caller cannot apply only part of the restore protocol.
public open class RestoreContinuityReconciler(
    private val storageResolver: StorageResolver,
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
    private val checkpointRepository: RestoreCheckpointRepository =
        RestoreCheckpointRepository(storageResolver.getBootstrapStorage(), CONTRACT_VERSION),
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(RestoreContinuityReconciler::class.java)
        internal val CONTRACT_VERSION = restoreResource("restore-continuity.version").trim().toInt()
        private const val COORDINATION_LOCK_SQL =
            "SELECT pg_advisory_lock(hashtextextended('chronicle-restore-continuity', 0))"
        private const val COORDINATION_UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('chronicle-restore-continuity', 0))"
        // Subject-bound authority cannot protect a subject absent from the backup. Token/hash
        // authority and immutable deletion/withdrawal evidence deliberately have no parent filter.
        private val AUTHORITY_SUBJECTS = run {
            val study = "EXISTS (SELECT 1 FROM studies parent WHERE parent.study_id = source.study_id)"
            val participant = "EXISTS (SELECT 1 FROM study_participants parent " +
                "WHERE parent.study_id = source.study_id AND parent.participant_id = source.participant_id)"
            mapOf(
                "data_collection_settings_revisions" to study,
                "published_data_collection_settings" to study,
                "archived_studies" to study,
                "enrollment_invitations" to participant,
                "restricted_participants" to participant,
                "withdrawn_participants" to "($participant OR EXISTS (SELECT 1 FROM api_keys parent " +
                    "WHERE parent.study_id = source.study_id AND parent.participant_id = source.participant_id))",
                "revoked_api_keys" to "EXISTS (SELECT 1 FROM api_keys parent WHERE parent.key_id = source.key_id)",
                "participant_collection_acknowledgment" to "$participant AND (source.evidence_api_key_id IS NULL " +
                    "OR EXISTS (SELECT 1 FROM api_keys parent WHERE parent.key_id = source.evidence_api_key_id " +
                    "AND parent.study_id = source.study_id AND parent.participant_id = source.participant_id))",
            )
        }
    }

    private fun applicableAuthority(table: String): String =
        "(SELECT source.* FROM chronicle_restore_continuity.$table source WHERE ${AUTHORITY_SUBJECTS.getValue(table)})"

    private fun skippedAuthorityCounts(connection: Connection): String =
        connection.createStatement().use { statement ->
            val counts = AUTHORITY_SUBJECTS.keys.joinToString(", ") { table ->
                "'$table', (SELECT count(*) FROM chronicle_restore_continuity.$table) - " +
                    "(SELECT count(*) FROM ${applicableAuthority(table)} applicable)"
            }
            statement.executeQuery("SELECT jsonb_build_object($counts)::text").use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }

    internal fun reconcile(): RestoreContinuityResult? {
        if (!checkpointRepository.exists()) return null
        val storage = storageResolver.getBootstrapStorage()
        return storage.connection.use { coordinationConnection ->
            coordinationConnection.createStatement().use { it.execute(COORDINATION_LOCK_SQL) }
            PinnedPlatformConnection.bootstrapSharing(storage, coordinationConnection, storageResolver.databaseRoles.runtimePools()) {
                try {
                    if (!checkpointRepository.exists()) return@bootstrapSharing null
                    setDeletionWorkerContext(coordinationConnection, transactionLocal = false)
                    val validatedCheckpoint = loadAndValidateCheckpoint()
                    val checkpoint = validatedCheckpoint.checkpoint
                    val alreadyProtectedOperationIds = validatedCheckpoint.protectedOperationIds
                    val skippedAuthorities = skippedAuthorityCounts(coordinationConnection)
                    val operations = loadOperations().map(::withRestoredSubject)
                    validatePublicConflicts(operations)
                    validateAuthorityConflicts()
                    applyRestoredAuthorities(checkpoint)
                    applyCapturedAuthorities()
                    applyContainmentAndReceipts()
                    applyErasedDeviceKeyTombstones()

                    var completedDeletionCount = 0L
                    operations.forEach { operation ->
                        if (operation.operationId in alreadyProtectedOperationIds) return@forEach
                        if (operation.mode != DataDeletionMode.STUDY_ERASURE && operation.participantId == null) {
                            restoreCompletedErasureOfAbsentSubject(operation)
                            completedDeletionCount += 1
                            return@forEach
                        }
                        val requiresCompletedReplay = dataDeletionOrchestrator.reconcileRestoredOperation(operation)
                        if (requiresCompletedReplay) {
                            dataDeletionOrchestrator.processRestoredCompletedOperation(operation.operationId)
                            completedDeletionCount += 1
                        }
                    }
                    check(
                        completedDeletionCount + alreadyProtectedOperationIds.size ==
                            checkpoint.sourceTombstoneCount,
                    ) {
                        "Restore continuity tombstone replay count does not match the checkpoint"
                    }
                    finalizeReconciliation(
                        checkpoint,
                        alreadyProtectedOperationIds.size.toLong(),
                        completedDeletionCount,
                        skippedAuthorities,
                    )
                    logger.info(
                        "restore_continuity outcome=reconciled checkpoint={} withdrawals={} protected={} replayed={}",
                        LogSanitizer.stableFingerprint(checkpoint.checkpointId.toString(), "checkpoint"),
                        checkpoint.withdrawalReceiptCount,
                        alreadyProtectedOperationIds.size,
                        completedDeletionCount,
                    )
                    RestoreContinuityResult(
                        checkpoint.checkpointId,
                        checkpoint.withdrawalReceiptCount,
                        completedDeletionCount,
                        alreadyProtectedOperationIds.size.toLong(),
                    )
                } finally {
                    clearRecoveryConnection(coordinationConnection)
                }
            }
        }
    }

    private fun clearRecoveryConnection(connection: Connection) {
        try {
            clearContext(connection)
        } finally {
            runCatching {
                connection.createStatement().use { it.execute(COORDINATION_UNLOCK_SQL) }
            }.onFailure { failure ->
                logger.error("Failed to release restore-continuity coordination lock exception={} sql_state={}",
                    failure.javaClass.simpleName, failure.sqlCause()?.sqlState)
            }
        }
    }

    private fun <T> withTransaction(
        connection: Connection,
        afterCommit: () -> Unit = {},
        block: () -> T,
    ): T {
        return connection.withTransaction {
            val result = block()
            connection.commit()
            afterCommit()
            result
        }
    }

    /** The validated checkpoint and the completed operations whose exact proof the backup already holds. */
    private class ValidatedRestoreCheckpoint(
        val checkpoint: RestoreContinuityCheckpoint,
        val protectedOperationIds: Set<UUID>,
    )

    private fun loadAndValidateCheckpoint(): ValidatedRestoreCheckpoint =
        storageResolver.getPlatformStorage().connection.use { connection ->
            val checkpoint = checkpointRepository.readValidated(connection)
            validateCheckpointRelations(connection)
            ValidatedRestoreCheckpoint(checkpoint, loadAlreadyProtectedCompletedOperationIds(connection))
        }

    private fun validateCheckpointRelations(connection: Connection) {
        val invalidRelations = connection.prepareStatement(
            """
            SELECT
                (SELECT count(*)
                 FROM chronicle_restore_continuity.deletion_tombstones tombstone
                 LEFT JOIN chronicle_restore_continuity.deletion_operations operation
                   ON operation.operation_id = tombstone.operation_id
                 WHERE operation.operation_id IS NULL
                    OR operation.status <> 'COMPLETED'
                    OR operation.mode <> tombstone.mode) +
                (SELECT count(*)
                 FROM chronicle_restore_continuity.deletion_operations
                 WHERE mode NOT IN ('COLLECTED_DATA_PURGE', 'WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                    OR status NOT IN ('QUARANTINED', 'ERASING', 'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELLED')
                    OR status = 'PREVIEW'
                    OR (mode = 'STUDY_ERASURE') <> (participant_block_token IS NULL)
                    OR participant_block_token IS DISTINCT FROM
                       COALESCE(chronicle_participant_block_token(study_id, participant_id), participant_block_token)
                    OR (participant_id IS NULL AND mode <> 'STUDY_ERASURE' AND status <> 'COMPLETED')
                    OR (status = 'CANCELLED') <> (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)) +
                (SELECT count(*)
                 FROM chronicle_restore_continuity.deletion_operations operation
                 LEFT JOIN chronicle_restore_continuity.deletion_tombstones tombstone
                   ON tombstone.operation_id = operation.operation_id
                 WHERE (operation.status = 'COMPLETED') <> (tombstone.operation_id IS NOT NULL)
                    OR (tombstone.operation_id IS NOT NULL AND (
                        operation.mode <> tombstone.mode
                        OR operation.registry_version <> tombstone.registry_version
                        OR operation.completed_at IS DISTINCT FROM tombstone.completed_at
                        OR operation.proof_hash IS DISTINCT FROM tombstone.proof_hash
                    )))
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
        check(invalidRelations == 0L) { "Restore continuity checkpoint contains inconsistent deletion evidence" }
    }

    private fun loadOperations(): List<RestoredDeletionOperation> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT operation.operation_id, operation.study_id,
                           operation.participant_ref, operation.participant_id,
                           operation.participant_block_token, operation.mode, operation.status, operation.requested_by,
                           operation.idempotency_key, operation.started_at,
                           operation.cancelled_by, operation.cancelled_at,
                           tombstone.operation_id IS NOT NULL AS has_source_tombstone
                    FROM chronicle_restore_continuity.deletion_operations operation
                    LEFT JOIN chronicle_restore_continuity.deletion_tombstones tombstone
                      ON tombstone.operation_id = operation.operation_id
                    ORDER BY operation.operation_id
                    """.trimIndent(),
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                RestoredDeletionOperation(
                                    operationId = resultSet.getObject("operation_id", UUID::class.java),
                                    studyId = resultSet.getObject("study_id", UUID::class.java),
                                    participantRef = resultSet.getString("participant_ref"),
                                    participantId = resultSet.getString("participant_id"),
                                    participantBlockToken = resultSet.getString("participant_block_token"),
                                    mode = DataDeletionMode.valueOf(resultSet.getString("mode")),
                                    status = resultSet.getString("status"),
                                    requestedBy = resultSet.getString("requested_by"),
                                    idempotencyKey = resultSet.getObject("idempotency_key", UUID::class.java),
                                    cancelledBy = resultSet.getString("cancelled_by"),
                                    cancelledAt = resultSet.getObject("cancelled_at", OffsetDateTime::class.java),
                                    hasSourceTombstone = resultSet.getBoolean("has_source_tombstone"),
                                    startedAt = resultSet.getObject("started_at", OffsetDateTime::class.java),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Completion clears an operation's participant_id and keeps only its participant_block_token. The
     * restored backup may still hold the subject's rows in clear; find the participant id they carry so
     * the canonical erasure can run again. Null when the backup holds nothing of the subject (enrolled
     * after the backup, for instance). The identifier is never written into the checkpoint.
     */
    internal fun withRestoredSubject(operation: RestoredDeletionOperation): RestoredDeletionOperation {
        if (operation.mode == DataDeletionMode.STUDY_ERASURE || operation.status != "COMPLETED") return operation
        val token = requireNotNull(operation.participantBlockToken)
        val sources = buildList<Pair<String, UUID>> {
            add(
                "SELECT participant_id FROM data_deletion_operations " +
                    "WHERE operation_id = ? AND participant_block_token = ?" to operation.operationId,
            )
            (listOf("study_participants", "api_keys") + ChronicleDataAssetRegistry.assetsFor(operation.mode)
                .filter { it.participantScope != ParticipantScope.TEXT_ARRAY_COLUMN &&
                    it.participantScope != ParticipantScope.BLOCK_TOKEN_COLUMN }
                .map(ParticipantDataAsset::tableName))
                .forEach { table ->
                    require(table.matches(Regex("^[a-z_]+$")))
                    add(
                        "SELECT DISTINCT participant_id FROM $table WHERE study_id::text = ?::text " +
                            "AND chronicle_participant_block_token(study_id::uuid, participant_id) = ? LIMIT 2" to operation.studyId,
                    )
                }
            ChronicleDataAssetRegistry.assetsFor(operation.mode)
                .filter { it.participantScope == ParticipantScope.TEXT_ARRAY_COLUMN }
                .forEach { asset ->
                    require(asset.tableName.matches(Regex("^[a-z_]+$")))
                    add(
                        "SELECT DISTINCT subject FROM ${asset.tableName}, unnest(participant_ids) subject " +
                            "WHERE study_id::text = ?::text AND chronicle_participant_block_token(study_id::uuid, subject) = ? LIMIT 2" to
                            operation.studyId,
                    )
                }
        }
        val subjects = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction(rollbackOnSuccess = true) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                buildSet {
                    for ((sql, scope) in sources) {
                        connection.prepareStatement(sql).use { statement ->
                            statement.setObject(1, scope)
                            statement.setString(2, token)
                            statement.executeQuery().use { resultSet ->
                                while (resultSet.next()) resultSet.getString(1)?.let(::add)
                            }
                        }
                        if (isNotEmpty()) break
                    }
                }
            }
        }
        check(subjects.size <= 1) { "Restore continuity deletion subject is ambiguous" }
        return operation.copy(participantId = subjects.firstOrNull())
    }

    /**
     * The restored backup holds nothing of this erased subject: there is nothing to erase again. Restore
     * the completed operation (participant_id stays NULL, its block token keeps fencing re-addition) and
     * its source proof, and drop any token-keyed rows the backup still has.
     */
    private fun restoreCompletedErasureOfAbsentSubject(operation: RestoredDeletionOperation) {
        check(operation.status == "COMPLETED" && operation.hasSourceTombstone) {
            "Only a completed erasure can lack its participant"
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                DeletionStudyFence.exclusive(connection, operation.studyId)
                connection.prepareStatement("DELETE FROM data_deletion_tombstones WHERE operation_id = ?").use { statement ->
                    statement.setObject(1, operation.operationId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO data_deletion_operations (
                        operation_id, study_id, participant_ref, participant_id, participant_block_token,
                        mode, status, requested_by, idempotency_key, registry_version,
                        completed_at, proof_hash
                    )
                    SELECT operation_id, study_id, participant_ref, NULL, participant_block_token,
                           mode, status, requested_by, idempotency_key, registry_version,
                           completed_at, proof_hash
                    FROM chronicle_restore_continuity.deletion_operations
                    WHERE operation_id = ?
                    ON CONFLICT (operation_id) DO UPDATE SET
                        participant_ref = EXCLUDED.participant_ref,
                        participant_block_token = EXCLUDED.participant_block_token,
                        status = EXCLUDED.status,
                        registry_version = EXCLUDED.registry_version,
                        completed_at = EXCLUDED.completed_at,
                        proof_hash = EXCLUDED.proof_hash,
                        updated_at = now()
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operation.operationId)
                    check(statement.executeUpdate() == 1) { "Restored completed erasure was not written" }
                }
                listOf(
                    """
                    INSERT INTO data_deletion_tombstones (
                        operation_id, study_ref, participant_ref, mode, registry_version, completed_at, proof_hash
                    )
                    SELECT operation_id, study_ref, participant_ref, mode, registry_version, completed_at, proof_hash
                    FROM chronicle_restore_continuity.deletion_tombstones
                    WHERE operation_id = ?
                    """,
                ).forEach { sql ->
                    connection.prepareStatement(sql.trimIndent()).use { statement ->
                        statement.setObject(1, operation.operationId)
                        statement.executeUpdate()
                    }
                }
                ChronicleDataAssetRegistry.assetsFor(operation.mode)
                    .filter { it.participantScope == ParticipantScope.BLOCK_TOKEN_COLUMN }
                    .forEach { asset ->
                        require(asset.tableName.matches(Regex("^[a-z_]+$")))
                        connection.prepareStatement(
                            "DELETE FROM ${asset.tableName} WHERE study_id = ? AND participant_block_token = ?",
                        ).use { statement ->
                            statement.setObject(1, operation.studyId)
                            statement.setString(2, operation.participantBlockToken)
                            statement.executeUpdate()
                        }
                    }
            }
        }
    }

    private fun applyCapturedAuthorities() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                        INSERT INTO participant_purge_cutoffs (study_id, participant_block_token, cutoff)
                        SELECT study_id, participant_block_token, cutoff
                        FROM chronicle_restore_continuity.participant_purge_cutoffs
                        ON CONFLICT (study_id, participant_block_token) DO UPDATE
                        SET cutoff = GREATEST(participant_purge_cutoffs.cutoff, EXCLUDED.cutoff)
                    """.trimIndent())
                    statement.executeUpdate("""
                        INSERT INTO participant_collection_acknowledgment
                        SELECT (jsonb_populate_record(NULL::participant_collection_acknowledgment, to_jsonb(source))).*
                        FROM ${applicableAuthority("participant_collection_acknowledgment")} source
                        ORDER BY source.settings_version, source.recorded_at, source.id
                        ON CONFLICT (id) DO NOTHING
                    """.trimIndent())
                    statement.executeQuery("""
                        SELECT count(*) FROM ${applicableAuthority("participant_collection_acknowledgment")} source
                        LEFT JOIN participant_collection_acknowledgment target USING (id)
                        WHERE to_jsonb(source) IS DISTINCT FROM to_jsonb(target)
                    """.trimIndent()).use { rows ->
                        check(rows.next() && rows.getLong(1) == 0L) { "Conflicting restored collection acknowledgment" }
                    }
                }
            }
        }
    }

    /**
     * Erasure deleted these device keys after keeping their hashes. A key enrolled after the backup
     * exists nowhere in it, so no replay can rebuild its tombstone: carry it with its original expiry.
     * Runs before any replay, so a re-erasure never extends a carried tombstone. A tombstone the
     * restored schema already has for the same hash takes the checkpoint's kind and original expiry.
     */
    private fun applyErasedDeviceKeyTombstones() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at)
                        SELECT key_hash, kind, expires_at
                        FROM chronicle_restore_continuity.erased_device_key_tombstones
                        ON CONFLICT (key_hash) DO UPDATE
                        SET kind = EXCLUDED.kind, expires_at = EXCLUDED.expires_at
                        """.trimIndent(),
                    )
                    statement.executeQuery(
                        """
                        SELECT count(*)
                        FROM chronicle_restore_continuity.erased_device_key_tombstones source
                        WHERE NOT EXISTS (
                              SELECT 1 FROM erased_device_key_tombstones target
                              WHERE target.key_hash = source.key_hash
                                AND target.kind = source.kind
                                AND target.expires_at = source.expires_at
                          )
                        """.trimIndent(),
                    ).use { resultSet ->
                        check(resultSet.next() && resultSet.getLong(1) == 0L) {
                            "Restore continuity erased device key tombstones could not be proven"
                        }
                    }
                }
            }
        }
    }

    private fun validatePublicConflicts(operations: List<RestoredDeletionOperation>) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val withdrawalConflicts = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM chronicle_restore_continuity.withdrawal_requests source
                    JOIN mobile_withdrawal_requests target
                      ON target.request_id = source.request_id
                      OR target.api_key_id = source.api_key_id
                    WHERE target.request_id <> source.request_id
                       OR target.api_key_id <> source.api_key_id
                       OR target.study_id <> source.study_id
                       OR target.participant_id <> source.participant_id
                       OR target.random_device_id <> source.random_device_id
                       OR target.already_withdrawn <> source.already_withdrawn
                       OR target.created_at <> source.created_at
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
            check(withdrawalConflicts == 0L) { "Restore continuity conflicts with immutable withdrawal evidence" }

            val deletionIdentityConflicts = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM chronicle_restore_continuity.deletion_operations source
                    JOIN data_deletion_operations target
                      ON target.operation_id = source.operation_id
                      OR target.idempotency_key = source.idempotency_key
                    WHERE target.operation_id <> source.operation_id
                       OR target.idempotency_key <> source.idempotency_key
                       OR target.study_id <> source.study_id
                       OR target.participant_block_token IS DISTINCT FROM source.participant_block_token
                       OR target.mode <> source.mode
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
            check(deletionIdentityConflicts == 0L) {
                "Restore continuity conflicts with an existing deletion identity"
            }

            val operationIds = operations.map(RestoredDeletionOperation::operationId).toSet()
            val idempotencyKeys = operations.map(RestoredDeletionOperation::idempotencyKey).toSet()
            check(operationIds.size == operations.size && idempotencyKeys.size == operations.size) {
                "Restore continuity contains duplicate deletion identities"
            }
        }
    }

    private fun validateAuthorityConflicts() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val conflicts = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT
                        (SELECT count(*)
                         FROM ${applicableAuthority("data_collection_settings_revisions")} source
                         JOIN data_collection_settings_revisions target
                           ON target.study_id = source.study_id
                          AND target.settings_version = source.settings_version
                         WHERE target.setting <> source.setting OR target.study_modules <> source.study_modules) +
                        (SELECT count(*)
                         FROM ${applicableAuthority("published_data_collection_settings")} source
                         JOIN studies target ON target.study_id = source.study_id
                         WHERE jsonb_typeof(target.settings -> 'DataCollection') = 'object'
                           AND target.settings -> 'DataCollection' ->> 'settingsVersion' ~ '^[1-9][0-9]*$'
                           AND (
                               (target.settings -> 'DataCollection' ->> 'settingsVersion')::INTEGER >
                                   source.settings_version
                               OR (
                                   (target.settings -> 'DataCollection' ->> 'settingsVersion')::INTEGER =
                                       source.settings_version
                                   AND target.settings -> 'DataCollection' <> source.setting
                               )
                           )) +
                        (SELECT count(*)
                         FROM ${applicableAuthority("enrollment_invitations")} source
                         JOIN participant_form_access_codes target
                           ON target.access_code_id = source.access_code_id
                           OR target.token_hash = source.token_hash
                         WHERE target.access_code_id <> source.access_code_id
                            OR target.token_hash <> source.token_hash
                            OR target.study_id <> source.study_id
                            OR target.participant_id <> source.participant_id
                            OR target.form_kind <> source.form_kind
                            OR (target.exchanged_at IS NOT NULL AND source.exchanged_at IS NULL)
                            OR (target.revoked_at IS NOT NULL AND source.revoked_at IS NULL)
                            OR (target.enrollment_attempt_id IS NOT NULL AND
                                target.enrollment_attempt_id IS DISTINCT FROM source.enrollment_attempt_id)
                            OR (target.enrollment_request_hash IS NOT NULL AND
                                target.enrollment_request_hash IS DISTINCT FROM source.enrollment_request_hash)
                            OR (target.enrollment_proposed_key_hash IS NOT NULL AND
                                target.enrollment_proposed_key_hash IS DISTINCT FROM source.enrollment_proposed_key_hash))
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
            check(conflicts == 0L) { "Restore continuity conflicts with monotonic security authority" }
        }
    }

    // This is one database transaction by design: splitting the statements across
    // independently callable methods would make partial authority replay easier.
    private fun applyRestoredAuthorities(checkpoint: RestoreContinuityCheckpoint) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                            DO ${'$'}block${'$'}
                            DECLARE revision RECORD;
                            BEGIN
                                FOR revision IN
                                    SELECT source.study_id, source.setting, source.study_modules
                                    FROM ${applicableAuthority("data_collection_settings_revisions")} source
                                    ORDER BY source.study_id, source.settings_version
                                LOOP
                                    UPDATE studies
                                    SET settings = jsonb_set(
                                        COALESCE(settings, '{}'::jsonb),
                                        '{DataCollection}', revision.setting, true
                                    ), modules = revision.study_modules
                                    WHERE study_id = revision.study_id;
                                END LOOP;

                                FOR revision IN
                                    SELECT source.study_id, source.setting, source.study_modules
                                    FROM ${applicableAuthority("published_data_collection_settings")} source
                                LOOP
                                    UPDATE studies
                                    SET settings = jsonb_set(
                                        COALESCE(settings, '{}'::jsonb),
                                        '{DataCollection}', revision.setting, true
                                    ), modules = revision.study_modules
                                    WHERE study_id = revision.study_id;
                                END LOOP;
                            END
                            ${'$'}block${'$'}
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    """
                    INSERT INTO participant_form_access_codes (
                        access_code_id, token_hash, study_id, participant_id, form_kind,
                        resource_id, logical_date, issuer_type, issued_by, expires_at,
                        exchanged_at, revoked_at, created_at, enrollment_attempt_id,
                        enrollment_source_device_hash, enrollment_random_device_id,
                        enrollment_manifest_digest, enrollment_request_hash,
                        enrollment_proposed_key_hash, enrollment_replay_expires_at,
                        enrollment_settings_version, enrollment_disclosure_version,
                        enrollment_enabled_modules, enrollment_required_modules
                    )
                    SELECT source.access_code_id, source.token_hash, source.study_id,
                           source.participant_id, source.form_kind, source.resource_id,
                           source.logical_date, source.issuer_type, source.issued_by,
                           source.expires_at, source.exchanged_at, source.revoked_at,
                           source.created_at, source.enrollment_attempt_id,
                           source.enrollment_source_device_hash, source.enrollment_random_device_id,
                           source.enrollment_manifest_digest, source.enrollment_request_hash,
                           source.enrollment_proposed_key_hash,
                           source.enrollment_replay_expires_at,
                           source.enrollment_settings_version,
                           source.enrollment_disclosure_version,
                           source.enrollment_enabled_modules,
                           source.enrollment_required_modules
                    FROM ${applicableAuthority("enrollment_invitations")} source
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM participant_form_access_codes target
                        WHERE target.access_code_id = source.access_code_id
                           OR target.token_hash = source.token_hash
                    )
                    """.trimIndent(),
                ).use { it.executeUpdate() }
                connection.prepareStatement(
                    """
                        UPDATE participant_form_access_codes target
                        SET resource_id = source.resource_id,
                            logical_date = source.logical_date,
                            issuer_type = source.issuer_type,
                            issued_by = source.issued_by,
                            expires_at = source.expires_at,
                            exchanged_at = source.exchanged_at,
                            revoked_at = source.revoked_at,
                            enrollment_attempt_id = source.enrollment_attempt_id,
                            enrollment_source_device_hash = source.enrollment_source_device_hash,
                            enrollment_random_device_id = source.enrollment_random_device_id,
                            enrollment_manifest_digest = source.enrollment_manifest_digest,
                            enrollment_request_hash = source.enrollment_request_hash,
                            enrollment_proposed_key_hash = source.enrollment_proposed_key_hash,
                            enrollment_replay_expires_at = source.enrollment_replay_expires_at,
                            enrollment_settings_version = source.enrollment_settings_version,
                            enrollment_disclosure_version = source.enrollment_disclosure_version,
                            enrollment_enabled_modules = source.enrollment_enabled_modules,
                            enrollment_required_modules = source.enrollment_required_modules
                        FROM ${applicableAuthority("enrollment_invitations")} source
                        WHERE target.access_code_id = source.access_code_id
                          AND target.token_hash = source.token_hash
                    """.trimIndent(),
                ).use { it.executeUpdate() }
                connection.prepareStatement(
                    """
                        UPDATE participant_form_access_codes target
                        SET revoked_at = COALESCE(target.revoked_at, ?)
                        WHERE target.form_kind = 'ENROLLMENT'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM ${applicableAuthority("enrollment_invitations")} source
                              WHERE source.access_code_id = target.access_code_id
                                AND source.token_hash = target.token_hash
                          )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, checkpoint.createdAt)
                    statement.executeUpdate()
                }
                verifyRestoredAuthorities(connection)
            }
        }
    }

    private fun verifyRestoredAuthorities(connection: Connection) {
        val violations = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    (SELECT count(*)
                     FROM ${applicableAuthority("data_collection_settings_revisions")} source
                     LEFT JOIN data_collection_settings_revisions target
                       ON target.study_id = source.study_id
                      AND target.settings_version = source.settings_version
                      AND target.setting = source.setting
                      AND target.study_modules = source.study_modules
                     WHERE target.study_id IS NULL) +
                    (SELECT count(*)
                     FROM ${applicableAuthority("published_data_collection_settings")} source
                     JOIN studies target ON target.study_id = source.study_id
                     WHERE target.settings -> 'DataCollection' <> source.setting OR target.modules <> source.study_modules) +
                    (SELECT count(*)
                     FROM participant_form_access_codes target
                     LEFT JOIN ${applicableAuthority("enrollment_invitations")} source
                       ON source.access_code_id = target.access_code_id
                      AND source.token_hash = target.token_hash
                     WHERE target.form_kind = 'ENROLLMENT'
                       AND source.access_code_id IS NULL
                       AND target.revoked_at IS NULL) +
                    (SELECT count(*)
                    FROM ${applicableAuthority("enrollment_invitations")} source
                    LEFT JOIN participant_form_access_codes target
                       ON target.access_code_id = source.access_code_id
                      AND target.token_hash = source.token_hash
                     WHERE target.access_code_id IS NULL
                        OR target.exchanged_at IS DISTINCT FROM source.exchanged_at
                        OR target.revoked_at IS DISTINCT FROM source.revoked_at
                        OR target.enrollment_attempt_id IS DISTINCT FROM source.enrollment_attempt_id
                        OR target.enrollment_request_hash IS DISTINCT FROM source.enrollment_request_hash
                        OR target.enrollment_proposed_key_hash IS DISTINCT FROM source.enrollment_proposed_key_hash)
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
        check(violations == 0L) { "Restore continuity security authority could not be proven" }
    }

    /**
     * A transaction-consistent dump containing the exact completed operation and tombstone
     * already proves that erasure committed before that backup snapshot. Avoid replaying years
     * of historical erasures; active/incomplete operations are always re-armed conservatively.
     */
    private fun loadAlreadyProtectedCompletedOperationIds(connection: Connection): Set<UUID> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT source.operation_id
                FROM chronicle_restore_continuity.deletion_operations source
                JOIN chronicle_restore_continuity.deletion_tombstones source_tombstone
                  ON source_tombstone.operation_id = source.operation_id
                JOIN data_deletion_operations target
                  ON target.operation_id = source.operation_id
                 AND target.study_id = source.study_id
                 AND target.participant_ref IS NOT DISTINCT FROM source.participant_ref
                 AND target.participant_id IS NOT DISTINCT FROM source.participant_id
                 AND target.mode = source.mode
                 AND target.status = 'COMPLETED'
                 AND target.requested_by = source.requested_by
                 AND target.idempotency_key = source.idempotency_key
                 AND target.registry_version = source.registry_version
                 AND target.completed_at IS NOT DISTINCT FROM source.completed_at
                 AND target.proof_hash IS NOT DISTINCT FROM source.proof_hash
                JOIN data_deletion_tombstones target_tombstone
                  ON target_tombstone.operation_id = source_tombstone.operation_id
                 AND target_tombstone.study_ref = source_tombstone.study_ref
                 AND target_tombstone.participant_ref IS NOT DISTINCT FROM source_tombstone.participant_ref
                 AND target_tombstone.mode = source_tombstone.mode
                 AND target_tombstone.registry_version = source_tombstone.registry_version
                 AND target_tombstone.completed_at = source_tombstone.completed_at
                 AND target_tombstone.proof_hash = source_tombstone.proof_hash
                WHERE source.status = 'COMPLETED'
                """.trimIndent(),
            ).use { resultSet ->
                buildSet {
                    while (resultSet.next()) {
                        add(resultSet.getObject("operation_id", UUID::class.java))
                    }
                }
            }
        }

    private fun applyContainmentAndReceipts() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO mobile_withdrawal_requests (
                            request_id, api_key_id, study_id, participant_id, random_device_id,
                            already_withdrawn, created_at
                        )
                        SELECT request_id, api_key_id, study_id, participant_id, random_device_id,
                               already_withdrawn, created_at
                        FROM chronicle_restore_continuity.withdrawal_requests
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    )
                    // Shared with selfhost/restore.sh; each script verifies its own containment.
                    statement.execute(restoreResource("restore-withdrawal-containment.sql"))
                    statement.execute(restoreResource("restore-restrictions-containment.sql"))
                    statement.execute(restoreResource("restore-restrictions-verify.sql"))
                }
                verifyWithdrawalReceipts(connection)
            }
        }
    }

    private fun verifyWithdrawalReceipts(connection: Connection) {
        val violations = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT count(*)
                FROM chronicle_restore_continuity.withdrawal_requests source
                LEFT JOIN mobile_withdrawal_requests target
                  ON target.request_id = source.request_id
                 AND target.api_key_id = source.api_key_id
                 AND target.study_id = source.study_id
                 AND target.participant_id = source.participant_id
                 AND target.random_device_id = source.random_device_id
                 AND target.already_withdrawn = source.already_withdrawn
                 AND target.created_at = source.created_at
                WHERE target.request_id IS NULL
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
        check(violations == 0L) { "Restore continuity withdrawal receipts could not be proven" }
    }

    private fun finalizeReconciliation(
        checkpoint: RestoreContinuityCheckpoint,
        alreadyProtectedDeletionCount: Long,
        completedDeletionCount: Long,
        skippedAuthorities: String,
    ) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                check(checkpointRepository.computeCheckpointSha256(connection) == checkpoint.checkpointSha256) {
                    "Restore continuity checkpoint changed during reconciliation"
                }
                insertReceipt(connection, checkpoint, alreadyProtectedDeletionCount, completedDeletionCount, skippedAuthorities)
                verifyReceipt(
                    connection,
                    checkpoint,
                    alreadyProtectedDeletionCount,
                    completedDeletionCount,
                    skippedAuthorities,
                )
                setDeletionWorkerContext(connection, transactionLocal = true)
                dropRestoredContinuitySchema(connection)
            }
        }
    }

    private fun insertReceipt(
        connection: Connection,
        checkpoint: RestoreContinuityCheckpoint,
        alreadyProtectedDeletionCount: Long,
        completedDeletionCount: Long,
        skippedAuthorities: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO restore_continuity_reconciliations (
                checkpoint_id, contract_version, source_schema_version,
                checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                withdrawn_participant_count, deletion_operation_count,
                source_tombstone_count, already_protected_deletion_count,
                replayed_completed_deletion_count, collection_revision_count,
                published_collection_settings_count, enrollment_invitation_count,
                restricted_participant_count, archived_study_count, skipped_authority_counts
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (checkpoint_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, checkpoint.checkpointId)
            statement.setInt(2, CONTRACT_VERSION)
            statement.setString(3, checkpoint.sourceSchemaVersion)
            statement.setString(4, checkpoint.checkpointSha256)
            statement.setLong(5, checkpoint.withdrawalReceiptCount)
            statement.setLong(6, checkpoint.revokedApiKeyCount)
            statement.setLong(7, checkpoint.withdrawnParticipantCount)
            statement.setLong(8, checkpoint.deletionOperationCount)
            statement.setLong(9, checkpoint.sourceTombstoneCount)
            statement.setLong(10, alreadyProtectedDeletionCount)
            statement.setLong(11, completedDeletionCount)
            statement.setLong(12, checkpoint.collectionRevisionCount)
            statement.setLong(13, checkpoint.publishedCollectionSettingsCount)
            statement.setLong(14, checkpoint.enrollmentInvitationCount)
            statement.setLong(15, checkpoint.restrictedParticipantCount)
            statement.setLong(16, checkpoint.archivedStudyCount)
            statement.setString(17, skippedAuthorities)
            statement.executeUpdate()
        }
    }

    private fun dropRestoredContinuitySchema(connection: Connection) {
        connection.createStatement().use {
            // Expired checkpoint markers suppress ON CONFLICT renewal during replay only.
            // They must not become fresh credential tombstones when the replay completes.
            it.executeUpdate("""
                DELETE FROM erased_device_key_tombstones target
                USING chronicle_restore_continuity.erased_device_key_tombstones source
                WHERE target.key_hash = source.key_hash AND source.expires_at <= now()
            """.trimIndent())
            it.executeQuery("""
                SELECT count(*) FROM erased_device_key_tombstones target
                JOIN chronicle_restore_continuity.erased_device_key_tombstones source
                  ON target.key_hash = source.key_hash
                WHERE source.expires_at <= now()
            """.trimIndent()).use { rows ->
                check(rows.next() && rows.getLong(1) == 0L) {
                    "Expired restored credential markers remain after replay"
                }
            }
            it.execute("DROP SCHEMA chronicle_restore_continuity CASCADE")
        }
    }

    private fun verifyReceipt(
        connection: Connection,
        checkpoint: RestoreContinuityCheckpoint,
        alreadyProtectedDeletionCount: Long,
        completedDeletionCount: Long,
        skippedAuthorities: String,
    ) {
        connection.prepareStatement(
            """
            SELECT contract_version, source_schema_version, checkpoint_sha256,
                   withdrawal_receipt_count, revoked_api_key_count,
                   withdrawn_participant_count, deletion_operation_count,
                   source_tombstone_count, already_protected_deletion_count,
                   replayed_completed_deletion_count, collection_revision_count,
                   published_collection_settings_count, enrollment_invitation_count,
                   restricted_participant_count, archived_study_count
            FROM restore_continuity_reconciliations
            WHERE checkpoint_id = ? AND skipped_authority_counts = ?::jsonb
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, checkpoint.checkpointId)
            statement.setString(2, skippedAuthorities)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Restore continuity receipt was not persisted" }
                checkpointRepository.requireCurrentContract(resultSet.getInt("contract_version"))
                check(resultSet.getString("source_schema_version") == checkpoint.sourceSchemaVersion)
                check(resultSet.getString("checkpoint_sha256") == checkpoint.checkpointSha256)
                check(resultSet.getLong("withdrawal_receipt_count") == checkpoint.withdrawalReceiptCount)
                check(resultSet.getLong("revoked_api_key_count") == checkpoint.revokedApiKeyCount)
                check(resultSet.getLong("withdrawn_participant_count") == checkpoint.withdrawnParticipantCount)
                check(resultSet.getLong("deletion_operation_count") == checkpoint.deletionOperationCount)
                check(resultSet.getLong("source_tombstone_count") == checkpoint.sourceTombstoneCount)
                check(resultSet.getLong("collection_revision_count") == checkpoint.collectionRevisionCount)
                check(
                    resultSet.getLong("published_collection_settings_count") ==
                        checkpoint.publishedCollectionSettingsCount,
                )
                check(resultSet.getLong("enrollment_invitation_count") == checkpoint.enrollmentInvitationCount)
                check(resultSet.getLong("restricted_participant_count") == checkpoint.restrictedParticipantCount)
                check(resultSet.getLong("archived_study_count") == checkpoint.archivedStudyCount)
                check(
                    resultSet.getLong("already_protected_deletion_count") ==
                        alreadyProtectedDeletionCount,
                )
                check(resultSet.getLong("replayed_completed_deletion_count") == completedDeletionCount)
                check(!resultSet.next())
            }
        }
    }
}
