package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.PostgresEventTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

class V112RuntimeAuditPrivilegeMigrationTest {
    @Test fun requestRoleKeepsAppendAndPublishButLosesReadAndDelete() {
        ChronicleContractTestSchema.prodPostgresContainer("runtime_audit_privileges").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)

            pg.createConnection("").use { c ->
                for (table in listOf("audit", "audit_buffer")) {
                    assertFalse("$table must not be readable", privilege(c, table, "SELECT"))
                    assertTrue("$table must stay appendable", privilege(c, table, "INSERT"))
                }
                assertFalse(privilege(c, "data_deletion_audit_outbox", "DELETE"))
                assertTrue(privilege(c, "data_deletion_audit_outbox", "UPDATE"))

                asApp(c) { s ->
                    c.prepareStatement(PostgresEventTables.buildMultilineInsertAuditEvents(1, true)).use { ps ->
                        (1..9).forEach { ps.setString(it, "v112") }
                        ps.setObject(10, OffsetDateTime.now())
                        ps.setObject(11, UUID.randomUUID())
                        (12..15).forEach { ps.setString(it, "v112") }
                        assertEquals(1, ps.executeUpdate())
                    }
                    s.executeUpdate("UPDATE data_deletion_audit_outbox SET published_at = now() WHERE false")
                }
                asApp(c) { s -> assertThrows(SQLException::class.java) { s.executeQuery("SELECT 1 FROM audit") } }
                asApp(c) { s ->
                    assertThrows(SQLException::class.java) { s.executeUpdate("DELETE FROM data_deletion_audit_outbox WHERE false") }
                }
            }
        }
    }

    private fun privilege(c: Connection, table: String, privilege: String): Boolean =
        c.createStatement().use { s ->
            s.executeQuery("SELECT has_table_privilege('chronicle_app', 'public.$table', '$privilege')").use { r ->
                r.next(); r.getBoolean(1)
            }
        }

    private fun asApp(c: Connection, block: (java.sql.Statement) -> Unit) {
        c.autoCommit = false
        try {
            c.createStatement().use { s -> s.execute("SET LOCAL ROLE chronicle_app"); block(s) }
        } finally {
            c.rollback(); c.autoCommit = true
        }
    }
}
