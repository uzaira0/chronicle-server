package com.openlattice.chronicle.services.delete

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.LogSanitizer
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

/** Real-PostgreSQL proof that restoring an older backup cannot revive a completed withdrawal. */
class RestoreContinuityReconcilerTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var storageResolver: StorageResolver

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_restore_continuity")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

            val hikariProperties = Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "4")
            }
            val postgresConfiguration = PostgresConfiguration(
                hikariConfiguration = hikariProperties,
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false,
            )
            val dataSourceManager = DataSourceManager(
                mapOf(
                    "default" to postgresConfiguration,
                    "chronicle" to postgresConfiguration,
                    "platform_read" to postgresConfiguration,
                ),
                HealthCheckRegistry(),
                MetricRegistry(),
            )
            storageResolver = StorageResolver(dataSourceManager, ChronicleStorageConfiguration())
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            postgres.stop()
        }
    }

    private val fixture = RestoreFixture()

    @After
    fun cleanFixture() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS chronicle_restore_continuity CASCADE")
            }
            connection.prepareStatement(
                """
                DELETE FROM data_deletion_tombstones
                WHERE operation_id = ?
                   OR operation_id IN (SELECT operation_id FROM data_deletion_operations WHERE study_id = ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.operationId)
                it.setObject(2, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM data_deletion_operations WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM mobile_withdrawal_requests WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM api_keys WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${fixture.apiKeyId}")
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM battery_telemetry WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM participant_form_access_codes WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeUpdate()
            }
        }
    }

    @Test
    fun `restore replay routes cold studies using a single platform pool slot`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        val properties = Properties().apply {
            setProperty("jdbcUrl", postgres.jdbcUrl)
            setProperty("username", postgres.username)
            setProperty("password", postgres.password)
            setProperty("maximumPoolSize", "1")
            setProperty("connectionTimeout", "300")
        }
        val configuration = PostgresConfiguration(
            hikariConfiguration = properties, usingCitus = false, flavor = PostgresFlavor.VANILLA,
            initializeIndices = false, initializeTables = false,
        )
        val manager = DataSourceManager(
            mapOf("default" to configuration, "chronicle" to configuration),
            HealthCheckRegistry(), MetricRegistry(),
        )
        try {
            val oneSlotResolver = StorageResolver(manager, ChronicleStorageConfiguration())
            val member = Mockito.mock(com.hazelcast.core.HazelcastInstance::class.java)
            @Suppress("UNCHECKED_CAST")
            val studies = Mockito.mock(com.hazelcast.map.IMap::class.java) as com.hazelcast.map.IMap<UUID, com.openlattice.chronicle.study.Study>
            Mockito.`when`(member.getMap<UUID, com.openlattice.chronicle.study.Study>("STUDIES")).thenReturn(studies)
            Mockito.`when`(studies.executeOnKey(org.mockito.kotlin.any(),
                org.mockito.kotlin.any<com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead>())).thenAnswer {
                manager.getDataSource("default").connection.use { "chronicle" }
            }
            oneSlotResolver.setStudyStorage(member)
            val orchestrator = DataDeletionOrchestrator(
                oneSlotResolver, Mockito.mock(AuditingManager::class.java),
                Clock.fixed(Instant.parse("2026-08-21T20:00:00Z"), ZoneOffset.UTC),
            )
            val result = RestoreContinuityReconciler(oneSlotResolver, orchestrator).reconcile()
            assertNotNull(result)
            assertEquals(1L, result!!.replayedCompletedDeletionCount)
            assertFalse(schemaExists("chronicle_restore_continuity"))
            Mockito.verify(studies, Mockito.never()).executeOnKey(org.mockito.kotlin.any(),
                org.mockito.kotlin.any<com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead>())
        } finally {
            manager.dataSources.values.forEach { it.close() }
        }
    }

    @Test
    fun `completed withdrawal is contained re-erased and receipted before checkpoint removal`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        val reconciler = newReconciler()

        val result = reconciler.reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(fixture.checkpointId, result.checkpointId)
        assertEquals(1L, result.withdrawalReceiptCount)
        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertEquals(0L, result.alreadyProtectedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        assertNull("a completed reconciliation must be an idempotent no-op", reconciler.reconcile())

        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(
                0L,
                count(connection, "study_participants", "study_id", fixture.studyId),
            )
            // Re-erasure deletes the restored device key and keeps only its hash tombstone.
            assertEquals(0L, count(connection, "api_keys", "key_id", fixture.apiKeyId))
            connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${fixture.apiKeyId}")
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("WITHDRAW_AND_ERASE", resultSet.getString(1))
                }
            }
            assertEquals(1L, count(connection, "mobile_withdrawal_requests", "request_id", fixture.requestId))
            assertEquals(0L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals("COMPLETED", deletionStatus(connection))
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
            connection.prepareStatement(
                """
                SELECT checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                       withdrawn_participant_count, deletion_operation_count, source_tombstone_count,
                       already_protected_deletion_count, replayed_completed_deletion_count
                FROM restore_continuity_reconciliations
                WHERE checkpoint_id = ?
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.checkpointId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(64, resultSet.getString("checkpoint_sha256").length)
                    assertEquals(1L, resultSet.getLong("withdrawal_receipt_count"))
                    assertEquals(1L, resultSet.getLong("revoked_api_key_count"))
                    assertEquals(1L, resultSet.getLong("withdrawn_participant_count"))
                    assertEquals(1L, resultSet.getLong("deletion_operation_count"))
                    assertEquals(1L, resultSet.getLong("source_tombstone_count"))
                    assertEquals(0L, resultSet.getLong("already_protected_deletion_count"))
                    assertEquals(1L, resultSet.getLong("replayed_completed_deletion_count"))
                }
            }
        }
    }

    @Test
    fun `completed proof already in backup is verified without physical replay`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        seedAlreadyProtectedPublicState()

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.alreadyProtectedDeletionCount)
        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
            assertEquals("COMPLETED", deletionStatus(connection))
            connection.prepareStatement(
                """
                SELECT already_protected_deletion_count, replayed_completed_deletion_count
                FROM restore_continuity_reconciliations
                WHERE checkpoint_id = ?
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.checkpointId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1L, resultSet.getLong("already_protected_deletion_count"))
                    assertEquals(0L, resultSet.getLong("replayed_completed_deletion_count"))
                }
            }
        }
    }

    @Test
    fun `immutable withdrawal conflict fails closed without consuming checkpoint`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO mobile_withdrawal_requests
                    (request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn)
                VALUES (?, ?, ?, ?, ?, false)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.requestId)
                it.setObject(2, UUID.randomUUID())
                it.setObject(3, fixture.studyId)
                it.setString(4, "different-participant")
                it.setObject(5, UUID.randomUUID())
                assertEquals(1, it.executeUpdate())
            }
        }

        assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(0L, count(connection, "restore_continuity_reconciliations", "checkpoint_id", fixture.checkpointId))
        }
    }

    @Test
    fun `immutable withdrawal timestamp conflict fails closed without consuming checkpoint`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO mobile_withdrawal_requests (
                    request_id, api_key_id, study_id, participant_id, device_id,
                    already_withdrawn, created_at
                ) VALUES (?, ?, ?, ?, ?, false, '2026-08-20T12:00:01Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.requestId)
                it.setObject(2, fixture.apiKeyId)
                it.setObject(3, fixture.studyId)
                it.setString(4, fixture.participantId)
                it.setObject(5, fixture.deviceId)
                assertEquals(1, it.executeUpdate())
            }
        }

        assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(
                0L,
                count(connection, "restore_continuity_reconciliations", "checkpoint_id", fixture.checkpointId),
            )
        }
    }

    @Test
    fun `checkpoint digest tampering fails before restored state changes`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE chronicle_restore_continuity.revoked_api_keys
                SET participant_id = 'tampered-participant'
                WHERE key_id = ?
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.apiKeyId)
                assertEquals(1, it.executeUpdate())
            }
        }

        assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `deletion identity conflict fails before withdrawal containment mutates restored state`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until
                ) VALUES (?, ?, 'conflicting-proof', ?, 'WITHDRAW_AND_ERASE', 'QUARANTINED',
                          'fixture', ?, 1, now() + interval '1 hour')
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.requestId)
                assertEquals(1, it.executeUpdate())
            }
        }

        assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `active retention hold remains a held deletion after restore reconciliation`() {
        seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            createContinuitySchema(connection)
            insertRecoveryOperation(
                connection = connection,
                mode = "WITHDRAW_AND_ERASE",
                status = "HELD",
                participantId = fixture.participantId,
            )
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.retention_holds (
                    hold_id, operation_id, study_id, reason, created_by,
                    created_at, review_at, released_by, released_at, release_reason
                ) VALUES (?, ?, ?, 'legal preservation', 'fixture',
                          '2026-08-20T12:01:00Z', '2026-09-20T12:01:00Z', NULL, NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.operationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("HELD", deletionStatus(connection))
            assertEquals(1L, count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `active hold overlapping a completed replay fails closed before containment`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until,
                    completed_at, proof_hash, cancelled_by, cancelled_at
                ) VALUES (?, ?, 'held-participant-proof', ?, 'COLLECTED_DATA_PURGE', 'HELD',
                          'legal-review', ?, 1, '2026-09-20T12:00:00Z',
                          NULL, NULL, NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.heldOperationId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.heldRequestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.retention_holds (
                    hold_id, operation_id, study_id, reason, created_by,
                    created_at, review_at, released_by, released_at, release_reason
                ) VALUES (?, ?, ?, 'legal preservation', 'fixture',
                          '2026-08-20T12:01:00Z', '2026-09-20T12:01:00Z', NULL, NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.heldOperationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            val digest = RestoreContinuityReconciler.computeCheckpointSha256(connection)
            connection.prepareStatement(
                """
                UPDATE chronicle_restore_continuity.checkpoint
                SET checkpoint_sha256 = ?, deletion_operation_count = 2
                WHERE checkpoint_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, digest)
                it.setObject(2, fixture.checkpointId)
                assertEquals(1, it.executeUpdate())
            }
        }

        assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `cancelled study erasure remains cancelled and restores form access`() {
        seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            seedActiveStudyErasureAccessRevocation(connection)
            createContinuitySchema(connection)
            insertRecoveryOperation(
                connection = connection,
                mode = "STUDY_ERASURE",
                status = "CANCELLED",
                participantId = null,
                cancelledBy = "study-owner",
                cancelledAt = "2026-08-20T12:03:00Z",
            )
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.retention_holds (
                    hold_id, operation_id, study_id, reason, created_by,
                    created_at, review_at, released_by, released_at, release_reason
                ) VALUES (?, ?, ?, 'completed legal review', 'fixture',
                          '2026-08-20T12:01:00Z', '2026-08-20T12:02:00Z',
                          'study-owner', '2026-08-20T12:02:30Z', 'review complete')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.operationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("CANCELLED", deletionStatus(connection))
            connection.prepareStatement(
                "SELECT revoked_at FROM participant_form_access_codes WHERE access_code_id = ?",
            ).use {
                it.setObject(1, fixture.accessCodeId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull(resultSet.getObject(1))
                }
            }
            assertEquals(
                0L,
                count(connection, "data_deletion_form_access_revocations", "operation_id", fixture.operationId),
            )
            assertEquals(1L, count(connection, "retention_holds", "hold_id", fixture.holdId))
            connection.prepareStatement(
                "SELECT released_by, release_reason FROM retention_holds WHERE hold_id = ?",
            ).use {
                it.setObject(1, fixture.holdId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("study-owner", resultSet.getString("released_by"))
                    assertEquals("review complete", resultSet.getString("release_reason"))
                }
            }
        }
    }

    @Test
    fun `restore receipt rejects owner mutation and truncate`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        assertNotNull(newReconciler().reconcile())

        storageResolver.getPlatformStorage().connection.use { connection ->
            listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE").forEach { privilege ->
                assertFalse(
                    "chronicle_app must not have $privilege on restore receipts",
                    hasTablePrivilege(connection, "chronicle_app", privilege),
                )
            }
        }

        listOf(
            "UPDATE restore_continuity_reconciliations SET source_schema_version = 'tampered'",
            "DELETE FROM restore_continuity_reconciliations WHERE checkpoint_id = '${fixture.checkpointId}'",
            "TRUNCATE restore_continuity_reconciliations",
        ).forEach { mutation ->
            assertThrows(SQLException::class.java) {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.createStatement().use { it.execute(mutation) }
                }
            }
        }
    }

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun `older restore cannot roll back collection authority or resurrect enrollment invitations`() {
        seedRestoredOlderState()
        val staleInvitationId = UUID.randomUUID()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE studies
                SET settings = jsonb_set(
                    COALESCE(settings, '{}'::jsonb), '{DataCollection}',
                    '{"settingsVersion":1,"enabledModules":["usage_events"]}'::jsonb, true
                )
                WHERE study_id = ?
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            listOf(staleInvitationId).forEach { accessCodeId ->
                connection.prepareStatement(
                    """
                    INSERT INTO participant_form_access_codes (
                        access_code_id, token_hash, study_id, participant_id, form_kind,
                        issuer_type, issued_by, expires_at
                    ) VALUES (?, decode(md5(?), 'hex'), ?, ?, 'ENROLLMENT',
                              'RESEARCHER', 'fixture', '2026-09-20T12:00:00Z')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, accessCodeId)
                    it.setString(2, accessCodeId.toString())
                    it.setObject(3, fixture.studyId)
                    it.setString(4, fixture.participantId)
                    assertEquals(1, it.executeUpdate())
                }
            }

            createContinuitySchema(connection)
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.data_collection_settings_revisions
                    (study_id, settings_version, setting, issued_at)
                VALUES (?, 2,
                        '{"settingsVersion":2,"enabledModules":[],"requiredModules":[]}'::jsonb,
                        '2026-08-20T12:01:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.published_data_collection_settings
                    (study_id, settings_version, setting)
                VALUES (?, 2,
                        '{"settingsVersion":2,"enabledModules":[],"requiredModules":[]}'::jsonb)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.enrollment_invitations (
                    access_code_id, token_hash, study_id, participant_id, form_kind,
                    resource_id, logical_date, issuer_type, issued_by, expires_at,
                    exchanged_at, revoked_at, created_at, enrollment_attempt_id,
                    enrollment_source_device_hash, enrollment_device_id,
                    enrollment_manifest_digest, enrollment_request_hash,
                    enrollment_proposed_key_hash, enrollment_replay_expires_at,
                    enrollment_settings_version, enrollment_disclosure_version,
                    enrollment_enabled_modules, enrollment_required_modules
                ) VALUES (?, decode(md5(?), 'hex'), ?, ?, 'ENROLLMENT', NULL, NULL,
                          'RESEARCHER', 'fixture', '2026-09-20T12:00:00Z',
                          '2026-08-20T12:01:30Z', NULL, '2026-08-20T12:00:00Z', ?,
                          ?, ?, ?, ?, ?, '2026-08-20T12:31:30Z', 2, 'disclosure-v2',
                          '["usage_events"]'::jsonb, '[]'::jsonb)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.accessCodeId)
                it.setString(2, fixture.accessCodeId.toString())
                it.setObject(3, fixture.studyId)
                it.setString(4, fixture.participantId)
                it.setObject(5, fixture.enrollmentAttemptId)
                it.setString(6, "1".repeat(64))
                it.setObject(7, fixture.deviceId)
                it.setString(8, "2".repeat(64))
                it.setString(9, "3".repeat(64))
                it.setString(10, "4".repeat(64))
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT settings -> 'DataCollection' ->> 'settingsVersion' FROM studies WHERE study_id = ?",
            ).use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("2", resultSet.getString(1))
                }
            }
            assertEquals(
                1L,
                count(connection, "data_collection_settings_revisions", "settings_version", 2),
            )
            connection.prepareStatement(
                "SELECT exchanged_at, enrollment_attempt_id FROM participant_form_access_codes WHERE access_code_id = ?",
            ).use {
                it.setObject(1, fixture.accessCodeId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNotNull(resultSet.getObject("exchanged_at"))
                    assertEquals(fixture.enrollmentAttemptId, resultSet.getObject("enrollment_attempt_id"))
                }
            }
            connection.prepareStatement(
                "SELECT revoked_at FROM participant_form_access_codes WHERE access_code_id = ?",
            ).use {
                it.setObject(1, staleInvitationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNotNull(resultSet.getObject(1))
                }
            }
        }
    }

    @Test
    fun `worker completed withdrawal captured by the restore script replays onto an older backup`() {
        seedRestoredOlderState()
        val operationId = eraseParticipantWithTheWorker()
        captureCheckpointWithRestoreScript()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT participant_id, participant_block_token
                FROM chronicle_restore_continuity.deletion_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use {
                it.setObject(1, operationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull("completion clears the participant id", resultSet.getString(1))
                    assertEquals(blockToken(), resultSet.getString(2))
                }
            }
        }
        // The backup predates the erasure: the participant, its key and its data are back.
        restoreBackupTakenBefore(operationId, participantInBackup = true)

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(0L, count(connection, "study_participants", "study_id", fixture.studyId))
            assertEquals(0L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(0L, count(connection, "api_keys", "key_id", fixture.apiKeyId))
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `erased key tombstones absent from the backup are carried with their original expiry`() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use {
                it.setObject(1, fixture.studyId)
                it.setString(2, "restore-continuity-${fixture.studyId}")
                assertEquals(1, it.executeUpdate())
            }
        }
        // Backup taken here. The participant enrolls a phone afterwards and is then erased.
        seedRestoredOlderState(includeStudy = false)
        val operationId = eraseParticipantWithTheWorker()
        val erasedKeyExpiry = tombstoneExpiry()
        assertNotNull(erasedKeyExpiry)
        captureCheckpointWithRestoreScript()
        restoreBackupTakenBefore(operationId, participantInBackup = false)
        assertNull("the backup holds no tombstone for the later key", tombstoneExpiry())

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertEquals("the tombstone keeps its original expiry", erasedKeyExpiry, tombstoneExpiry())
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `contract 2 checkpoint left unresolved by the previous release is upgraded and replayed`() {
        seedRestoredOlderState()
        val operationId = eraseParticipantWithTheWorker()
        captureLegacyCheckpoint()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT participant_id FROM chronicle_restore_continuity.deletion_operations WHERE operation_id = ?",
            ).use {
                it.setObject(1, operationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull("contract 2 holds neither the id nor a token", resultSet.getString(1))
                }
            }
        }
        restoreBackupTakenBefore(operationId, participantInBackup = true)
        // The backup was taken during the quarantine: its copy of the operation is the authoritative binding.
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until
                )
                SELECT operation_id, study_id, participant_ref, ?, mode, 'QUARANTINED',
                       requested_by, idempotency_key, registry_version, quarantine_until
                FROM chronicle_restore_continuity.deletion_operations WHERE operation_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, fixture.participantId)
                it.setObject(2, operationId)
                assertEquals(1, it.executeUpdate())
            }
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(0L, count(connection, "study_participants", "study_id", fixture.studyId))
            assertEquals(0L, count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `contract 2 upgrade refuses a live participant whose fingerprint matches the erased participant_ref`() {
        seedRestoredOlderState()
        val operationId = eraseParticipantWithTheWorker()
        val liveParticipantId = "live-${fixture.participantId}"
        storageResolver.getPlatformStorage().connection.use { connection ->
            // Stands in for a 48-bit collision (or a fingerprint key rotated since the erasure).
            connection.prepareStatement("UPDATE data_deletion_operations SET participant_ref = ? WHERE operation_id = ?")
                .use {
                    it.setString(1, LogSanitizer.stableFingerprint(liveParticipantId, "participant"))
                    it.setObject(2, operationId)
                    assertEquals(1, it.executeUpdate())
                }
        }
        captureLegacyCheckpoint()
        // The backup predates both the erasure and the erased participant; the live one is its sole match.
        restoreBackupTakenBefore(operationId, participantInBackup = false)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, liveParticipantId)
                it.setObject(3, UUID.randomUUID())
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO battery_telemetry (
                    study_id, participant_id, sample_id, sample_timestamp, timezone, level_percent, charging_state
                ) VALUES (?, ?, 'live-sample', now(), 'UTC', 80, 'NOT_CHARGING')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, liveParticipantId)
                assertEquals(1, it.executeUpdate())
            }
        }

        val failure = assertThrows(IllegalStateException::class.java) { newReconciler().reconcile() }

        assertTrue(failure.message.orEmpty().contains("cannot be upgraded safely"))
        assertTrue(failure.message.orEmpty().contains(operationId.toString()))
        assertTrue(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT contract_version FROM chronicle_restore_continuity.checkpoint").use {
                    assertTrue(it.next())
                    assertEquals("the upgrade rolled back", 2, it.getInt(1))
                }
            }
            assertEquals(1L, count(connection, "study_participants", "participant_id", liveParticipantId))
            assertEquals(1L, count(connection, "battery_telemetry", "participant_id", liveParticipantId))
            assertEquals(0L, count(connection, "data_deletion_operations", "operation_id", operationId))
        }
    }

    @Test
    fun `completed purge already in the backup does not conflict with a later held operation`() {
        seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            createContinuitySchema(connection)
            // The purge completed before the backup, so the backup and the checkpoint hold the same proof.
            listOf("", "chronicle_restore_continuity.").forEach { schema ->
                val (operations, tombstones) = if (schema.isEmpty()) {
                    "data_deletion_operations" to "data_deletion_tombstones"
                } else {
                    "${schema}deletion_operations" to "${schema}deletion_tombstones"
                }
                connection.prepareStatement(
                    """
                    INSERT INTO $operations (
                        operation_id, study_id, participant_ref, participant_id, mode, status,
                        requested_by, idempotency_key, registry_version, quarantine_until,
                        completed_at, proof_hash
                    ) VALUES (?, ?, 'participant-source-proof', ?, 'COLLECTED_DATA_PURGE', 'COMPLETED',
                              'fixture', ?, 1, '2026-08-20T12:00:00Z', '2026-08-20T12:01:00Z', 'source-proof')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fixture.operationId)
                    it.setObject(2, fixture.studyId)
                    it.setString(3, fixture.participantId)
                    it.setObject(4, fixture.requestId)
                    assertEquals(1, it.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO $tombstones (
                        operation_id, study_ref, participant_ref, mode, registry_version, completed_at, proof_hash
                    ) VALUES (?, 'study-source-proof', 'participant-source-proof', 'COLLECTED_DATA_PURGE', 1,
                              '2026-08-20T12:01:00Z', 'source-proof')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fixture.operationId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            // After the backup: another deletion of the same participant, placed on hold.
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until
                ) VALUES (?, ?, 'participant-source-proof', ?, 'COLLECTED_DATA_PURGE', 'HELD',
                          'legal-review', ?, 1, '2026-09-20T12:00:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.heldOperationId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.heldRequestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.retention_holds (
                    hold_id, operation_id, study_id, reason, created_by, created_at, review_at
                ) VALUES (?, ?, ?, 'legal preservation', 'fixture', '2026-08-20T12:01:00Z', '2026-09-20T12:01:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.heldOperationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.alreadyProtectedDeletionCount)
        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT status FROM data_deletion_operations WHERE operation_id = ?").use {
                it.setObject(1, fixture.heldOperationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("HELD", resultSet.getString(1))
                }
            }
            assertEquals(1L, count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
        }
    }

    @Test
    fun `absent completed purge restores proof and preserves a later hold`() {
        seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            listOf("api_keys", "battery_telemetry", "study_participants").forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE study_id = ?").use {
                    it.setObject(1, fixture.studyId); it.executeUpdate()
                }
            }
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            createContinuitySchema(connection)
            // The checkpoint carries a completed purge; the selected backup predates this subject.
            listOf("chronicle_restore_continuity.").forEach { schema ->
                val (operations, tombstones) = if (schema.isEmpty()) {
                    "data_deletion_operations" to "data_deletion_tombstones"
                } else {
                    "${schema}deletion_operations" to "${schema}deletion_tombstones"
                }
                connection.prepareStatement(
                    """
                    INSERT INTO $operations (
                        operation_id, study_id, participant_ref, participant_id, mode, status,
                        requested_by, idempotency_key, registry_version, quarantine_until,
                        completed_at, proof_hash
                    ) VALUES (?, ?, 'participant-source-proof', ?, 'COLLECTED_DATA_PURGE', 'COMPLETED',
                              'fixture', ?, 1, '2026-08-20T12:00:00Z', '2026-08-20T12:01:00Z', 'source-proof')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fixture.operationId)
                    it.setObject(2, fixture.studyId)
                    it.setString(3, fixture.participantId)
                    it.setObject(4, fixture.requestId)
                    assertEquals(1, it.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO $tombstones (
                        operation_id, study_ref, participant_ref, mode, registry_version, completed_at, proof_hash
                    ) VALUES (?, 'study-source-proof', 'participant-source-proof', 'COLLECTED_DATA_PURGE', 1,
                              '2026-08-20T12:01:00Z', 'source-proof')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, fixture.operationId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            // After the backup: another deletion of the same participant, placed on hold.
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until
                ) VALUES (?, ?, 'participant-source-proof', ?, 'COLLECTED_DATA_PURGE', 'HELD',
                          'legal-review', ?, 1, '2026-09-20T12:00:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.heldOperationId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.heldRequestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.retention_holds (
                    hold_id, operation_id, study_id, reason, created_by, created_at, review_at
                ) VALUES (?, ?, ?, 'legal preservation', 'fixture', '2026-08-20T12:01:00Z', '2026-09-20T12:01:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.heldOperationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(0L, result.alreadyProtectedDeletionCount)
        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT status FROM data_deletion_operations WHERE operation_id = ?").use {
                it.setObject(1, fixture.heldOperationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("HELD", resultSet.getString(1))
                }
            }
            assertEquals(1L, count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals(1L, count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
        }
    }

    @Test
    fun `a tombstone V115 synthesized at restore takes the checkpoint's kind and original expiry`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        // A pre-V114 backup: the completed erasure retained its revoked device key.
        seedAlreadyProtectedPublicState()
        val hash = "restore-hash-${fixture.apiKeyId}"
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    java.io.File(
                        "src/main/resources/db/migration/V115__tombstone_device_keys_of_completed_erasures.sql",
                    ).readText(),
                )
                it.execute("UPDATE erased_device_key_tombstones SET kind = 'STUDY_ERASURE' WHERE key_hash = '$hash'")
            }
        }
        val synthesized = tombstoneExpiry()
        assertNotNull("V115 tombstones the retained key with a restore-time expiry", synthesized)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.erased_device_key_tombstones (key_hash, kind, expires_at)
                VALUES (?, 'WITHDRAW_AND_ERASE', date_trunc('second', now()) + interval '30 days')
                """.trimIndent(),
            ).use {
                it.setString(1, hash)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                UPDATE chronicle_restore_continuity.checkpoint
                SET checkpoint_sha256 = ?, erased_device_key_tombstone_count = 1
                WHERE checkpoint_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, RestoreContinuityReconciler.computeCheckpointSha256(connection))
                it.setObject(2, fixture.checkpointId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val original = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT expires_at FROM chronicle_restore_continuity.erased_device_key_tombstones",
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getObject(1, java.time.OffsetDateTime::class.java)
                }
            }
        }
        assertTrue(original.isBefore(synthesized))

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.alreadyProtectedDeletionCount)
        assertEquals(original, tombstoneExpiry())
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, hash)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("WITHDRAW_AND_ERASE", resultSet.getString(1))
                }
            }
        }
    }

    @Test
    fun `expired checkpoint key markers override V115 and are swept before checkpoint consumption`() {
        seedRestoredOlderState()
        seedContinuityCheckpoint()
        // A pre-V114 backup: the completed erasure retained its revoked device key.
        seedAlreadyProtectedPublicState()
        // A source completion with a different exact proof needs canonical replay, even though
        // the old completed operation was sufficient for V115 to synthesize the key marker.
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE chronicle_restore_continuity.deletion_operations SET proof_hash = 'later-source-proof'")
                it.executeUpdate("UPDATE chronicle_restore_continuity.deletion_tombstones SET proof_hash = 'later-source-proof'")
            }
        }
        val hash = "restore-hash-${fixture.apiKeyId}"
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    java.io.File(
                        "src/main/resources/db/migration/V115__tombstone_device_keys_of_completed_erasures.sql",
                    ).readText(),
                )
                it.execute("UPDATE erased_device_key_tombstones SET kind = 'STUDY_ERASURE' WHERE key_hash = '$hash'")
            }
        }
        val synthesized = tombstoneExpiry()
        assertNotNull("V115 tombstones the retained key with a restore-time expiry", synthesized)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.erased_device_key_tombstones (key_hash, kind, expires_at)
                VALUES (?, 'WITHDRAW_AND_ERASE', date_trunc('second', now()) - interval '1 second')
                """.trimIndent(),
            ).use {
                it.setString(1, hash)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                UPDATE chronicle_restore_continuity.checkpoint
                SET checkpoint_sha256 = ?, erased_device_key_tombstone_count = 1
                WHERE checkpoint_id = ?
                """.trimIndent(),
            ).use {
                it.setString(1, RestoreContinuityReconciler.computeCheckpointSha256(connection))
                it.setObject(2, fixture.checkpointId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val original = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT expires_at FROM chronicle_restore_continuity.erased_device_key_tombstones",
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getObject(1, java.time.OffsetDateTime::class.java)
                }
            }
        }
        assertTrue(original.isBefore(synthesized))

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(0L, result.alreadyProtectedDeletionCount)
        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertNull("expired markers are removed after replay", tombstoneExpiry())
        assertFalse(schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, hash)
                it.executeQuery().use { resultSet ->
                    assertFalse("no renewed capability remains", resultSet.next())
                }
            }
        }
    }

    private fun blockToken(): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest("${fixture.studyId}:${fixture.participantId}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The real erasure path: NOT_ENROLLED, a WITHDRAW_AND_ERASE quarantine, then the deletion worker. */
    private fun eraseParticipantWithTheWorker(): UUID {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "UPDATE study_participants SET participation_status = 'NOT_ENROLLED' WHERE study_id = ? AND participant_id = ?",
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        val operationId = orchestrator.quarantineParticipant(
            fixture.studyId, fixture.participantId, DataDeletionMode.WITHDRAW_AND_ERASE, "fixture", UUID.randomUUID(),
        )
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?",
            ).use {
                it.setObject(1, operationId)
                assertEquals(1, it.executeUpdate())
            }
        }
        assertEquals(1, orchestrator.processDueOperations(limit = 1))
        assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
        return operationId
    }

    /** Runs selfhost/restore.sh's own checkpoint capture against the current database. */
    private fun captureCheckpointWithRestoreScript() {
        val script = java.io.File("../selfhost/restore.sh").readText()
        val start = "CREATE SCHEMA chronicle_restore_continuity;"
        captureCheckpoint(start + script.substringAfter("<<'SQL' >/dev/null\n$start").substringBefore("\nSQL\n"))
    }

    /** restore.sh of the previous release captured this, then its server could not start. */
    private fun captureLegacyCheckpoint() =
        captureCheckpoint(java.io.File("src/test/resources/restore-continuity-contract-v2-capture.sql").readText())

    private fun captureCheckpoint(capture: String) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                // The script revokes from both runtime roles that a self-host cluster always has.
                statement.execute(
                    """
                    DO ${'$'}${'$'} BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_app') THEN CREATE ROLE chronicle_app; END IF;
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle_admin') THEN CREATE ROLE chronicle_admin; END IF;
                    END ${'$'}${'$'}
                    """.trimIndent(),
                )
                statement.execute(capture)
            }
        }
    }

    /** What restoring a dump taken before [operationId] leaves: no erasure evidence, optionally the subject. */
    private fun restoreBackupTakenBefore(operationId: UUID, participantInBackup: Boolean) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            listOf(
                "data_deletion_audit_outbox", "retention_holds", "data_deletion_form_access_revocations",
                "export_job_revocations", "data_deletion_steps", "data_deletion_tombstones", "data_deletion_operations",
            ).forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE operation_id = ?").use {
                    it.setObject(1, operationId)
                    it.executeUpdate()
                }
            }
            connection.prepareStatement("DELETE FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${fixture.apiKeyId}")
                it.executeUpdate()
            }
        }
        if (participantInBackup) seedRestoredOlderState(includeStudy = false)
    }

    private fun tombstoneExpiry(): java.time.OffsetDateTime? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT expires_at FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${fixture.apiKeyId}")
                it.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getObject(1, java.time.OffsetDateTime::class.java) else null
                }
            }
        }

    private fun assertCompletedWithoutIdentifier(connection: Connection, operationId: UUID) {
        connection.prepareStatement(
            "SELECT status, participant_id, participant_block_token FROM data_deletion_operations WHERE operation_id = ?",
        ).use {
            it.setObject(1, operationId)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                assertEquals("COMPLETED", resultSet.getString(1))
                assertNull(resultSet.getString(2))
                assertEquals(blockToken(), resultSet.getString(3))
            }
        }
    }

    private fun newReconciler(): RestoreContinuityReconciler {
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
            Clock.fixed(Instant.parse("2026-08-21T20:00:00Z"), ZoneOffset.UTC),
        )
        return RestoreContinuityReconciler(storageResolver, orchestrator)
    }

    private fun seedRestoredOlderState(includeStudy: Boolean = true) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            if (includeStudy) connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use {
                it.setObject(1, fixture.studyId)
                it.setString(2, "restore-continuity-${fixture.studyId}")
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                it.setObject(3, UUID.randomUUID())
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO api_keys (
                    key_id, study_id, key_hash, key_prefix, name, scope, created_by,
                    expires_at, revoked, participant_id, device_id
                ) VALUES (?, ?, ?, 'restore-key', 'restore key', 'MOBILE', 'fixture',
                          now() + interval '1 day', false, ?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.apiKeyId)
                it.setObject(2, fixture.studyId)
                it.setString(3, "restore-hash-${fixture.apiKeyId}")
                it.setString(4, fixture.participantId)
                it.setObject(5, fixture.deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO battery_telemetry (
                    study_id, participant_id, sample_id, sample_timestamp, timezone,
                    level_percent, charging_state
                ) VALUES (?, ?, 'restored-sample', now(), 'UTC', 80, 'NOT_CHARGING')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun seedAlreadyProtectedPublicState() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("DELETE FROM battery_telemetry WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                "DELETE FROM study_participants WHERE study_id = ? AND participant_id = ?",
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("UPDATE api_keys SET revoked = true WHERE key_id = ?").use {
                it.setObject(1, fixture.apiKeyId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO mobile_withdrawal_requests (
                    request_id, api_key_id, study_id, participant_id, device_id,
                    already_withdrawn, created_at
                ) VALUES (?, ?, ?, ?, ?, false, '2026-08-20T12:00:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.requestId)
                it.setObject(2, fixture.apiKeyId)
                it.setObject(3, fixture.studyId)
                it.setString(4, fixture.participantId)
                it.setObject(5, fixture.deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until,
                    completed_at, proof_hash
                ) VALUES (?, ?, 'participant-source-proof', ?, 'WITHDRAW_AND_ERASE', 'COMPLETED',
                          'mobile-self-withdrawal', ?, 1, '2026-08-20T12:00:00Z',
                          '2026-08-20T12:01:00Z', 'source-proof')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.operationId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.requestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_tombstones (
                    operation_id, study_ref, participant_ref, mode, registry_version,
                    completed_at, proof_hash
                ) VALUES (?, 'study-source-proof', 'participant-source-proof',
                          'WITHDRAW_AND_ERASE', 1, '2026-08-20T12:01:00Z', 'source-proof')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.operationId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun seedContinuityCheckpoint() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            createContinuitySchema(connection)
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.withdrawal_requests
                    (request_id, api_key_id, study_id, participant_id, device_id,
                     already_withdrawn, created_at)
                VALUES (?, ?, ?, ?, ?, false, '2026-08-20T12:00:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.requestId)
                it.setObject(2, fixture.apiKeyId)
                it.setObject(3, fixture.studyId)
                it.setString(4, fixture.participantId)
                it.setObject(5, fixture.deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.revoked_api_keys
                    (key_id, study_id, participant_id, device_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.apiKeyId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.withdrawn_participants
                    (study_id, participant_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until,
                    completed_at, proof_hash, cancelled_by, cancelled_at
                ) VALUES (?, ?, 'participant-source-proof', ?, 'WITHDRAW_AND_ERASE', 'COMPLETED',
                          'mobile-self-withdrawal', ?, 1, '2026-08-20T12:00:00Z',
                          '2026-08-20T12:01:00Z', 'source-proof', NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.operationId)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                it.setObject(4, fixture.requestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_tombstones (
                    operation_id, study_ref, participant_ref, mode, registry_version,
                    completed_at, proof_hash
                ) VALUES (?, 'study-source-proof', 'participant-source-proof',
                          'WITHDRAW_AND_ERASE', 1, '2026-08-20T12:01:00Z', 'source-proof')
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.operationId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }
    }

    private fun insertRecoveryOperation(
        connection: Connection,
        mode: String,
        status: String,
        participantId: String?,
        cancelledBy: String? = null,
        cancelledAt: String? = null,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO chronicle_restore_continuity.deletion_operations (
                operation_id, study_id, participant_ref, participant_id, mode, status,
                requested_by, idempotency_key, registry_version, quarantine_until,
                completed_at, proof_hash, cancelled_by, cancelled_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'fixture', ?, 1, '2026-08-20T12:00:00Z',
                      NULL, NULL, ?, ?::timestamptz)
            """.trimIndent(),
        ).use {
            it.setObject(1, fixture.operationId)
            it.setObject(2, fixture.studyId)
            it.setString(3, participantId?.let { "participant-source-proof" })
            it.setString(4, participantId)
            it.setString(5, mode)
            it.setString(6, status)
            it.setObject(7, fixture.requestId)
            it.setString(8, cancelledBy)
            it.setString(9, cancelledAt)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun sealContinuityCheckpoint(connection: Connection) {
        val digest = RestoreContinuityReconciler.computeCheckpointSha256(connection)
        connection.prepareStatement(
            """
            INSERT INTO chronicle_restore_continuity.checkpoint (
                contract_version, checkpoint_id, created_at, source_schema_version,
                checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                withdrawn_participant_count, deletion_operation_count, source_tombstone_count,
                collection_revision_count, published_collection_settings_count,
                enrollment_invitation_count, erased_device_key_tombstone_count
            ) SELECT 3, ?, '2026-08-20T12:02:00Z', '100', ?,
                     (SELECT count(*) FROM chronicle_restore_continuity.withdrawal_requests),
                     (SELECT count(*) FROM chronicle_restore_continuity.revoked_api_keys),
                     (SELECT count(*) FROM chronicle_restore_continuity.withdrawn_participants),
                     (SELECT count(*) FROM chronicle_restore_continuity.deletion_operations),
                     (SELECT count(*) FROM chronicle_restore_continuity.deletion_tombstones),
                     (SELECT count(*) FROM chronicle_restore_continuity.data_collection_settings_revisions),
                     (SELECT count(*) FROM chronicle_restore_continuity.published_data_collection_settings),
                     (SELECT count(*) FROM chronicle_restore_continuity.enrollment_invitations),
                     (SELECT count(*) FROM chronicle_restore_continuity.erased_device_key_tombstones)
            """.trimIndent(),
        ).use {
            it.setObject(1, fixture.checkpointId)
            it.setString(2, digest)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun seedActiveStudyErasureAccessRevocation(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO participant_form_access_codes (
                access_code_id, token_hash, study_id, participant_id, form_kind,
                issuer_type, issued_by, expires_at, revoked_at
            ) VALUES (?, decode(md5(?), 'hex'), ?, ?, 'APP_USAGE',
                      'RESEARCHER', 'fixture', now() + interval '1 day', '2026-08-20T12:00:00Z')
            """.trimIndent(),
        ).use {
            it.setObject(1, fixture.accessCodeId)
            it.setString(2, fixture.accessCodeId.toString())
            it.setObject(3, fixture.studyId)
            it.setString(4, fixture.participantId)
            assertEquals(1, it.executeUpdate())
        }
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations (
                operation_id, study_id, participant_ref, participant_id, mode, status,
                requested_by, idempotency_key, registry_version, quarantine_until
            ) VALUES (?, ?, NULL, NULL, 'STUDY_ERASURE', 'QUARANTINED',
                      'fixture', ?, 1, '2026-08-21T20:00:00Z')
            """.trimIndent(),
        ).use {
            it.setObject(1, fixture.operationId)
            it.setObject(2, fixture.studyId)
            it.setObject(3, fixture.requestId)
            assertEquals(1, it.executeUpdate())
        }
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_form_access_revocations (
                operation_id, study_id, resource_kind, resource_id,
                original_revoked_at, revoked_at
            ) VALUES (?, ?, 'ACCESS_CODE', ?, NULL, '2026-08-20T12:00:00Z')
            """.trimIndent(),
        ).use {
            it.setObject(1, fixture.operationId)
            it.setObject(2, fixture.studyId)
            it.setObject(3, fixture.accessCodeId)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun createContinuitySchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA chronicle_restore_continuity")
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.checkpoint (
                    contract_version INTEGER NOT NULL,
                    checkpoint_id UUID PRIMARY KEY,
                    created_at TIMESTAMPTZ NOT NULL,
                    source_schema_version TEXT NOT NULL,
                    checkpoint_sha256 TEXT NOT NULL,
                    withdrawal_receipt_count BIGINT NOT NULL,
                    revoked_api_key_count BIGINT NOT NULL,
                    withdrawn_participant_count BIGINT NOT NULL,
                    deletion_operation_count BIGINT NOT NULL,
                    source_tombstone_count BIGINT NOT NULL,
                    collection_revision_count BIGINT NOT NULL,
                    published_collection_settings_count BIGINT NOT NULL,
                    enrollment_invitation_count BIGINT NOT NULL,
                    erased_device_key_tombstone_count BIGINT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.withdrawal_requests (
                    request_id UUID PRIMARY KEY, api_key_id UUID NOT NULL UNIQUE,
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    device_id UUID NOT NULL, already_withdrawn BOOLEAN NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.revoked_api_keys (
                    key_id UUID PRIMARY KEY, study_id UUID NOT NULL,
                    participant_id TEXT, device_id UUID
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.withdrawn_participants (
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    PRIMARY KEY (study_id, participant_id)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.deletion_operations (
                    operation_id UUID PRIMARY KEY, study_id UUID NOT NULL,
                    participant_ref TEXT, participant_id TEXT,
                    -- What V68 derives in public; these fixtures never seed a completed participant shape.
                    participant_block_token TEXT GENERATED ALWAYS AS
                        (md5(study_id::text || ':' || participant_id)) STORED,
                    mode TEXT NOT NULL,
                    status TEXT NOT NULL, requested_by TEXT NOT NULL,
                    idempotency_key UUID NOT NULL UNIQUE, registry_version INTEGER NOT NULL,
                    quarantine_until TIMESTAMPTZ, completed_at TIMESTAMPTZ,
                    proof_hash TEXT, cancelled_by TEXT, cancelled_at TIMESTAMPTZ
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.retention_holds (
                    hold_id UUID PRIMARY KEY, operation_id UUID NOT NULL,
                    study_id UUID NOT NULL, reason TEXT NOT NULL, created_by TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL, review_at TIMESTAMPTZ NOT NULL,
                    released_by TEXT, released_at TIMESTAMPTZ, release_reason TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.deletion_tombstones (
                    operation_id UUID PRIMARY KEY, study_ref TEXT NOT NULL,
                    participant_ref TEXT, mode TEXT NOT NULL, registry_version INTEGER NOT NULL,
                    completed_at TIMESTAMPTZ NOT NULL, proof_hash TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.data_collection_settings_revisions (
                    study_id UUID NOT NULL, settings_version INTEGER NOT NULL,
                    setting JSONB NOT NULL, issued_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.published_data_collection_settings (
                    study_id UUID NOT NULL, settings_version INTEGER NOT NULL,
                    setting JSONB NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.enrollment_invitations (
                    access_code_id UUID NOT NULL, token_hash BYTEA NOT NULL,
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    form_kind TEXT NOT NULL, resource_id UUID, logical_date DATE,
                    issuer_type TEXT NOT NULL, issued_by TEXT NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL, exchanged_at TIMESTAMPTZ,
                    revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
                    enrollment_attempt_id UUID, enrollment_source_device_hash TEXT,
                    enrollment_device_id UUID, enrollment_manifest_digest TEXT,
                    enrollment_request_hash TEXT, enrollment_proposed_key_hash TEXT,
                    enrollment_replay_expires_at TIMESTAMPTZ,
                    enrollment_settings_version INTEGER, enrollment_disclosure_version TEXT,
                    enrollment_enabled_modules JSONB, enrollment_required_modules JSONB
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.erased_device_key_tombstones (
                    key_hash TEXT PRIMARY KEY, kind TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private fun schemaExists(schema: String): Boolean =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT to_regnamespace(?) IS NOT NULL").use {
                it.setString(1, schema)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBoolean(1)
                }
            }
        }

    private fun participationStatus(connection: Connection): String =
        connection.prepareStatement(
            "SELECT participation_status FROM study_participants WHERE study_id = ? AND participant_id = ?",
        ).use {
            it.setObject(1, fixture.studyId)
            it.setString(2, fixture.participantId)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString(1)
            }
        }

    private fun apiKeyRevoked(connection: Connection): Boolean =
        connection.prepareStatement("SELECT revoked FROM api_keys WHERE key_id = ?").use {
            it.setObject(1, fixture.apiKeyId)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private fun deletionStatus(connection: Connection): String =
        connection.prepareStatement("SELECT status FROM data_deletion_operations WHERE operation_id = ?").use {
            it.setObject(1, fixture.operationId)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString(1)
            }
        }

    private fun count(connection: Connection, table: String, column: String, value: Any): Long {
        require(table.matches(Regex("^[a-z_]+$")))
        require(column.matches(Regex("^[a-z_]+$")))
        return connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use {
            it.setObject(1, value)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }
    }

    private fun hasTablePrivilege(connection: Connection, role: String, privilege: String): Boolean =
        connection.prepareStatement(
            "SELECT has_table_privilege(?, 'public.restore_continuity_reconciliations', ?)",
        ).use {
            it.setString(1, role)
            it.setString(2, privilege)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private class RestoreFixture {
        val checkpointId: UUID = UUID.randomUUID()
        val studyId: UUID = UUID.randomUUID()
        val participantId: String = "restore-participant-${UUID.randomUUID()}"
        val deviceId: UUID = UUID.randomUUID()
        val apiKeyId: UUID = UUID.randomUUID()
        val requestId: UUID = UUID.randomUUID()
        val operationId: UUID = UUID.randomUUID()
        val heldOperationId: UUID = UUID.randomUUID()
        val heldRequestId: UUID = UUID.randomUUID()
        val holdId: UUID = UUID.randomUUID()
        val accessCodeId: UUID = UUID.randomUUID()
        val enrollmentAttemptId: UUID = UUID.randomUUID()
    }
}
