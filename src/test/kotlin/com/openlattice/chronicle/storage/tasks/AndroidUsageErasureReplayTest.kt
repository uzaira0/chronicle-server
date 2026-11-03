package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.contract.seedCandidate

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.Rule
import com.openlattice.chronicle.storage.rls.RLSFixtureContext
import org.mockito.Mockito
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

/** Real Android usage admission and both drains against the complete erasure schema. */
class AndroidUsageErasureReplayTest {
    @get:Rule
    val fixtureContext = RLSFixtureContext()

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("android_usage_erasure_replay")
        private lateinit var manager: DataSourceManager
        private lateinit var storage: StorageResolver

        @BeforeClass
        @JvmStatic
        fun setUp() {
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            val configuration = PostgresConfiguration(Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "1")
                    setProperty("connectionTimeout", "1000")
            }, usingCitus = false, flavor = PostgresFlavor.VANILLA,
                initializeIndices = false, initializeTables = false)
            manager = DataSourceManager(mapOf("default" to configuration, "chronicle" to configuration),
                HealthCheckRegistry(), MetricRegistry())
            storage = StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::manager.isInitialized) manager.dataSources.values.forEach { it.close() }
        }
    }

    @Test
    fun admissionAcknowledgesOldOnlyAndMixedRetriesWithoutRestoringPrePurgeEvents() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(
            java.time.temporal.ChronoUnit.MICROS), clientEventId = UUID.randomUUID())
        val newest = fresh.copy(timestamp = fresh.timestamp!!.plusDays(1), clientEventId = UUID.randomUUID())
        Mockito.reset(fixture.studies)
        assertEquals(emptySet<UUID>(), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original)))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        Mockito.verifyNoInteractions(fixture.studies)
        assertEquals(setOf(fresh.clientEventId, newest.clientEventId), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original, fresh, newest)))
        fixture.upload.drainEventStorage()
        assertEquals(listOf(fresh.timestamp, newest.timestamp), eventDates(fixture.studyId))
    }

    @Test
    fun receiptDependentProductionPrimaryKeyCannotRestoreOldEventsWithANewUploadTime() = withFixture { fixture ->
        // The test schema carries the production usage-event key (ChronicleContractTestSchema).
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS), clientEventId = UUID.randomUUID())
        assertEquals(emptySet<UUID>(), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original), OffsetDateTime.now().plusSeconds(1)))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        assertEquals(setOf(fresh.clientEventId), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original, fresh), OffsetDateTime.now().plusSeconds(2)))
        fixture.upload.drainEventStorage()
        assertEquals(listOf(fresh.timestamp), eventDates(fixture.studyId))
    }

    @Test
    fun bufferedOnlyPurgeAcknowledgesOldOnlyAndMixedRetries() = withFixture(bufferedOnly = true) { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(
            java.time.temporal.ChronoUnit.MICROS), clientEventId = UUID.randomUUID())
        val newest = fresh.copy(timestamp = fresh.timestamp!!.plusDays(1), clientEventId = UUID.randomUUID())
        assertEquals(emptySet<UUID>(), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original)))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        assertEquals(setOf(fresh.clientEventId, newest.clientEventId), fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.randomDeviceId, listOf(fixture.original, fresh, newest)))
        backgroundDrain()
        assertEquals(listOf(fresh.timestamp, newest.timestamp), eventDates(fixture.studyId))
    }

    @Test
    fun inlineDrainDiscardsReplayAndUpdatesMetadataOnlyFromFreshRows() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(
            java.time.temporal.ChronoUnit.MICROS))
        enqueueReplay(fixture)
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.randomDeviceId, listOf(fresh))
        Mockito.reset(fixture.studies)
        fixture.upload.drainEventStorage()
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        assertEquals(listOf(fresh.timestamp), eventDates(fixture.studyId))
        val updates = Mockito.mockingDetails(fixture.studies).invocations.filter {
            it.method.name == "insertOrUpdateParticipantStats"
        }
        assertEquals(1, updates.size)
        val stats = updates.single().arguments.single() as ParticipantStats
        assertEquals(setOf(fresh.timestamp!!.toLocalDate()), stats.androidUniqueDates)
        assertEquals(fresh.timestamp, stats.androidFirstDate)
        assertEquals(fresh.timestamp, stats.androidLastDate)
    }

    @Test
    fun backgroundDrainDiscardsReplayAndCommitsFreshRowsAcrossStudies() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(
            java.time.temporal.ChronoUnit.MICROS))
        enqueueReplay(fixture)
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.randomDeviceId, listOf(fresh))
        val healthyStudy = seedParticipant()
        val healthy = fresh.copy(studyId = healthyStudy)
        fixture.upload.uploadAndroidUsageEvents(healthyStudy, fixture.participantId, UUID.randomUUID(), listOf(healthy))
        backgroundDrain()
        assertEquals(listOf(fresh.timestamp), eventDates(fixture.studyId))
        assertEquals(listOf(healthy.timestamp), eventDates(healthyStudy))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        assertEquals(0, count(healthyStudy, "upload_buffer"))
    }

    @Test
    fun infrastructureFailuresRestoreClaimsInBothDrainPaths() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(
            java.time.temporal.ChronoUnit.MICROS))
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.randomDeviceId, listOf(fresh))
        val suffix = UUID.randomUUID().toString().replace("-", "")
        postgres.createConnection("").use { connection -> connection.createStatement().use { statement ->
            statement.execute("""CREATE FUNCTION fail_usage_$suffix() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN IF NEW.study_id = '${fixture.studyId}' THEN
                    RAISE EXCEPTION 'simulated destination infrastructure failure' USING ERRCODE = '08006';
                END IF; RETURN NEW; END ${'$'}${'$'}""")
            statement.execute(
                "CREATE TRIGGER fail_usage_$suffix BEFORE INSERT ON chronicle_usage_events FOR EACH ROW EXECUTE FUNCTION fail_usage_$suffix()")
        } }
        try {
            assertEquals("08006", assertThrows(SQLException::class.java) {
                fixture.upload.drainEventStorage()
            }.sqlState)
            assertEquals(1, count(fixture.studyId, "upload_buffer"))
            backgroundDrain(expectFailure = true)
            assertEquals(1, count(fixture.studyId, "upload_buffer"))
            assertEquals(0, count(fixture.studyId, "chronicle_usage_events"))
        } finally {
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                it.execute("DROP TRIGGER fail_usage_$suffix ON chronicle_usage_events")
                it.execute("DROP FUNCTION fail_usage_$suffix()")
            } }
        }
        backgroundDrain()
        assertEquals(listOf(fresh.timestamp), eventDates(fixture.studyId))
    }

    private fun withFixture(bufferedOnly: Boolean = false, block: (Fixture) -> Unit) {
        TestSecurityUtils.setupSecurityContext()
        try {
            val studyId = seedParticipant()
            val studies = Mockito.mock(StudyManager::class.java)
            val upload = AppDataUploadService(storage, studies)
            val recorded = OffsetDateTime.parse("2026-09-01T00:00:00Z")
            val original = ChronicleUsageEvent(studyId = studyId, participantId = "usage-erasure-replay",
                appPackageName = "com.example.replay", interactionType = "Move to Foreground",
                timestamp = recorded, timezone = "UTC", user = "", applicationLabel = "Replay")
            val device = UUID.randomUUID()
            upload.uploadAndroidUsageEvents(studyId, original.participantId, device, listOf(original))
            val raw = postgres.createConnection("").use { connection ->
                connection.prepareStatement("SELECT data::text FROM upload_buffer WHERE study_id = ?").use {
                    it.setObject(1, studyId)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
                }
            }
            if (!bufferedOnly) {
                upload.drainEventStorage()
                assertEquals(1, count(studyId, "chronicle_usage_events"))
            }
            val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
            val operation = orchestrator.quarantineParticipant(studyId, original.participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE, "usage-replay-test", UUID.randomUUID())
            assertEquals("COMPLETED", orchestrator.eraseNow(operation, studyId, "test-owner").status)
            block(Fixture(studyId, original.participantId, device, original, raw, studies, upload))
        } finally {
            // This class owns its container; failed drains must not contaminate another test's queue.
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { it.executeUpdate("DELETE FROM upload_buffer") }
            }
            TestSecurityUtils.clearSecurityContext()
        }
    }

    private fun seedParticipant(): UUID {
        val study = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'Android usage replay')").use {
                it.setObject(1, study); it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, " +
                "'usage-erasure-replay', ?, 'ENROLLED')").use {
                it.setObject(1, study); it.setObject(2, seedCandidate(connection)); it.executeUpdate()
            }
        }
        return study
    }

    private fun enqueueReplay(fixture: Fixture) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, random_device_id) VALUES (?, ?, ?::jsonb, " +
                "now(), 'Android', ?)").use {
                it.setObject(1, fixture.studyId); it.setString(2, fixture.participantId)
                it.setString(3, fixture.raw); it.setObject(4, fixture.randomDeviceId); it.executeUpdate()
            }
        }
    }

    private fun backgroundDrain(expectFailure: Boolean = false) {
        val dependencies = MoveToEventStorageTaskDependencies(storage, Mockito.mock(StudyManager::class.java))
        val task = object : MoveToEventStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal
            .labels(ChronicleMetrics.BackgroundTask.USAGE_MOVER.label)
        val before = failures.get()
        ScopedLogCapture(MoveToEventStorageTask::class.java).use { logs ->
            task.runTask()
            assertEquals(before + (if (expectFailure) 1.0 else 0.0), failures.get(), 0.0)
            if (expectFailure) {
                assertTrue("The public task boundary must retain the actual destination failure", logs.messages.any {
                    it.startsWith("Exception when moving events to event storage.") &&
                        it.contains("simulated destination infrastructure failure")
                })
            }
        }
    }

    private fun count(study: UUID, table: String): Int = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id::text = ?").use {
            it.setString(1, study.toString())
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }

    private fun eventDates(study: UUID): List<OffsetDateTime> = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT event_timestamp FROM chronicle_usage_events WHERE study_id = ?::text ORDER BY event_timestamp").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, OffsetDateTime::class.java)) } }
        }
    }

    private data class Fixture(val studyId: UUID, val participantId: String, val randomDeviceId: UUID,
        val original: ChronicleUsageEvent, val raw: String, val studies: StudyManager, val upload: AppDataUploadService)
}
