package com.openlattice.chronicle.contract

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.map.IMap
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.collection.AmbientAudioClassificationEvent
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
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.fixtures.FixtureFamily
import com.openlattice.chronicle.fixtures.FixtureRegistry
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.services.upload.AmbientAudioUploadService
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.upload.EncryptedPayloadUploadService
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.ScreenTimeCaptureSource
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.services.upload.UserIdentificationEnvelope
import com.openlattice.chronicle.services.upload.UserIdentificationUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.MoveAndroidSensorDataToStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTaskDependencies
import com.openlattice.chronicle.storage.tasks.MoveToIosEventStorageTask
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.DeviceIdUtils
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

// =============================================================================
// Tranche 6, fixture-ingestion half (docs/shared-contracts/
// 06-web-backend-db-alignment.md §Backend checks, 07-proof-and-testing-matrix.md
// "Backend ingestion" row — chronicle-models repo).
//
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
//      sample_id PK for android_sensor_data, and the iOS mover's duplicate-merge
//      pass for sensor_data);
//   4. every invalid-*.json fixture is REJECTED by the ingestion path (Jackson
//      decode -> DTO init require(...) validation) and persists nothing.
//
// encrypted-payloads is the deliberate release-gate exception: its DTO still decodes, but V95
// proves that the service/database reject ciphertext until it can be decrypted into the complete
// participant export contract. Android sensor samples are committed directly to their final table. The iOS
// SensorKit and screen-time families retain their upload-buffer mover path.
// A separate Android legacy-buffer test proves pre-upgrade rows still drain.
//
// ios-device is the one registry family not exercised here: its backendHandler is
// EnrollmentService (device registration), not a payload upload service; it is
// covered by the enrollment-surface tests, not the payload-ingestion matrix.
// =============================================================================
class PayloadFixtureIngestionTest {

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
            val hikariProps = Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "5")
            }
            val pgConfig = PostgresConfiguration(
                hikariConfiguration = hikariProps,
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false,
            )
            dataSourceManager = DataSourceManager(
                mapOf("default" to pgConfig, "chronicle" to pgConfig, "platform_read" to pgConfig),
                HealthCheckRegistry(),
                MetricRegistry(),
            )
            storageResolver = StorageResolver(dataSourceManager, ChronicleStorageConfiguration())

            // The study->storage IMap normally comes from Hazelcast; a mock whose
            // executeOnKey returns null makes resolveAndGetFlavor fall back to the
            // default event storage, which is what production does for unmapped studies.
            val studyStorage = mock<IMap<UUID, Study>>()
            StorageResolver::class.java.getDeclaredField("studyStorage").apply {
                isAccessible = true
                set(storageResolver, studyStorage)
            }

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
            val iosEnvelopes = listOf("screen-time-usage", "user-identification").map { family ->
                mapper.readTree(validFixture(family))
            }

            storageResolver.getPlatformStorage().connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO studies (study_id, title) VALUES (?, ?) ON CONFLICT DO NOTHING",
                ).use { ps ->
                    for ((studyId, title) in listOf(
                        SHARED_STUDY_ID to "Fixture Ingestion Study",
                        UUID.fromString(iosEnvelopes[0].path("studyId").asText()) to "Screen Time Fixture Study",
                        UUID.fromString(iosEnvelopes[1].path("studyId").asText()) to "User Identification Fixture Study",
                    )) {
                        ps.setObject(1, studyId)
                        ps.setString(2, title)
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
                        ps.setObject(3, UUID.randomUUID())
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
        assertDirectFamilyContract("battery-telemetry", service::class.java, "sample_id") { s, p, f ->
            service.upload(s, p, decodeList(f, BatterySample::class.java))
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
    fun `ambient-audio fixtures ingest through AmbientAudioUploadService`() {
        val service = AmbientAudioUploadService(storageResolver)
        assertDirectFamilyContract("ambient-audio", service::class.java, "event_id") { s, p, f ->
            service.upload(s, p, decodeList(f, AmbientAudioClassificationEvent::class.java))
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
            service.upload(s, p, decodeList(f, AndroidHealthMetricEvent::class.java))
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
    // encrypted-payloads: EncryptedPayloadUploadService -> encrypted_payloads
    // -------------------------------------------------------------------------

    @Test
    fun `encrypted-payloads fixtures stay blocked until they have a complete export path`() {
        val registryEntry = family("encrypted-payloads")
        val service = EncryptedPayloadUploadService(storageResolver)
        assertEquals(
            "encrypted-payloads: registry backendHandler drifted",
            service::class.java.simpleName,
            registryEntry.backendHandler,
        )
        val participantId = participantFor("encrypted-payloads")
        val deviceId = UUID.fromString("00000000-0000-4000-8000-0000000000e0")

        fun ingest(file: File): Int = service.upload(
            SHARED_STUDY_ID,
            participantId,
            deviceId,
            listOf(mapper.readValue(file, EncryptedEnvelope::class.java)),
        )

        // V95 is the release boundary: accepting ciphertext before OWNER-gated decryption is
        // integrated with participant exports would silently strand study data.
        assertThrows(SQLException::class.java) { ingest(validFixture("encrypted-payloads")) }
        assertEquals(
            "encrypted-payloads: release gate must persist no ciphertext",
            0,
            scopedCount(registryEntry.backendTable, SHARED_STUDY_ID, participantId),
        )

        // Malformed envelopes remain rejected before the database boundary as well.
        for (invalid in invalidFixtures("encrypted-payloads")) {
            assertIngestionRejects("encrypted-payloads", invalid, ::ingest)
            assertEquals(
                "encrypted-payloads: rejected ${invalid.name} must persist nothing",
                0,
                scopedCount(registryEntry.backendTable, SHARED_STUDY_ID, participantId),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Direct Android sensor ingestion and legacy-buffer compatibility
    // -------------------------------------------------------------------------

    private fun bufferCount(studyId: UUID, participantId: String): Int =
        scopedCount(ChroniclePostgresTables.UPLOAD_BUFFER.name, studyId, participantId)

    /**
     * sensor_data stores study_id as TEXT_UUID (varchar) — the iOS mover binds it with
     * setString — so its scope queries must bind text, unlike the uuid-typed tables.
     */
    private fun sensorDataCount(table: String, studyId: UUID, participantId: String): Int =
        hds().connection.use { c ->
            c.prepareStatement(
                "SELECT count(*) FROM $table WHERE study_id = ? AND participant_id = ?",
            ).use { ps ->
                ps.setString(1, studyId.toString())
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
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
        val deviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d0")
        val valid = validFixture("android-sensor-data")
        val expected = fixtureEventTimestamps(valid)

        fun ingest(file: File): Int =
            service.upload(SHARED_STUDY_ID, participantId, deviceId, decodeList(file, AndroidSensorSample::class.java))

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
                "SELECT count(*) FROM $table WHERE study_id = ? AND participant_id = ? AND device_id = ?",
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setObject(3, deviceId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(
                        "android-sensor-data: rows must carry the uploading device id",
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
    }

    @Test
    fun `legacy android sensor buffer rows still drain transactionally`() {
        val participantId = participantFor("android-sensor-data-legacy-buffer")
        val deviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        val samples = decodeList(validFixture("android-sensor-data"), AndroidSensorSample::class.java)
            .mapIndexed { index, sample ->
                sample.copy(id = UUID.nameUUIDFromBytes("legacy-android-sensor-$index".toByteArray()))
            }
        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO upload_buffer
                    (study_id, participant_id, data, uploaded_at, upload_type, device_id)
                VALUES (?, ?, ?::jsonb, now(), 'AndroidSensor', ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, SHARED_STUDY_ID)
                ps.setString(2, participantId)
                ps.setString(3, mapper.writeValueAsString(samples))
                ps.setObject(4, deviceId)
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
    fun `legacy buffer claim is bounded by rows rather than participant pairs`() {
        val participantId = participantFor("android-sensor-data-bounded-drain")
        hds().connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO upload_buffer
                        (study_id, participant_id, data, uploaded_at, upload_type, device_id)
                    VALUES (?, ?, '[]'::jsonb, '2000-01-01T00:00:00Z', 'AndroidSensor', ?)
                    """.trimIndent(),
                ).use { ps ->
                    repeat(5) {
                        ps.setObject(1, SHARED_STUDY_ID)
                        ps.setString(2, participantId)
                        ps.setObject(3, UUID.randomUUID())
                        ps.addBatch()
                    }
                    assertEquals(5, ps.executeBatch().sum())
                }

                val claimed = connection.createStatement().use { statement ->
                    statement.executeQuery(ChroniclePostgresTables.getMoveSql(2, UploadType.AndroidSensor)).use { rs ->
                        var rows = 0
                        while (rs.next()) rows++
                        rows
                    }
                }
                assertEquals("move SQL must claim no more than its row limit", 2, claimed)

                connection.prepareStatement(
                    "SELECT count(*) FROM upload_buffer WHERE study_id = ? AND participant_id = ?",
                ).use { ps ->
                    ps.setObject(1, SHARED_STUDY_ID)
                    ps.setString(2, participantId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        assertEquals("three rows from the same participant must remain", 3, rs.getInt(1))
                    }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `ios-sensorkit-data fixtures ingest through SensorDataUploadService and the ios mover`() {
        val registryEntry = family("ios-sensorkit-data")
        val service = SensorDataUploadService(storageResolver, mock<StudyService>())
        assertEquals(
            "ios-sensorkit-data: registry backendHandler drifted",
            service::class.java.simpleName,
            registryEntry.backendHandler,
        )
        val table = registryEntry.backendTable
        val participantId = participantFor("ios-sensorkit-data")
        val deviceId = UUID.fromString("00000000-0000-4000-8000-0000000000d2")
        val mover = FixtureDrivenIosMover(moverDependencies)
        val valid = validFixture("ios-sensorkit-data")
        val samples = decodeList(valid, SensorDataSample::class.java)

        // The iOS mover FLATTENS each deviceUsage sample into one sensor_data row per
        // (appUsage entry x textInputSession) plus one row per webUsage-only category
        // (MoveToIosEventStorageTask.mapDeviceUsageData). Derive the expected row count
        // from the fixture's nested data payload so the fixture stays the source of truth.
        val usageData = mapper.readTree(samples.single().data)
        val appCategories = usageData.path("appUsage").fieldNames().asSequence().toSet()
        val webCategories = usageData.path("webUsage").fieldNames().asSequence().toSet()
        val appRows = usageData.path("appUsage").sumOf { entries ->
            entries.sumOf { entry -> maxOf(1, entry.path("textInputSessions").size()) }
        }
        val expectedRows = appRows + (webCategories - appCategories).size
        assertEquals(
            "ios-sensorkit-data: canonical fixture flattening drifted (recheck mapDeviceUsageData)",
            2,
            expectedRows,
        )

        fun ingest(file: File): Int =
            service.upload(SHARED_STUDY_ID, participantId, deviceId, decodeList(file, SensorDataSample::class.java))

        // 1) Valid ingestion end to end.
        assertEquals(samples.size, ingest(valid))
        assertEquals(1, bufferCount(SHARED_STUDY_ID, participantId))
        mover.runTask()
        assertEquals("ios-sensorkit-data: mover must drain the buffer", 0, bufferCount(SHARED_STUDY_ID, participantId))
        assertSensorDataRows(
            family = "ios-sensorkit-data",
            table = table,
            studyId = SHARED_STUDY_ID,
            participantId = participantId,
            expectedCount = expectedRows,
            // Every flattened row keeps the sample's shared columns, so all rows carry
            // the single fixture sample id and its exact timestamps.
            expectedDistinctSampleIds = samples.size,
            expectedStart = samples.single().startDate,
            expectedEnd = samples.single().endDate,
            expectedExactRecorded = samples.single().dateRecorded,
            expectedSampleId = samples.single().id,
        )

        // 2) Idempotency: the iOS duplicate-merge pass collapses the re-ingest.
        ingest(valid)
        mover.runTask()
        assertEquals(
            "ios-sensorkit-data: the iOS dedup pass must collapse duplicate samples",
            expectedRows,
            sensorDataCount(table, SHARED_STUDY_ID, participantId),
        )

        // 3) Malformed fixture (missing sensor) rejected at decode; nothing staged.
        for (invalid in invalidFixtures("ios-sensorkit-data")) {
            assertIngestionRejects("ios-sensorkit-data", invalid, ::ingest)
            assertEquals(0, bufferCount(SHARED_STUDY_ID, participantId))
            assertEquals(expectedRows, sensorDataCount(table, SHARED_STUDY_ID, participantId))
        }
    }

    @Test
    fun `screen-time-usage fixtures ingest through ScreenTimeUsageUploadService and the ios mover`() {
        val registryEntry = family("screen-time-usage")
        assertEquals(
            "screen-time-usage: registry backendHandler drifted",
            ScreenTimeUsageUploadService::class.java.simpleName,
            registryEntry.backendHandler,
        )
        val table = registryEntry.backendTable

        // The envelope DTO lives in chronicle-server (no chronicle-models jvmClass);
        // the fixture must decode with the server's hardened upload mapper.
        val valid = validFixture("screen-time-usage")
        val envelope = mapper.readValue(valid, ScreenTimeUsageEnvelope::class.java)
        val studyId = UUID.fromString(envelope.studyId)
        val participantId = envelope.participantId
        val sourceDeviceId = envelope.deviceId
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val sensorService = SensorDataUploadService(storageResolver, mock<StudyService>())
        val mover = FixtureDrivenIosMover(moverDependencies)

        fun ingest(candidate: ScreenTimeUsageEnvelope = envelope): Int {
            val samples = ScreenTimeUsageUploadService.toSensorDataSamples(candidate, sourceDeviceId)
            assertEquals(
                "screen-time-usage: every record in the canonical valid fixture must be ingestible " +
                    "(none may be dropped as category-summary/threshold rows)",
                candidate.records.size,
                samples.size,
            )
            return sensorService.upload(studyId, participantId, deviceId, samples)
        }

        // 1) Valid ingestion end to end (controller path: envelope -> samples -> buffer -> mover).
        assertEquals(envelope.records.size, ingest())
        assertEquals(1, bufferCount(studyId, participantId))
        mover.runTask()
        assertEquals("screen-time-usage: mover must drain the buffer", 0, bufferCount(studyId, participantId))

        // All fixture records share the same observation window and capture time. Each
        // screen-time record maps to exactly one flattened sensor_data row (one app
        // category without text-input sessions, or one web domain).
        val record = envelope.records.first()
        assertSensorDataRows(
            family = "screen-time-usage",
            table = table,
            studyId = studyId,
            participantId = participantId,
            expectedCount = envelope.records.size,
            expectedDistinctSampleIds = envelope.records.size,
            expectedStart = record.observationStart,
            expectedEnd = record.observationEnd,
            expectedExactRecorded = record.capturedAt,
            // The reportSummary record keeps its wire id; the deviceActivityExport record's
            // sample id is the deterministic SHA-256 UUID, asserted stable via idempotency.
            expectedSampleId = envelope.records.first { it.source == ScreenTimeCaptureSource.reportSummary }.id,
        )

        // 2) Idempotency: deterministic sample ids + the iOS dedup pass -> no duplicates.
        ingest()
        mover.runTask()
        assertEquals(
            "screen-time-usage: re-ingesting the same envelope must not create duplicate rows",
            envelope.records.size,
            sensorDataCount(table, studyId, participantId),
        )

        // 3) A later capture of the same Apple bucket is a new raw snapshot, even when
        // every entity key is unchanged. Replaying that exact capture is still idempotent.
        val directRecord = envelope.records.single { it.source == ScreenTimeCaptureSource.deviceActivityExport }
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
        mover.runTask()
        assertEquals(envelope.records.size + 1, sensorDataCount(table, studyId, participantId))

        assertEquals(1, ingest(laterCapture))
        mover.runTask()
        assertEquals(
            "screen-time-usage: retrying one capture must not duplicate that raw snapshot",
            envelope.records.size + 1,
            sensorDataCount(table, studyId, participantId),
        )

        hds().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT usage_delta_seconds, notification_delta_count, pickup_delta_count,
                       interval_start_utc, interval_end_utc, delta_status
                FROM screen_time_usage_deltas
                WHERE study_id = ? AND participant_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, studyId.toString())
                statement.setString(2, participantId)
                statement.executeQuery().use { rows ->
                    assertTrue("screen-time-usage: delta projection should contain the direct row", rows.next())
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
                    assertTrue("screen-time-usage: report summaries must stay out of the direct view", !rows.next())
                }
            }
        }
    }

    @Test
    fun `user-identification fixtures ingest through UserIdentificationUploadService and the ios mover`() {
        val registryEntry = family("user-identification")
        assertEquals(UserIdentificationUploadService::class.java.simpleName, registryEntry.backendHandler)
        val valid = validFixture("user-identification")
        val envelope = mapper.readValue(valid, UserIdentificationEnvelope::class.java)
        val studyId = UUID.fromString(envelope.studyId)
        val participantId = envelope.participantId
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, envelope.deviceId)
        val sensorService = SensorDataUploadService(storageResolver, mock<StudyService>())
        val mover = FixtureDrivenIosMover(moverDependencies)

        fun ingest(): Int = sensorService.upload(
            studyId,
            participantId,
            deviceId,
            UserIdentificationUploadService.toSensorDataSamples(envelope, envelope.deviceId),
        )

        assertEquals(envelope.records.size, ingest())
        mover.runTask()
        assertEquals(0, bufferCount(studyId, participantId))
        val record = envelope.records.single()
        assertSensorDataRows(
            family = "user-identification",
            table = registryEntry.backendTable,
            studyId = studyId,
            participantId = participantId,
            expectedCount = 1,
            expectedDistinctSampleIds = 1,
            expectedStart = record.capturedAt,
            expectedEnd = record.capturedAt.plusSeconds(1),
            expectedExactRecorded = record.capturedAt,
            expectedSampleId = record.id,
        )

        ingest()
        mover.runTask()
        assertEquals(0, bufferCount(studyId, participantId))
        assertEquals(1, sensorDataCount(registryEntry.backendTable, studyId, participantId))

        for (invalid in invalidFixtures("user-identification")) {
            assertIngestionRejects("user-identification", invalid) { fixture ->
                mapper.readValue(fixture, UserIdentificationEnvelope::class.java)
                0
            }
            assertEquals(1, sensorDataCount(registryEntry.backendTable, studyId, participantId))
        }
    }

    /** Shared assertions for rows the iOS mover lands in sensor_data. */
    @Suppress("LongParameterList")
    private fun assertSensorDataRows(
        family: String,
        table: String,
        studyId: UUID,
        participantId: String,
        expectedCount: Int,
        expectedDistinctSampleIds: Int,
        expectedStart: OffsetDateTime,
        expectedEnd: OffsetDateTime,
        expectedExactRecorded: OffsetDateTime,
        expectedSampleId: UUID,
    ) {
        hds().connection.use { c ->
            c.prepareStatement(
                """
                SELECT sample_id, sensor_type, datetimestart, datetimeend, exact_recordeddate, recordeddate
                FROM $table WHERE study_id = ? AND participant_id = ?
                """.trimIndent(),
            ).use { ps ->
                // sensor_data study_id is TEXT_UUID (varchar): bind text like the mover does.
                ps.setString(1, studyId.toString())
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    val sampleIds = mutableSetOf<String>()
                    var rows = 0
                    while (rs.next()) {
                        rows++
                        sampleIds += rs.getString("sample_id")
                        assertEquals("$family: sensor_type", "deviceUsage", rs.getString("sensor_type"))
                        assertEquals(
                            "$family: datetimestart (observation start) not preserved",
                            expectedStart.toInstant(),
                            rs.getObject("datetimestart", OffsetDateTime::class.java).toInstant(),
                        )
                        assertEquals(
                            "$family: datetimeend (observation end) not preserved",
                            expectedEnd.toInstant(),
                            rs.getObject("datetimeend", OffsetDateTime::class.java).toInstant(),
                        )
                        assertEquals(
                            "$family: exact_recordeddate (capture time) not preserved",
                            expectedExactRecorded.toInstant(),
                            rs.getObject("exact_recordeddate", OffsetDateTime::class.java).toInstant(),
                        )
                        assertNotNull(
                            "$family: recordeddate must be populated",
                            rs.getObject("recordeddate", OffsetDateTime::class.java),
                        )
                    }
                    assertEquals("$family: persisted row count", expectedCount, rows)
                    assertEquals("$family: distinct sample ids", expectedDistinctSampleIds, sampleIds.size)
                    assertTrue(
                        "$family: fixture sample id $expectedSampleId must survive to sensor_data " +
                            "(persisted: $sampleIds)",
                        expectedSampleId.toString() in sampleIds,
                    )
                }
            }
        }
    }
}

/** Production iOS mover with its Hazelcast-provided dependencies pinned for the test. */
private class FixtureDrivenIosMover(
    private val deps: MoveToEventStorageTaskDependencies,
) : MoveToIosEventStorageTask() {
    override fun getDependency(): MoveToEventStorageTaskDependencies = deps
}

/** Production Android sensor mover with its dependencies pinned for the test. */
private class FixtureDrivenAndroidSensorMover(
    private val deps: MoveToEventStorageTaskDependencies,
) : MoveAndroidSensorDataToStorageTask() {
    override fun getDependency(): MoveToEventStorageTaskDependencies = deps
}
