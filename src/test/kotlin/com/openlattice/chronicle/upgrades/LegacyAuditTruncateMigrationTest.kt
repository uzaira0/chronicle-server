package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * Reproduces the fresh-install ordering where role/default-privilege bootstrap runs before
 * Flyway creates the legacy audit tables. The bootstrap's existence-guarded REVOKE is then a
 * no-op, while chronicle_admin's default ALL grant gives it effective TRUNCATE on the future
 * tables. V54/V59 removed UPDATE/DELETE but historically left that destructive privilege open.
 */
class LegacyAuditTruncateMigrationTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_legacy_audit_truncate")
    }

    @Test
    fun `forward migration closes default privilege truncate gap without removing append access`() {
        postgres.createConnection("").use { connection ->
            seedLegacyPrivilegeGap(connection)
            assertPrivilegeGapPresent(connection)
            grantAppTruncateForMigrationCoverage(connection)

            assertAppendAndReadWork(connection, "chronicle_app")
            assertAppendAndReadWork(connection, "chronicle_admin")

            connection.createStatement().use { it.execute(migration) }
            assertHardenedAuditPrivileges(connection)
        }
    }

    private fun seedLegacyPrivilegeGap(connection: Connection) {
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

            // Mirrors the production bootstrap guard: no tables exist yet, so this cannot
            // revoke any default privilege that will materialize on the later Flyway tables.
            statement.execute(
                """
                DO ${'$'}${'$'}
                DECLARE audit_table TEXT;
                BEGIN
                    FOREACH audit_table IN ARRAY ARRAY['audit', 'audit_buffer'] LOOP
                        IF to_regclass('public.' || audit_table) IS NOT NULL THEN
                            EXECUTE format(
                                'REVOKE UPDATE, DELETE, TRUNCATE ON public.%I FROM chronicle_app, chronicle_admin',
                                audit_table
                            );
                        END IF;
                    END LOOP;
                END ${'$'}${'$'}
                """.trimIndent(),
            )

            statement.execute("CREATE TABLE public.audit (id INTEGER PRIMARY KEY)")
            statement.execute("CREATE TABLE public.audit_buffer (id INTEGER PRIMARY KEY)")

            // Model the V54/V59 end state exactly: append/read survive, UPDATE/DELETE do not,
            // but chronicle_admin's default ALL grant still includes TRUNCATE.
            statement.execute(
                "REVOKE UPDATE, DELETE ON public.audit, public.audit_buffer " +
                    "FROM chronicle_app, chronicle_admin",
            )
        }
    }

    private fun assertPrivilegeGapPresent(connection: Connection) {
        assertFalse(hasPrivilege(connection, "chronicle_app", "audit", "TRUNCATE"))
        assertTrue(hasPrivilege(connection, "chronicle_admin", "audit", "TRUNCATE"))
        assertTrue(hasPrivilege(connection, "chronicle_admin", "audit_buffer", "TRUNCATE"))
    }

    private fun grantAppTruncateForMigrationCoverage(connection: Connection) {
        // chronicle_app's current default grant is already safe, but V96 names both runtime
        // roles defensively. Exercise that branch too so dropping either REVOKE is observable.
        connection.createStatement().use {
            it.execute("GRANT TRUNCATE ON public.audit, public.audit_buffer TO chronicle_app")
        }
        assertTrue(hasPrivilege(connection, "chronicle_app", "audit", "TRUNCATE"))
        assertTrue(hasPrivilege(connection, "chronicle_app", "audit_buffer", "TRUNCATE"))
    }

    private fun assertHardenedAuditPrivileges(connection: Connection) {
        listOf("chronicle_app", "chronicle_admin").forEach { role ->
            listOf("audit", "audit_buffer").forEach { table ->
                assertFalse("$role must not truncate $table", hasPrivilege(connection, role, table, "TRUNCATE"))
                assertTrue("$role must retain SELECT on $table", hasPrivilege(connection, role, table, "SELECT"))
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

    private fun assertAppendAndReadWork(connection: Connection, role: String) {
        inRole(connection, role) { roleConnection ->
            roleConnection.createStatement().use { statement ->
                assertTrue(statement.executeUpdate("INSERT INTO public.audit VALUES (1)") == 1)
                statement.executeQuery("SELECT COUNT(*) FROM public.audit").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertTrue(resultSet.getInt(1) == 1)
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

    private val migration: String
        get() = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V96__close_legacy_audit_truncate_gap.sql"),
        ).bufferedReader().use { it.readText() }
}
