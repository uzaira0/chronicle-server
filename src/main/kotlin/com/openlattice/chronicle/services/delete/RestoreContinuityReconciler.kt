package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.LogSanitizer
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

private data class RestoreContinuityCheckpoint(
    val checkpointId: UUID,
    val sourceSchemaVersion: String,
    val checkpointSha256: String,
    val withdrawalReceiptCount: Long,
    val revokedApiKeyCount: Long,
    val withdrawnParticipantCount: Long,
    val deletionOperationCount: Long,
    val sourceTombstoneCount: Long,
    val collectionRevisionCount: Long,
    val publishedCollectionSettingsCount: Long,
    val enrollmentInvitationCount: Long,
    val createdAt: OffsetDateTime,
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
// together so no caller can apply only part of the fail-closed restore protocol.
@Suppress("LargeClass")
public open class RestoreContinuityReconciler(
    private val storageResolver: StorageResolver,
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(RestoreContinuityReconciler::class.java)
        private const val RECOVERY_SCHEMA = "chronicle_restore_continuity"
        private const val CONTRACT_VERSION = 2
        private const val COORDINATION_LOCK_SQL =
            "SELECT pg_advisory_lock(hashtextextended('chronicle-restore-continuity', 0))"
        private const val COORDINATION_UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('chronicle-restore-continuity', 0))"

        private const val CHECKPOINT_DIGEST_SQL = """
            WITH canonical(line) AS (
                SELECT concat_ws('|',
                    'withdrawal', request_id::text, api_key_id::text, study_id::text,
                    participant_id, device_id::text, already_withdrawn::text,
                    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
                FROM chronicle_restore_continuity.withdrawal_requests
                UNION ALL
                SELECT concat_ws('|',
                    'revoked-key', key_id::text, study_id::text,
                    COALESCE(participant_id, '<null>'), COALESCE(device_id::text, '<null>'))
                FROM chronicle_restore_continuity.revoked_api_keys
                UNION ALL
                SELECT concat_ws('|', 'withdrawn', study_id::text, participant_id)
                FROM chronicle_restore_continuity.withdrawn_participants
                UNION ALL
                SELECT concat_ws('|',
                    'operation', operation_id::text, study_id::text,
                    COALESCE(participant_ref, '<null>'), COALESCE(participant_id, '<null>'),
                    mode, status, requested_by, idempotency_key::text, registry_version::text,
                    COALESCE(to_char(quarantine_until AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    COALESCE(to_char(completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    COALESCE(proof_hash, '<null>'), COALESCE(cancelled_by, '<null>'),
                    COALESCE(to_char(cancelled_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'))
                FROM chronicle_restore_continuity.deletion_operations
                UNION ALL
                SELECT concat_ws('|',
                    'hold', hold_id::text, operation_id::text, study_id::text, reason,
                    created_by, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    to_char(review_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    COALESCE(released_by, '<null>'),
                    COALESCE(to_char(released_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    COALESCE(release_reason, '<null>'))
                FROM chronicle_restore_continuity.retention_holds
                UNION ALL
                SELECT concat_ws('|',
                    'tombstone', operation_id::text, study_ref,
                    COALESCE(participant_ref, '<null>'), mode, registry_version::text,
                    to_char(completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    proof_hash)
                FROM chronicle_restore_continuity.deletion_tombstones
                UNION ALL
                SELECT concat_ws('|', 'collection-revision', study_id::text,
                    settings_version::text, setting::text,
                    to_char(issued_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
                FROM chronicle_restore_continuity.data_collection_settings_revisions
                UNION ALL
                SELECT concat_ws('|', 'published-collection-settings', study_id::text,
                    settings_version::text, setting::text)
                FROM chronicle_restore_continuity.published_data_collection_settings
                UNION ALL
                SELECT concat_ws('|', 'enrollment-invitation', access_code_id::text,
                    encode(token_hash, 'hex'), study_id::text, participant_id, form_kind,
                    COALESCE(resource_id::text, '<null>'), COALESCE(logical_date::text, '<null>'),
                    issuer_type, issued_by,
                    to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    COALESCE(to_char(exchanged_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    COALESCE(to_char(revoked_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    COALESCE(enrollment_attempt_id::text, '<null>'),
                    COALESCE(enrollment_source_device_hash, '<null>'),
                    COALESCE(enrollment_device_id::text, '<null>'),
                    COALESCE(enrollment_manifest_digest, '<null>'),
                    COALESCE(enrollment_request_hash, '<null>'),
                    COALESCE(enrollment_proposed_key_hash, '<null>'),
                    COALESCE(to_char(enrollment_replay_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '<null>'),
                    COALESCE(enrollment_settings_version::text, '<null>'),
                    COALESCE(enrollment_disclosure_version, '<null>'),
                    COALESCE(enrollment_enabled_modules::text, '<null>'),
                    COALESCE(enrollment_required_modules::text, '<null>'))
                FROM chronicle_restore_continuity.enrollment_invitations
            )
            SELECT encode(
                sha256(convert_to(COALESCE(string_agg(line, E'\n' ORDER BY line), ''), 'UTF8')),
                'hex'
            )
            FROM canonical
        """

        internal fun computeCheckpointSha256(connection: Connection): String =
            connection.createStatement().use { statement ->
                statement.executeQuery(CHECKPOINT_DIGEST_SQL).use { resultSet ->
                    check(resultSet.next()) { "Unable to calculate restore continuity checkpoint digest" }
                    resultSet.getString(1)
                }
            }
    }

    internal fun reconcile(): RestoreContinuityResult? {
        if (!recoverySchemaExists()) return null
        return storageResolver.getPlatformStorage().connection.use { coordinationConnection ->
            coordinationConnection.createStatement().use { it.execute(COORDINATION_LOCK_SQL) }
            try {
                if (!recoverySchemaExists()) return@use null
                val checkpoint = loadAndValidateCheckpoint()
                val operations = loadOperations()
                val holds = loadHolds().groupBy(RestoredRetentionHold::operationId)
                validatePublicConflicts(operations)
                validateAuthorityConflicts()
                val alreadyProtectedOperationIds = loadAlreadyProtectedCompletedOperationIds()
                applyRestoredAuthorities(checkpoint)
                applyContainmentAndReceipts()

                var completedDeletionCount = 0L
                operations.forEach { operation ->
                    if (operation.operationId in alreadyProtectedOperationIds) return@forEach
                    val requiresCompletedReplay = dataDeletionOrchestrator.reconcileRestoredOperation(
                        operation,
                        holds[operation.operationId].orEmpty(),
                    )
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
                runCatching {
                    coordinationConnection.createStatement().use { it.execute(COORDINATION_UNLOCK_SQL) }
                }.onFailure { failure ->
                    logger.error("Failed to release restore-continuity coordination lock", failure)
                }
            }
        }
    }

    private fun recoverySchemaExists(): Boolean =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT to_regnamespace(?) IS NOT NULL").use { statement ->
                statement.setString(1, RECOVERY_SCHEMA)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getBoolean(1)
                }
            }
        }

    @Suppress("LongMethod")
    private fun loadAndValidateCheckpoint(): RestoreContinuityCheckpoint =
        storageResolver.getPlatformStorage().connection.use { connection ->
            val checkpoint = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT contract_version, checkpoint_id, source_schema_version,
                           checkpoint_sha256, withdrawal_receipt_count,
                           revoked_api_key_count, withdrawn_participant_count,
                           deletion_operation_count, source_tombstone_count,
                           collection_revision_count, published_collection_settings_count,
                           enrollment_invitation_count, created_at
                    FROM chronicle_restore_continuity.checkpoint
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next()) { "Restore continuity checkpoint is missing" }
                    val loaded = RestoreContinuityCheckpoint(
                        checkpointId = resultSet.getObject("checkpoint_id", UUID::class.java),
                        sourceSchemaVersion = resultSet.getString("source_schema_version"),
                        checkpointSha256 = resultSet.getString("checkpoint_sha256"),
                        withdrawalReceiptCount = resultSet.getLong("withdrawal_receipt_count"),
                        revokedApiKeyCount = resultSet.getLong("revoked_api_key_count"),
                        withdrawnParticipantCount = resultSet.getLong("withdrawn_participant_count"),
                        deletionOperationCount = resultSet.getLong("deletion_operation_count"),
                        sourceTombstoneCount = resultSet.getLong("source_tombstone_count"),
                        collectionRevisionCount = resultSet.getLong("collection_revision_count"),
                        publishedCollectionSettingsCount =
                            resultSet.getLong("published_collection_settings_count"),
                        enrollmentInvitationCount = resultSet.getLong("enrollment_invitation_count"),
                        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java),
                    )
                    check(resultSet.getInt("contract_version") == CONTRACT_VERSION) {
                        "Unsupported restore continuity checkpoint contract"
                    }
                    check(!resultSet.next()) { "Restore continuity checkpoint contains multiple authorities" }
                    loaded
                }
            }
            val actualCounts = listOf(
                "withdrawal_requests",
                "revoked_api_keys",
                "withdrawn_participants",
                "deletion_operations",
                "deletion_tombstones",
                "data_collection_settings_revisions",
                "published_data_collection_settings",
                "enrollment_invitations",
            ).map { table -> countRecoveryRows(connection, table) }
            check(
                actualCounts == listOf(
                    checkpoint.withdrawalReceiptCount,
                    checkpoint.revokedApiKeyCount,
                    checkpoint.withdrawnParticipantCount,
                    checkpoint.deletionOperationCount,
                    checkpoint.sourceTombstoneCount,
                    checkpoint.collectionRevisionCount,
                    checkpoint.publishedCollectionSettingsCount,
                    checkpoint.enrollmentInvitationCount,
                ),
            ) { "Restore continuity checkpoint row counts do not match its protected tables" }
            check(computeCheckpointSha256(connection) == checkpoint.checkpointSha256) {
                "Restore continuity checkpoint digest does not match its protected tables"
            }
            validateCheckpointRelations(connection)
            checkpoint
        }

    private fun countRecoveryRows(connection: Connection, tableName: String): Long {
        require(tableName.matches(Regex("^[a-z_]+$")))
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $RECOVERY_SCHEMA.$tableName").use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
    }

    private fun validateCheckpointRelations(connection: Connection) {
        val invalidRelations = connection.createStatement().use { statement ->
            statement.executeQuery(
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
                     FROM chronicle_restore_continuity.retention_holds hold
                     LEFT JOIN chronicle_restore_continuity.deletion_operations operation
                       ON operation.operation_id = hold.operation_id
                      AND operation.study_id = hold.study_id
                     WHERE operation.operation_id IS NULL) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.deletion_operations
                     WHERE mode NOT IN ('COLLECTED_DATA_PURGE', 'WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                        OR status NOT IN ('QUARANTINED', 'HELD', 'READY', 'ERASING',
                                          'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELLED')
                        OR status = 'PREVIEW'
                        OR (mode = 'STUDY_ERASURE') <> (participant_id IS NULL)
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
                        ))) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.retention_holds
                     WHERE (released_at IS NULL) <>
                           (released_by IS NULL AND release_reason IS NULL)) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.deletion_operations operation
                     WHERE (operation.status = 'HELD') <>
                           EXISTS (
                               SELECT 1
                               FROM chronicle_restore_continuity.retention_holds hold
                               WHERE hold.operation_id = operation.operation_id
                                 AND hold.released_at IS NULL
                           )) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.deletion_tombstones tombstone
                     JOIN chronicle_restore_continuity.deletion_operations completed
                       ON completed.operation_id = tombstone.operation_id
                     JOIN chronicle_restore_continuity.retention_holds hold
                       ON hold.released_at IS NULL
                     JOIN chronicle_restore_continuity.deletion_operations held
                       ON held.operation_id = hold.operation_id
                      AND held.study_id = hold.study_id
                     WHERE held.study_id = completed.study_id
                       AND (
                           completed.mode = 'STUDY_ERASURE'
                           OR held.mode = 'STUDY_ERASURE'
                           OR held.participant_id = completed.participant_id
                       ))
                """.trimIndent(),
            ).use { resultSet ->
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
                           operation.mode, operation.status, operation.requested_by,
                           operation.idempotency_key, operation.quarantine_until,
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
                                    mode = DataDeletionMode.valueOf(resultSet.getString("mode")),
                                    status = resultSet.getString("status"),
                                    requestedBy = resultSet.getString("requested_by"),
                                    idempotencyKey = resultSet.getObject("idempotency_key", UUID::class.java),
                                    quarantineUntil = resultSet.getObject("quarantine_until", OffsetDateTime::class.java),
                                    cancelledBy = resultSet.getString("cancelled_by"),
                                    cancelledAt = resultSet.getObject("cancelled_at", OffsetDateTime::class.java),
                                    hasSourceTombstone = resultSet.getBoolean("has_source_tombstone"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun loadHolds(): List<RestoredRetentionHold> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT hold_id, operation_id, study_id, reason, created_by, created_at,
                           review_at, released_by, released_at, release_reason
                    FROM chronicle_restore_continuity.retention_holds
                    ORDER BY created_at, hold_id
                    """.trimIndent(),
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                RestoredRetentionHold(
                                    holdId = resultSet.getObject("hold_id", UUID::class.java),
                                    operationId = resultSet.getObject("operation_id", UUID::class.java),
                                    studyId = resultSet.getObject("study_id", UUID::class.java),
                                    reason = resultSet.getString("reason"),
                                    createdBy = resultSet.getString("created_by"),
                                    createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java),
                                    reviewAt = resultSet.getObject("review_at", OffsetDateTime::class.java),
                                    releasedBy = resultSet.getString("released_by"),
                                    releasedAt = resultSet.getObject("released_at", OffsetDateTime::class.java),
                                    releaseReason = resultSet.getString("release_reason"),
                                ),
                            )
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
                       OR target.device_id <> source.device_id
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
                       OR target.participant_id IS DISTINCT FROM source.participant_id
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
                         FROM chronicle_restore_continuity.data_collection_settings_revisions source
                         JOIN data_collection_settings_revisions target
                           ON target.study_id = source.study_id
                          AND target.settings_version = source.settings_version
                         WHERE target.setting <> source.setting) +
                        (SELECT count(*)
                         FROM chronicle_restore_continuity.published_data_collection_settings source
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
                         FROM chronicle_restore_continuity.enrollment_invitations source
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
    @Suppress("LongMethod")
    private fun applyRestoredAuthorities(checkpoint: RestoreContinuityCheckpoint) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        SELECT set_config('app.current_user_id', 'chronicle-deletion-worker', true),
                               set_config('app.is_admin', 'true', true)
                        """.trimIndent(),
                    )
                }
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                            DO ${'$'}block${'$'}
                            DECLARE revision RECORD;
                            BEGIN
                                FOR revision IN
                                    SELECT source.study_id, source.setting
                                    FROM chronicle_restore_continuity.data_collection_settings_revisions source
                                    JOIN studies target ON target.study_id = source.study_id
                                    ORDER BY source.study_id, source.settings_version
                                LOOP
                                    UPDATE studies
                                    SET settings = jsonb_set(
                                        COALESCE(settings, '{}'::jsonb),
                                        '{DataCollection}', revision.setting, true
                                    )
                                    WHERE study_id = revision.study_id;
                                END LOOP;

                                FOR revision IN
                                    SELECT source.study_id, source.setting
                                    FROM chronicle_restore_continuity.published_data_collection_settings source
                                    JOIN studies target ON target.study_id = source.study_id
                                LOOP
                                    UPDATE studies
                                    SET settings = jsonb_set(
                                        COALESCE(settings, '{}'::jsonb),
                                        '{DataCollection}', revision.setting, true
                                    )
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
                        enrollment_source_device_hash, enrollment_device_id,
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
                           source.enrollment_source_device_hash, source.enrollment_device_id,
                           source.enrollment_manifest_digest, source.enrollment_request_hash,
                           source.enrollment_proposed_key_hash,
                           source.enrollment_replay_expires_at,
                           source.enrollment_settings_version,
                           source.enrollment_disclosure_version,
                           source.enrollment_enabled_modules,
                           source.enrollment_required_modules
                    FROM chronicle_restore_continuity.enrollment_invitations source
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
                            enrollment_device_id = source.enrollment_device_id,
                            enrollment_manifest_digest = source.enrollment_manifest_digest,
                            enrollment_request_hash = source.enrollment_request_hash,
                            enrollment_proposed_key_hash = source.enrollment_proposed_key_hash,
                            enrollment_replay_expires_at = source.enrollment_replay_expires_at,
                            enrollment_settings_version = source.enrollment_settings_version,
                            enrollment_disclosure_version = source.enrollment_disclosure_version,
                            enrollment_enabled_modules = source.enrollment_enabled_modules,
                            enrollment_required_modules = source.enrollment_required_modules
                        FROM chronicle_restore_continuity.enrollment_invitations source
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
                              FROM chronicle_restore_continuity.enrollment_invitations source
                              WHERE source.access_code_id = target.access_code_id
                                AND source.token_hash = target.token_hash
                          )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, checkpoint.createdAt)
                    statement.executeUpdate()
                }
                verifyRestoredAuthorities(connection)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun verifyRestoredAuthorities(connection: Connection) {
        val violations = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.data_collection_settings_revisions source
                     JOIN studies study ON study.study_id = source.study_id
                     LEFT JOIN data_collection_settings_revisions target
                       ON target.study_id = source.study_id
                      AND target.settings_version = source.settings_version
                      AND target.setting = source.setting
                     WHERE target.study_id IS NULL) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.published_data_collection_settings source
                     JOIN studies target ON target.study_id = source.study_id
                     WHERE target.settings -> 'DataCollection' <> source.setting) +
                    (SELECT count(*)
                     FROM participant_form_access_codes target
                     LEFT JOIN chronicle_restore_continuity.enrollment_invitations source
                       ON source.access_code_id = target.access_code_id
                      AND source.token_hash = target.token_hash
                     WHERE target.form_kind = 'ENROLLMENT'
                       AND source.access_code_id IS NULL
                       AND target.revoked_at IS NULL) +
                    (SELECT count(*)
                    FROM chronicle_restore_continuity.enrollment_invitations source
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
    private fun loadAlreadyProtectedCompletedOperationIds(): Set<UUID> =
        storageResolver.getPlatformStorage().connection.use { connection ->
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
                     AND target.quarantine_until IS NOT DISTINCT FROM source.quarantine_until
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
                      AND NOT EXISTS (
                          SELECT hold_id, operation_id, study_id, reason, created_by,
                                 created_at, review_at, released_by, released_at, release_reason
                          FROM chronicle_restore_continuity.retention_holds
                          WHERE operation_id = source.operation_id
                          EXCEPT
                          SELECT hold_id, operation_id, study_id, reason, created_by,
                                 created_at, review_at, released_by, released_at, release_reason
                          FROM retention_holds
                          WHERE operation_id = source.operation_id
                      )
                      AND NOT EXISTS (
                          SELECT hold_id, operation_id, study_id, reason, created_by,
                                 created_at, review_at, released_by, released_at, release_reason
                          FROM retention_holds
                          WHERE operation_id = source.operation_id
                          EXCEPT
                          SELECT hold_id, operation_id, study_id, reason, created_by,
                                 created_at, review_at, released_by, released_at, release_reason
                          FROM chronicle_restore_continuity.retention_holds
                          WHERE operation_id = source.operation_id
                      )
                    """.trimIndent(),
                ).use { resultSet ->
                    buildSet {
                        while (resultSet.next()) {
                            add(resultSet.getObject("operation_id", UUID::class.java))
                        }
                    }
                }
            }
        }

    private fun applyContainmentAndReceipts() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO mobile_withdrawal_requests (
                            request_id, api_key_id, study_id, participant_id, device_id,
                            already_withdrawn, created_at
                        )
                        SELECT request_id, api_key_id, study_id, participant_id, device_id,
                               already_withdrawn, created_at
                        FROM chronicle_restore_continuity.withdrawal_requests
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        UPDATE api_keys target
                        SET revoked = true
                        FROM chronicle_restore_continuity.revoked_api_keys source
                        WHERE target.key_id = source.key_id
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        UPDATE api_keys target
                        SET revoked = true
                        FROM chronicle_restore_continuity.withdrawn_participants source
                        WHERE target.study_id = source.study_id
                          AND target.participant_id = source.participant_id
                          AND target.participant_id IS NOT NULL
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        UPDATE study_participants target
                        SET participation_status = 'NOT_ENROLLED', updated_at = now()
                        FROM chronicle_restore_continuity.withdrawn_participants source
                        WHERE target.study_id = source.study_id
                          AND target.participant_id = source.participant_id
                        """.trimIndent(),
                    )
                }
                verifyContainment(connection)
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun verifyContainment(connection: Connection) {
        val violations = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.revoked_api_keys source
                     JOIN api_keys target ON target.key_id = source.key_id
                     WHERE NOT target.revoked) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.withdrawn_participants source
                     JOIN api_keys target
                       ON target.study_id = source.study_id
                      AND target.participant_id = source.participant_id
                     WHERE NOT target.revoked) +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.withdrawn_participants source
                     JOIN study_participants target
                       ON target.study_id = source.study_id
                      AND target.participant_id = source.participant_id
                     WHERE target.participation_status <> 'NOT_ENROLLED') +
                    (SELECT count(*)
                     FROM chronicle_restore_continuity.withdrawal_requests source
                     LEFT JOIN mobile_withdrawal_requests target
                       ON target.request_id = source.request_id
                      AND target.api_key_id = source.api_key_id
                      AND target.study_id = source.study_id
                      AND target.participant_id = source.participant_id
                      AND target.device_id = source.device_id
                      AND target.already_withdrawn = source.already_withdrawn
                      AND target.created_at = source.created_at
                     WHERE target.request_id IS NULL)
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }
        check(violations == 0L) { "Restore continuity containment could not be proven" }
    }

    private fun finalizeReconciliation(
        checkpoint: RestoreContinuityCheckpoint,
        alreadyProtectedDeletionCount: Long,
        completedDeletionCount: Long,
    ) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                check(computeCheckpointSha256(connection) == checkpoint.checkpointSha256) {
                    "Restore continuity checkpoint changed during reconciliation"
                }
                connection.prepareStatement(
                    """
                    INSERT INTO restore_continuity_reconciliations (
                        checkpoint_id, contract_version, source_schema_version,
                        checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                        withdrawn_participant_count, deletion_operation_count,
                        source_tombstone_count, already_protected_deletion_count,
                        replayed_completed_deletion_count, collection_revision_count,
                        published_collection_settings_count, enrollment_invitation_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.executeUpdate()
                }
                verifyReceipt(
                    connection,
                    checkpoint,
                    alreadyProtectedDeletionCount,
                    completedDeletionCount,
                )
                connection.createStatement().use {
                    it.execute("DROP SCHEMA chronicle_restore_continuity CASCADE")
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun verifyReceipt(
        connection: Connection,
        checkpoint: RestoreContinuityCheckpoint,
        alreadyProtectedDeletionCount: Long,
        completedDeletionCount: Long,
    ) {
        connection.prepareStatement(
            """
            SELECT contract_version, source_schema_version, checkpoint_sha256,
                   withdrawal_receipt_count, revoked_api_key_count,
                   withdrawn_participant_count, deletion_operation_count,
                   source_tombstone_count, already_protected_deletion_count,
                   replayed_completed_deletion_count, collection_revision_count,
                   published_collection_settings_count, enrollment_invitation_count
            FROM restore_continuity_reconciliations
            WHERE checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, checkpoint.checkpointId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Restore continuity receipt was not persisted" }
                check(resultSet.getInt("contract_version") == CONTRACT_VERSION)
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
