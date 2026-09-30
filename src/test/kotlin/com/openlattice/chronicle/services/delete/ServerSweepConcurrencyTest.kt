package com.openlattice.chronicle.services.delete

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.TransactionOnlyParticipantStatsCache
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/** Queued writers reproduce the row/fence inversion against the complete production schema. */
class ServerSweepConcurrencyTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        @BeforeClass @JvmStatic fun start() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_sweep_concurrency")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        }
        @AfterClass @JvmStatic fun stop() { if (::postgres.isInitialized) postgres.stop() }
    }

    private fun pool(name: String): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 5
        addDataSourceProperty("ApplicationName", name)
        connectionInitSql = "SET statement_timeout = '3s'; SET deadlock_timeout = '10s'"
    })

    private fun resolver(hds: HikariDataSource): StorageResolver = object : StorageResolver(
        Mockito.mock(DataSourceManager::class.java), Mockito.mock(ChronicleStorageConfiguration::class.java),
    ) {
        override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource = hds
    }

    private fun seed(studyId: UUID, participantId: String) {
        val setting = AndroidDataCollectionSetting(modules = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true, required = false),
        ), settingsVersion = 1)
        val settings = ObjectMappers.newJsonMapper().writeValueAsString(StudySettings(
            mapOf(StudySettingType.DataCollection to setting),
        ))
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title, settings) VALUES (?, 'sweep', ?::jsonb)").use {
                it.setObject(1, studyId); it.setString(2, settings); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, 'ENROLLED')").use {
                it.setObject(1, studyId); it.setString(2, participantId); it.setObject(3, UUID.randomUUID()); it.executeUpdate()
            }
        }
    }

    private fun studyService(storage: StorageResolver, audit: AuditingManager = Mockito.mock(AuditingManager::class.java)): StudyService {
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        @Suppress("UNCHECKED_CAST")
        val studies = Mockito.mock(IMap::class.java) as IMap<UUID, Study>
        Mockito.`when`(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        return StudyService(storage, Mockito.mock(AuthorizationManager::class.java),
            Mockito.mock(CandidateManager::class.java), Mockito.mock(EnrollmentManager::class.java),
            Mockito.mock(SurveysManager::class.java), Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(StudyLimitsManager::class.java), audit, hazelcast,
            TransactionOnlyParticipantStatsCache, Mockito.mock(WebhookService::class.java))
    }

    private fun awaitBlocked(name: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { statement ->
                statement.setString(1, name)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { if (it.next() && it.getBoolean(1)) return }
                    Thread.sleep(10)
                }
            }
        }
        fail("Expected a queued database writer: $name")
    }

    private fun queuedRace(studyId: UUID, rowSelect: String, rowUpdate: String, write: (StorageResolver) -> Unit) {
        val writerName = "sweep-writer-${UUID.randomUUID()}"
        val deleterName = "sweep-deleter-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        pool(writerName).use { writerPool ->
            pool(deleterName).use { deletionPool ->
                postgres.createConnection("").use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute(rowSelect) }
                    val writer = executor.submit {
                        TestSecurityUtils.setupSecurityContext()
                        try { write(resolver(writerPool)) } finally { TestSecurityUtils.clearSecurityContext() }
                    }
                    try {
                        awaitBlocked(writerName)
                        val deletion = executor.submit {
                            deletionPool.connection.use { connection ->
                                connection.autoCommit = false
                                try {
                                    connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))").use {
                                        it.setObject(1, studyId); it.execute()
                                    }
                                    connection.createStatement().use { it.execute(rowUpdate) }
                                    connection.commit()
                                } finally { connection.rollback() }
                            }
                        }
                        awaitBlocked(deleterName)
                        blocker.commit()
                        writer.get(5, TimeUnit.SECONDS)
                        deletion.get(5, TimeUnit.SECONDS)
                    } finally { blocker.rollback() }
                }
            }
        }
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun collectionGuardTakesDeletionFenceBeforeStudyRow() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        queuedRace(studyId, "SELECT * FROM studies WHERE study_id = '$studyId' FOR UPDATE",
            "UPDATE studies SET title = title WHERE study_id = '$studyId'") { storage ->
            val deviceId = UUID.randomUUID()
            val inserted = ParticipantCollectionAcknowledgmentService(storage).withCollectionHaltRecheck(studyId, participantId, deviceId) {
                com.openlattice.chronicle.storage.PinnedPlatformConnection.resolve(storage.getPlatformStorage()).connection.use { connection ->
                    connection.createStatement().use { it.executeUpdate("""INSERT INTO android_sensor_data
                        (study_id, participant_id, sample_id, device_id, sensor_type, sample_timestamp, timezone, values)
                        VALUES ('$studyId', '$participantId', '${UUID.randomUUID()}', '$deviceId', 'ACCELEROMETER', now(), 'UTC', '[1,2,3]'::jsonb)""") }
                }
            }
            assertEquals(1, inserted)
        }
    }

    @Test fun participantStatusTakesDeletionFenceBeforeParticipantRow() = participantRace(false)
    @Test fun participantAnnotationsTakeDeletionFenceBeforeParticipantRow() = participantRace(true)

    private fun participantRace(annotations: Boolean) {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        queuedRace(studyId, "SELECT * FROM study_participants WHERE study_id = '$studyId' FOR UPDATE",
            "UPDATE study_participants SET participant_notes = participant_notes WHERE study_id = '$studyId'") { storage ->
            if (annotations) studyService(storage).updateParticipantAnnotations(studyId, participantId, mapOf("participantNotes" to "note"))
            else studyService(storage).updateParticipationStatus(studyId, participantId, ParticipationStatus.PAUSED)
        }
    }

    @Test fun accessCodeExchangeTakesDeletionFenceBeforeCodeRow() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        val accessCodeId = UUID.randomUUID(); val rawCode = "a".repeat(48)
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO participant_form_access_codes (access_code_id, token_hash, study_id, participant_id, form_kind, issuer_type, issued_by, expires_at) VALUES (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'sweep', now() + interval '1 day')").use {
                it.setObject(1, accessCodeId); it.setBytes(2, MessageDigest.getInstance("SHA-256").digest(rawCode.toByteArray()))
                it.setObject(3, studyId); it.setString(4, participantId); it.executeUpdate()
            }
        }
        queuedRace(studyId, "SELECT * FROM participant_form_access_codes WHERE access_code_id = '$accessCodeId' FOR UPDATE",
            "UPDATE participant_form_access_codes SET revoked_at = revoked_at WHERE access_code_id = '$accessCodeId'") { storage ->
            assertNotNull(ParticipantFormAccessService(storage).exchangeAccessCode(rawCode))
        }
    }

    @Test fun legacyParticipantAuditWritersUseTheSharedSanitizer() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        val audit = Mockito.mock(AuditingManager::class.java)
        val events = mutableListOf<AuditableEvent>()
        Mockito.doAnswer { events += it.getArgument<List<AuditableEvent>>(0); 1 }
            .`when`(audit).recordEvents(org.mockito.kotlin.any())
        pool("sweep-audit").use { hds ->
            TestSecurityUtils.setupSecurityContext()
            try {
                val service = studyService(resolver(hds), audit)
                service.updateParticipationStatus(studyId, participantId, ParticipationStatus.PAUSED)
                service.updateParticipantAnnotations(studyId, participantId, emptyMap())
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
        assertEquals(2, events.size)
        events.forEach {
            assertFalse(it.description.contains(participantId))
            assertTrue(it.description.contains(com.openlattice.chronicle.util.LogSanitizer.stableFingerprint(participantId, "participant")))
        }
    }
    @Test fun filteredAppsReadAndReloadRespectDurableStudyErasure() {
        for (status in listOf("QUARANTINED", "COMPLETED")) {
            val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
            seed(studyId, participantId)
            pool("sweep-filtered-apps").use { hds ->
                hds.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute("INSERT INTO filtered_apps (study_id, app_package_name) VALUES ('$studyId', 'example.package')")
                        it.execute("""INSERT INTO data_deletion_operations
                            (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version, quarantine_until)
                            VALUES ('${UUID.randomUUID()}', '$studyId', 'STUDY_ERASURE', '$status', 'sweep', '${UUID.randomUUID()}', 1, now())""")
                    }
                }
                val hazelcast = Mockito.mock(HazelcastInstance::class.java)
                @Suppress("UNCHECKED_CAST")
                val apps = Mockito.mock(IMap::class.java) as IMap<UUID, KotlinDelegatedStringSet>
                Mockito.`when`(hazelcast.getMap<UUID, KotlinDelegatedStringSet>(HazelcastMap.FILTERED_APPS.name)).thenReturn(apps)
                Mockito.`when`(apps[studyId]).thenReturn(KotlinDelegatedStringSet(setOf("example.package")))
                val service = SurveysService(hazelcast, resolver(hds), Mockito.mock(EnrollmentManager::class.java),
                    Mockito.mock(ScheduledTasksManager::class.java), Mockito.mock(AuditingManager::class.java),
                    Mockito.mock(HazelcastIdGenerationService::class.java))
                TestSecurityUtils.setupSecurityContext()
                try {
                    assertThrows(NoSuchElementException::class.java) { service.getAppsFilteredForStudyAppUsageSurvey(studyId) }
                    Mockito.verify(apps).evict(studyId)
                    assertNull(com.openlattice.chronicle.mapstores.apps.FilteredAppsMapstore(hds).load(studyId))
                } finally { TestSecurityUtils.clearSecurityContext() }
            }
        }
    }

}
