package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
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
        postgres.createConnection("").use { connection ->
            seedReopenedPrivileges(connection)
            assertTrue(hasSchemaPrivilege(connection, "chronicle_app", "CREATE"))
            assertTrue(hasTablePrivilege(connection, "chronicle_app", "audit", "UPDATE"))
            assertTrue(hasTablePrivilege(connection, "chronicle_admin", "audit_buffer", "DELETE"))

            connection.createStatement().use { it.execute(migration) }
            assertPrivilegesConverged(connection)
            assertAppCannotCreatePublicTable(connection)
        }
    }

    private fun assertPrivilegesConverged(connection: Connection) {
        listOf("chronicle_app", "chronicle_admin").forEach { role ->
            assertFalse("$role must not create public-schema objects", hasSchemaPrivilege(connection, role, "CREATE"))
            listOf("audit", "audit_buffer").forEach { table ->
                assertTrue("$role retains audit read access", hasTablePrivilege(connection, role, table, "SELECT"))
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

    private fun seedReopenedPrivileges(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE ROLE chronicle_app NOSUPERUSER NOINHERIT NOBYPASSRLS")
            statement.execute("CREATE ROLE chronicle_admin NOSUPERUSER NOINHERIT BYPASSRLS")
            statement.execute("CREATE TABLE public.audit (id integer primary key)")
            statement.execute("CREATE TABLE public.audit_buffer (id integer primary key)")
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO chronicle_app, chronicle_admin")
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON public.audit, public.audit_buffer TO chronicle_app, chronicle_admin")
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

    private val migration: String
        get() = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V97__converge_runtime_immutability_privileges.sql"),
        ).bufferedReader().use { it.readText() }
}
