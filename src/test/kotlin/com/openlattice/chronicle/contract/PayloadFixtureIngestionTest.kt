package com.openlattice.chronicle.contract

import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.fixtures.FixtureFamily
import com.openlattice.chronicle.fixtures.FixtureRegistry
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.studies.AuthorizedHealthScope
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRecord
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRowKind
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.MoveAndroidSensorDataToStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTaskDependencies
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Rule
import com.openlattice.chronicle.storage.rls.RLSFixtureContext
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

// =============================================================================
// The canonical cross-language payload fixtures live in
// chronicle-models/fixtures/payloads/ (registry.json, chronicle-fixture-registry/v1).
// This suite proves, against a REAL Percona PG 17.5 (prod image) with the framework schema plus the
// full Flyway corpus applied (same bootstrap as CollectionModuleCoverageMatrixDbTest),
// that for every currently enabled fixture family with a payload upload path in chronicle-server:
//
//   1. the family's valid.json decodes into the JVM DTOs with the server's REAL
//      upload-path ObjectMapper (JacksonSecurityConfig.secureObjectMapper — the
//      exact mapper Spring MVC uses to read upload request bodies), and the REAL
//      upload service persists it into the matrix table;
//   2. rows carry the expected study/participant scoping and the fixture's event
//      timestamps survive round-trip to TIMESTAMPTZ exactly (instant equality);
//   3. re-ingesting the same fixture is idempotent per the matrix semantics
//      (PK + ON CONFLICT DO NOTHING for the dedicated collection tables,
//      sample_id PK for android_sensor_data, and the capture key of
//      ios_screen_time_usage);
//   4. every invalid-*.json fixture is REJECTED by the ingestion path (Jackson
//      decode -> DTO init require(...) validation) and persists nothing.
//
// Android sensor samples and iOS Screen Time records are committed directly to their
// final tables. A separate Android legacy-buffer test proves pre-upgrade rows still drain.
//
// ios-device is the one registry family not exercised here: its backendHandler is
// EnrollmentService (device registration), not a payload upload service; it is
// covered by the enrollment-surface tests, not the payload-ingestion matrix.
// =============================================================================
class PayloadFixtureIngestionTest {
    @get:Rule
    val fixtureContext = RLSFixtureContext()


    internal companion object {

        /** Scope used for every family whose fixture does not itself carry scoping ids. */
        private val SHARED_STUDY_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000f0")

        private lateinit var dataSourceManager: DataSourceManager
        private lateinit var storageResolver: StorageResolver
        private lateinit var moverDependencies: MoveToEventStorageTaskDependencies

        /** The server's real upload-path mapper (Spring MVC message-converter mapper). */
        private lateinit var mapper: ObjectMapper

        private lateinit var modelsRoot: File

        /**
         * family -> registry entry, parsed and validated by chronicle-models' own
         * FixtureRegistry (the single strict parser: kebab-case, module-id membership,
         * file-under-family-dir, schema version, uniqueness) — no laxer local copy.
         */
        private lateinit var registryFamilies: Map<String, FixtureFamily>

        /**
         * Locates the chronicle-models checkout that owns the canonical fixtures. Honors
         * -Dchronicle.models.root (the same property chronicle-models' own build.gradle
         * uses) and falls back to the canonical methodic sibling layout.
         */
        private fun resolveModelsRoot(): File {
            val candidates = listOfNotNull(
                System.getProperty("chronicle.models.root")?.let(::File),
                File("../chronicle-models"),
                File("chronicle-models"),
            )
            return candidates.firstOrNull { File(it, "fixtures/payloads/registry.json").isFile }
                ?: error(
                    "Could not locate chronicle-models/fixtures/payloads/registry.json from " +
                        "cwd=${File(".").absolutePath}; set -Dchronicle.models.root",
                )
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            // Shared bootstrapped container (framework schema + chronicle role + full
            // Flyway corpus): see ChronicleContractTestSchema.sharedPostgres.
            val postgres = ChronicleContractTestSchema.sharedPostgres

            // Real DataSourceManager + StorageResolver, wired exactly the way
            // FlywayMigrationCorpusTest builds them for upload-path services.
            dataSourceManager = DataSourceManager(
                ChronicleContractTestSchema.storageConfigurations(postgres, maximumPoolSize = 5),
                HealthCheckRegistry(), MetricRegistry(),
            )
            storageResolver = StorageResolver(dataSourceManager, ChronicleStorageConfiguration())

            // The study->storage IMap normally comes from Hazelcast; a mock whose
            // executeOnKey returns null makes resolveAndGetFlavor fall back to the
            // default event storage, which is what production does for unmapped studies.
            val studyStorage = mock<IMap<UUID, Study>>()
            val hazelcast = mock<HazelcastInstance>()
            whenever(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studyStorage)
            storageResolver.setStudyStorage(hazelcast)

            moverDependencies = MoveToEventStorageTaskDependencies(storageResolver, mock<StudyManager>())
            mapper = JacksonSecurityConfig().secureObjectMapper()
            modelsRoot = resolveModelsRoot()
            registryFamilies = FixtureRegistry
                .load(modelsRoot, CollectionModuleId.entries.map { it.id }.toSet())
                .families
                .associateBy { it.family }

            seedStudiesAndParticipants()
        }

        /**
         * Seeds the prerequisite study + participant rows for every scope the suite
         * ingests into: the shared study with one participant per family, plus the
         * study/participant the screen-time fixture itself carries.
         */
        private fun seedStudiesAndParticipants() {
            val iosEnvelopes = listOf(mapper.readTree(validFixture("screen-time-usage")))

            storageResolver.getBootstrapStorage().connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO studies (study_id, title, settings) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
                ).use { ps ->
                    for ((studyId, title) in listOf(
                        SHARED_STUDY_ID to "Fixture Ingestion Study",
                        UUID.fromString(iosEnvelopes[0].path("studyId").asText()) to "Screen Time Fixture Study",
                    )) {
                        ps.setObject(1, studyId)
                        ps.setString(2, title)
                        ps.setString(3, mapper.writeValueAsString(StudySettings(mapOf(
                            StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting(),
                        ))))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
                    VALUES (?, ?, ?, 'ENROLLED') ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { ps ->
                    val participants =
                        registryFamilies.keys.map { SHARED_STUDY_ID to participantFor(it) } +
                            iosEnvelopes.map { envelope ->
                                UUID.fromString(envelope.path("studyId").asText()) to envelope.path("participantId").asText()
                            }
                    for ((studyId, participantId) in participants) {
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setObject(3, seedCandidate(conn))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        /** One deterministic participant per family keeps every family's rows isolated. */
        internal fun participantFor(family: String): String = "fixture-$family"

        internal fun family(name: String): FixtureFamily =
            registryFamilies[name] ?: error("Family '$name' missing from canonical fixture registry")

        private fun fixtureFiles(name: String): List<File> =
            family(name).fixtureFiles.map { File(modelsRoot, it) }

        internal fun validFixture(name: String): File =
            fixtureFiles(name).first { it.name == "valid.json" }

        internal fun invalidFixtures(name: String): List<File> =
            fixtureFiles(name).filter { it.name.startsWith("invalid-") }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            // Only the pools are ours; the shared container is reaped by Ryuk at JVM exit.
            if (::dataSourceManager.isInitialized) {
                dataSourceManager.dataSources.values.forEach(HikariDataSource::close)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hds(): HikariDataSource = storageResolver.getPlatformStorage()

    private fun <T> decodeList(file: File, type: Class<T>): List<T> = mapper.readValue(
        file,
        mapper.typeFactory.constructCollectionType(List::class.java, type),
    )

    /** (id -> event timestamp) pairs straight from the fixture JSON — the wire truth. */
    private fun fixtureEventTimestamps(file: File): Map<String, OffsetDateTime> =
        mapper.readTree(file).associate { node ->
            node.path("id").asText() to OffsetDateTime.parse(node.path("timestamp").asText())
        }

    private fun scopedCount(table: String, studyId: UUID, participantId: String): Int =
        hds().connection.use { c ->
            c.prepareStatement(
                "SELECT count(*) FROM $table WHERE study_id = ? AND participant_id = ?",
            ).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** (event id -> persisted event timestamp) for one study/participant scope. */
    private fun persistedEventTimestamps(
        table: String,
        idColumn: String,
        studyId: UUID,
        participantId: String,
    ): Map<String, OffsetDateTime> = hds().connection.use { c ->
        c.prepareStatement(
            "SELECT $idColumn, sample_timestamp FROM $table WHERE study_id = ? AND participant_id = ?",
        ).use { ps ->
            ps.setObject(1, studyId)
            ps.setString(2, participantId)
            ps.executeQuery().use { rs ->
                val out = mutableMapOf<String, OffsetDateTime>()
                while (rs.next()) {
                    out[rs.getString(1)] = rs.getObject(2, OffsetDateTime::class.java)
                }
                out
            }
        }
    }

    private fun assertTimestampsPreserved(
        family: String,
        expected: Map<String, OffsetDateTime>,
        persisted: Map<String, OffsetDateTime>,
    ) {
        assertEquals(
            "$family: persisted row count must equal the fixture payload count",
            expected.keys,
            persisted.keys,
        )
        for ((id, expectedTs) in expected) {
            val actual = persisted.getValue(id)
            assertEquals(
                "$family: event $id timestamp not preserved (expected $expectedTs, got $actual)",
                expectedTs.toInstant(),
                actual.toInstant(),
            )
        }
    }

    private fun assertIngestionRejects(family: String, fixture: File, ingest: (File) -> Int) {
        val thrown: Exception = try {
            ingest(fixture)
            fail("$family: malformed fixture ${fixture.name} must be rejected by the ingestion path")
            return
        } catch (rejected: Exception) {
            rejected
        }
        assertTrue(
            "$family: ${fixture.name} must fail as a decode/validation rejection " +
                "(got ${thrown::class.java.name}: ${thrown.message})",
            thrown is JacksonException || thrown is IllegalArgumentException,
        )
    }

    /**
     * The full family contract for the twelve direct-write collection modules:
     * valid.json ingests through the real upload service, rows land scoped with
     * timestamps preserved, double-ingest is deduped by the table PK, and every
     * invalid-*.json is rejected persisting nothing.
     */
    private fun assertDirectFamilyContract(
        familyName: String,
        expectedHandler: Class<*>,
        idColumn: String,
        ingest: (UUID, String, File) -> Int,
    ) {
        val registryEntry = family(familyName)
        assertEquals(
            "$familyName: registry backendHandler drifted from the driven service",
            expectedHandler.simpleName,
            registryEntry.backendHandler,
        )
        val table = registryEntry.backendTable
        val participantId = participantFor(familyName)
        val valid = validFixture(familyName)
        val expected = fixtureEventTimestamps(valid)

        // 1) Valid ingestion: rows persisted, scoped, timestamps preserved.
        val accepted = ingest(SHARED_STUDY_ID, participantId, valid)
        assertEquals("$familyName: upload must accept the full valid batch", expected.size, accepted)
        assertTimestampsPreserved(
            familyName,
            expected,
            persistedEventTimestamps(table, idColumn, SHARED_STUDY_ID, participantId),
        )

        // 2) Idempotency: identical re-ingest adds no rows (PK + ON CONFLICT DO NOTHING).
        ingest(SHARED_STUDY_ID, participantId, valid)
        assertEquals(
            "$familyName: re-ingesting the same fixture must not create duplicate rows",
            expected.size,
            scopedCount(table, SHARED_STUDY_ID, participantId),
        )

        // 3) Malformed fixtures: rejected, nothing persisted.
        for (invalid in invalidFixtures(familyName)) {
            assertIngestionRejects(familyName, invalid) { f -> ingest(SHARED_STUDY_ID, participantId, f) }
            assertEquals(
                "$familyName: rejected fixture ${invalid.name} must persist nothing",
                expected.size,
                scopedCount(table, SHARED_STUDY_ID, participantId),
            )
        }
        // Replay behavior uses an actual purge, including ledger minimization after completion.
        val orchestrator = DataDeletionOrchestrator(storageResolver, mock<AuditingManager>())
        val operation = orchestrator.quarantineParticipant(SHARED_STUDY_ID, participantId,
            DataDeletionMode.COLLECTED_DATA_PURGE, "fixture-purge", UUID.randomUUID())
        assertEquals("COMPLETED", orchestrator.eraseNow(operation, SHARED_STUDY_ID, "test-owner").status)
        assertEquals(expected.size, ingest(SHARED_STUDY_ID, participantId, valid))
        assertEquals("$familyName: old-only retry must be acknowledged and dropped", 0,
            scopedCount(table, SHARED_STUDY_ID, participantId))
        val old = mapper.readTree(valid).first()
        fun freshEvent(days: Long) = old.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
            val observed = OffsetDateTime.now().plusDays(days)
            put("id", UUID.randomUUID().toString()); put("timestamp", observed.toString())
            // A reread time does not make an old health record/network window fresh.
            listOf("startMillis" to "endMillis", "bucketStartMillis" to "bucketEndMillis").forEach { (start, end) ->
                if (has(start) && has(end)) {
                    val duration = path(end).asLong() - path(start).asLong()
                    val endMillis = observed.toInstant().toEpochMilli()
                    put(start, endMillis - duration); put(end, endMillis)
                }
            }
        }
        val fresh = freshEvent(1)
        val newest = freshEvent(2)
        val fixtureDir = File("build/purge-fixtures").apply { mkdirs() }
        val mixed = File(fixtureDir, "$familyName-${UUID.randomUUID()}.json")
        try {
            mapper.writeValue(mixed, listOf(old, fresh, newest))
            assertEquals(3, ingest(SHARED_STUDY_ID, participantId, mixed))
            assertEquals("$familyName: mixed retry keeps only fresh rows", 2,
                scopedCount(table, SHARED_STUDY_ID, participantId))
            val stop = OffsetDateTime.parse(fresh.path("timestamp").asText()).plusHours(12)
            for (status in listOf("PAUSED", "COLLECTION_COMPLETED")) {
                stopParticipant(SHARED_STUDY_ID, participantId, status)
                hds().connection.use { connection ->
                    connection.prepareStatement("UPDATE study_participants SET capture_cutoff = ? WHERE study_id = ? AND participant_id = ?").use {
                        it.setObject(1, stop); it.setObject(2, SHARED_STUDY_ID); it.setString(3, participantId); it.executeUpdate()
                    }
                    connection.prepareStatement("DELETE FROM $table WHERE study_id = ? AND participant_id = ?").use {
                        it.setObject(1, SHARED_STUDY_ID); it.setString(2, participantId); it.executeUpdate()
                    }
                }
                mapper.writeValue(mixed, listOf(fresh, newest))
                assertEquals("$familyName: the entire $status batch is acknowledged", 2, ingest(SHARED_STUDY_ID, participantId, mixed))
                // One clock: the phone drops rows inside its windows; the server stores the queue.
                assertEquals("$familyName: every queued capture is stored", 2, scopedCount(table, SHARED_STUDY_ID, participantId))
                assertEquals(2, ingest(SHARED_STUDY_ID, participantId, mixed))
                assertEquals("$familyName: retries store each capture once", 2, scopedCount(table, SHARED_STUDY_ID, participantId))
            }
        } finally { mixed.delete() }
    }

    // -------------------------------------------------------------------------
    // The twelve direct-write collection-module families
    // -------------------------------------------------------------------------

    @Test
    fun `device-settings fixtures ingest through DeviceSettingsUploadService`() {
        val service = DeviceSettingsUploadService(storageResolver)
        assertDirectFamilyContract("device-settings", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidDeviceSettingsEvent::class.java))
        }
    }

    @Test
    fun `battery-telemetry fixtures ingest through BatteryTelemetryUploadService`() {
        val service = BatteryTelemetryUploadService(storageResolver)
        val deviceId = UUID.randomUUID()
        assertDirectFamilyContract("battery-telemetry", service::class.java, "sample_id") { s, p, f ->
            service.upload(s, p, deviceId, decodeList(f, BatterySample::class.java))
        }
    }

    @Test
    fun `interaction-events fixtures ingest through InteractionEventsUploadService`() {
        val service = InteractionEventsUploadService(storageResolver)
        assertDirectFamilyContract("interaction-events", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidInteractionEvent::class.java))
        }
    }

    @Test
    fun `app-audio-activity fixtures ingest through AppAudioActivityUploadService`() {
        val service = AppAudioActivityUploadService(storageResolver)
        assertDirectFamilyContract("app-audio-activity", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidAudioActivityEvent::class.java))
        }
    }

    @Test
    fun `app-audio-content fixtures ingest through AppAudioContentUploadService`() {
        val service = AppAudioContentUploadService(storageResolver)
        assertDirectFamilyContract("app-audio-content", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidAudioContentEvent::class.java))
        }
    }

    @Test
    fun `notification-activity fixtures ingest through NotificationActivityUploadService`() {
        val service = NotificationActivityUploadService(storageResolver)
        assertDirectFamilyContract("notification-activity", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidNotificationActivityEvent::class.java))
        }
    }

    @Test
    fun `sleep-events fixtures ingest through SleepEventsUploadService`() {
        val service = SleepEventsUploadService(storageResolver)
        assertDirectFamilyContract("sleep-events", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidSleepEvent::class.java))
        }
    }

    @Test
    fun `activity-recognition fixtures ingest through ActivityRecognitionEventsUploadService`() {
        val service = ActivityRecognitionEventsUploadService(storageResolver)
        assertDirectFamilyContract("activity-recognition", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidActivityRecognitionEvent::class.java))
        }
    }

    @Test
    fun `health-metrics fixtures ingest through HealthMetricsUploadService`() {
        val service = HealthMetricsUploadService(storageResolver)
        assertDirectFamilyContract("health-metrics", service::class.java, "event_id") { s, p, f ->
            service.upload(AuthorizedHealthScope(s, p, UUID.randomUUID(), HealthConnectRecordType.entries.toSet()),
                decodeList(f, AndroidHealthMetricEvent::class.java))
        }
    }

    @Test
    fun `connectivity-state fixtures ingest through ConnectivityStateEventsUploadService`() {
        val service = ConnectivityStateEventsUploadService(storageResolver)
        assertDirectFamilyContract("connectivity-state", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidConnectivityStateEvent::class.java))
        }
    }

    @Test
    fun `app-network-usage fixtures ingest through AppNetworkUsageUploadService`() {
        val service = AppNetworkUsageUploadService(storageResolver)
        assertDirectFamilyContract("app-network-usage", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AndroidAppNetworkUsageEvent::class.java))
        }
    }

    // -------------------------------------------------------------------------
    // Direct Android sensor ingestion and legacy-buffer compatibility
    // -------------------------------------------------------------------------

    private fun bufferCount(studyId: UUID, participantId: String): Int =
        scopedCount(ChroniclePostgresTables.UPLOAD_BUFFER.name, studyId, participantId)

    private fun stopParticipant(studyId: UUID, participantId: String, status: String): OffsetDateTime =
        hds().connection.use { connection ->
            connection.prepareStatement("""UPDATE study_participants SET participation_status = ?
                WHERE study_id = ? AND participant_id = ? RETURNING capture_cutoff""").use {
                it.setString(1, status); it.setObject(2, studyId); it.setString(3, participantId)
                it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
            }
        }

    @Test
    fun `android-sensor-data fixtures commit directly and idempotently to final storage`() {
        val registryEntry = family("android-sensor-data")
        val service = AndroidSensorDataUploadService(storageResolver)
        assertEquals(
            "android-sensor-data: registry backendHandler drifted",
            service::class.java.simpleName,
            registryEntry.backendHandler,
        )
        val table = registryEntry.backendTable
        val participantId = participantFor("android-sensor-data")
        val randomDeviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d0")
        val valid = validFixture("android-sensor-data")
        val expected = fixtureEventTimestamps(valid)

        fun ingest(file: File): Int =
            service.upload(SHARED_STUDY_ID, participantId, randomDeviceId, decodeList(file, AndroidSensorSample::class.java))

        // 1) The service must not acknowledge before final-table rows are visible.
        assertEquals(expected.size, ingest(valid))
        assertEquals("android-sensor-data: direct upload must not stage a buffer row", 0, bufferCount(SHARED_STUDY_ID, participantId))
        assertTimestampsPreserved(
            "android-sensor-data",
            expected,
            persistedEventTimestamps(table, "sample_id", SHARED_STUDY_ID, participantId),
        )
        hds().connection.use { c ->
            c.prepareStatement(
                "SELECT count(*) FROM $table WHERE study_id = ? AND participant_id = ? AND random_device_id = ?",
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setObject(3, randomDeviceId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(
                        "android-sensor-data: rows must carry the uploading random device id",
                        expected.size,
                        rs.getInt(1),
                    )
                }
            }
        }

        // 2) Ambiguous-response retries are idempotent on sample_id.
        ingest(valid)
        assertEquals(
            "android-sensor-data: duplicate samples must be dropped by the sample_id PK",
            expected.size,
            scopedCount(table, SHARED_STUDY_ID, participantId),
        )

        // 3) Malformed fixtures (bad uuid, bad date) are rejected before persistence.
        for (invalid in invalidFixtures("android-sensor-data")) {
            assertIngestionRejects("android-sensor-data", invalid, ::ingest)
            assertEquals(0, bufferCount(SHARED_STUDY_ID, participantId))
            assertEquals(expected.size, scopedCount(table, SHARED_STUDY_ID, participantId))
        }
        val permitted = expected.toMutableMap()
        val template = decodeList(valid, AndroidSensorSample::class.java).first()
        for ((index, status) in listOf("PAUSED", "COLLECTION_COMPLETED").withIndex()) {
            val cutoff = stopParticipant(SHARED_STUDY_ID, participantId, status)
            val before = template.copy(id = UUID.randomUUID(), timestamp = cutoff.minusSeconds(index + 1L))
            val after = template.copy(id = UUID.randomUUID(), timestamp = cutoff.plusSeconds(1))
            assertEquals(2, service.upload(SHARED_STUDY_ID, participantId, randomDeviceId, listOf(before, after)))
            // One clock: the server stores the queue; the phone drops rows inside its windows.
            permitted[before.id.toString()] = before.timestamp
            permitted[after.id.toString()] = after.timestamp
            assertTimestampsPreserved("android-sensor-data", permitted,
                persistedEventTimestamps(table, "sample_id", SHARED_STUDY_ID, participantId))
        }
    }

    @Test
    fun `android sensor buffer rows drain transactionally`() {
        val participantId = participantFor("android-sensor-data-legacy-buffer")
        val randomDeviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        val samples = decodeList(validFixture("android-sensor-data"), AndroidSensorSample::class.java)
            .mapIndexed { index, sample ->
                sample.copy(id = UUID.nameUUIDFromBytes("legacy-android-sensor-$index".toByteArray()))
            }
        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO upload_buffer
                    (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
                VALUES (?, ?, ?::jsonb, now(), 'AndroidSensor', ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setString(3, mapper.writeValueAsString(samples))
                ps.setObject(4, randomDeviceId)
                assertEquals(1, ps.executeUpdate())

                ps.setString(3, "{\"not\":\"a sensor sample list\"}")
                assertEquals(1, ps.executeUpdate())
            }
        }

        FixtureDrivenAndroidSensorMover(moverDependencies).runTask()

        assertEquals("malformed data must be retained outside the retry queue", 1, bufferCount(SHARED_STUDY_ID, participantId))
        assertEquals(
            samples.size,
            scopedCount("android_sensor_data", SHARED_STUDY_ID, participantId),
        )
        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM upload_buffer
                WHERE study_id = ? AND participant_id = ? AND upload_type = 'AndroidSensorRejected'
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertEquals("one poison row must be quarantined exactly once", 1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `android sensor buffer drain keeps the server receipt of the buffer row`() {
        val participantId = participantFor("android-sensor-data-receipt")
        val randomDeviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d2")
        val received = OffsetDateTime.now().minusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val samples = decodeList(validFixture("android-sensor-data"), AndroidSensorSample::class.java)
            .mapIndexed { index, sample -> sample.copy(id = UUID.nameUUIDFromBytes("receipt-android-sensor-$index".toByteArray())) }
        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
                VALUES (?, ?, ?::jsonb, ?, 'AndroidSensor', ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setString(3, mapper.writeValueAsString(samples))
                ps.setObject(4, received)
                ps.setObject(5, randomDeviceId)
                assertEquals(1, ps.executeUpdate())
            }
        }

        FixtureDrivenAndroidSensorMover(moverDependencies).runTask()

        hds().connection.use { connection ->
            connection.prepareStatement(
                "SELECT DISTINCT uploaded_at FROM android_sensor_data WHERE study_id = ? AND participant_id = ?",
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    val receipts = generateSequence { if (rs.next()) rs.getObject(1, OffsetDateTime::class.java).toInstant() else null }
                    assertEquals("the drain must carry the buffer receipt, not stamp the move time",
                        listOf(received.toInstant()), receipts.toList())
                }
            }
        }
    }

    @Test
    fun `buffer claim is bounded by rows rather than participant pairs`() {
        val participantId = participantFor("android-sensor-data-bounded-drain")
        hds().connection.use { connection ->
            connection.autoCommit = false
            try {
                insertSensorBufferRows(connection, participantId, 5)
                val claimed = connection.createStatement().use { statement ->
                    val rs = statement.executeQuery(ChroniclePostgresTables.getMoveSql(2, UploadType.AndroidSensor))
                    generateSequence { rs.takeIf { it.next() } }.count() // closed with its statement
                }
                assertEquals("move SQL must claim no more than its row limit", 2, claimed)
                assertEquals("three rows from the same participant must remain", 3, bufferRows(connection, participantId))
            } finally {
                connection.rollback()
            }
        }
    }

    private fun insertSensorBufferRows(connection: java.sql.Connection, participantId: String, rows: Int) {
        connection.prepareStatement(
            """
            INSERT INTO upload_buffer
                (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
            VALUES (?, ?, '[]'::jsonb, '2000-01-01T00:00:00Z', 'AndroidSensor', ?)
            """.trimIndent(),
        ).use { ps ->
            repeat(rows) {
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setObject(3, UUID.randomUUID())
                ps.addBatch()
            }
            assertEquals(rows, ps.executeBatch().sum())
        }
    }

    private fun bufferRows(connection: java.sql.Connection, participantId: String): Int =
        connection.prepareStatement("SELECT count(*) FROM upload_buffer WHERE study_id = ? AND participant_id = ?").use { ps ->
            ps.setObject(1, SHARED_STUDY_ID)
            ps.setString(2, participantId)
            val rs = ps.executeQuery() // closed with its statement
            rs.next()
            rs.getInt(1)
        }

    @Test
    fun `screen-time-usage fixtures ingest through ScreenTimeUsageUploadService`() {
        val registryEntry = family("screen-time-usage")
        val service = ScreenTimeUsageUploadService(storageResolver)
        assertEquals(
            "screen-time-usage: registry backendHandler drifted",
            service::class.java.simpleName,
            registryEntry.backendHandler,
        )
        val table = registryEntry.backendTable

        // The envelope DTO lives in chronicle-server (no chronicle-models jvmClass);
        // the fixture must decode with the server's hardened upload mapper.
        val valid = validFixture("screen-time-usage")
        val envelope = mapper.readValue(valid, ScreenTimeUsageEnvelope::class.java)
        val studyId = UUID.fromString(envelope.studyId)
        val participantId = envelope.participantId
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, envelope.randomDeviceId)

        fun ingest(candidate: ScreenTimeUsageEnvelope = envelope): Int =
            service.upload(studyId, participantId, randomDeviceId, candidate)

        // 1) Valid ingestion end to end: one row per record, every wire field kept.
        assertEquals(envelope.records.size, ingest())
        assertEquals(envelope.records.associate { it.id to it.capturedAt.toInstant() }, storedCaptures(table, studyId, participantId))

        // 2) Idempotency: the capture key admits a retried envelope once.
        ingest()
        assertEquals(
            "screen-time-usage: re-ingesting the same envelope must not create duplicate rows",
            envelope.records.size,
            scopedCount(table, studyId, participantId),
        )

        // 3) A later capture of the same Apple window is a new row, even when every
        // entity key is unchanged. Replaying that exact capture is still idempotent.
        val directRecord = envelope.records.single { it.rowKind == ScreenTimeUsageRowKind.application }
        val laterCapture = envelope.copy(
            generatedAt = envelope.generatedAt.plusMinutes(15),
            records = listOf(
                directRecord.copy(
                    id = UUID.randomUUID(),
                    capturedAt = directRecord.capturedAt.plusMinutes(15),
                    durationSeconds = directRecord.durationSeconds + 60,
                    notificationCount = directRecord.notificationCount?.plus(1),
                    pickupCount = directRecord.pickupCount?.plus(1),
                )
            ),
        )
        assertEquals(1, ingest(laterCapture))
        assertEquals(envelope.records.size + 1, scopedCount(table, studyId, participantId))
        assertEquals(1, ingest(laterCapture.copy(records = laterCapture.records.map { it.copy(id = UUID.randomUUID()) })))
        assertEquals(
            "screen-time-usage: retrying one capture must not duplicate that row",
            envelope.records.size + 1,
            scopedCount(table, studyId, participantId),
        )

        assertDirectRowDelta(studyId, participantId, directRecord)
    }

    /** The delta view keeps the newest of the app window's two captures; the web-domain row has its own delta. */
    private fun assertDirectRowDelta(studyId: UUID, participantId: String, directRecord: ScreenTimeUsageRecord) {
        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT row_kind, usage_delta_seconds, notification_delta_count, pickup_delta_count,
                       interval_start_utc, interval_end_utc, delta_status
                FROM screen_time_usage_deltas
                WHERE study_id = ? AND participant_id = ?
                ORDER BY row_kind
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows ->
                    assertTrue("screen-time-usage: delta projection should contain the app row", rows.next())
                    assertEquals("application", rows.getString("row_kind"))
                    assertEquals(960.0, rows.getDouble("usage_delta_seconds"), 0.001)
                    assertEquals(4, rows.getInt("notification_delta_count"))
                    assertEquals(6, rows.getInt("pickup_delta_count"))
                    assertEquals(
                        directRecord.observationStart.toInstant(),
                        rows.getObject("interval_start_utc", OffsetDateTime::class.java).toInstant(),
                    )
                    assertEquals(
                        directRecord.observationEnd.toInstant(),
                        rows.getObject("interval_end_utc", OffsetDateTime::class.java).toInstant(),
                    )
                    assertEquals("ok", rows.getString("delta_status"))
                    assertTrue("screen-time-usage: delta projection should contain the web-domain row", rows.next())
                    assertEquals("webDomain", rows.getString("row_kind"))
                    assertEquals(300.0, rows.getDouble("usage_delta_seconds"), 0.001)
                    assertTrue("screen-time-usage: one delta row per app or domain window", !rows.next())
                }
            }
        }
    }

    /** record id -> captured_at of every stored row of one participant. */
    private fun storedCaptures(table: String, studyId: UUID, participantId: String): Map<UUID, java.time.Instant> =
        hds().connection.use { connection ->
            connection.prepareStatement("SELECT record_id, captured_at FROM $table WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, studyId); it.setString(2, participantId)
                it.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getObject(1, UUID::class.java), rows.getObject(2, OffsetDateTime::class.java).toInstant())
                        }
                    }
                }
            }
        }
}

/** Production Android sensor mover with its dependencies pinned for the test. */
private class FixtureDrivenAndroidSensorMover(
    private val deps: MoveToEventStorageTaskDependencies,
) : MoveAndroidSensorDataToStorageTask() {
    override fun getDependency(): MoveToEventStorageTaskDependencies = deps
}
