package com.openlattice.chronicle.services.delete

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.postgres
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.storageResolver
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
        @BeforeClass
        @JvmStatic
        fun setUpClass() = RestoreContinuityFixture.start()

        @AfterClass
        @JvmStatic
        fun tearDownClass() = RestoreContinuityFixture.stop()
    }

    private val fixture = RestoreContinuityFixture()

    @After
    fun cleanFixture() = fixture.cleanFixture()

    @Test
    fun `a signed purge confirmation has one operation and one watermark across retries`() {
        fixture.seedRestoredOlderState()
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val audit = Mockito.mock(AuditingManager::class.java)
            val orchestrator = DataDeletionOrchestrator(storageResolver, audit)
            val service = ParticipantPurgeService(storageResolver, orchestrator,
                Mockito.mock(com.openlattice.chronicle.services.apikeys.ApiKeyService::class.java), audit)
            val preview = service.previewPurge(fixture.studyId, fixture.participantId)
            val request = com.openlattice.chronicle.study.ParticipantPurgeRequest(fixture.participantId, preview.confirmationToken)
            val operation = service.executePurge(fixture.studyId, request).single()
            assertEquals(operation, service.executePurge(fixture.studyId, request).single())
            // An equivalent expiry spelling cannot turn the same signed confirmation into a second request.
            assertEquals(operation, service.executePurge(fixture.studyId, request.copy(
                confirmationToken = preview.confirmationToken.replace("|", "|0"))).single())
            processWithWorker(orchestrator, operation)
            assertEquals(operation, service.executePurge(fixture.studyId, request).single())
            storageResolver.getPlatformStorage().connection.use { connection ->
                assertEquals(1L, fixture.count(connection, "data_deletion_operations", "study_id", fixture.studyId))
                assertEquals(1L, fixture.count(connection, "participant_purge_cutoffs", "study_id", fixture.studyId))
                connection.prepareStatement("""SELECT purge.cutoff = operation.started_at
                    FROM participant_purge_cutoffs purge JOIN data_deletion_operations operation USING (study_id)
                    WHERE operation.operation_id = ?""").use {
                    it.setObject(1, operation)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertTrue(rows.getBoolean(1)) }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.executePurge(fixture.studyId, request.copy(confirmationToken = "invalid|1"))
            }
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun `a busy export leaves its study due and does not consume the unrelated deletion budget`() {
        for (sessionLock in listOf(false, true)) {
            val other = RestoreContinuityFixture()
            fixture.seedRestoredOlderState()
            other.seedRestoredOlderState()
            try {
                val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
                val blocked = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                    DataDeletionMode.COLLECTED_DATA_PURGE, "fixture", UUID.randomUUID())
                val ready = orchestrator.quarantineParticipant(other.studyId, other.participantId,
                    DataDeletionMode.WITHDRAW_AND_ERASE, "fixture", UUID.randomUUID())
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.prepareStatement("""UPDATE data_deletion_operations
                        SET quarantine_until = now() - (? * interval '1 minute') WHERE operation_id = ?""").use {
                        for ((operation, minutes) in listOf(blocked to 2, ready to 1)) {
                            it.setInt(1, minutes); it.setObject(2, operation); it.executeUpdate()
                        }
                    }
                }
                postgres.createConnection("").use { export ->
                    if (sessionLock) export.prepareStatement(
                        "SELECT pg_advisory_lock_shared(${com.openlattice.chronicle.storage.DeletionStudyFence.EXPORT_LOCK_KEY_SQL})").use {
                        it.setObject(1, fixture.studyId); it.execute()
                    } else {
                        export.autoCommit = false
                        com.openlattice.chronicle.storage.DeletionStudyFence.exportShared(export, fixture.studyId)
                    }
                    assertEquals(1, orchestrator.processDueOperations(limit = 1))
                    assertEquals("QUARANTINED", orchestrator.getOperation(blocked).status)
                    assertEquals("COMPLETED", orchestrator.getOperation(ready).status)
                    storageResolver.getPlatformStorage().connection.use { connection ->
                        assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
                        assertEquals(0L, other.count(connection, "battery_telemetry", "study_id", other.studyId))
                    }
                }
                assertEquals(1, orchestrator.processDueOperations(limit = 1))
                assertEquals("COMPLETED", orchestrator.getOperation(blocked).status)
            } finally {
                other.cleanFixture()
                fixture.cleanFixture()
            }
        }
    }

    @Test
    fun `purge replay preserves its original watermark and accepts captures before restore`() {
        fixture.seedRestoredOlderState()
        val operationId = deleteParticipantWithTheWorker(DataDeletionMode.COLLECTED_DATA_PURGE)
        val cutoff = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT started_at FROM data_deletion_operations WHERE operation_id = ?").use {
                it.setObject(1, operationId)
                it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, java.time.OffsetDateTime::class.java) }
            }
        }
        val upload = com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService(storageResolver)
        val sample = com.openlattice.chronicle.collection.BatterySample(
            "after-original-purge", cutoff.plusNanos(1000), "UTC", 80,
            com.openlattice.chronicle.collection.BatteryChargingState.DISCHARGING,
            com.openlattice.chronicle.collection.BatteryPlugType.UNPLUGGED, 250, 4000,
            com.openlattice.chronicle.collection.BatteryHealth.GOOD,
        )
        upload.upload(fixture.studyId, fixture.participantId, listOf(sample))
        captureCheckpointWithRestoreScript()
        restoreBackupTakenBefore(operationId, participantInBackup = false)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("DELETE FROM participant_purge_cutoffs WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId); it.executeUpdate()
            }
            val mapper = com.geekbeast.mappers.mappers.ObjectMappers.newJsonMapper()
            val times = listOf(cutoff.minusSeconds(1), cutoff, cutoff.plusSeconds(1), null)
            for (type in com.openlattice.chronicle.services.upload.UploadType.values()) {
                val payload = times.map { capturedAt ->
                    when (type) {
                        com.openlattice.chronicle.services.upload.UploadType.Android -> mapOf(
                            com.openlattice.chronicle.storage.PostgresEventColumns.TIMESTAMP.name to
                                mapOf(com.openlattice.chronicle.util.JsonFields.VALUE_FIELD to capturedAt))
                        com.openlattice.chronicle.services.upload.UploadType.AndroidSensor ->
                            mapOf(com.openlattice.chronicle.util.JsonFields.TIMESTAMP to capturedAt)
                        com.openlattice.chronicle.services.upload.UploadType.Ios -> mapOf(
                            com.openlattice.chronicle.storage.ParticipantPurgeCutoff.IOS_CAPTURE_TIME_FIELD to capturedAt)
                    }
                }
                connection.prepareStatement("""
                    INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, device_id)
                    VALUES (?, ?, ?::jsonb, now(), ?, ?)
                """.trimIndent()).use {
                    it.setObject(1, fixture.studyId); it.setString(2, fixture.participantId)
                    it.setString(3, mapper.writeValueAsString(payload)); it.setString(4, type.name)
                    it.setObject(5, fixture.deviceId); assertEquals(1, it.executeUpdate())
                }
            }
        }
        newReconciler().reconcile()
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("Capture after the original cutoff must survive replay", 1L,
                fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            connection.prepareStatement("SELECT data, upload_type FROM upload_buffer WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows ->
                    var batches = 0
                    while (rows.next()) {
                        batches++
                        val retained = com.geekbeast.mappers.mappers.ObjectMappers.newJsonMapper().readTree(rows.getString(1))
                        val type = com.openlattice.chronicle.services.upload.UploadType.valueOf(rows.getString(2))
                        assertEquals("Mixed batches must retain exactly the two post-purge captures", 2, retained.size())
                        assertEquals(setOf(cutoff.toInstant(), cutoff.plusSeconds(1).toInstant()), retained.map {
                            com.openlattice.chronicle.storage.ParticipantPurgeCutoff.bufferedCollectionTime(it, type)!!.toInstant()
                        }.toSet())
                    }
                    assertEquals(com.openlattice.chronicle.services.upload.UploadType.values().size, batches)
                }
            }
        }
        upload.upload(fixture.studyId, fixture.participantId, listOf(
            sample.copy(id = "queued-after-purge"), sample.copy(id = "before-purge", timestamp = cutoff.minusSeconds(1))))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(2L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            connection.prepareStatement("SELECT cutoff FROM participant_purge_cutoffs WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(cutoff.toInstant(), rows.getObject(1,
                    java.time.OffsetDateTime::class.java).toInstant()) }
            }
        }
    }

    @Test
    fun `restore replays immutable module decisions in their original order`() {
        fixture.seedRestoredOlderState()
        val service = com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService(storageResolver)
        val module = com.openlattice.chronicle.collection.CollectionModuleId.BATTERY_TELEMETRY
        val acceptedId = UUID.randomUUID()
        val declinedId = UUID.randomUUID()
        storageResolver.getPlatformStorage().connection.use { connection ->
            val settings = com.openlattice.chronicle.study.StudySettings(mapOf(
                com.openlattice.chronicle.study.StudySettingType.DataCollection to
                    com.openlattice.chronicle.collection.AndroidDataCollectionSetting(modules = mapOf(
                        module to com.openlattice.chronicle.collection.CollectionModuleSetting(enabled = true, required = true)), settingsVersion =
                            1)))
            connection.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use {
                it.setString(1, com.geekbeast.mappers.mappers.ObjectMappers.getJsonMapper().writeValueAsString(settings))
                it.setObject(2, fixture.studyId); it.executeUpdate()
            }
            for ((id, declined) in listOf(acceptedId to false, declinedId to true)) {
                connection.prepareStatement("""
                    INSERT INTO participant_collection_acknowledgment (id, study_id, participant_id, source_device_id,
                        acknowledged_modules, declined_modules, acknowledged_at, recorded_at, settings_version,
                        evidence_access_code_id, evidence_api_key_id)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, now(), now() + ? * interval '1 second', 1, ?, ?)
                """.trimIndent()).use {
                    it.setObject(1, id); it.setObject(2, fixture.studyId); it.setString(3, fixture.participantId)
                    it.setString(4, fixture.deviceId.toString())
                    val modules = "[\"${module.id}\"]"
                    it.setString(5, if (declined) "[]" else modules); it.setString(6, if (declined) modules else "[]")
                    it.setInt(7, if (declined) 1 else 0); it.setObject(8, UUID.randomUUID()); it.setObject(9, fixture.apiKeyId)
                    it.executeUpdate()
                }
            }
        }
        assertTrue(service.isCollectionHalted(fixture.studyId, fixture.participantId, fixture.deviceId))
        captureCheckpointWithRestoreScript()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("DELETE FROM participant_collection_acknowledgment WHERE id = ?").use {
                it.setObject(1, declinedId); assertEquals(1, it.executeUpdate())
            }
        }
        assertFalse(service.isCollectionHalted(fixture.studyId, fixture.participantId, fixture.deviceId))
        newReconciler().reconcile()
        assertTrue(service.isCollectionHalted(fixture.studyId, fixture.participantId, fixture.deviceId))
        assertThrows(com.openlattice.chronicle.services.studies.CollectionHaltedException::class.java) {
            service.withCollectionHaltRecheck(fixture.studyId, fixture.participantId, fixture.deviceId, setOf(module)) { error(
                "declined module reached write") }
        }
    }

    @Test
    fun `older backup preserves manual pause completion and study archive`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { it.execute("""
                UPDATE study_participants SET participation_status = 'PAUSED';
                INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
                VALUES ('${fixture.studyId}', 'completed-fixture', '${UUID.randomUUID()}', 'COLLECTION_COMPLETED');
                UPDATE studies SET lifecycle_status = 'ARCHIVED' WHERE study_id = '${fixture.studyId}';
            """.trimIndent()) }
        }
        captureCheckpointWithRestoreScript()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { it.execute("""
                UPDATE study_participants SET participation_status = 'ENROLLED';
                UPDATE studies SET lifecycle_status = 'ACTIVE' WHERE study_id = '${fixture.studyId}';
            """.trimIndent()) }
        }
        val result = newReconciler().reconcile()
        assertNotNull(result)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT participant_id, participation_status FROM study_participants WHERE study_id = '${fixture.studyId}'").use { rows ->
                    val statuses = mutableMapOf<String, String>()
                    while (rows.next()) statuses[rows.getString(1)] = rows.getString(2)
                    assertEquals(mapOf(fixture.participantId to "PAUSED", "completed-fixture" to "COLLECTION_COMPLETED"), statuses)
                }
                statement.executeQuery("SELECT lifecycle_status FROM studies WHERE study_id = '${fixture.studyId}'").use {
                    assertTrue(it.next()); assertEquals("ARCHIVED", it.getString(1))
                }
            }
            connection.prepareStatement(
                "SELECT restricted_participant_count, archived_study_count FROM restore_continuity_reconciliations WHERE checkpoint_id = ?",
            ).use { statement ->
                statement.setObject(1, result!!.checkpointId)
                statement.executeQuery().use {
                    assertTrue(it.next()); assertEquals(2L, it.getLong(1)); assertEquals(1L, it.getLong(2))
                    assertFalse(it.next())
                }
            }
        }
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
    }

    @Test
    fun `restore replay routes cold studies using a single platform pool slot`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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
            assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
            Mockito.verify(studies, Mockito.never()).executeOnKey(org.mockito.kotlin.any(),
                org.mockito.kotlin.any<com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead>())
        } finally {
            manager.dataSources.values.forEach { it.close() }
        }
    }

    @Test
    fun `completed withdrawal is contained re-erased and receipted before checkpoint removal`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
        val reconciler = newReconciler()

        val result = reconciler.reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(fixture.checkpointId, result.checkpointId)
        assertEquals(1L, result.withdrawalReceiptCount)
        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertEquals(0L, result.alreadyProtectedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        assertNull("a completed reconciliation must be an idempotent no-op", reconciler.reconcile())

        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(
                0L,
                fixture.count(connection, "study_participants", "study_id", fixture.studyId),
            )
            // Re-erasure deletes the restored device key and keeps only its hash tombstone.
            assertEquals(0L, fixture.count(connection, "api_keys", "key_id", fixture.apiKeyId))
            connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${fixture.apiKeyId}")
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("WITHDRAW_AND_ERASE", resultSet.getString(1))
                }
            }
            assertEquals(1L, fixture.count(connection, "mobile_withdrawal_requests", "request_id", fixture.requestId))
            assertEquals(0L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals("COMPLETED", deletionStatus(connection))
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
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
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
        seedAlreadyProtectedPublicState()

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.alreadyProtectedDeletionCount)
        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
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
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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

        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(0L, fixture.count(connection, "restore_continuity_reconciliations", "checkpoint_id", fixture.checkpointId))
        }
    }

    @Test
    fun `immutable withdrawal timestamp conflict fails closed without consuming checkpoint`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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

        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(
                0L,
                fixture.count(connection, "restore_continuity_reconciliations", "checkpoint_id", fixture.checkpointId),
            )
        }
    }

    @Test
    fun `checkpoint digest tampering fails before restored state changes`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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

        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `deletion identity conflict fails before withdrawal containment mutates restored state`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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

        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `active retention hold remains a held deletion after restore reconciliation`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            fixture.createContinuitySchema(connection)
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
            fixture.sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("HELD", deletionStatus(connection))
            assertEquals(1L, fixture.count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `API retention hold captured by the restore script reconciles without erasure`() {
        fixture.seedRestoredOlderState()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
            Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC),
        )
        val operationId = orchestrator.quarantineParticipant(
            fixture.studyId, fixture.participantId, DataDeletionMode.COLLECTED_DATA_PURGE, "fixture", UUID.randomUUID(),
        )
        val holdId = orchestrator.placeHold(
            operationId, fixture.studyId, "legal preservation", "fixture",
            java.time.OffsetDateTime.parse("2026-09-20T12:00:00Z"),
        )
        assertEquals("QUARANTINED", orchestrator.getOperation(operationId).status)
        captureCheckpointWithRestoreScript()
        // The older backup retains the participant and data but predates the operation and hold.
        restoreBackupTakenBefore(operationId, participantInBackup = false)

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        assertEquals("HELD", orchestrator.getOperation(operationId).status)
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, fixture.count(connection, "retention_holds", "hold_id", holdId))
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `active hold overlapping a completed replay fails closed before containment`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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

        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("ENROLLED", fixture.participationStatus(connection))
            assertFalse(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
        }
    }

    @Test
    fun `cancelled study erasure remains cancelled and restores form access`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            seedActiveStudyErasureAccessRevocation(connection)
            fixture.createContinuitySchema(connection)
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
            fixture.sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
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
                fixture.count(connection, "data_deletion_form_access_revocations", "operation_id", fixture.operationId),
            )
            assertEquals(1L, fixture.count(connection, "retention_holds", "hold_id", fixture.holdId))
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
    fun `cancelled study erasure keeps its unreleased hold through reconciliation`() {
        // cancelStudyErasure cancels a HELD erasure without releasing its hold.
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            seedActiveStudyErasureAccessRevocation(connection)
            fixture.createContinuitySchema(connection)
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
                ) VALUES (?, ?, ?, 'legal preservation', 'fixture',
                          '2026-08-20T12:01:00Z', '2026-09-20T12:01:00Z', NULL, NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, fixture.holdId)
                it.setObject(2, fixture.operationId)
                it.setObject(3, fixture.studyId)
                assertEquals(1, it.executeUpdate())
            }
            fixture.sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile()

        assertNotNull(result)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("CANCELLED", deletionStatus(connection))
            connection.prepareStatement("SELECT released_at FROM retention_holds WHERE hold_id = ?").use {
                it.setObject(1, fixture.holdId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull(resultSet.getObject(1))
                }
            }
        }
    }

    @Test
    fun `restore receipt rejects owner mutation and truncate`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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
        fixture.seedRestoredOlderState()
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

            fixture.createContinuitySchema(connection)
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
            fixture.sealContinuityCheckpoint(connection)
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
                fixture.count(connection, "data_collection_settings_revisions", "settings_version", 2),
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
    fun `rollback verification accepts a backup that already holds the captured deletion tombstone`() {
        fixture.seedRestoredOlderState()
        val operationId = deleteParticipantWithTheWorker()
        captureCheckpointWithRestoreScript()

        // The database already holds every captured fact, so restore.sh may consume the checkpoint.
        runRollbackVerificationFromRestoreScript()

        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", operationId))
        }
    }

    @Test
    fun `worker completed withdrawal captured by the restore script replays onto an older backup`() {
        fixture.seedRestoredOlderState()
        val operationId = deleteParticipantWithTheWorker()
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
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(0L, fixture.count(connection, "study_participants", "study_id", fixture.studyId))
            assertEquals(0L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(0L, fixture.count(connection, "api_keys", "key_id", fixture.apiKeyId))
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `backup with an active withdrawal fence and participant reconciles containment`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                com.openlattice.chronicle.services.studies.StudyService.SET_PARTICIPATION_STATUS_SQL,
            ).use {
                it.setString(1, com.openlattice.chronicle.data.ParticipationStatus.NOT_ENROLLED.name)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
            Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC),
        )
        val operationId = orchestrator.quarantineParticipant(
            fixture.studyId, fixture.participantId, DataDeletionMode.WITHDRAW_AND_ERASE, "fixture", UUID.randomUUID(),
        )
        captureCheckpointWithRestoreScript()
        // This backup contains both the active operation and its participant; keep the public fence.
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, fixture.count(connection, "data_deletion_operations", "operation_id", operationId))
            assertEquals(1L, fixture.count(connection, "study_participants", "study_id", fixture.studyId))
            connection.prepareStatement("SELECT chronicle_participant_mutation_allowed(?::uuid, ?::text)").use {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertFalse(resultSet.getBoolean(1))
                }
            }
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        assertEquals("QUARANTINED", orchestrator.getOperation(operationId).status)
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals("NOT_ENROLLED", fixture.participationStatus(connection))
            assertTrue(apiKeyRevoked(connection))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
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
        fixture.seedRestoredOlderState(includeStudy = false)
        val operationId = deleteParticipantWithTheWorker()
        val erasedKeyExpiry = tombstoneExpiry()
        assertNotNull(erasedKeyExpiry)
        captureCheckpointWithRestoreScript()
        restoreBackupTakenBefore(operationId, participantInBackup = false)
        assertNull("the backup holds no tombstone for the later key", tombstoneExpiry())

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertEquals("the tombstone keeps its original expiry", erasedKeyExpiry, tombstoneExpiry())
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `contract 2 checkpoint left unresolved by the previous release is upgraded and replayed`() {
        fixture.seedRestoredOlderState()
        val operationId = deleteParticipantWithTheWorker()
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
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            assertEquals(0L, fixture.count(connection, "study_participants", "study_id", fixture.studyId))
            assertEquals(0L, fixture.count(connection, "battery_telemetry", "study_id", fixture.studyId))
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", operationId))
            assertCompletedWithoutIdentifier(connection, operationId)
        }
    }

    @Test
    fun `contract 2 upgrade refuses a live participant whose fingerprint matches the erased participant_ref`() {
        fixture.seedRestoredOlderState()
        val operationId = deleteParticipantWithTheWorker()
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
        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT contract_version FROM chronicle_restore_continuity.checkpoint").use {
                    assertTrue(it.next())
                    assertEquals("the upgrade rolled back", 2, it.getInt(1))
                }
            }
            assertEquals(1L, fixture.count(connection, "study_participants", "participant_id", liveParticipantId))
            assertEquals(1L, fixture.count(connection, "battery_telemetry", "participant_id", liveParticipantId))
            assertEquals(0L, fixture.count(connection, "data_deletion_operations", "operation_id", operationId))
        }
    }

    @Test
    fun `completed purge already in the backup does not conflict with a later held operation`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            fixture.createContinuitySchema(connection)
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
            fixture.sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(1L, result.alreadyProtectedDeletionCount)
        assertEquals(0L, result.replayedCompletedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT status FROM data_deletion_operations WHERE operation_id = ?").use {
                it.setObject(1, fixture.heldOperationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("HELD", resultSet.getString(1))
                }
            }
            assertEquals(1L, fixture.count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
        }
    }

    @Test
    fun `absent completed purge restores proof and preserves a later hold`() {
        fixture.seedRestoredOlderState()
        storageResolver.getPlatformStorage().connection.use { connection ->
            listOf("api_keys", "battery_telemetry", "study_participants").forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE study_id = ?").use {
                    it.setObject(1, fixture.studyId); it.executeUpdate()
                }
            }
        }
        storageResolver.getPlatformStorage().connection.use { connection ->
            fixture.createContinuitySchema(connection)
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
            fixture.sealContinuityCheckpoint(connection)
        }

        val result = newReconciler().reconcile() ?: throw AssertionError("expected a reconciliation result")

        assertEquals(0L, result.alreadyProtectedDeletionCount)
        assertEquals(1L, result.replayedCompletedDeletionCount)
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT status FROM data_deletion_operations WHERE operation_id = ?").use {
                it.setObject(1, fixture.heldOperationId)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("HELD", resultSet.getString(1))
                }
            }
            assertEquals(1L, fixture.count(connection, "retention_holds", "hold_id", fixture.holdId))
            assertEquals(1L, fixture.count(connection, "data_deletion_tombstones", "operation_id", fixture.operationId))
        }
    }

    @Test
    fun `a tombstone V115 synthesized at restore takes the checkpoint's kind and original expiry`() {
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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
        fixture.seedRestoredOlderState()
        fixture.seedContinuityCheckpoint()
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
        assertFalse(fixture.schemaExists("chronicle_restore_continuity"))
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, hash)
                it.executeQuery().use { resultSet ->
                    assertFalse("no renewed capability remains", resultSet.next())
                }
            }
        }
    }

    @Test
    fun `participant token function preserves stored identifiers and null semantics`() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT chronicle_participant_block_token(?::uuid, ?::text), " +
                    "chronicle_participant_block_token(NULL::uuid, ?::text), " +
                    "chronicle_participant_block_token(?::uuid, NULL::text)",
            ).use { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setString(2, fixture.participantId)
                statement.setString(3, fixture.participantId)
                statement.setObject(4, fixture.studyId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(blockToken(), rows.getString(1))
                    assertNull(rows.getString(2))
                    assertNull(rows.getString(3))
                }
            }
        }
    }

    @Test
    fun `shared erasure predicate distinguishes every mode and lifecycle status`() {
        fixture.seedRestoredOlderState()
        val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(
            storageResolver, Mockito.mock(com.openlattice.chronicle.ids.HazelcastIdGenerationService::class.java),
            Mockito.mock(com.openlattice.chronicle.services.candidates.CandidateManager::class.java),
        )
        val statuses = listOf("PREVIEW" to false, "CANCELLED" to false, "QUARANTINED" to true,
            "HELD" to true, "READY" to true, "ERASING" to true, "VERIFYING" to true,
            "COMPLETED" to true, "FAILED" to true)
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.setDeletionWorkerContext(connection, true)
                for (mode in DataDeletionMode.entries) for ((status, active) in statuses) {
                    val savepoint = connection.setSavepoint()
                    connection.prepareStatement(
                        """
                        INSERT INTO data_deletion_operations (
                            operation_id, study_id, participant_id, mode, status, requested_by,
                            idempotency_key, registry_version, cancelled_by, cancelled_at,
                            worker_lease_token, worker_lease_expires_at
                        ) VALUES (?, ?, ?, ?, ?, 'fixture', ?, 1, ?, ?, ?,
                                  CASE WHEN ? THEN now() + interval '1 hour' END)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, fixture.studyId)
                        statement.setString(3, if (mode == DataDeletionMode.STUDY_ERASURE) null else fixture.participantId)
                        statement.setString(4, mode.name)
                        statement.setString(5, status)
                        statement.setObject(6, UUID.randomUUID())
                        val cancelled = status == "CANCELLED"
                        statement.setString(7, if (cancelled) "fixture" else null)
                        statement.setObject(8, if (cancelled) java.time.OffsetDateTime.now() else null)
                        val leased = status in setOf("ERASING", "VERIFYING")
                        statement.setObject(9, if (leased) UUID.randomUUID() else null)
                        statement.setBoolean(10, leased)
                        assertEquals(1, statement.executeUpdate())
                    }
                    connection.prepareStatement(
                        "SELECT chronicle_has_participant_erasure(?::uuid, ?::text), " +
                            "chronicle_has_participant_erasure(?::uuid, ?::text), " +
                            "chronicle_has_participant_erasure(?::uuid, ?::text)",
                    ).use { statement ->
                        statement.setObject(1, fixture.studyId)
                        statement.setString(2, fixture.participantId)
                        statement.setObject(3, fixture.studyId)
                        statement.setString(4, "unrelated-participant")
                        statement.setObject(5, UUID.randomUUID())
                        statement.setString(6, fixture.participantId)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("$mode/$status", active && mode != DataDeletionMode.COLLECTED_DATA_PURGE, rows.getBoolean(1))
                            assertEquals("$mode/$status unrelated participant", active && mode == DataDeletionMode.STUDY_ERASURE, rows.getBoolean(2))
                            assertFalse("$mode/$status unrelated study", rows.getBoolean(3))
                        }
                    }
                    val erased = active && mode != DataDeletionMode.COLLECTED_DATA_PURGE
                    com.openlattice.chronicle.storage.PinnedPlatformConnection.pinning(
                        storageResolver.getPlatformStorage(), connection,
                    ) {
                        assertEquals("$mode/$status device status",
                            if (erased) com.openlattice.chronicle.data.ParticipationStatus.ERASED
                            else com.openlattice.chronicle.data.ParticipationStatus.ENROLLED,
                            enrollment.getDeviceParticipationStatus(fixture.studyId, fixture.participantId))
                        val requireReusable = {
                            com.openlattice.chronicle.storage.ParticipantErasureFence.requireReusable(
                                connection, fixture.studyId, fixture.participantId,
                            )
                        }
                        if (erased) assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
                            requireReusable()
                        } else requireReusable()
                    }
                    connection.rollback(savepoint)
                    connection.releaseSavepoint(savepoint)
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun blockToken(): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest("${fixture.studyId}:${fixture.participantId}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Executes a real quarantine and deletion worker; withdrawal also ends enrollment. */
    private fun deleteParticipantWithTheWorker(mode: DataDeletionMode = DataDeletionMode.WITHDRAW_AND_ERASE): UUID {
        if (mode == DataDeletionMode.WITHDRAW_AND_ERASE) storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                com.openlattice.chronicle.services.studies.StudyService.SET_PARTICIPATION_STATUS_SQL,
            ).use {
                it.setString(1, com.openlattice.chronicle.data.ParticipationStatus.NOT_ENROLLED.name)
                it.setObject(2, fixture.studyId)
                it.setString(3, fixture.participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        val operationId = orchestrator.quarantineParticipant(
            fixture.studyId, fixture.participantId, mode, "fixture", UUID.randomUUID(),
        )
        processWithWorker(orchestrator, operationId)
        return operationId
    }

    private fun processWithWorker(orchestrator: DataDeletionOrchestrator, operationId: UUID) {
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
    }

    /** Runs selfhost/restore.sh's own checkpoint capture against the current database. */
    private fun captureCheckpointWithRestoreScript() =
        captureCheckpoint(restoreScriptSql("capturing withdrawal and erasure continuity checkpoint"))

    private fun restoreScriptSql(marker: String): String {
        val script = java.io.File("../selfhost/restore.sh").readText()
        return script.substringAfter(marker)
            .substringAfter("<<SQL").substringAfter('\n')
            .substringBefore("\nSQL\n")
            .replace("\\${'$'}", "${'$'}")
            .replace("${'$'}CHECKPOINT_DIGEST_SQL", RestoreContinuityReconciler.restoreResource("restore-continuity-digest.sql"))
            .replace("${'$'}AUTHORITIES_SCHEMA_SQL", RestoreContinuityReconciler.restoreResource("restore-authorities-schema.sql"))
            .replace("${'$'}RESTRICTIONS_SCHEMA_SQL", RestoreContinuityReconciler.restoreResource("restore-restrictions-schema.sql"))
            .replace("${'$'}RESTORE_CONTINUITY_CONTRACT_VERSION", RestoreContinuityReconciler.CONTRACT_VERSION.toString())
    }

    /** Runs selfhost/restore.sh's rollback-backup verification, which consumes a fully protected checkpoint. */
    private fun runRollbackVerificationFromRestoreScript() {
        val verification = restoreScriptSql("verifying rollback backup already contains protected continuity evidence")
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                // The block opens its own transaction; never return an aborted one to the pool.
                try {
                    statement.execute(verification)
                } catch (error: java.sql.SQLException) {
                    statement.execute("ROLLBACK")
                    throw error
                }
            }
        }
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
        if (participantInBackup) fixture.seedRestoredOlderState(includeStudy = false)
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
}
