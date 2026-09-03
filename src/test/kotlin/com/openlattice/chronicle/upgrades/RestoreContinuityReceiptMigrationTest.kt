package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Proves the V98 receipt stays owner-written and immutable after fresh-install default grants. */
class RestoreContinuityReceiptMigrationTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_restore_receipt")
    }

    @Test
    fun `migration overrides broad future grants and rejects owner mutation`() {
        postgres.createConnection("").use { connection ->
            seedRuntimeRolesAndDefaultGrants(connection)
            connection.createStatement().use { it.execute(migration) }

            listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE").forEach { privilege ->
                assertFalse(hasPrivilege(connection, "chronicle_app", privilege))
            }
            assertTrue(hasPrivilege(connection, "chronicle_admin", "SELECT"))
            listOf("INSERT", "UPDATE", "DELETE", "TRUNCATE").forEach { privilege ->
                assertFalse(hasPrivilege(connection, "chronicle_admin", privilege))
            }

            val checkpointId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO restore_continuity_reconciliations (
                    checkpoint_id, contract_version, source_schema_version,
                    checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                    withdrawn_participant_count, deletion_operation_count,
                    source_tombstone_count, already_protected_deletion_count,
                    replayed_completed_deletion_count
                ) VALUES (?, 1, '97', ?, 1, 1, 1, 1, 1, 0, 1)
                """.trimIndent(),
            ).use {
                it.setObject(1, checkpointId)
                it.setString(2, "a".repeat(64))
                assertTrue(it.executeUpdate() == 1)
            }

            listOf(
                "UPDATE restore_continuity_reconciliations SET source_schema_version = 'tampered'",
                "DELETE FROM restore_continuity_reconciliations WHERE checkpoint_id = '$checkpointId'",
                "TRUNCATE restore_continuity_reconciliations",
            ).forEach { mutation ->
                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.execute(mutation) }
                }
            }
        }
    }

    private fun seedRuntimeRolesAndDefaultGrants(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE ROLE chronicle_app NOSUPERUSER NOINHERIT NOBYPASSRLS")
            statement.execute("CREATE ROLE chronicle_admin NOSUPERUSER NOINHERIT BYPASSRLS")
            statement.execute("GRANT USAGE ON SCHEMA public TO chronicle_app, chronicle_admin")
            statement.execute(
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public " +
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO chronicle_app",
            )
            statement.execute(
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public " +
                    "GRANT ALL PRIVILEGES ON TABLES TO chronicle_admin",
            )
        }
    }

    private fun hasPrivilege(connection: Connection, role: String, privilege: String): Boolean =
        connection.prepareStatement(
            "SELECT has_table_privilege(?, 'public.restore_continuity_reconciliations', ?)",
        ).use {
            it.setString(1, role)
            it.setString(2, privilege)
            it.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private val migration: String
        get() = requireNotNull(
            javaClass.getResourceAsStream(
                "/db/migration/V98__record_restore_continuity_reconciliation.sql",
            ),
        ).bufferedReader().use { it.readText() }
}
