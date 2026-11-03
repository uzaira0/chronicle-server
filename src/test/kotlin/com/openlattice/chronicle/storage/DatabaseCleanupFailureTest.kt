package com.openlattice.chronicle.storage

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedOperation
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/** A real failed PostgreSQL transaction must retain its original retry classification through every cleanup. */
class DatabaseCleanupFailureTest : ChronicleServerTests() {
    @Test fun aDeadAuditedConnectionKeepsTheOperationFailure() {
        hds.connection.use { connection ->
            var original: SQLException? = null
            val failure = assertThrows(SQLException::class.java) {
                AuditedOperation(connection, {
                    original = assertThrows(SQLException::class.java) {
                        it.createStatement().use { statement -> statement.execute("SELECT pg_terminate_backend(pg_backend_pid())") }
                    }
                    throw checkNotNull(original)
                }, { emptyList<AuditableEvent>() }, mock<AuditingManager>()).execute()
            }
            assertEquals("57P01", checkNotNull(original).sqlState)
            assertSame("rollback and auto-commit restoration preserve the original failure", original, failure)
        }
    }

    @Test fun restoringRlsContextKeepsSerializationAndDeadlockFailures() {
        val contexts: List<(Connection, () -> Unit) -> Unit> = listOf(
            { connection, action -> RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection, action) },
            { connection, action -> RLSConnectionCustomizer.withWithdrawalEnrollmentContext(connection, action) },
        )
        for (state in listOf("40001", "40P01")) for (context in contexts) assertContextFailure(state, context)
    }

    private fun assertContextFailure(state: String, context: (Connection, () -> Unit) -> Unit) = hds.connection.use { connection ->
        connection.autoCommit = false
        try {
            val failure = assertThrows(SQLException::class.java) {
                context(connection) { failStatement(connection, state) }
            }
            assertEquals("the aborted transaction's 25P02 must not replace $state", state, failure.sqlState)
        } finally {
            connection.rollback()
        }
    }

    private fun failStatement(connection: Connection, state: String) = connection.prepareStatement("SELECT fixture_cleanup_failure(?)").use {
        it.setString(1, state)
        it.execute()
        Unit
    }

    @Test fun failedRollbackNeverCommitsTheFailedWriteByRestoringAutocommit() {
        val id = UUID.randomUUID()
        val original = SQLException("fixture write failed", "53100")
        val rollbackFailure = SQLException("fixture rollback failed", "08006")
        hds.connection.use { real ->
            val connection = object : Connection by real {
                override fun rollback() { throw rollbackFailure }
            }
            val failure = assertThrows(SQLException::class.java) {
                connection.withTransaction {
                    insertProbe(real, id)
                    throw original
                }
            }
            assertSame(original, failure)
            assertTrue("rollback failure is retained", rollbackFailure in failure.suppressed)
            assertTrue("no implicit commit on failed rollback", !real.autoCommit)
        }
        hds.connection.use { connection ->
            assertEquals(0, probeRows(connection, id))
        }
    }

    @Test fun transactionCleanupRestoresTheCallersModeAndReadScopesDiscardWrites() {
        for (autocommit in listOf(true, false)) hds.connection.use { connection ->
            val id = UUID.randomUUID()
            connection.autoCommit = autocommit
            connection.withTransaction(rollbackOnSuccess = true) {
                insertProbe(connection, id)
            }
            assertEquals(autocommit, connection.autoCommit)
            assertEquals(0, probeRows(connection, id))
        }
    }

    private fun insertProbe(connection: Connection, id: UUID) =
        connection.prepareStatement("INSERT INTO fixture_cleanup_probe VALUES (?)").use {
            it.setObject(1, id); it.executeUpdate()
        }

    private fun probeRows(connection: Connection, id: UUID): Int =
        connection.prepareStatement("SELECT count(*) FROM fixture_cleanup_probe WHERE id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }

    init {
        hds.connection.use { connection -> connection.createStatement().use {
            it.execute("""
                CREATE OR REPLACE FUNCTION fixture_cleanup_failure(state text) RETURNS void LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'fixture transaction failure' USING ERRCODE = state; END ${'$'}${'$'};
                CREATE TABLE IF NOT EXISTS fixture_cleanup_probe (id uuid PRIMARY KEY);
            """.trimIndent())
        } }
    }
}
