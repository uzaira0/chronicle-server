package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.contract.seedCandidate

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatteryHealth
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSFixtureContext
import org.apache.logging.log4j.Level
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Properties
import java.util.UUID

/**
 * The server committed a batch, the response was lost, and the phone serialized the same queued rows again.
 * Each phone-assigned row identity must be stored exactly once.
 */
class AndroidUploadRetryTest {
    @get:Rule
    val fixtureContext = RLSFixtureContext()

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("android_upload_retry")
        private lateinit var manager: DataSourceManager
        private lateinit var storage: StorageResolver
        private val mapper = ObjectMappers.newJsonMapper()
        private const val KILL_FUNCTION = "CREATE OR REPLACE FUNCTION test_kill_inserting_session() RETURNS trigger " +
            "LANGUAGE plpgsql AS \$\$ BEGIN PERFORM pg_terminate_backend(pg_backend_pid()); RETURN NULL; END \$\$"
        private const val KILL_FINAL_INSERT = "CREATE TRIGGER test_kill_final_insert AFTER INSERT ON chronicle_usage_events " +
            "FOR EACH STATEMENT EXECUTE FUNCTION test_kill_inserting_session()"
        private const val DROP_FINAL_KILL = "DROP TRIGGER test_kill_final_insert ON chronicle_usage_events"
        private const val KILL_BUFFER_INSERT = "CREATE TRIGGER test_kill_buffer_insert AFTER INSERT ON upload_buffer " +
            "FOR EACH STATEMENT EXECUTE FUNCTION test_kill_inserting_session()"
        private const val DROP_BUFFER_KILL = "DROP TRIGGER test_kill_buffer_insert ON upload_buffer"

        @BeforeClass
        @JvmStatic
        fun setUp() {
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            val configuration = PostgresConfiguration(Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "1")
            }, usingCitus = false, flavor = PostgresFlavor.VANILLA, initializeIndices = false, initializeTables = false)
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

    private val participant = "upload-retry"
    private val device = UUID.randomUUID()
    private val study: UUID = UUID.randomUUID()

    @Before fun seed() {
        TestSecurityUtils.setupSecurityContext()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'Upload retry')").use {
                it.setObject(1, study); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO study_participants (study_id, participant_id, candidate_id, " +
                "participation_status) VALUES (?, ?, ?, 'ENROLLED')").use {
                it.setObject(1, study); it.setString(2, participant); it.setObject(3, seedCandidate(connection)); it.executeUpdate()
            }
        }
    }

    @After fun clear() = TestSecurityUtils.clearSecurityContext()

    @Test
    fun usageReceiptTimeSurvivesDelayedMovesAndNewerTelemetry() {
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        val map = org.mockito.kotlin.mock<IMap<ParticipantKey, ParticipantStats>>()
        Mockito.`when`(hazelcast.getMap<ParticipantKey, ParticipantStats>("PARTICIPANT_STATS")).thenReturn(map)
        val cache = HazelcastParticipantStatsCache(storage, hazelcast)
        val studies = Mockito.mock(StudyManager::class.java)
        Mockito.doAnswer { cache.merge(it.getArgument(0)); null }.`when`(studies)
            .insertOrUpdateParticipantStats(org.mockito.kotlin.any())
        val upload = AppDataUploadService(storage, studies)
        val received = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val event = ChronicleUsageEvent(studyId = study, participantId = participant, appPackageName = "com.example.receipt",
            interactionType = "Move to Foreground", timestamp = received.minusDays(1),
            timezone = "UTC", user = "", applicationLabel = "Receipt", clientEventId = UUID.randomUUID())

        upload.uploadAndroidUsageEvents(study, participant, device, listOf(event), received)
        assertEquals(received.toInstant(), cache.get(study, participant)?.androidLastPing?.toInstant())
        upload.drainEventStorage()
        assertEquals(received.toInstant(), cache.get(study, participant)?.androidLastPing?.toInstant())

        upload.uploadAndroidUsageEvents(study, participant, device,
            listOf(event.copy(clientEventId = UUID.randomUUID())), received.minusHours(1))
        BatteryTelemetryUploadService(storage).upload(study, participant, device, listOf(battery()))
        val latest = receiptTime("battery_telemetry")
        assertEquals(latest, cache.get(study, participant)?.androidLastPing?.toInstant())
        upload.drainEventStorage()
        assertEquals(latest, cache.get(study, participant)?.androidLastPing?.toInstant())
    }

    @Test
    fun telemetryOnlyReceiptsUseTheirStoredInstantAndRollBackWithTheirOwner() {
        val stats = ParticipantStatsMapstore(storage.getPlatformStorage())
        val key = ParticipantKey(study, participant)
        assertNull(stats.load(key)?.androidLastPing)
        val batteryUpload = BatteryTelemetryUploadService(storage)
        val sample = battery()
        val source = storage.getPlatformStorage()
        source.connection.use { owner ->
            owner.autoCommit = false
            assertThrows(IllegalStateException::class.java) {
                PinnedPlatformConnection.committing(source, owner) {
                    batteryUpload.upload(study, participant, device, listOf(sample))
                    assertTrue(stats.readUsing(owner, key)?.androidLastPing != null)
                    error("Roll back the receipt and its stats")
                }
            }
            owner.rollback()
        }
        assertNull(stats.load(key)?.androidLastPing)
        batteryUpload.upload(study, participant, device, listOf(sample))
        assertEquals(receiptTime("battery_telemetry"),
            stats.load(key)?.androidLastPing?.toInstant())
        val event = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), sample.timestamp.toLocalDate(),
            "BATTERY", "TIMEOUT", 1, sample.timestamp, sample.timestamp)
        UploadDiagnosticsUploadService(storage).upload(study, participant, device, listOf(event))
        // Diagnostics are not research data; a phone that only reports failures must not look fresh.
        assertEquals(receiptTime("battery_telemetry"),
            stats.load(key)?.androidLastPing?.toInstant())
    }

    private fun battery() = BatterySample(UUID.randomUUID().toString(), OffsetDateTime.now().minusMinutes(1),
        "UTC", 80, BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 220, 4000, BatteryHealth.GOOD)

    private fun receiptTime(table: String): java.time.Instant = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT uploaded_at FROM $table WHERE study_id = ?").use { statement ->
            statement.setObject(1, study)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getObject(1, OffsetDateTime::class.java).toInstant()
            }
        }
    }

    @Test
    fun usageRetryAfterALostResponseStoresEachClientEventOnce() {
        val upload = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        val event = ChronicleUsageEvent(studyId = study, participantId = participant, appPackageName = "com.example.retry",
            interactionType = "Move to Foreground", timestamp = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
            timezone = "UTC", user = "", applicationLabel = "Retry", clientEventId = UUID.randomUUID())
        upload.uploadAndroidUsageEvents(study, participant, device, listOf(event))
        upload.drainEventStorage()
        // The phone re-serializes the queued row; its resolved label changed after an app update.
        val reserialized = mapper.readValue(mapper.writeValueAsBytes(event.copy(applicationLabel = "Retry 2")),
            ChronicleUsageEvent::class.java)
        upload.uploadAndroidUsageEvents(study, participant, device, listOf(reserialized))
        upload.drainEventStorage()
        assertEquals(listOf(event.clientEventId.toString()), column("chronicle_usage_events", "client_event_id"))
    }

    @Test
    fun logicalDuplicatesAreCountedWhenRemoved() {
        val upload = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        val event = ChronicleUsageEvent(studyId = study, participantId = participant, appPackageName = "com.example.twice",
            interactionType = "Move to Foreground", timestamp = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
            timezone = "UTC", user = "", applicationLabel = "Twice", clientEventId = UUID.randomUUID())
        // A same-batch copy is dropped by the production key at insert; the sweep removes a copy whose later
        // upload gives it a different receipt.
        upload.uploadAndroidUsageEvents(study, participant, device, listOf(event))
        upload.drainEventStorage()
        upload.uploadAndroidUsageEvents(study, participant, device, listOf(event.copy(clientEventId = UUID.randomUUID())),
            OffsetDateTime.now().plusSeconds(1))
        ScopedLogCapture(AppDataUploadService::class.java, Level.INFO).use { logs ->
            upload.drainEventStorage()
            assertTrue(logs.messages.toString(), logs.messages.any { it.startsWith("Removed 1 logical duplicate usage events") })
        }
        assertEquals(1, column("chronicle_usage_events", "client_event_id").size)
    }

    @Test
    fun sensorRetryAfterALostResponseStoresEachSampleOnce() {
        val upload = AndroidSensorDataUploadService(storage)
        val sample = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.light,
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), "UTC", x = 1f)
        upload.upload(study, participant, device, listOf(sample))
        val reserialized = mapper.readValue(mapper.writeValueAsBytes(sample), AndroidSensorSample::class.java)
        upload.upload(study, participant, device, listOf(reserialized))
        assertEquals(listOf(sample.id.toString()), column("android_sensor_data", "sample_id"))
    }

    /**
     * The database session dies while the mover holds a batch, after the final rows are written and before
     * the move commits. The buffer keeps the batch; the next server's drain stores each acknowledged row once with its
     * original receipt time, and the phone's resend of the same rows after its lost response stores nothing twice.
     */
    @Test
    fun aMoveKilledBeforeCommitLeavesTheBatchBufferedAndTheNextDrainStoresItOnce() {
        val received = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val events = (1..3).map { usageEvent("com.example.killed$it") }
        val upload = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        assertEquals(events.mapTo(hashSetOf()) { it.clientEventId }, upload.uploadAndroidUsageEvents(study, participant, device, events, received))
        killingInserts(KILL_FINAL_INSERT, DROP_FINAL_KILL) { assertThrows(Exception::class.java) { upload.drainEventStorage() } }
        assertEquals("the killed move left the whole batch buffered", events.size, bufferedRows())
        assertEquals(emptyList<String>(), storedReceipts().keys.toList())

        val restarted = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        restarted.drainEventStorage()
        restarted.uploadAndroidUsageEvents(study, participant, device, events, received.plusMinutes(5))
        restarted.drainEventStorage()
        assertEquals(events.associate { it.clientEventId.toString() to received.toInstant() }, storedReceipts())
        assertEquals("no acknowledged row is stranded", 0, bufferedRows())
    }

    /**
     * A session killed inside the buffer write acknowledges nothing and keeps nothing; a buffer write that
     * committed before its response was lost is resent before the drain runs. Each row is stored once, at the receipt
     * time of the first committed upload.
     */
    @Test
    fun aKilledBufferWriteAndAResendBeforeTheDrainStoreEachRowOnceAtItsFirstReceipt() {
        val received = OffsetDateTime.parse("2026-09-02T08:00:00Z")
        val events = (1..2).map { usageEvent("com.example.resent$it") }
        val upload = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        killingInserts(KILL_BUFFER_INSERT, DROP_BUFFER_KILL) {
            assertThrows(Exception::class.java) { upload.uploadAndroidUsageEvents(study, participant, device, events, received.minusMinutes(1)) }
        }
        assertEquals("a killed buffer write keeps nothing", 0, bufferedRows())
        val restarted = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        restarted.uploadAndroidUsageEvents(study, participant, device, events, received)
        restarted.uploadAndroidUsageEvents(study, participant, device, events, received.plusMinutes(10))
        restarted.drainEventStorage()
        assertEquals(events.associate { it.clientEventId.toString() to received.toInstant() }, storedReceipts())
        assertEquals(0, bufferedRows())
    }

    private fun usageEvent(packageName: String) = ChronicleUsageEvent(studyId = study, participantId = participant,
        appPackageName = packageName, interactionType = "Move to Foreground",
        timestamp = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS), timezone = "UTC", user = "",
        applicationLabel = packageName, clientEventId = UUID.randomUUID())

    /** Runs [body] while every insert the trigger [create] watches terminates its own database session. */
    private fun killingInserts(create: String, drop: String, body: () -> Unit) {
        postgres.createConnection("").use { connection -> connection.createStatement().use { it.execute(KILL_FUNCTION); it.execute(create) } }
        try {
            body()
        } finally {
            postgres.createConnection("").use { connection -> connection.createStatement().use { it.execute(drop) } }
        }
    }

    private fun bufferedRows(): Int = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM upload_buffer, jsonb_array_elements(data) WHERE study_id = ? AND upload_type = 'Android'",
        ).use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    /** Stored usage receipt instants by client event ID; a duplicate ID fails the read. */
    private fun storedReceipts(): Map<String, java.time.Instant> = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "SELECT client_event_id, uploaded_at FROM chronicle_usage_events WHERE study_id::text = ?",
        ).use {
            it.setString(1, study.toString())
            it.executeQuery().use(::receiptsStoredOnce)
        }
    }

    private fun receiptsStoredOnce(rows: java.sql.ResultSet): Map<String, java.time.Instant> {
        val receipts = buildList { while (rows.next()) add(rows.getString(1) to rows.getObject(2, OffsetDateTime::class.java).toInstant()) }
        val duplicates = receipts.groupingBy { it.first }.eachCount().filterValues { it > 1 }
        assertEquals("each client event is stored once", emptyMap<String, Int>(), duplicates)
        return receipts.toMap()
    }

    /** Upload, the scheduled mover and the admin drain name the participant only by its keyed reference. */
    @Test
    fun uploadAndBothDrainsLogOnlyKeyedParticipantReferences() {
        val studies = Mockito.mock(StudyManager::class.java)
        val upload = AppDataUploadService(storage, studies)
        val scheduled = object : MoveToEventStorageTask() {
            override fun getDependency() = MoveToEventStorageTaskDependencies(storage, studies)
        }
        val start = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        fun event(n: Long) = ChronicleUsageEvent(studyId = study, participantId = participant, appPackageName = "com.example.logs",
            interactionType = "Move to Foreground", timestamp = start.plusSeconds(n), timezone = "UTC", user = "",
            applicationLabel = "Logs", clientEventId = UUID.randomUUID())
        val logs = ScopedLogCapture("com.openlattice.chronicle").use { capture ->
            upload.uploadAndroidUsageEvents(study, participant, device, listOf(event(0)))
            scheduled.runTask()
            upload.uploadAndroidUsageEvents(study, participant, device, listOf(event(1)))
            upload.drainEventStorage()
            capture.messages.toList()
        }
        assertEquals(2, column("chronicle_usage_events", "client_event_id").size)
        assertTrue("the drains logged their participant reference",
            logs.any { it.contains(com.openlattice.chronicle.util.LogSanitizer.stableFingerprint(participant, "participant")) })
        assertEquals(emptyList<String>(), logs.filter { it.contains(participant) })
    }

    /** An event naming another study and participant is stored only under the authenticated URL subject. */
    @Test
    fun anEventNamingAnotherSubjectIsStoredUnderTheAuthenticatedSubject() {
        val upload = AppDataUploadService(storage, Mockito.mock(StudyManager::class.java))
        val foreignStudy = UUID.randomUUID()
        val event = ChronicleUsageEvent(studyId = foreignStudy, participantId = "someone-else", appPackageName = "com.example.subject",
            interactionType = "Move to Foreground", timestamp = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
            timezone = "UTC", user = "", applicationLabel = "Subject", clientEventId = UUID.randomUUID())
        upload.uploadAndroidUsageEvents(study, participant, device, listOf(event))
        upload.drainEventStorage()
        assertEquals(listOf(participant), column("chronicle_usage_events", "participant_id"))
        for (table in listOf("chronicle_usage_events", "upload_buffer")) {
            assertEquals(table, 0, count("SELECT count(*) FROM $table WHERE study_id::text = ? OR participant_id = ?",
                foreignStudy.toString(), "someone-else"))
        }
    }

    /** The scheduled mover and the admin drain queue behind one running batch, then both run; each event lands once. */
    @Test
    fun overlappingScheduledAndAdminDrainsStoreEachEventOnce() {
        val configuration = PostgresConfiguration(Properties().apply {
            setProperty("jdbcUrl", postgres.jdbcUrl)
            setProperty("username", postgres.username)
            setProperty("password", postgres.password)
            setProperty("maximumPoolSize", "4")
        }, usingCitus = false, flavor = PostgresFlavor.VANILLA, initializeIndices = false, initializeTables = false)
        val pooled = DataSourceManager(mapOf("default" to configuration, "chronicle" to configuration),
            HealthCheckRegistry(), MetricRegistry())
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val wide = StorageResolver(pooled, ChronicleStorageConfiguration(defaultEventStorage = "default"))
            val studies = Mockito.mock(StudyManager::class.java)
            val upload = AppDataUploadService(wide, studies)
            val start = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).minusDays(1)
            // More than one 128-row batch, so both drains find work once the running batch ends.
            val events = (0 until 300).map { n ->
                ChronicleUsageEvent(studyId = study, participantId = participant, appPackageName = "com.example.overlap",
                    interactionType = "Move to Foreground", timestamp = start.plusSeconds(n.toLong()),
                    timezone = "UTC", user = "", applicationLabel = "Overlap", clientEventId = UUID.randomUUID())
            }
            upload.uploadAndroidUsageEvents(study, participant, device, events)
            val scheduled = object : MoveToEventStorageTask() {
                override fun getDependency() = MoveToEventStorageTaskDependencies(wide, studies)
            }
            postgres.createConnection("").use { running ->
                running.autoCommit = false
                com.openlattice.chronicle.storage.DeletionTableLockOrder.lockDrain(running, "chronicle_usage_events")
                val drains = listOf(executor.submit { scheduled.runTask() }, executor.submit { upload.drainEventStorage() })
                awaitLockWaiters(2)
                running.rollback()
                drains.forEach { it.get(2, java.util.concurrent.TimeUnit.MINUTES) }
            }
            assertEquals(events.map { it.clientEventId.toString() }.sorted(),
                column("chronicle_usage_events", "client_event_id").sortedBy { it })
            assertEquals(0, count("SELECT count(*) FROM upload_buffer WHERE study_id = ?", study))
        } finally {
            executor.shutdownNow()
            pooled.dataSources.values.forEach { it.close() }
        }
    }

    /** Waits, bounded, until [count] backends are queued on a heavyweight lock. */
    private fun awaitLockWaiters(waiters: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos()
        while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()") < waiters) {
            check(System.nanoTime() < deadline) { "$waiters drains never queued on the drain locks" }
            Thread.onSpinWait()
        }
    }

    private fun count(sql: String, vararg params: Any): Int = postgres.createConnection("").use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun column(table: String, name: String): List<String?> = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT $name::text FROM $table WHERE study_id::text = ?").use {
            it.setString(1, study.toString())
            it.executeQuery().use { rows -> buildList<String?> { while (rows.next()) add(rows.getString(1)) } }
        }.also { assertTrue("$table has no rows", it.isNotEmpty()) }
    }
}
