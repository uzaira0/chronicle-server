package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.setDeletionWorkerContext
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
    val erasedDeviceKeyTombstoneCount: Long,
    val restrictedParticipantCount: Long,
    val archivedStudyCount: Long,
    val purgeCutoffCount: Long,
    val collectionAcknowledgmentCount: Long,
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
        internal val CONTRACT_VERSION = restoreResource("restore-continuity.version").trim().toInt()
        private const val PREVIOUS_CONTRACT_VERSION = 3
        private const val RESTRICTIONS_CONTRACT_VERSION = 4
        private const val LEGACY_CONTRACT_VERSION = 2
        private const val COORDINATION_LOCK_SQL =
            "SELECT pg_advisory_lock(hashtextextended('chronicle-restore-continuity', 0))"
        private const val COORDINATION_UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('chronicle-restore-continuity', 0))"

        internal fun restoreResource(name: String): String =
            requireNotNull(RestoreContinuityReconciler::class.java.getResourceAsStream("/$name")) {
                "Missing restore continuity resource $name"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        private val CHECKPOINT_DIGEST_SQL = restoreResource("restore-continuity-digest.sql") + "SELECT value FROM digest"
        private val RESTRICTIONS_CHECKPOINT_DIGEST_SQL = CHECKPOINT_DIGEST_SQL
            .replace(Regex(""",\s*COALESCE\(to_char\(capture_cutoff.*?'<null>'\)"""), "")
            .replace(Regex("""\s*COALESCE\(to_char\(started_at.*?'<null>'\),"""), "")
            .replace(Regex("""UNION ALL\s+SELECT concat_ws\('\|', 'purge-cutoff'.*?FROM chronicle_restore_continuity\.participant_collection_acknowledgment acknowledgment""",
                RegexOption.DOT_MATCHES_ALL), "")
        private val PREVIOUS_CHECKPOINT_DIGEST_SQL = RESTRICTIONS_CHECKPOINT_DIGEST_SQL.replace(
            Regex("""UNION ALL\s+SELECT concat_ws\('\|', 'restricted-participant'.*?FROM chronicle_restore_continuity\.archived_studies""",
                RegexOption.DOT_MATCHES_ALL), "",
        )

        /**
         * Contract 2 (restore.sh through 2026.10.3) hashed the same rows without the operation block
         * token and without erased device-key tombstones.
         */
        private val LEGACY_CHECKPOINT_DIGEST_SQL = PREVIOUS_CHECKPOINT_DIGEST_SQL
            .replace("COALESCE(participant_block_token, '<null>'),", "")
            .replace(
                Regex(
                    """UNION ALL\s+SELECT concat_ws\('\|', 'erased-device-key'.*?""" +
                        """FROM chronicle_restore_continuity\.erased_device_key_tombstones""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
                "",
            )
            .also { legacy ->
                check(!legacy.contains("participant_block_token") && !legacy.contains("erased-device-key"))
            }

        internal fun computeCheckpointSha256(
            connection: Connection,
            digestSql: String = CHECKPOINT_DIGEST_SQL,
        ): String =
            connection.createStatement().use { statement ->
                statement.executeQuery(digestSql).use { resultSet ->
                    check(resultSet.next()) { "Unable to calculate restore continuity checkpoint digest" }
                    resultSet.getString(1)
                }
            }
    }

    internal fun reconcile(): RestoreContinuityResult? {
        if (!recoverySchemaExists()) return null
        val storage = storageResolver.getPlatformStorage()
        return storage.connection.use { coordinationConnection ->
            coordinationConnection.createStatement().use { it.execute(COORDINATION_LOCK_SQL) }
            PinnedPlatformConnection.sharing(storage, coordinationConnection) {
                try {
                    if (!recoverySchemaExists()) return@sharing null
                    val (checkpoint, alreadyProtectedOperationIds) = loadAndValidateCheckpoint()
                    val operations = loadOperations().map(::withRestoredSubject)
                    val holds = loadHolds().groupBy(RestoredRetentionHold::operationId)
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
    }

    private fun <T> withTransaction(
        connection: Connection,
        afterCommit: () -> Unit = {},
        block: () -> T,
    ): T {
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            afterCommit()
            return result
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
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

    /** The validated checkpoint and the completed operations whose exact proof the backup already holds. */
    @Suppress("LongMethod")
    private fun loadAndValidateCheckpoint(): Pair<RestoreContinuityCheckpoint, Set<UUID>> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            // Read the version alone first: an older contract lacks columns this one selects.
            val contractVersion = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT contract_version FROM chronicle_restore_continuity.checkpoint").use {
                    check(it.next()) { "Restore continuity checkpoint is missing" }
                    val version = it.getInt(1)
                    check(!it.next()) { "Restore continuity checkpoint contains multiple authorities" }
                    version
                }
            }
            if (contractVersion == LEGACY_CONTRACT_VERSION) upgradeLegacyCheckpoint(connection)
            if (contractVersion in setOf(LEGACY_CONTRACT_VERSION, PREVIOUS_CONTRACT_VERSION)) upgradePreviousCheckpoint(connection)
            if (contractVersion in setOf(LEGACY_CONTRACT_VERSION, PREVIOUS_CONTRACT_VERSION, RESTRICTIONS_CONTRACT_VERSION)) {
                upgradeRestrictionsCheckpoint(connection)
            }
            val checkpoint = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT contract_version, checkpoint_id, source_schema_version,
                           checkpoint_sha256, withdrawal_receipt_count,
                           revoked_api_key_count, withdrawn_participant_count,
                           deletion_operation_count, source_tombstone_count,
                           collection_revision_count, published_collection_settings_count,
                           enrollment_invitation_count, erased_device_key_tombstone_count,
                           restricted_participant_count, archived_study_count,
                           purge_cutoff_count, collection_acknowledgment_count, created_at
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
                        erasedDeviceKeyTombstoneCount = resultSet.getLong("erased_device_key_tombstone_count"),
                        restrictedParticipantCount = resultSet.getLong("restricted_participant_count"),
                        archivedStudyCount = resultSet.getLong("archived_study_count"),
                        purgeCutoffCount = resultSet.getLong("purge_cutoff_count"),
                        collectionAcknowledgmentCount = resultSet.getLong("collection_acknowledgment_count"),
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
                "erased_device_key_tombstones", "restricted_participants", "archived_studies",
                "participant_purge_cutoffs", "participant_collection_acknowledgment",
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
                    checkpoint.erasedDeviceKeyTombstoneCount,
                    checkpoint.restrictedParticipantCount, checkpoint.archivedStudyCount,
                    checkpoint.purgeCutoffCount, checkpoint.collectionAcknowledgmentCount,
                ),
            ) { "Restore continuity checkpoint row counts do not match its protected tables" }
            check(computeCheckpointSha256(connection) == checkpoint.checkpointSha256) {
                "Restore continuity checkpoint digest does not match its protected tables"
            }
            // Known before the relations check: a proof the backup already holds needs no replay, so a
            // later hold on the same subject cannot conflict with it.
            val alreadyProtectedOperationIds = loadAlreadyProtectedCompletedOperationIds(connection)
            val absentCompletedIds = loadOperations().asSequence()
                .filter { it.status == "COMPLETED" && it.hasSourceTombstone && it.mode != DataDeletionMode.STUDY_ERASURE }
                .map(::withRestoredSubject)
                .filter { it.participantId == null }
                .map(RestoredDeletionOperation::operationId).toSet()
            validateCheckpointRelations(connection, alreadyProtectedOperationIds + absentCompletedIds)
            checkpoint to alreadyProtectedOperationIds
        }

    /**
     * A contract-2 checkpoint left unresolved across an upgrade (restore.sh reuses it). It is proven
     * against its own digest and counts, then brought to contract 3 in one transaction:
     * - each operation's block token is derived from its participant id; where completion already
     *   cleared the id, from the restored backup's copy of the same operation. Nothing else binds an
     *   operation to a subject: participant_ref is a truncated, keyed log fingerprint, so a match can
     *   be a live participant. An operation bound by neither fails closed and the upgrade rolls back;
     * - it carried no erased device-key tombstones, so that table starts empty.
     */
    @Suppress("LongMethod")
    private fun upgradeLegacyCheckpoint(connection: Connection) {
        withTransaction(connection, afterCommit = {
            logger.info("restore_continuity outcome=checkpoint_upgraded from_contract={}", LEGACY_CONTRACT_VERSION)
        }) {
            setDeletionWorkerContext(connection, transactionLocal = true)
            val legacyTables = listOf(
                "withdrawal_requests", "revoked_api_keys", "withdrawn_participants", "deletion_operations",
                "deletion_tombstones", "data_collection_settings_revisions",
                "published_data_collection_settings", "enrollment_invitations",
            )
            val (legacyDigest, legacyCounts) = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                           withdrawn_participant_count, deletion_operation_count, source_tombstone_count,
                           collection_revision_count, published_collection_settings_count,
                           enrollment_invitation_count
                    FROM chronicle_restore_continuity.checkpoint
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())
                    resultSet.getString(1) to (2..legacyTables.size + 1).map(resultSet::getLong)
                }
            }
            check(legacyTables.map { countRecoveryRows(connection, it) } == legacyCounts) {
                "Restore continuity checkpoint row counts do not match its protected tables"
            }
            check(computeCheckpointSha256(connection, LEGACY_CHECKPOINT_DIGEST_SQL) == legacyDigest) {
                "Restore continuity checkpoint digest does not match its protected tables"
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE chronicle_restore_continuity.deletion_operations ADD COLUMN participant_block_token TEXT",
                )
                statement.executeUpdate(
                    """
                    UPDATE chronicle_restore_continuity.deletion_operations
                    SET participant_block_token = chronicle_participant_block_token(study_id, participant_id)
                    WHERE participant_id IS NOT NULL
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    UPDATE chronicle_restore_continuity.deletion_operations source
                    SET participant_block_token = target.participant_block_token
                    FROM data_deletion_operations target
                    WHERE source.participant_block_token IS NULL
                      AND source.mode <> 'STUDY_ERASURE'
                      AND target.operation_id = source.operation_id
                      AND target.study_id = source.study_id
                      AND target.mode = source.mode
                      AND target.participant_ref IS NOT DISTINCT FROM source.participant_ref
                    """.trimIndent(),
                )
            }
            val unbound = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT operation_id FROM chronicle_restore_continuity.deletion_operations
                    WHERE participant_block_token IS NULL AND mode <> 'STUDY_ERASURE'
                    ORDER BY operation_id LIMIT 1
                    """.trimIndent(),
                ).use { resultSet -> if (resultSet.next()) resultSet.getObject(1, UUID::class.java) else null }
            }
            check(unbound == null) {
                "Restore continuity contract 2 checkpoint cannot be upgraded safely: deletion operation " +
                    "$unbound has no participant id and the restored backup holds no copy of it. Chronicle " +
                    "stays stopped with the checkpoint preserved; finish or abandon the restore as described " +
                    "in selfhost/docs/UPGRADE-ROLLBACK.md"
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE chronicle_restore_continuity.erased_device_key_tombstones (
                        key_hash TEXT PRIMARY KEY,
                        kind TEXT NOT NULL,
                        expires_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    ALTER TABLE chronicle_restore_continuity.checkpoint
                    ADD COLUMN erased_device_key_tombstone_count BIGINT NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
            }
            updateCheckpointContract(connection, PREVIOUS_CONTRACT_VERSION, PREVIOUS_CHECKPOINT_DIGEST_SQL)
        }
    }

    private fun upgradePreviousCheckpoint(connection: Connection) {
        withTransaction(connection) {
            val digest = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT checkpoint_sha256 FROM chronicle_restore_continuity.checkpoint").use {
                    check(it.next())
                    it.getString(1)
                }
            }
            check(computeCheckpointSha256(connection, PREVIOUS_CHECKPOINT_DIGEST_SQL) == digest) {
                "Restore continuity checkpoint digest does not match its protected tables"
            }
            connection.createStatement().use { statement ->
                statement.execute(restoreResource("restore-restrictions-schema.sql"))
                statement.execute("""
                    ALTER TABLE chronicle_restore_continuity.checkpoint
                    ADD COLUMN restricted_participant_count BIGINT NOT NULL DEFAULT 0,
                    ADD COLUMN archived_study_count BIGINT NOT NULL DEFAULT 0
                """.trimIndent())
                statement.execute("""
                    REVOKE ALL ON ALL TABLES IN SCHEMA chronicle_restore_continuity
                    FROM PUBLIC, chronicle_app, chronicle_admin
                """.trimIndent())
            }
            updateCheckpointContract(connection, RESTRICTIONS_CONTRACT_VERSION, RESTRICTIONS_CHECKPOINT_DIGEST_SQL)
        }
    }

    private fun upgradeRestrictionsCheckpoint(connection: Connection) {
        withTransaction(connection) {
            val digest = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT checkpoint_sha256 FROM chronicle_restore_continuity.checkpoint").use {
                    check(it.next())
                    it.getString(1)
                }
            }
            check(computeCheckpointSha256(connection, RESTRICTIONS_CHECKPOINT_DIGEST_SQL) == digest) {
                "Restore continuity checkpoint digest does not match its protected tables"
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE chronicle_restore_continuity.restricted_participants ADD COLUMN IF NOT EXISTS capture_cutoff TIMESTAMPTZ")
                statement.execute("ALTER TABLE chronicle_restore_continuity.deletion_operations ADD COLUMN started_at TIMESTAMPTZ")
                // Older contracts cannot recover missing decisions. Never substitute replay time
                // for a completed purge: its completion is the latest original watermark available.
                statement.executeUpdate("""
                    UPDATE chronicle_restore_continuity.deletion_operations source
                    SET started_at = COALESCE(
                        (SELECT target.started_at FROM data_deletion_operations target
                         WHERE target.operation_id = source.operation_id), source.completed_at)
                """.trimIndent())
                statement.execute(restoreResource("restore-authorities-schema.sql"))
                statement.execute("""
                    REVOKE ALL ON ALL TABLES IN SCHEMA chronicle_restore_continuity
                    FROM PUBLIC, chronicle_app, chronicle_admin
                """.trimIndent())
            }
            updateCheckpointContract(connection, CONTRACT_VERSION, CHECKPOINT_DIGEST_SQL)
        }
    }

    private fun updateCheckpointContract(connection: Connection, version: Int, digestSql: String) {
        connection.prepareStatement(
            "UPDATE chronicle_restore_continuity.checkpoint SET contract_version = ?, checkpoint_sha256 = ?",
        ).use {
            it.setInt(1, version)
            it.setString(2, computeCheckpointSha256(connection, digestSql))
            check(it.executeUpdate() == 1)
        }
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

    private fun validateCheckpointRelations(connection: Connection, alreadyProtectedOperationIds: Set<UUID>) {
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
                    ))) +
                (SELECT count(*)
                 FROM chronicle_restore_continuity.retention_holds
                 WHERE (released_at IS NULL) <>
                       (released_by IS NULL AND release_reason IS NULL)) +
                (SELECT count(*)
                 FROM chronicle_restore_continuity.deletion_operations operation
                 CROSS JOIN LATERAL (
                     SELECT EXISTS (
                         SELECT 1
                         FROM chronicle_restore_continuity.retention_holds hold
                         WHERE hold.operation_id = operation.operation_id
                           AND hold.released_at IS NULL
                     ) AS has_active_hold
                 ) hold_state
                 -- HELD requires an active hold; holds may also outlive a hold-free
                 -- transition (placed on QUARANTINED, released later, or left by a cancel).
                 WHERE CASE
                     WHEN operation.status = 'HELD' THEN NOT hold_state.has_active_hold
                     WHEN operation.status IN ('QUARANTINED', 'READY', 'FAILED', 'CANCELLED') THEN false
                     ELSE hold_state.has_active_hold
                 END) +
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
                   AND NOT completed.operation_id = ANY(?)
                   AND (
                       completed.mode = 'STUDY_ERASURE'
                       OR held.mode = 'STUDY_ERASURE'
                       OR held.participant_block_token = completed.participant_block_token
                   ))
            """.trimIndent(),
        ).use { statement ->
            statement.setArray(1, connection.createArrayOf("uuid", alreadyProtectedOperationIds.toTypedArray()))
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
                           operation.idempotency_key, operation.quarantine_until, operation.started_at,
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
                                    quarantineUntil = resultSet.getObject("quarantine_until", OffsetDateTime::class.java),
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

    /**
     * Completion clears an operation's participant_id and keeps only its participant_block_token. The
     * restored backup may still hold the subject's rows in clear; find the participant id they carry so
     * the canonical erasure can run again. Null when the backup holds nothing of the subject (enrolled
     * after the backup, for instance). The identifier is never written into the checkpoint.
     */
    private fun withRestoredSubject(operation: RestoredDeletionOperation): RestoredDeletionOperation {
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
            connection.autoCommit = false
            try {
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
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
        check(subjects.size <= 1) { "Restore continuity deletion subject is ambiguous" }
        return operation.copy(participantId = subjects.firstOrNull())
    }

    /**
     * The restored backup holds nothing of this erased subject: there is nothing to erase again. Restore
     * the completed operation (participant_id stays NULL, its block token keeps fencing re-addition), its
     * holds and its source proof, and drop any token-keyed rows the backup still has.
     */
    private fun restoreCompletedErasureOfAbsentSubject(operation: RestoredDeletionOperation) {
        check(operation.status == "COMPLETED" && operation.hasSourceTombstone) {
            "Only a completed erasure can lack its participant"
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
                com.openlattice.chronicle.storage.DeletionStudyFence.exclusive(connection, operation.studyId)
                listOf("data_deletion_tombstones", "retention_holds").forEach { table ->
                    connection.prepareStatement("DELETE FROM $table WHERE operation_id = ?").use { statement ->
                        statement.setObject(1, operation.operationId)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    """
                    INSERT INTO data_deletion_operations (
                        operation_id, study_id, participant_ref, participant_id, participant_block_token,
                        mode, status, requested_by, idempotency_key, registry_version, quarantine_until,
                        completed_at, proof_hash
                    )
                    SELECT operation_id, study_id, participant_ref, NULL, participant_block_token,
                           mode, status, requested_by, idempotency_key, registry_version, quarantine_until,
                           completed_at, proof_hash
                    FROM chronicle_restore_continuity.deletion_operations
                    WHERE operation_id = ?
                    ON CONFLICT (operation_id) DO UPDATE SET
                        participant_ref = EXCLUDED.participant_ref,
                        participant_block_token = EXCLUDED.participant_block_token,
                        status = EXCLUDED.status,
                        registry_version = EXCLUDED.registry_version,
                        quarantine_until = EXCLUDED.quarantine_until,
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
                    """
                    INSERT INTO retention_holds (
                        hold_id, operation_id, study_id, reason, created_by, created_at,
                        review_at, released_by, released_at, release_reason
                    )
                    SELECT hold_id, operation_id, study_id, reason, created_by, created_at,
                           review_at, released_by, released_at, release_reason
                    FROM chronicle_restore_continuity.retention_holds
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
                        UPDATE study_participants target
                        SET capture_cutoff = source.capture_cutoff
                        FROM chronicle_restore_continuity.restricted_participants source
                        WHERE target.study_id = source.study_id AND target.participant_id = source.participant_id
                          AND target.participation_status IN ('PAUSED', 'COLLECTION_COMPLETED')
                    """.trimIndent())
                    statement.executeUpdate("""
                        INSERT INTO participant_purge_cutoffs (study_id, participant_block_token, cutoff)
                        SELECT study_id, participant_block_token, cutoff
                        FROM chronicle_restore_continuity.participant_purge_cutoffs
                        ON CONFLICT (study_id, participant_block_token) DO UPDATE
                        SET cutoff = GREATEST(participant_purge_cutoffs.cutoff, EXCLUDED.cutoff)
                    """.trimIndent())
                    statement.executeQuery("""
                        SELECT count(*) FROM chronicle_restore_continuity.participant_collection_acknowledgment source
                        JOIN participant_collection_acknowledgment target USING (id)
                        WHERE to_jsonb(source) IS DISTINCT FROM to_jsonb(target)
                    """.trimIndent()).use { rows ->
                        check(rows.next() && rows.getLong(1) == 0L) { "Conflicting restored collection acknowledgment" }
                    }
                    statement.executeUpdate("""
                        INSERT INTO participant_collection_acknowledgment
                        SELECT (jsonb_populate_record(NULL::participant_collection_acknowledgment, to_jsonb(source))).*
                        FROM chronicle_restore_continuity.participant_collection_acknowledgment source
                        ORDER BY source.settings_version, source.recorded_at, source.id
                        ON CONFLICT (id) DO NOTHING
                    """.trimIndent())
                }
            }
        }
    }

    /**
     * Erasure deleted these device keys after keeping their hashes. A key enrolled after the backup
     * exists nowhere in it, so no replay can rebuild its tombstone: carry it with its original expiry.
     * Runs before any replay, so a re-erasure never extends a carried tombstone. A tombstone the
     * restored schema already has for the same hash (V115 writes restore-time expiries for a pre-V114
     * backup) takes the checkpoint's kind and original expiry.
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
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
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

    private fun applyContainmentAndReceipts() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            withTransaction(connection) {
                setDeletionWorkerContext(connection, transactionLocal = true)
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
                connection.createStatement().use {
                    it.execute(restoreResource("restore-restrictions-containment.sql"))
                }
                verifyContainment(connection)
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
            withTransaction(connection) {
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
                        published_collection_settings_count, enrollment_invitation_count,
                        restricted_participant_count, archived_study_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.executeUpdate()
                }
                verifyReceipt(
                    connection,
                    checkpoint,
                    alreadyProtectedDeletionCount,
                    completedDeletionCount,
                )
                setDeletionWorkerContext(connection, transactionLocal = true)
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
                   published_collection_settings_count, enrollment_invitation_count,
                   restricted_participant_count, archived_study_count
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
