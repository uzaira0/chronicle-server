package com.openlattice.chronicle.services.delete

import com.zaxxer.hikari.HikariDataSource
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

private const val RECOVERY_SCHEMA = "chronicle_restore_continuity"

internal data class RestoreContinuityCheckpoint(
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
) {
    val expectedRowCounts: Map<String, Long> = mapOf(
        "withdrawal_requests" to withdrawalReceiptCount,
        "revoked_api_keys" to revokedApiKeyCount,
        "withdrawn_participants" to withdrawnParticipantCount,
        "deletion_operations" to deletionOperationCount,
        "deletion_tombstones" to sourceTombstoneCount,
        "data_collection_settings_revisions" to collectionRevisionCount,
        "published_data_collection_settings" to publishedCollectionSettingsCount,
        "enrollment_invitations" to enrollmentInvitationCount,
        "erased_device_key_tombstones" to erasedDeviceKeyTombstoneCount,
        "restricted_participants" to restrictedParticipantCount,
        "archived_studies" to archivedStudyCount,
        "participant_purge_cutoffs" to purgeCutoffCount,
        "participant_collection_acknowledgment" to collectionAcknowledgmentCount,
    )
}

internal fun restoreResource(name: String): String =
    requireNotNull(RestoreCheckpointRepository::class.java.getResourceAsStream("/$name")) {
        "Missing restore continuity resource $name"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

/** Owns checkpoint reads, row counts, and the current contract's canonical digest. */
public class RestoreCheckpointRepository(
    private val bootstrapStorage: HikariDataSource,
    private val contractVersion: Int,
) {
    private val storage get() = PinnedPlatformConnection.resolve(bootstrapStorage)
    private val checkpointDigestSql = restoreResource("restore-continuity-digest.sql") + "SELECT value FROM digest"
    private val tokenKeyContinuitySql = restoreResource("restore-token-key-continuity.sql")

    internal fun computeCheckpointSha256(
        connection: Connection,
    ): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(checkpointDigestSql).use { resultSet ->
                check(resultSet.next()) { "Unable to calculate restore continuity checkpoint digest" }
                resultSet.getString(1)
            }
        }

    internal fun exists(): Boolean =
        storage.connection.use { connection ->
            connection.prepareStatement("SELECT to_regnamespace(?) IS NOT NULL").use { statement ->
                statement.setString(1, RECOVERY_SCHEMA)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getBoolean(1)
                }
            }
        }

    internal fun readValidated(connection: Connection): RestoreContinuityCheckpoint {
        val checkpoint = readCurrentCheckpoint(connection)
        check(checkpoint.expectedRowCounts.all { (table, expected) -> countRecoveryRows(connection, table) == expected }) {
            "Restore continuity checkpoint row counts do not match its protected tables"
        }
        check(computeCheckpointSha256(connection) == checkpoint.checkpointSha256) {
            "Restore continuity checkpoint digest does not match its protected tables"
        }
        connection.createStatement().use { it.execute(tokenKeyContinuitySql) }
        return checkpoint
    }

    internal fun requireCurrentContract(version: Int) {
        check(version == contractVersion) { "Unsupported restore continuity checkpoint contract" }
    }

    private fun readCurrentCheckpoint(connection: Connection): RestoreContinuityCheckpoint {
        // Read the version alone first: an older contract lacks columns this one selects.
        val contractVersion = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT contract_version FROM chronicle_restore_continuity.checkpoint").use {
                check(it.next()) { "Restore continuity checkpoint is missing" }
                val version = it.getInt(1)
                check(!it.next()) { "Restore continuity checkpoint contains multiple authorities" }
                version
            }
        }
        requireCurrentContract(contractVersion)
        return connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT * FROM chronicle_restore_continuity.checkpoint",
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
                requireCurrentContract(resultSet.getInt("contract_version"))
                check(!resultSet.next()) { "Restore continuity checkpoint contains multiple authorities" }
                loaded
            }
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

}
