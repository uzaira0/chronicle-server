package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.util.UUID

class ServerBatchMigrationRegressionTest {
    @Test
    fun outboxReplayDeduplicatesOnAFreshKeylessAuditTable() = withDatabase("130") { pg ->
        pg.createConnection("").use { connection -> connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE audit DROP CONSTRAINT audit_pkey")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { connection ->
            val key = com.openlattice.chronicle.services.delete.DataDeletionOrchestrator.DELETION_AUDIT_EVENT_ID
            val event = com.openlattice.chronicle.auditing.AuditableEvent(
                com.openlattice.chronicle.authorization.AclKey(UUID.randomUUID()),
                UUID.randomUUID(), com.openlattice.chronicle.authorization.Principal(
                    com.openlattice.chronicle.authorization.PrincipalType.USER, "staff"),
                com.openlattice.chronicle.auditing.AuditEventType.UPDATE_PARTICIPATION_STATUS,
                data = mapOf(key to UUID.randomUUID().toString()),
            )
            val manager = auditManager()
            connection.autoCommit = false
            repeat(2) { manager.recordEvents(connection, listOf(event)); connection.commit() }
            connection.prepareStatement("SELECT count(*) FROM audit WHERE data::jsonb ->> ? = ?").use { statement ->
                statement.setString(1, key); statement.setString(2, event.data.getValue(key).toString())
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
            }
        }
    }
    @Test fun retainedErasedStudyAuditDoesNotBlockV93() = withDatabase("92") { pg ->
        val erased = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("""INSERT INTO study_settings_audit (id, study_id, changed_by, setting_key, after_value, change_summary)
                VALUES ('${UUID.randomUUID()}', '$erased', 'test', 'DataCollection', '{"settingsVersion":1}', 'retained')""")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM data_collection_settings_revisions WHERE study_id = '$erased'").use {
                it.next(); assertEquals(0, it.getInt(1))
            }
            s.executeQuery("SELECT count(*) FROM study_settings_audit WHERE study_id = '$erased'").use {
                it.next(); assertEquals(1, it.getInt(1))
            }
        } }
    }

    @Test fun legacyPausedAndCompletedCutoffsUseTransactionalEvidenceBeforeLaterAnnotations() = withDatabase("117") { pg ->
        val study = UUID.randomUUID()
        val created = java.time.OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val stopped = created.plusDays(6)
        val uncertainStop = stopped.minusHours(1)
        val pausedStop = stopped.minusHours(2)
        // Unbound evidence (a keyed fingerprint stop, a study-wide duration pause) never sets or shortens a cutoff.
        val knownCutoffs = mapOf("paused" to pausedStop, "completed" to stopped)
        pg.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title, created_at) VALUES (?, 'legacy cutoff', ?)").use {
                it.setObject(1, study); it.setObject(2, created); it.executeUpdate()
            }
            for ((participant, status) in mapOf("paused" to "PAUSED", "completed" to "COLLECTION_COMPLETED", "duration" to "PAUSED",
                "unknown" to "PAUSED", "enrolled" to "ENROLLED")) {
                connection.prepareStatement("""INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status, updated_at)
                    VALUES (?, ?, ?, ?, ?)""").use {
                    it.setObject(1, study); it.setString(2, participant); it.setObject(3, UUID.randomUUID())
                    it.setString(4, status); it.setObject(5, stopped.plusDays(3)); it.executeUpdate()
                }
            }
            val events = listOf("paused", "completed").flatMap { participant ->
                listOf("PAUSED" to created.plusDays(2), "ENROLLED" to created.plusDays(4),
                    "PAUSED" to (if (participant == "paused") pausedStop else stopped),
                    "COLLECTION_COMPLETED" to stopped.plusDays(1)).map { (status, time) ->
                    com.openlattice.chronicle.auditing.AuditableEvent(
                        aclKey = com.openlattice.chronicle.authorization.AclKey(study),
                        securablePrincipalId = UUID.randomUUID(),
                        principal = com.openlattice.chronicle.authorization.SystemUser.CHRONICLE.principal,
                        eventType = com.openlattice.chronicle.auditing.AuditEventType.UPDATE_PARTICIPATION_STATUS,
                        timestamp = time,
                        description = "Set participation status of participant $participant in study $study to $status",
                    )
                }
            } + listOf(
                com.openlattice.chronicle.auditing.AuditableEvent(
                    aclKey = com.openlattice.chronicle.authorization.AclKey(study),
                    securablePrincipalId = UUID.randomUUID(),
                    principal = com.openlattice.chronicle.authorization.SystemUser.CHRONICLE.principal,
                    eventType = com.openlattice.chronicle.auditing.AuditEventType.UPDATE_PARTICIPATION_STATUS,
                    timestamp = uncertainStop.plusMinutes(1),
                    description = "Set participation status of participant duration in study $study to ENROLLED",
                ),
                com.openlattice.chronicle.auditing.AuditableEvent(
                    aclKey = com.openlattice.chronicle.authorization.AclKey(study),
                    securablePrincipalId = UUID.randomUUID(),
                    principal = com.openlattice.chronicle.authorization.SystemUser.CHRONICLE.principal,
                    eventType = com.openlattice.chronicle.auditing.AuditEventType.UPDATE_PARTICIPATION_STATUS,
                    study = study, timestamp = stopped,
                    description = "Paused 3 enrolled participants after study duration elapsed",
                ),
                com.openlattice.chronicle.auditing.AuditableEvent(
                    aclKey = com.openlattice.chronicle.authorization.AclKey(study),
                    securablePrincipalId = UUID.randomUUID(),
                    principal = com.openlattice.chronicle.authorization.SystemUser.CHRONICLE.principal,
                    eventType = com.openlattice.chronicle.auditing.AuditEventType.UPDATE_PARTICIPATION_STATUS,
                    timestamp = uncertainStop,
                    description = "Set participation status of participant participant:0123456789ab in study $study to PAUSED",
                ),
            )
            connection.autoCommit = false
            auditManager().recordEvents(connection, events)
            connection.commit()
        }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT participant_id, capture_cutoff FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, study)
                it.executeQuery().use { rows ->
                    val cutoffs = buildMap {
                        while (rows.next()) put(rows.getString(1), rows.getObject(2, java.time.OffsetDateTime::class.java))
                    }
                    assertEquals(knownCutoffs + mapOf("duration" to null, "unknown" to null, "enrolled" to null), cutoffs)
                }
            }
            for ((participant, cutoff) in knownCutoffs) {
                val times = listOf(cutoff.minusSeconds(1), cutoff, stopped.plusSeconds(1), null)
                assertEquals(listOf(times.first()), com.openlattice.chronicle.storage.ParticipantCollectionGate
                    .retainQueuedCaptures(connection, study, participant, times) { it })
            }
            for (participant in listOf("duration", "unknown")) {
                assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, org.junit.Assert.assertThrows(
                    org.springframework.web.server.ResponseStatusException::class.java,
                ) {
                    com.openlattice.chronicle.storage.ParticipantCollectionGate.retainQueuedCaptures(
                        connection, study, participant, listOf(created.plusDays(1))) { it }
                }.statusCode)
                assertFalse(com.openlattice.chronicle.storage.ParticipantCollectionGate.isActive(
                    connection, study, participant, allowQueuedCaptures = true))
            }
            assertTrue(com.openlattice.chronicle.storage.ParticipantCollectionGate.isActive(connection, study, "enrolled"))
            connection.rollback()
        }
    }

    @Test fun trustedNonSuperuserCanEraseReceiptsButRuntimeCannot() = withDatabase("119") { pg ->
        val study = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("CREATE ROLE erasure_test_owner NOLOGIN NOSUPERUSER NOBYPASSRLS")
            s.execute("GRANT USAGE ON SCHEMA public TO erasure_test_owner")
            s.execute("GRANT SELECT ON data_deletion_operations TO erasure_test_owner")
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO erasure_test_owner")
            s.execute("ALTER FUNCTION chronicle_delete_study_rows(TEXT, UUID) OWNER TO erasure_test_owner")
            s.execute("""INSERT INTO studies (study_id, title, settings) VALUES ('$study', 'test', '{"DataCollection":{"settingsVersion":1}}')""")
            s.execute("""INSERT INTO mobile_withdrawal_requests (request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn)
                VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', '$study', 'p1', '${UUID.randomUUID()}', false)""")
            s.execute("""INSERT INTO data_deletion_operations (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version,
                worker_lease_token, worker_lease_expires_at) VALUES ('${UUID.randomUUID()}', '$study', 'STUDY_ERASURE', 'ERASING', 'test',
                '${UUID.randomUUID()}', 1, '${UUID.randomUUID()}', now() + interval '1 hour')""")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("SET ROLE chronicle_app")
            for (table in listOf("mobile_withdrawal_requests", "data_collection_settings_revisions")) {
                assertThrows(SQLException::class.java) { s.executeUpdate("DELETE FROM $table WHERE study_id = '$study'") }
            }
            s.execute("RESET ROLE")
            com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.setDeletionWorkerContext(c, transactionLocal = false)
            for (table in listOf("mobile_withdrawal_requests", "data_collection_settings_revisions")) {
                s.executeQuery("SELECT chronicle_delete_study_rows('$table', '$study')").use { it.next(); assertEquals(1L, it.getLong(1)) }
                s.executeQuery("SELECT count(*) FROM $table WHERE study_id = '$study'").use { it.next(); assertEquals(0, it.getInt(1)) }
            }
        } }
    }

    @Test fun cancellingDeferredScrubPreservesEnrolledDeviceKeys() = withDatabase("110") { pg ->
        val study = UUID.randomUUID(); val phone = UUID.randomUUID(); val operation = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("""INSERT INTO devices (study_id, device_id, participant_id, device_type, source_device)
                VALUES ('$study', '$phone', 'p1', 'Ios', '{"@class":"IOSDevice","name":"Private Phone","model":"iPhone"}')""")
            for ((id, name) in listOf("legacy" to "Private Phone", "current" to phone.toString())) {
                s.execute("""INSERT INTO sensor_data (study_id, participant_id, sample_id, sensor_type, sample_duration,
                    device_version, device_name, device_model, device_system_name) VALUES ('$study', 'p1', '$id', 'deviceUsage', 1,
                    '17.0', '$name', 'iPhone', '$name')""")
            }
            s.execute("""INSERT INTO data_deletion_operations (operation_id, study_id, participant_id, mode, status, requested_by,
                idempotency_key, registry_version) VALUES ('$operation', '$study', 'p1', 'COLLECTED_DATA_PURGE', 'QUARANTINED', 'test',
                '${UUID.randomUUID()}', 1)""")
        } }
        assertTrue(ChronicleContractTestSchema.migrate(pg).success)
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT source_device ? 'name' FROM devices WHERE device_id = '$phone'").use { r ->
                assertTrue(r.next()); assertTrue("Keep the name mapping until the quarantined sensors can be scrubbed", r.getBoolean(1))
            }
            s.execute(
                "UPDATE data_deletion_operations SET status = 'CANCELLED', cancelled_by = 'test', cancelled_at = now() WHERE operation_id = " +
                "'$operation'")
            repeat(2) {
                s.executeQuery("SELECT device_name, device_system_name FROM sensor_data WHERE study_id = '$study' ORDER BY sample_id").use { r ->
                    var count = 0
                    while (r.next()) { count++; assertEquals(phone.toString(), r.getString(1)); assertEquals("", r.getString(2)) }
                    assertEquals(2, count)
                }
                s.execute("UPDATE data_deletion_operations SET status = 'CANCELLED' WHERE operation_id = '$operation'")
            }
            s.executeQuery("SELECT source_device ? 'name' FROM devices WHERE device_id = '$phone'").use { r -> r.next(); assertFalse(r.getBoolean(
                1)) }
        } }
    }

    private fun auditManager(): com.openlattice.chronicle.auditing.PostgresAuditingManager {
        val resolver = org.mockito.kotlin.mock<com.openlattice.chronicle.storage.StorageResolver>()
        val pool = org.mockito.kotlin.mock<com.zaxxer.hikari.HikariDataSource>()
        org.mockito.kotlin.whenever(resolver.getPlatformStorage()).thenReturn(pool)
        org.mockito.kotlin.whenever(resolver.getAuditStorage()).thenReturn(com.geekbeast.configuration.postgres.PostgresFlavor.VANILLA to pool)
        return com.openlattice.chronicle.auditing.PostgresAuditingManager(resolver)
    }

    private fun withDatabase(target: String, action: (PostgreSQLContainer<*>) -> Unit) {
        ChronicleContractTestSchema.prodPostgresContainer("server_batch_migration").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            pg.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
            assertTrue(FlywayMigrationService.baseConfiguration().dataSource(pg.jdbcUrl, pg.username, pg.password).target(target).load().migrate(
                ).success)
            action(pg)
        }
    }
}
