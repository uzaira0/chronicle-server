package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException

/** Fresh audit grants preserve append access and deny destructive runtime privileges. */
class AuditMutationPrivilegesTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_legacy_audit_truncate")
    }

    @Test
    fun `forward migration closes default privilege truncate gap without removing append access`() {
        ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        postgres.createConnection("").use { connection ->
            assertHardenedAuditPrivileges(connection)
        }
    }

    private fun assertHardenedAuditPrivileges(connection: Connection) {
        listOf("chronicle_app", "chronicle_admin").forEach { role ->
            listOf("audit", "audit_buffer").forEach { table ->
                assertFalse("$role must not truncate $table", hasPrivilege(connection, role, table, "TRUNCATE"))
                assertEquals("$role read access on $table", role == "chronicle_admin", hasPrivilege(connection, role, table, "SELECT"))
                assertTrue("$role must retain INSERT on $table", hasPrivilege(connection, role, table, "INSERT"))
                assertFalse("$role must not update $table", hasPrivilege(connection, role, table, "UPDATE"))
                assertFalse("$role must not delete from $table", hasPrivilege(connection, role, table, "DELETE"))
                assertThrows(SQLException::class.java) {
                    inRole(connection, role) { roleConnection ->
                        roleConnection.createStatement().use { it.execute("TRUNCATE TABLE public.$table") }
                    }
                }
            }
        }
    }

    private fun inRole(connection: Connection, role: String, action: (Connection) -> Unit) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL ROLE $role") }
            action(connection)
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    private fun hasPrivilege(connection: Connection, role: String, table: String, privilege: String): Boolean =
        connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)").use { statement ->
            statement.setString(1, role)
            statement.setString(2, "public.$table")
            statement.setString(3, privilege)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

}
