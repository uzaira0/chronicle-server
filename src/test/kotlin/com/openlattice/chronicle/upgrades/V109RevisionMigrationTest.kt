package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

class V109RevisionMigrationTest {
    private fun withMigratedDatabase(test: (PostgreSQLContainer<*>) -> Unit) {
        ChronicleContractTestSchema.prodPostgresContainer("revision_upgrade").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)
            test(pg)
        }
    }

    @Test fun webhookParticipantIndexContainsOnlyParticipantScopedDeliveries() = withMigratedDatabase { pg ->
        val study = UUID.randomUUID(); val webhook = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("INSERT INTO studies (study_id, title) VALUES ('$study', 'revision')")
            s.execute(
                "INSERT INTO webhook_registrations (webhook_id, study_id, url, created_by) VALUES ('$webhook', '$study', " +
                "'https://example.org', 'revision')")
            s.execute("""INSERT INTO webhook_deliveries (webhook_id, event_type, payload, delivery_state)
                SELECT '$webhook', 'DATA_SUBMITTED', '{}'::jsonb, 'PENDING' FROM generate_series(1, 1000)""")
            s.execute("""INSERT INTO webhook_deliveries (webhook_id, event_type, participant_id, payload, delivery_state)
                VALUES ('$webhook', 'DATA_SUBMITTED', 'erased', '{}', 'PENDING')""")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("ANALYZE webhook_deliveries")
            s.executeQuery("SELECT reltuples::integer FROM pg_class WHERE relname = 'webhook_deliveries_participant_scope'").use { r ->
                assertTrue(r.next()); assertEquals("Index construction must be limited to participant scope", 1, r.getInt(1))
            }
        } }
    }
}
