package com.openlattice.chronicle.upgrades

import com.geekbeast.configuration.postgres.PostgresFlavor

import com.openlattice.chronicle.storage.ResolvedStorage

import com.openlattice.chronicle.contract.seedCandidate

import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.exceptions.CollectionUnavailableException

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.util.UUID

private const val ERASURE_TEST_OWNER = "erasure_test_owner"

class ServerBatchMigrationRegressionTest {
    @Test
    fun outboxReplayDeduplicatesOnAFreshKeylessAuditTable() = withDatabase() { pg ->
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
            repeat(2) {
                PinnedPlatformConnection.committing(requireNotNull(manager.transactionStorage), connection) {
                    manager.recordEvents(connection, listOf(event))
                }
            }
            connection.prepareStatement("SELECT count(*) FROM audit WHERE data::jsonb ->> ? = ?").use { statement ->
                statement.setString(1, key); statement.setString(2, event.data.getValue(key).toString())
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
            }
        }
    }
    /** A batch whose commit landed but whose acknowledgment was lost is resent with new events: each is stored once. */
    @Test fun aRetryAfterALostCommitAckStoresEachEventOnceAndLaterEventsStillFlush() = withDatabase() { pg ->
        fun event(type: com.openlattice.chronicle.auditing.AuditEventType) = com.openlattice.chronicle.auditing.AuditableEvent(
            com.openlattice.chronicle.authorization.AclKey(UUID.randomUUID()), UUID.randomUUID(),
            com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "staff"), type,
        )
        val manager = auditManager { pg.createConnection("") }
        fun commitSuccesses(batch: List<com.openlattice.chronicle.auditing.AuditableEvent>) = pg.createConnection("").use { connection ->
            connection.autoCommit = false
            PinnedPlatformConnection.committing(requireNotNull(manager.transactionStorage), connection) {
                manager.recordEvents(connection, batch)
            }
        }
        val success = event(com.openlattice.chronicle.auditing.AuditEventType.UPDATE_STUDY)
        val nextSuccess = event(com.openlattice.chronicle.auditing.AuditEventType.UPDATE_STUDY)
        val denial = event(com.openlattice.chronicle.auditing.AuditEventType.ACCESS_DENIED)
        val nextDenial = event(com.openlattice.chronicle.auditing.AuditEventType.ACCESS_DENIED)
        val later = event(com.openlattice.chronicle.auditing.AuditEventType.UPDATE_STUDY)
        commitSuccesses(listOf(success))
        commitSuccesses(listOf(success, nextSuccess))
        manager.recordEvents(listOf(denial))
        manager.recordEvents(listOf(denial, nextDenial))
        commitSuccesses(listOf(later))
        val stored = pg.createConnection("").use { connection ->
            connection.prepareStatement("SELECT event_id::text, count(*) FROM audit WHERE event_id::text = ANY (?) GROUP BY 1").use {
                val ids = listOf(success, nextSuccess, denial, nextDenial, later).map { event -> event.eventId.toString() }
                it.setArray(1, connection.createArrayOf("text", ids.toTypedArray()))
                it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) } }
            }
        }
        assertEquals(listOf(success, nextSuccess, denial, nextDenial, later).associate { it.eventId.toString() to 1 }, stored)
    }

    @Test fun pausedAndCompletedCutoffsMigrateAsCaptureWindows() = withDatabase() { pg ->
        val study = UUID.randomUUID()
        val created = java.time.OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val stopped = created.plusDays(6)
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
                    it.setObject(1, study); it.setString(2, participant); it.setObject(3, seedCandidate(connection))
                    it.setString(4, status); it.setObject(5, stopped.plusDays(3)); it.executeUpdate()
                }
            }
            }
        pg.createConnection("").use { connection ->
            connection.prepareStatement("UPDATE study_participants SET capture_cutoff = ? WHERE study_id = ? AND participant_id = ?").use {
                for ((participant, cutoff) in knownCutoffs) {
                    it.setObject(1, cutoff); it.setObject(2, study); it.setString(3, participant); it.executeUpdate()
                }
            }
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
            // The migrated cutoff publishes the window; the phone, not this gate, drops rows inside it.
            for (participant in knownCutoffs.keys) ParticipantCollectionGate(connection).requireCollectionAvailable(study, participant)
            for (participant in listOf("duration", "unknown")) {
                assertEquals(CollectionUnavailableException.Scope.PARTICIPANT, assertThrows(
                    CollectionUnavailableException::class.java,
                ) {
                    ParticipantCollectionGate(connection).requireCollectionAvailable(study, participant)
                }.scope)
                assertFalse(ParticipantCollectionGate(connection).isActive(study, participant, allowQueuedCaptures = true))
            }
            assertTrue(ParticipantCollectionGate(connection).isActive(study, "enrolled"))
            connection.rollback()
        }
    }

    @Test fun trustedNonSuperuserCanEraseReceiptsButRuntimeCannot() = withDatabase() { pg ->
        val study = UUID.randomUUID()
        pg.createConnection("").use { c -> c.createStatement().use { s ->
            s.execute("CREATE ROLE $ERASURE_TEST_OWNER NOLOGIN NOSUPERUSER NOBYPASSRLS")
            s.execute("GRANT USAGE ON SCHEMA public TO $ERASURE_TEST_OWNER")
            s.execute("GRANT SELECT ON data_deletion_operations TO $ERASURE_TEST_OWNER")
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $ERASURE_TEST_OWNER")
            s.execute("ALTER FUNCTION chronicle_delete_study_rows(TEXT, UUID) OWNER TO $ERASURE_TEST_OWNER")
            for (table in listOf("mobile_withdrawal_requests", "data_collection_settings_revisions")) {
                s.execute("GRANT SELECT, DELETE ON $table TO $ERASURE_TEST_OWNER")
                for (policy in listOf("erasure_worker_select", "erasure_worker_delete")) {
                    s.execute("ALTER POLICY $policy ON $table TO $ERASURE_TEST_OWNER")
                }
            }
            s.execute("""INSERT INTO studies (study_id, title, settings) VALUES ('$study', 'test', '{"DataCollection":{"settingsVersion":1}}')""")
            s.execute("""INSERT INTO mobile_withdrawal_requests (request_id, api_key_id, study_id, participant_id, random_device_id, already_withdrawn)
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

    private fun auditManager(
        connect: (() -> java.sql.Connection)? = null,
    ): com.openlattice.chronicle.auditing.PostgresAuditingManager {
        val resolver = org.mockito.kotlin.mock<com.openlattice.chronicle.storage.StorageResolver>()
        val pool = org.mockito.kotlin.mock<com.zaxxer.hikari.HikariDataSource>()
        connect?.let { org.mockito.kotlin.whenever(pool.connection).thenAnswer { _ -> it() } }
        org.mockito.kotlin.whenever(resolver.getPlatformStorage()).thenReturn(pool)
        org.mockito.kotlin.whenever(resolver.getAuditStorage()).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, pool))
        return com.openlattice.chronicle.auditing.PostgresAuditingManager(resolver)
    }

    private fun withDatabase(action: (PostgreSQLContainer<*>) -> Unit) {
        ChronicleContractTestSchema.prodPostgresContainer("server_batch_migration").use { pg ->
            pg.start(); ChronicleContractTestSchema.waitForQueryReady(pg)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)
            action(pg)
        }
    }
}
