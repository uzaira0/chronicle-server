package com.openlattice.chronicle.storage.tasks

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.InvocationTargetException
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

/** Real upload/drain behavior against the production schema and complete Flyway corpus. */
class IosSensorPoisonedUploadTest {
    private val testStudies = mutableListOf<UUID>()

    companion object {
        private lateinit var manager: DataSourceManager
        private lateinit var storage: StorageResolver

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val postgres = ChronicleContractTestSchema.sharedPostgres
            val configuration = PostgresConfiguration(
                hikariConfiguration = Properties().apply {
                    setProperty("jdbcUrl", postgres.jdbcUrl)
                    setProperty("username", postgres.username)
                    setProperty("password", postgres.password)
                    setProperty("maximumPoolSize", "5")
                },
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false,
            )
            manager = DataSourceManager(
                mapOf("default" to configuration, "chronicle" to configuration),
                HealthCheckRegistry(),
                MetricRegistry(),
            )
            storage = StorageResolver(manager, ChronicleStorageConfiguration())
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            manager.dataSources.values.forEach { it.close() }
        }
    }

    @After
    fun removeTestPayloads() {
        storage.getPlatformStorage().connection.use { connection ->
            listOf("upload_buffer" to "?", "sensor_data" to "?::text").forEach { (table, parameter) ->
                connection.prepareStatement("DELETE FROM $table WHERE study_id = $parameter").use { statement ->
                    testStudies.forEach { study ->
                        statement.setObject(1, study)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    @Test
    fun acknowledgesValidSamplesThatTheDrainCanMaterialize() {
        val study = seedParticipant()
        val service = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        assertEquals(1, service.upload(study, "ios-poison-test", UUID.randomUUID(), listOf(sample())))
        assertEquals(1, count(study, "upload_buffer"))
        drain()
        assertEquals(0, count(study, "upload_buffer"))
        assertEquals(1, count(study, "sensor_data"))
    }

    @Test
    fun rejectsUnmaterializableNestedPayloadsBeforeAcknowledgment() {
        val study = seedParticipant()
        val service = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        invalidSamples().forEach { sample ->
            assertThrows("Rejected ${sample.sensor} sample must never be acknowledged", IllegalArgumentException::class.java) {
                service.upload(study, "ios-poison-test", UUID.randomUUID(), listOf(sample))
            }
        }
        assertEquals(0, count(study, "upload_buffer"))
    }

    @Test
    fun rejectsEntireNewMixedBatchBeforeWritingTheBuffer() {
        val study = seedParticipant()
        val service = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            service.upload(study, "ios-poison-test", UUID.randomUUID(), listOf(sample(), sample().copy(data = "not-json")))
        }
        assertEquals(0, count(study, "upload_buffer"))
    }

    @Test
    fun materializesHealthySamplesFromTheSameLegacyRowAsMalformedSamples() {
        val study = seedParticipant()
        val good = sample()
        val bad = sample().copy(data = "not-json")
        buffer(study, UUID.randomUUID(), SensorDataUploadService.mapper.writeValueAsString(listOf(good, bad)),
            OffsetDateTime.now())
        drain()
        assertEquals(1, count(study, "sensor_data"))
        assertEquals(1, count(study, "upload_buffer", "AND upload_type = 'IosSensorRejected'"))
        val diagnostic = readRejectedDiagnostic(study)
        assertEquals(SensorDataUploadService.mapper.valueToTree<JsonNode>(listOf(bad)), diagnostic)
        drain()
        assertEquals(1, count(study, "sensor_data"))
    }

    @Test
    fun quarantinesIndividualLegacyRowsAndDrainsHealthyRowsIdempotently() {
        val poisonedStudy = seedParticipant()
        val healthyStudy = seedParticipant()
        val device = UUID.randomUUID()
        val uploaded = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val invalidRawRows = invalidSamples().map { SensorDataUploadService.mapper.writeValueAsString(listOf(it)) } +
            listOf("{}", "null", "[null]")
        invalidRawRows.forEach { buffer(poisonedStudy, device, it, uploaded) }
        buffer(healthyStudy, device, SensorDataUploadService.mapper.writeValueAsString(listOf(sample())), uploaded.plusDays(1))

        drain()

        assertEquals(1, count(healthyStudy, "sensor_data"))
        assertEquals(0, count(healthyStudy, "upload_buffer"))
        assertEquals(0, count(poisonedStudy, "sensor_data"))
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT ${PostgresColumns.UPLOAD_DATA.name}::text, uploaded_at, device_id, upload_type FROM upload_buffer WHERE study_id = ?",
            ).use { statement ->
                statement.setObject(1, poisonedStudy)
                statement.executeQuery().use { rows ->
                    val retained = mutableListOf<String>()
                    while (rows.next()) {
                        retained += rows.getString(1)
                        assertEquals(uploaded.toInstant(), rows.getObject(2, OffsetDateTime::class.java).toInstant())
                        assertEquals(device, rows.getObject(3, UUID::class.java))
                        assertEquals("IosSensorRejected", rows.getString(4))
                    }
                    assertEquals(invalidRawRows.size, retained.size)
                    assertEquals(
                        invalidRawRows.map { SensorDataUploadService.mapper.readTree(it) }.toSet(),
                        retained.map { SensorDataUploadService.mapper.readTree(it) }.toSet(),
                    )
                }
            }
        }
        drain()
        assertEquals(1, count(healthyStudy, "sensor_data"))
        assertEquals(invalidRawRows.size, count(poisonedStudy, "upload_buffer"))
    }

    @Test
    fun oldestPoisonedFullBatchCannotStarveAnotherStudy() {
        val poisonedStudy = seedParticipant()
        val healthyStudy = seedParticipant()
        val uploaded = OffsetDateTime.parse("2026-08-01T00:00:00Z")
        val raw = SensorDataUploadService.mapper.writeValueAsString(listOf(sample().copy(data = "not-json")))
        repeat(128) { buffer(poisonedStudy, UUID.randomUUID(), raw, uploaded) }
        buffer(healthyStudy, UUID.randomUUID(), SensorDataUploadService.mapper.writeValueAsString(listOf(sample())), uploaded.plusDays(1))

        drain()
        drain()

        assertEquals(1, count(healthyStudy, "sensor_data"))
        assertEquals(0, count(healthyStudy, "upload_buffer"))
        assertEquals(128, count(poisonedStudy, "upload_buffer"))
    }

    @Test
    fun storageFailureRollsBackTheClaimAndQuarantine() {
        val study = seedParticipant()
        val uploaded = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        buffer(study, UUID.randomUUID(), SensorDataUploadService.mapper.writeValueAsString(listOf(sample().copy(data = "not-json"))), uploaded)
        buffer(study, UUID.randomUUID(), SensorDataUploadService.mapper.writeValueAsString(listOf(sample())), uploaded.plusSeconds(1))
        val failingStorage = Mockito.mock(StorageResolver::class.java)
        val failingEvents = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(failingStorage.getPlatformStorage()).thenReturn(storage.getPlatformStorage())
        Mockito.`when`(failingStorage.resolveAndGetFlavor(study))
            .thenReturn(PostgresFlavor.VANILLA to failingEvents)
        Mockito.`when`(failingStorage.getEventStorageWithFlavor(PostgresFlavor.VANILLA)).thenReturn(failingEvents)
        Mockito.`when`(failingEvents.connection).thenThrow(SQLException("simulated storage failure"))

        assertThrows(SQLException::class.java) { drain(failingStorage) }

        assertEquals(2, count(study, "upload_buffer", "AND upload_type = 'Ios'"))
        assertEquals(0, count(study, "sensor_data"))
        drain()
        assertEquals(1, count(study, "sensor_data"))
        assertEquals(1, count(study, "upload_buffer", "AND upload_type = 'IosSensorRejected'"))
    }

    @Test
    fun acknowledgesOldOnlyAndMixedIosRetryAfterPurge() {
        val study = seedParticipant()
        val erased = eraseDrainedSamples(study)
        val service = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        val fresh = sample().copy(dateRecorded = OffsetDateTime.now().plusDays(1))
        val newest = sample().copy(dateRecorded = OffsetDateTime.now().plusDays(2), duration = 120.0)
        assertEquals(1, service.upload(study, "ios-poison-test", UUID.randomUUID(), listOf(erased.first())))
        assertEquals(0, count(study, "upload_buffer"))
        RLSRequestContext.withSystemContext {
            val source = storage.getPlatformStorage()
            source.connection.use { owner ->
                owner.autoCommit = false
                PinnedPlatformConnection.pinning(source, owner) {
                    assertEquals(3, service.upload(study, "ios-poison-test", UUID.randomUUID(),
                        listOf(erased.first(), fresh, newest)))
                    assertEquals(false, owner.autoCommit)
                    assertEquals(1, manager.dataSources.values.sumOf { it.hikariPoolMXBean?.activeConnections ?: 0 })
                }
                owner.commit()
            }
        }
        drain()
        assertEquals(2, count(study, "sensor_data"))
        assertEquals(0, count(study, "upload_buffer"))
    }

    @Test
    fun discardsOldMalformedLegacySamplesAndMaterializesGoodSamplesWhileQuarantiningOnlyFreshInvalidSamples() {
        val study = seedParticipant()
        val erased = eraseDrainedSamples(study).map { it.copy(data = "not-json", device = "not-json") }
        val fresh = sample().copy(dateRecorded = OffsetDateTime.now().plusDays(1))
        val invalid = sample().copy(data = "not-json", dateRecorded = OffsetDateTime.now().plusDays(2))
        buffer(study, UUID.randomUUID(), SensorDataUploadService.mapper.writeValueAsString(erased + fresh + invalid),
            OffsetDateTime.now())
        drain()
        assertEquals(1, count(study, "sensor_data"))
        assertEquals(1, count(study, "upload_buffer", "AND upload_type = 'IosSensorRejected'"))
        assertEquals(SensorDataUploadService.mapper.valueToTree<JsonNode>(listOf(invalid)), readRejectedDiagnostic(study))
        drain()
        assertEquals(1, count(study, "sensor_data"))
    }

    private fun eraseDrainedSamples(study: UUID): List<SensorDataSample> {
        val original = sample()
        val samples = listOf(original, original.copy(id = UUID.randomUUID()))
        val service = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        assertEquals(2, service.upload(study, "ios-poison-test", UUID.randomUUID(), samples))
        drain()
        assertEquals(1, count(study, "sensor_data"))
        purgeCollectedData(study)
        return samples
    }

    private fun purgeCollectedData(study: UUID) {
        val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
        val operation = orchestrator.quarantineParticipant(study, "ios-poison-test",
            DataDeletionMode.COLLECTED_DATA_PURGE, "ios-replay-test", UUID.randomUUID())
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operation)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertEquals(1, orchestrator.processDueOperations(limit = 1))
        assertEquals("COMPLETED", orchestrator.getOperation(operation).status)
        assertEquals(0, count(study, "sensor_data"))
        assertEquals(1, count(study, "participant_purge_cutoffs"))
    }

    private fun readRejectedDiagnostic(study: UUID): JsonNode =
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT data::text FROM upload_buffer WHERE study_id = ? AND upload_type = 'IosSensorRejected'",
            ).use { statement ->
                statement.setObject(1, study)
                statement.executeQuery().use { row ->
                    assertEquals(true, row.next())
                    val diagnostic = SensorDataUploadService.mapper.readTree(row.getString(1))
                    assertEquals(false, row.next())
                    diagnostic
                }
            }
        }

    private fun drain(resolver: StorageResolver = storage) {
        val dependencies = MoveToEventStorageTaskDependencies(resolver, Mockito.mock(StudyManager::class.java))
        val task = object : MoveToIosEventStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }
        // Invoke the transaction directly so a swallowed scheduler exception cannot make the test pass.
        RLSRequestContext.withSystemContext {
            try {
                MoveToIosEventStorageTask::class.java.getDeclaredMethod("moveToEventStorage").apply {
                    isAccessible = true
                }.invoke(task)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }

    private fun seedParticipant(): UUID {
        val study = UUID.randomUUID()
        testStudies += study
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'iOS poison regression')").use {
                it.setObject(1, study)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) " +
                    "VALUES (?, 'ios-poison-test', ?, 'ENROLLED')",
            ).use {
                it.setObject(1, study)
                it.setObject(2, UUID.randomUUID())
                it.executeUpdate()
            }
        }
        return study
    }

    private fun buffer(study: UUID, device: UUID, raw: String, uploaded: OffsetDateTime) {
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO upload_buffer (study_id, participant_id, ${PostgresColumns.UPLOAD_DATA.name}, uploaded_at, upload_type, device_id) " +
                    "VALUES (?, 'ios-poison-test', ?::jsonb, ?, 'Ios', ?)",
            ).use {
                it.setObject(1, study)
                it.setString(2, raw)
                it.setObject(3, uploaded)
                it.setObject(4, device)
                it.executeUpdate()
            }
        }
    }

    private fun count(study: UUID, table: String, extra: String = ""): Int {
        val parameter = if (table == "sensor_data") "?::text" else "?"
        return storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id = $parameter $extra").use {
                it.setObject(1, study)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    private fun invalidSamples(): List<SensorDataSample> = listOf(
        sample().copy(data = "not-json"),
        sample().copy(device = "not-json"),
        sample().copy(device = "null"),
        sample().copy(data = "null"),
        sample().copy(timezone = "Invalid/Timezone"),
        sample().copy(sensor = SensorType.accelerometer, data = "not-json"),
        sample().copy(sensor = SensorType.accelerometer, data = """{"schemaVersion":3,"encoding":"ieee754-binary64-xor-bytepack-zlib-base64","sampleCount":1,"provenance":"system_recorded","timeUnit":"seconds","uncompressedByteCount":1,"channels":[null],"payload":"eA=="}"""),
        sample().copy(sensor = SensorType.pedometer, data = """{"schemaVersion":2,"provenance":"os_buffered","numberOfSteps":1}"""),
        sample().copy(sensor = SensorType.deviceUsage, data = "not-json"),
        sample().copy(sensor = SensorType.deviceUsage, data = """{"totalScreenWakes":0,"totalUnlocks":0,"totalUnlockDuration":0,"appUsage":{"apps":[null]},"webUsage":{}}"""),
        sample().copy(sensor = SensorType.deviceUsage, data = """{"totalScreenWakes":0,"totalUnlocks":0,"totalUnlockDuration":0,"appUsage":{"apps":null},"webUsage":{}}"""),
        sample().copy(sensor = SensorType.keyboardMetrics, data = "null"),
        sample().copy(sensor = SensorType.messagesUsage, data = "not-json"),
    )

    private fun sample(): SensorDataSample {
        val timestamp = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        return SensorDataSample(
            UUID.randomUUID(), timestamp, 60.0,
            """{"totalIncomingCalls":1,"totalOutgoingCalls":2,"totalPhoneDuration":3.0,"totalUniqueContacts":2}""",
            """{"model":"iPhone","name":"test","systemName":"iOS","systemVersion":"26"}""",
            "UTC", SensorType.phoneUsage, timestamp, timestamp.plusSeconds(60),
        )
    }
}
