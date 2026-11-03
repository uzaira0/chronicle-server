package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** New public tables must explicitly declare their row isolation or explain their global role. */
class RlsCoverageTest {
    private val globalTables = mapOf(
        "permissions" to "The shared ACL graph resolves study/organization authorization before request context exists.",
        "principals" to "Global identity and role definitions participate in the shared ACL graph.",
        "principal_trees" to "Global role membership resolves the principals used to build request context.",
        "securable_objects" to "Global ACL object types and reserved names are shared by every authorization lookup.",
        "id_gen" to "The global UUID allocator stores ranges, not tenant records.",
        "base_long_ids" to "The global sequence allocator stores named ranges, not tenant records.",
        "default_filtered_apps" to "The built-in application catalog is shared configuration, not collected data.",
        "flyway_schema_history" to "Flyway owns the global migration ledger; runtime does not migrate schemas.",
        "chronicle_participant_token_key" to "Only SECURITY DEFINER token hashing reads this key; runtime DML is revoked.",
        "restore_continuity_reconciliations" to "Bootstrap-owned restore receipts; runtime DML is revoked.",
    )

    @Test
    fun everyPublicTableEnforcesRlsOrHasAnExplicitGlobalReason() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            val protection = tableProtection(connection)
            val unprotected = protection.filter { (table, secured) -> table !in globalTables && !secured }.keys
            assertTrue("Missing ENABLE/FORCE RLS or policy: $unprotected", unprotected.isEmpty())
            assertEquals("Remove stale global exceptions", globalTables.keys, protection.keys.intersect(globalTables.keys))
            for (table in listOf("chronicle_participant_token_key", "restore_continuity_reconciliations", "flyway_schema_history")) {
                assertTrue("Runtime must not access $table", !hasRuntimeDml(connection, table))
            }
        }
    }

    private fun tableProtection(connection: Connection): Map<String, Boolean> = connection.createStatement().use { statement ->
        statement.executeQuery("""
            SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
        """.trimIndent()).use(::readTableProtection)
    }

    private fun readTableProtection(rows: ResultSet): Map<String, Boolean> = buildMap {
        while (rows.next()) {
            put(rows.getString(1), rows.getBoolean(2) && rows.getBoolean(3) && rows.getBoolean(4))
        }
    }

    private fun hasRuntimeDml(connection: Connection, table: String): Boolean = connection.prepareStatement("""
        SELECT has_table_privilege('chronicle_app', ?, 'SELECT,INSERT,UPDATE,DELETE')
    """.trimIndent()).use { statement ->
        statement.setString(1, "public.$table")
        statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
    }
}
