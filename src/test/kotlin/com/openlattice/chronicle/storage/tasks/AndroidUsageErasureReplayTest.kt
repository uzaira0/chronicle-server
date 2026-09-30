package com.openlattice.chronicle.storage.tasks

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.InvocationTargetException
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

/** Real Android usage admission and both drains against the complete erasure schema. */
class AndroidUsageErasureReplayTest {
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
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        val newest = fresh.copy(timestamp = fresh.timestamp!!.plusDays(1))
        Mockito.reset(fixture.studies)
        assertEquals(1, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.deviceId, listOf(fixture.original)))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        Mockito.verifyNoInteractions(fixture.studies)
        assertEquals(3, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.deviceId, listOf(fixture.original, fresh, newest)))
        fixture.upload.moveToEventStorage(fixture.studyId, fixture.participantId)
        assertEquals(listOf(fresh.timestamp, newest.timestamp), eventDates(fixture.studyId))
    }

    @Test
    fun receiptDependentProductionPrimaryKeyCannotRestoreOldEventsWithANewUploadTime() {
        val keyColumns = (com.openlattice.chronicle.storage.PostgresDataTables.CHRONICLE_USAGE_EVENTS.columns -
            com.openlattice.chronicle.storage.PostgresEventColumns.ACTIVITY_CLASS).map { it.name }
        val originallyNullable = postgres.createConnection("").use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT attname FROM pg_attribute WHERE attrelid = 'chronicle_usage_events'::regclass AND attnum > 0 AND NOT attnotnull").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            } }
        }
        postgres.createConnection("").use { connection -> connection.createStatement().use {
            it.execute("ALTER TABLE chronicle_usage_events ADD CONSTRAINT receipt_schema_pk PRIMARY KEY (${keyColumns.joinToString(",")})")
        } }
        try {
            withFixture { fixture ->
                val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    .plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                assertEquals(1, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
                    fixture.deviceId, listOf(fixture.original), OffsetDateTime.now().plusSeconds(1)))
                assertEquals(0, count(fixture.studyId, "upload_buffer"))
                assertEquals(2, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
                    fixture.deviceId, listOf(fixture.original, fresh), OffsetDateTime.now().plusSeconds(2)))
                fixture.upload.moveToEventStorage(fixture.studyId, fixture.participantId)
                assertEquals(listOf(fresh.timestamp), eventDates(fixture.studyId))
            }
        } finally {
            postgres.createConnection("").use { connection -> connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE chronicle_usage_events DROP CONSTRAINT receipt_schema_pk")
                keyColumns.filter { it in originallyNullable }.forEach {
                    statement.execute("ALTER TABLE chronicle_usage_events ALTER COLUMN $it DROP NOT NULL")
                }
            } }
        }
    }

    @Test
    fun bufferedOnlyPurgeAcknowledgesOldOnlyAndMixedRetries() = withFixture(bufferedOnly = true) { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        val newest = fresh.copy(timestamp = fresh.timestamp!!.plusDays(1))
        assertEquals(1, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.deviceId, listOf(fixture.original)))
        assertEquals(0, count(fixture.studyId, "upload_buffer"))
        assertEquals(3, fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId,
            fixture.deviceId, listOf(fixture.original, fresh, newest)))
        backgroundDrain()
        assertEquals(listOf(fresh.timestamp, newest.timestamp), eventDates(fixture.studyId))
    }

    @Test
    fun inlineDrainDiscardsLegacyReplayAndUpdatesMetadataOnlyFromFreshRows() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        enqueueLegacy(fixture)
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.deviceId, listOf(fresh))
        Mockito.reset(fixture.studies)
        fixture.upload.moveToEventStorage(fixture.studyId, fixture.participantId)
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
    fun backgroundDrainDiscardsLegacyReplayAndCommitsFreshRowsAcrossStudies() = withFixture { fixture ->
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        enqueueLegacy(fixture)
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.deviceId, listOf(fresh))
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
        val fresh = fixture.original.copy(timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        fixture.upload.uploadAndroidUsageEvents(fixture.studyId, fixture.participantId, fixture.deviceId, listOf(fresh))
        val suffix = UUID.randomUUID().toString().replace("-", "")
        postgres.createConnection("").use { connection -> connection.createStatement().use { statement ->
            statement.execute("""CREATE FUNCTION fail_usage_$suffix() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN IF NEW.study_id = '${fixture.studyId}' THEN
                    RAISE EXCEPTION 'simulated destination infrastructure failure' USING ERRCODE = '08006';
                END IF; RETURN NEW; END ${'$'}${'$'}""")
            statement.execute("CREATE TRIGGER fail_usage_$suffix BEFORE INSERT ON chronicle_usage_events FOR EACH ROW EXECUTE FUNCTION fail_usage_$suffix()")
        } }
        try {
            assertEquals("08006", assertThrows(SQLException::class.java) {
                fixture.upload.moveToEventStorage(fixture.studyId, fixture.participantId)
            }.sqlState)
            assertEquals(1, count(fixture.studyId, "upload_buffer"))
            assertEquals("08006", assertThrows(SQLException::class.java) { backgroundDrain() }.sqlState)
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
            val upload = AppDataUploadService(storage, Mockito.mock(EnrollmentManager::class.java), studies)
            val recorded = OffsetDateTime.parse("2026-09-01T00:00:00Z")
            val original = ChronicleUsageEvent(studyId = studyId, participantId = "usage-erasure-replay",
                appPackageName = "com.example.replay", interactionType = "Move to Foreground",
                timestamp = recorded, timezone = "UTC", user = "", applicationLabel = "Replay")
            val device = UUID.randomUUID()
            assertEquals(1, upload.uploadAndroidUsageEvents(studyId, original.participantId, device, listOf(original)))
            val raw = postgres.createConnection("").use { connection ->
                connection.prepareStatement("SELECT data::text FROM upload_buffer WHERE study_id = ?").use {
                    it.setObject(1, studyId)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
                }
            }
            if (!bufferedOnly) {
                upload.moveToEventStorage(studyId, original.participantId)
                assertEquals(1, count(studyId, "chronicle_usage_events"))
            }
            val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
            val operation = orchestrator.quarantineParticipant(studyId, original.participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE, "usage-replay-test", UUID.randomUUID())
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?").use {
                    it.setObject(1, operation); assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            assertEquals("COMPLETED", orchestrator.getOperation(operation).status)
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
            connection.prepareStatement("INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, 'usage-erasure-replay', ?, 'ENROLLED')").use {
                it.setObject(1, study); it.setObject(2, UUID.randomUUID()); it.executeUpdate()
            }
        }
        return study
    }

    private fun enqueueLegacy(fixture: Fixture) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, device_id) VALUES (?, ?, ?::jsonb, now(), 'Android', ?)").use {
                it.setObject(1, fixture.studyId); it.setString(2, fixture.participantId)
                it.setString(3, fixture.raw); it.setObject(4, fixture.deviceId); it.executeUpdate()
            }
        }
    }

    private fun backgroundDrain() {
        val dependencies = MoveToEventStorageTaskDependencies(storage, Mockito.mock(StudyManager::class.java))
        val task = object : MoveToEventStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }
        RLSRequestContext.withSystemContext {
            try {
                MoveToEventStorageTask::class.java.getDeclaredMethod("moveToEventStorage").apply {
                    isAccessible = true
                }.invoke(task)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
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

    private data class Fixture(val studyId: UUID, val participantId: String, val deviceId: UUID,
        val original: ChronicleUsageEvent, val raw: String, val studies: StudyManager, val upload: AppDataUploadService)
}
