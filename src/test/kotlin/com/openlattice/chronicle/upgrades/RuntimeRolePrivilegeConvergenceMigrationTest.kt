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

/** Proves an upgraded persistent database converges to the current runtime-role boundary. */
class RuntimeRolePrivilegeConvergenceMigrationTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_runtime_privilege_convergence")
    }

    @Test
    fun `forward migration removes reopened mutation and schema creation privileges`() {
        ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        postgres.createConnection("").use { connection ->
            assertPrivilegesConverged(connection)
            assertAppCannotCreatePublicTable(connection)
        }
    }

    private fun assertPrivilegesConverged(connection: Connection) {
        listOf("chronicle_app", "chronicle_admin").forEach { role ->
            assertFalse("$role must not create public-schema objects", hasSchemaPrivilege(connection, role, "CREATE"))
            listOf("audit", "audit_buffer").forEach { table ->
                assertEquals("$role audit read boundary", role == "chronicle_admin", hasTablePrivilege(connection, role, table, "SELECT"))
                assertTrue("$role retains audit append access", hasTablePrivilege(connection, role, table, "INSERT"))
                assertMutationPrivilegesDenied(connection, role, table)
            }
        }
    }

    private fun assertMutationPrivilegesDenied(connection: Connection, role: String, table: String) {
        listOf("UPDATE", "DELETE", "TRUNCATE").forEach { privilege ->
            assertFalse(
                "$role must not $privilege $table",
                hasTablePrivilege(connection, role, table, privilege),
            )
        }
    }

    private fun assertAppCannotCreatePublicTable(connection: Connection) {
        assertThrows(SQLException::class.java) {
            inRole(connection, "chronicle_app") { roleConnection ->
                roleConnection.createStatement().use { it.execute("CREATE TABLE public.runtime_role_escape (id int)") }
            }
        }
    }

    private fun hasSchemaPrivilege(connection: Connection, role: String, privilege: String): Boolean =
        connection.prepareStatement("SELECT has_schema_privilege(?, 'public', ?)").use { statement ->
            statement.setString(1, role)
            statement.setString(2, privilege)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private fun hasTablePrivilege(
        connection: Connection,
        role: String,
        table: String,
        privilege: String,
    ): Boolean = connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)").use { statement ->
        statement.setString(1, role)
        statement.setString(2, "public.$table")
        statement.setString(3, privilege)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            resultSet.getBoolean(1)
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

}
