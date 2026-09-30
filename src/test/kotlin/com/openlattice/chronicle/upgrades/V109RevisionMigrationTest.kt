package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.*
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.UUID

class V109RevisionMigrationTest {
    private fun legacy(test: (PostgreSQLContainer<*>) -> Unit) {
        ChronicleContractTestSchema.prodPostgresContainer("revision_upgrade").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            pg.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
            assertTrue(FlywayMigrationService.baseConfiguration().dataSource(pg.jdbcUrl, pg.username, pg.password)
                .target("108").load().migrate().success)
            test(pg)
        }
    }

    private fun operation(c: Connection, study: UUID, mode: String, subject: String = "erased", days: Int = 1): UUID {
        val id = UUID.randomUUID()
        c.createStatement().use { it.execute("""INSERT INTO data_deletion_operations
            (operation_id, study_id, participant_ref, participant_block_token, mode, status, requested_by,
             idempotency_key, registry_version, started_at, completed_at)
            VALUES ('$id', '$study', 'opaque', md5('$study:$subject'), '$mode', 'COMPLETED', 'revision',
            '${UUID.randomUUID()}', 1, now() - interval '$days days', now())""") }
        return id
    }

    @Test fun historicalPurgesSeedLatestStartAndSkipSubsequentlyErasedSubjects() = legacy { pg ->
        val study = UUID.randomUUID(); val erasedStudy = UUID.randomUUID()
        pg.createConnection("").use { c ->
            operation(c, study, "COLLECTED_DATA_PURGE", days = 5)
            operation(c, study, "COLLECTED_DATA_PURGE", days = 3)
            operation(c, study, "COLLECTED_DATA_PURGE", "withdrawn", 5)
            operation(c, study, "WITHDRAW_AND_ERASE", "withdrawn", 1)
            operation(c, erasedStudy, "COLLECTED_DATA_PURGE", days = 5)
            operation(c, erasedStudy, "STUDY_ERASURE", days = 1)
        }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use {
            it.executeQuery("""SELECT cutoff = (SELECT max(started_at) FROM data_deletion_operations
                WHERE study_id = '$study' AND mode = 'COLLECTED_DATA_PURGE' AND participant_block_token = md5('$study:erased'))
                FROM participant_purge_cutoffs WHERE study_id = '$study' AND participant_block_token = md5('$study:erased')""").use { r ->
                assertTrue("Historical completed purge needs a replay watermark", r.next()); assertTrue(r.getBoolean(1))
            }
            it.executeQuery("SELECT count(*) FROM participant_purge_cutoffs").use { r -> r.next(); assertEquals(1, r.getInt(1)) }
        } }
    }

    @Test fun completedErasuresRemoveOnlyScopedNotificationLogs() = legacy { pg ->
        val study = UUID.randomUUID(); val whole = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            for (scope in listOf(study, whole)) {
                s.execute("INSERT INTO studies (study_id, title) VALUES ('$scope', 'revision')")
                for (subject in listOf("erased", "control", "")) s.execute("""INSERT INTO notifications
                    (notification_id, study_id, participant_id, created_at, updated_at, message_id, status, notification_type,
                    delivery_type, body, destination, is_html)
                    VALUES ('${UUID.randomUUID()}', '$scope', '$subject', now(), now(), '${UUID.randomUUID()}',
                    'INITIAL', 'PASSIVE_DATA_COLLECTION_COMPLIANCE', 'EMAIL', 'historical body', 'test@example.org', false)""")
            }
            operation(c, study, "WITHDRAW_AND_ERASE"); operation(c, whole, "STUDY_ERASURE")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT participant_id FROM notifications WHERE study_id = '$study' ORDER BY participant_id").use { r ->
                val scopes = buildList { while (r.next()) add(r.getString(1)) }; assertEquals(listOf("", "control"), scopes)
            }
            s.executeQuery("SELECT participant_id FROM notifications WHERE study_id = '$whole'").use { r ->
                assertTrue(r.next()); assertEquals("", r.getString(1)); assertFalse(r.next())
            }
        } }
    }

    @Test fun previouslyRevokedExportsLoseRequestIdentifiersEvenWithoutArtifacts() = legacy { pg ->
        val study = UUID.randomUUID(); val export = UUID.randomUUID(); val control = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("INSERT INTO studies (study_id, title) VALUES ('$study', 'revision')")
            for (id in listOf(export, control)) s.execute("""INSERT INTO export_jobs
                (export_id, study_id, created_by, request, status, format, created_at, completed_at, file_path)
                VALUES ('$id', '$study', 'revision', '{"participantIds":["erased"],"startDate":"2026-01-01"}', 'FAILED', 'CSV', now(), now(), null)""")
            val op = operation(c, study, "WITHDRAW_AND_ERASE")
            s.execute("INSERT INTO export_job_revocations (export_id, study_id, operation_id) VALUES ('$export', '$study', '$op')")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT request = '{}'::jsonb FROM export_jobs WHERE export_id = '$export'").use { r -> r.next(); assertTrue(r.getBoolean(1)) }
            s.executeQuery("SELECT request <> '{}'::jsonb FROM export_jobs WHERE export_id = '$control'").use { r -> r.next(); assertTrue(r.getBoolean(1)) }
        } }
        assertEquals(0, ChronicleContractTestSchema.migrate(pg).migrationsExecuted)
    }

    @Test fun erasureWorkerCanScrubExportRequestsUnderMigratedPrivileges() = legacy { pg ->
        val sql = "SELECT has_column_privilege('chronicle_app', 'export_jobs', 'request', 'UPDATE')"
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery(sql).use { r -> r.next(); assertFalse(r.getBoolean(1)) }
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery(sql).use { r -> r.next(); assertTrue(r.getBoolean(1)) }
        } }
    }

    @Test fun webhookParticipantIndexContainsOnlyParticipantScopedDeliveries() = legacy { pg ->
        val study = UUID.randomUUID(); val webhook = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("INSERT INTO studies (study_id, title) VALUES ('$study', 'revision')")
            s.execute("INSERT INTO webhook_registrations (webhook_id, study_id, url, created_by) VALUES ('$webhook', '$study', 'https://example.org', 'revision')")
            s.execute("""INSERT INTO webhook_deliveries (webhook_id, event_type, payload)
                SELECT '$webhook', 'DATA_SUBMITTED', '{}'::jsonb FROM generate_series(1, 1000)""")
            s.execute("""INSERT INTO webhook_deliveries (webhook_id, event_type, payload)
                VALUES ('$webhook', 'DATA_SUBMITTED', '{"data":{"participantId":"erased"}}')""")
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
