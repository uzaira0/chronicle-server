package com.openlattice.chronicle.services.apikeys

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.sql.Connection

class WithdrawalAuditAtomicityTest : ServerSweepTestFixture() {
    @Test fun withdrawalSuccessAuditsCommitAndRollBackWithTheSameConnection() {
        TestSecurityUtils.setupSecurityContext()
        try { for (commit in listOf(false, true)) verifyWithdrawalTransaction(commit) }
        finally { TestSecurityUtils.clearSecurityContext() }
    }

    private fun verifyWithdrawalTransaction(commit: Boolean) {
        val study = UUID.randomUUID()
        val participant = "withdrawal-atomicity"
        val key = UUID.randomUUID()
        val device = UUID.randomUUID()
        seed(study, participant)
        pool("withdrawal-audit").use { pool ->
            val resolver = mock<StorageResolver>()
            whenever(resolver.getPlatformStorage()).thenReturn(pool)
            whenever(resolver.getAuditStorage()).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, pool))
            pool.connection.use { connection ->
                seedKey(connection, study, participant, key, device)
                connection.autoCommit = false
                try {
                    PinnedPlatformConnection.committing(pool, connection, commitWhen = { commit }) {
                        MobileWithdrawalIntentBinder(PostgresAuditingManager(resolver)).bind(
                            connection, study, participant, device, key, UUID.randomUUID(),
                        )
                    }
                } finally { connection.rollback(); connection.autoCommit = true }
                assertPersistedState(connection, study, key, commit)
            }
        }
    }

    private fun seedKey(connection: Connection, study: UUID, participant: String, key: UUID, device: UUID) {
        connection.prepareStatement("""
            INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, name, scope,
                created_by, expires_at, participant_id, random_device_id)
            VALUES (?, ?, ?, 'audit', 'audit', 'WRITE', 'test', now() + interval '1 day', ?, ?)
        """.trimIndent()).use { statement ->
            statement.setObject(1, key); statement.setObject(2, study)
            statement.setString(3, key.toString().replace("-", "").repeat(2))
            statement.setString(4, participant); statement.setObject(5, device); statement.executeUpdate()
        }
    }

    private fun assertPersistedState(connection: Connection, study: UUID, key: UUID, commit: Boolean) {
        connection.prepareStatement("SELECT count(*) FROM audit WHERE study_id = ?").use {
            it.setObject(1, study.toString())
            it.executeQuery().use { rows -> rows.next(); assertEquals(if (commit) 2 else 0, rows.getInt(1)) }
        }
        if (!commit) connection.prepareStatement("SELECT revoked FROM api_keys WHERE key_id = ?").use {
            it.setObject(1, key)
            it.executeQuery().use { rows -> rows.next(); assertFalse(rows.getBoolean(1)) }
        }
    }
}
