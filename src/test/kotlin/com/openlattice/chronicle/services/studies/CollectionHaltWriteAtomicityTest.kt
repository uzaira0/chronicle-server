package com.openlattice.chronicle.services.studies

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The upload gate committed and released its per-device lock before the controller wrote, so a
 * consent DECLINE or a required-settings revision landing in between still let the batch persist.
 * These are the real-PostgreSQL contracts for the atomic recheck that closed that window.
 */
class CollectionHaltWriteAtomicityTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var hds: HikariDataSource
        private lateinit var storageResolver: StorageResolver
        private lateinit var service: ParticipantCollectionAcknowledgmentService

        private const val PARTICIPANT_ID = "participant-1"
        private val mapper = ObjectMappers.getJsonMapper()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_halt_atomicity")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE guard_probe (id UUID PRIMARY KEY, study_id UUID NOT NULL)")
                }
            }
            // maximumPoolSize = 1 proves the guarded write joins the recheck's connection instead
            // of borrowing a second one: without the pin, the nested borrow would block and fail.
            hds = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 1
                    connectionTimeout = TimeUnit.SECONDS.toMillis(5)
                },
            )
            storageResolver = object : StorageResolver(
                Mockito.mock(DataSourceManager::class.java),
                Mockito.mock(ChronicleStorageConfiguration::class.java),
            ) {
                override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource =
                    PinnedPlatformConnection.resolve(hds)
            }
            service = ParticipantCollectionAcknowledgmentService(storageResolver)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::hds.isInitialized) hds.close()
            if (Companion::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun pausedAndCompletedEnrollmentAcceptsOnlyDatedCapturesBeforeItsCutoff() {
        for (status in listOf("PAUSED", "COLLECTION_COMPLETED")) {
            val studyId = newStudy(required = false, settingsVersion = 1)
            val device = UUID.randomUUID()
            val cutoff = postgres.createConnection("").use { connection ->
                connection.prepareStatement(
                    "UPDATE study_participants SET participation_status = ? WHERE study_id = ? RETURNING capture_cutoff").use {
                    it.setString(1, status); it.setObject(2, studyId)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant() }
                }
            }
            val queued = UUID.randomUUID()
            service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, device, latestCapturedAt = cutoff.minusSeconds(1)) { insertProbe(queued,
                studyId) }
            assertTrue(probeExists(queued))
            for (capturedAt in listOf(null, cutoff, cutoff.plusSeconds(1))) {
                val fresh = UUID.randomUUID()
                assertThrows(CollectionHaltedException::class.java) {
                    service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, device, latestCapturedAt = capturedAt) { insertProbe(fresh, studyId) }
                }
                assertFalse(probeExists(fresh))
            }
        }
    }

    @Test
    fun historicalVersionCannotAuthorizePostRevisionCaptures() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val modules = setOf(CollectionModuleId.BATTERY_TELEMETRY)
        val supersededAt = postgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, scope, created_by,
                    expires_at, participant_id, device_id)
                VALUES (?, ?, ?, 'test', 'WRITE', 'atomicity', now() + interval '1 day', ?, ?)
            """.trimIndent()).use { statement ->
                statement.setObject(1, keyId)
                statement.setObject(2, studyId)
                statement.setString(3, keyId.toString())
                statement.setString(4, PARTICIPANT_ID)
                statement.setObject(5, deviceId)
                statement.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO participant_collection_acknowledgment (id, study_id, participant_id,
                    source_device_id, acknowledged_modules, acknowledged_at, settings_version,
                    evidence_access_code_id, evidence_api_key_id)
                VALUES (?, ?, ?, ?, ?::jsonb, now(), 1, ?, ?)
            """.trimIndent()).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.setString(3, PARTICIPANT_ID)
                statement.setString(4, deviceId.toString())
                statement.setString(5, mapper.writeValueAsString(modules.map { it.id }))
                statement.setObject(6, UUID.randomUUID())
                statement.setObject(7, keyId)
                statement.executeUpdate()
            }
            updateCollectionSettings(connection, studyId, required = true, settingsVersion = 2)
            connection.prepareStatement("SELECT issued_at FROM data_collection_settings_revisions WHERE study_id = ? AND settings_version = 2")
                .use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getObject(1, OffsetDateTime::class.java).toInstant()
                    }
                }
        }
        val oldProbe = UUID.randomUUID()
        service.withCollectionUploadRecheck(studyId, PARTICIPANT_ID, deviceId, modules, 1, false,
            supersededAt.minusSeconds(1)) { insertProbe(oldProbe, studyId) }
        assertTrue("The pre-revision offline queue still flushes", probeExists(oldProbe))
        for (capturedAt in listOf(null, supersededAt, supersededAt.plusSeconds(1))) {
            val freshProbe = UUID.randomUUID()
            assertThrows(CollectionHaltedException::class.java) {
                service.withCollectionUploadRecheck(studyId, PARTICIPANT_ID, deviceId, modules, 1, false,
                    capturedAt) { insertProbe(freshProbe, studyId) }
            }
            assertFalse("The old version must not authorize fresh or undated data", probeExists(freshProbe))
        }
        postgres.createConnection("").use { connection ->
            updateCollectionSettings(connection, studyId, required = false, settingsVersion = 3, enabled = false)
        }
        val legacyProbe = UUID.randomUUID()
        service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId, modules,
            supersededAt.minusSeconds(1)) { insertProbe(legacyProbe, studyId) }
        assertTrue("Legacy queues also preserve pre-revision captures", probeExists(legacyProbe))
        assertThrows(CollectionHaltedException::class.java) {
            service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId, modules,
                supersededAt.plusSeconds(1)) { insertProbe(UUID.randomUUID(), studyId) }
        }
    }

    @Test
    fun `an optional module without acceptance cannot commit a write`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()
        assertFalse(service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId))
        assertThrows(CollectionHaltedException::class.java) {
            service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId, setOf(CollectionModuleId.BATTERY_TELEMETRY)) {
                insertProbe(probeId, studyId)
            }
        }
        assertFalse(probeExists(probeId))
    }

    @Test
    fun `halt recorded after the gate and before the write rejects the write`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()

        assertFalse(
            "gate must pass while no enabled module is required",
            service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId),
        )

        // The concurrent consent/settings transaction lands in the window the gate left open.
        recordHalt(studyId, settingsVersion = 2)

        val probeId = UUID.randomUUID()
        assertThrows(CollectionHaltedException::class.java) {
            service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId) {
                insertProbe(probeId, studyId)
            }
        }
        assertFalse("the write must be rolled back with the recheck", probeExists(probeId))
    }

    /**
     * Negative control for the test above: the same sequence with the atomic recheck removed is
     * exactly the reported defect — the write commits after the halt was recorded.
     */
    @Test
    fun `without the atomic recheck the same sequence persists the write`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()

        assertFalse(service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId))
        recordHalt(studyId, settingsVersion = 2)
        assertTrue(
            "the halt must be visible to the predicate",
            service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId),
        )

        val probeId = UUID.randomUUID()
        insertProbe(probeId, studyId)
        assertTrue(probeExists(probeId))
    }

    @Test
    fun `nested transaction control leaves the guarded write for the owner to commit`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()

        service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId) {
            insertProbe(probeId, studyId)
            storageResolver.getPlatformStorage().connection.use { borrowed ->
                borrowed.commit()
                borrowed.rollback()
                borrowed.autoCommit = true
            }
            assertFalse("Only the guard owner may publish the write", probeExists(probeId))
        }
        assertTrue("The guard owner commits after write returns", probeExists(probeId))
    }

    @Test
    fun `halt recorded after the write commits does not affect it`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()

        service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId) {
            insertProbe(probeId, studyId)
        }
        assertTrue(probeExists(probeId))

        recordHalt(studyId, settingsVersion = 2)

        assertTrue(service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId))
        assertTrue("a later halt must not retract a committed write", probeExists(probeId))
    }

    /**
     * The write is inside the serialization window: while the guard's transaction is open, a
     * settings write that would record a halt blocks on the `FOR SHARE` row lock the predicate
     * took, so it can only land strictly before or strictly after the guarded write.
     */
    @Test
    fun `a settings halt cannot commit while a guarded write is in flight`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()
        val insideGuard = CountDownLatch(1)
        val haltAttempted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val halt = executor.submit {
                insideGuard.await(30, TimeUnit.SECONDS)
                haltAttempted.countDown()
                // Uses its own physical connection so it cannot ride the guard's transaction.
                postgres.createConnection("").use { connection ->
                    updateCollectionSettings(connection, studyId, required = true, settingsVersion = 2)
                }
            }

            service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId) {
                insideGuard.countDown()
                assertTrue(haltAttempted.await(30, TimeUnit.SECONDS))
                Thread.sleep(TimeUnit.SECONDS.toMillis(1))
                assertFalse("the halt must not commit while the guarded write is open", halt.isDone)
                insertProbe(probeId, studyId)
            }

            halt.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(probeExists(probeId))
        assertTrue(service.isCollectionHalted(studyId, PARTICIPANT_ID, deviceId))
    }

    /**
     * The diagnostics service used to force autocommit back on before returning, so the guard's
     * own commit() threw "Cannot commit when autoCommit is enabled" and every non-empty batch
     * answered 500. The batch must commit through the pinned connection like any other upload.
     */
    @Test
    fun `upload diagnostics commit inside the recheck`() {
        val studyId = newStudy(required = false, settingsVersion = 1)
        val deviceId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val event = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(),
            day = LocalDate.now(),
            moduleFamily = "USAGE_LIFECYCLE",
            issueCode = "TIMEOUT",
            count = 1,
            firstOccurredAt = now,
            lastOccurredAt = now,
        )

        val accepted = service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, deviceId) {
            val uploaded = UploadDiagnosticsUploadService(storageResolver).upload(studyId, PARTICIPANT_ID, deviceId, listOf(event))
            assertFalse("The nested upload commit must wait for the guard owner", diagnosticExists(studyId, event.id))
            uploaded
        }

        assertEquals(listOf(event.id), accepted)
        assertTrue("the batch must be committed by the guard", diagnosticExists(studyId, event.id))
    }

    private fun newStudy(required: Boolean, settingsVersion: Int): UUID {
        val studyId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title, settings) VALUES (?, 'atomicity', ?::jsonb)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, settingsJson(required, settingsVersion))
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, 'ENROLLED')").use {
                it.setObject(1, studyId); it.setString(2, PARTICIPANT_ID); it.setObject(3, UUID.randomUUID()); it.executeUpdate()
            }
        }
        return studyId
    }

    /** A required-settings revision is one of the two ways a collection halt is recorded. */
    private fun recordHalt(studyId: UUID, settingsVersion: Int) {
        postgres.createConnection("").use { connection ->
            updateCollectionSettings(connection, studyId, required = true, settingsVersion = settingsVersion)
        }
    }

    private fun updateCollectionSettings(
        connection: java.sql.Connection,
        studyId: UUID,
        required: Boolean,
        settingsVersion: Int,
        enabled: Boolean = true,
    ) {
        connection.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use { statement ->
            statement.setString(1, settingsJson(required, settingsVersion, enabled))
            statement.setObject(2, studyId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun settingsJson(required: Boolean, settingsVersion: Int, enabled: Boolean = true): String = mapper.writeValueAsString(
        StudySettings(
            mapOf(
                StudySettingType.DataCollection to AndroidDataCollectionSetting(
                    modules = linkedMapOf(
                        CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                            enabled = enabled,
                            required = required,
                        ),
                    ),
                    settingsVersion = settingsVersion,
                ),
            ),
        ),
    )

    /** Writes through the same seam every upload service uses, so the pin is what is under test. */
    private fun insertProbe(probeId: UUID, studyId: UUID) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("INSERT INTO guard_probe (id, study_id) VALUES (?, ?)").use { statement ->
                statement.setObject(1, probeId)
                statement.setObject(2, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun probeExists(probeId: UUID): Boolean = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT 1 FROM guard_probe WHERE id = ?").use { statement ->
            statement.setObject(1, probeId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    private fun diagnosticExists(studyId: UUID, eventId: String): Boolean = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT 1 FROM upload_diagnostics WHERE study_id = ? AND event_id = ?").use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, eventId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }
    @Test
    fun archiveRejectsAnUploadAndAnExistingFormSubmission() {
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val studyId = newStudy(required = false, settingsVersion = 1)
            val forms = com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService(storageResolver)
            val code = forms.createAccessCode(studyId, PARTICIPANT_ID,
                com.openlattice.chronicle.participantaccess.ParticipantFormKind.TIME_USE_DIARY, null, null, null,
                com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType.RESEARCHER, "archive-test")
            val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
            val scope = checkNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            val ids = Mockito.mock(com.openlattice.chronicle.ids.HazelcastIdGenerationService::class.java)
            Mockito.`when`(ids.getNextId()).thenReturn(UUID.randomUUID())
            StudyLifecycleService(storageResolver, Mockito.mock(StudyService::class.java),
                Mockito.mock(com.openlattice.chronicle.authorization.AuthorizationManager::class.java), ids,
                Mockito.mock(com.openlattice.chronicle.auditing.AuditingManager::class.java),
                Mockito.mock(com.openlattice.chronicle.services.delete.DataDeletionOrchestrator::class.java),
                Mockito.mock(com.openlattice.chronicle.services.webhooks.WebhookService::class.java),
                Mockito.mock(StudyLimitsManager::class.java))
                .archiveStudy(studyId, "archive-test")
            val probe = UUID.randomUUID()
            org.junit.Assert.assertThrows(CollectionHaltedException::class.java) {
                service.withCollectionHaltRecheck(studyId, PARTICIPANT_ID, UUID.randomUUID()) { insertProbe(probe, studyId) }
            }
            val receipts = com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService(storageResolver, mapper)
            val error = org.junit.Assert.assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
                receipts.executeWithSubmissionId(scope, scope.formKind, "archived", UUID.randomUUID(), emptyList<String>()) {
                    insertProbe(probe, studyId)
                    probe
                }
            }
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, error.statusCode)
            assertFalse(probeExists(probe))
            org.junit.Assert.assertNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

}
