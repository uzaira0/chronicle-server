package com.openlattice.chronicle.storage.tasks

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.InvocationTargetException
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.Properties
import java.util.UUID

/** Exercises identity loss in the real iOS duplicate merge before an actual collected-data purge. */
class IosSensorErasureReplayTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("ios_erasure_replay")

        private lateinit var manager: DataSourceManager
        private lateinit var storage: StorageResolver

        @BeforeClass
        @JvmStatic
        fun setUp() {
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            val configuration = PostgresConfiguration(
                hikariConfiguration = Properties().apply {
                    setProperty("jdbcUrl", postgres.jdbcUrl)
                    setProperty("username", postgres.username)
                    setProperty("password", postgres.password)
                    setProperty("maximumPoolSize", "1")
                    setProperty("connectionTimeout", "1000")
                },
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false,
            )
            manager = DataSourceManager(
                mapOf("default" to configuration, "chronicle" to configuration),
                HealthCheckRegistry(), MetricRegistry(),
            )
            storage = StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::manager.isInitialized) manager.dataSources.values.forEach { it.close() }
        }
    }

    @Test
    fun `buffered-only purge acknowledges old only and mixed retries and keeps fresh samples`() {
        val studyId = UUID.randomUUID()
        val participantId = "ios-erasure-replay"
        val deviceId = UUID.randomUUID()
        val upload = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        TestSecurityUtils.setupSecurityContext()
        try {
            seedParticipant(studyId, participantId)
            val old = sample(UUID.randomUUID())
            assertEquals(1, upload.upload(studyId, participantId, deviceId, listOf(old)))
            val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
            val operation = orchestrator.quarantineParticipant(studyId, participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE, "ios-replay-test", UUID.randomUUID())
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?").use {
                    it.setObject(1, operation); assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            assertEquals("COMPLETED", orchestrator.getOperation(operation).status)
            assertEquals(1, upload.upload(studyId, participantId, deviceId, listOf(old)))
            val fresh = sample(UUID.randomUUID()).copy(dateRecorded = OffsetDateTime.now().plusDays(1))
            val newest = sample(UUID.randomUUID()).copy(dateRecorded = OffsetDateTime.now().plusDays(2), duration = 120.0)
            assertEquals(3, upload.upload(studyId, participantId, deviceId, listOf(old, fresh, newest)))
            drain()
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("SELECT sample_id FROM sensor_data WHERE study_id = ?::text").use {
                    it.setObject(1, studyId)
                    it.executeQuery().use { rows ->
                        val retained = buildSet { while (rows.next()) add(rows.getString(1)) }
                        assertEquals(setOf(fresh.id.toString(), newest.id.toString()), retained)
                    }
                }
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    @Test
    fun `legacy SensorKit device usage reread after purge is fenced by its recorded time`() {
        val studyId = UUID.randomUUID()
        val participantId = "ios-sensorkit-reread"
        val upload = SensorDataUploadService(storage, Mockito.mock(StudyService::class.java))
        TestSecurityUtils.setupSecurityContext()
        try {
            seedParticipant(studyId, participantId)
            val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
            val operation = orchestrator.quarantineParticipant(studyId, participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE, "sensorkit-reread-test", UUID.randomUUID())
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?").use {
                    it.setObject(1, operation); assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            // SensorKit reread: original observation time, endDate moved to the new fetch bound.
            val reread = sample(UUID.randomUUID()).copy(sensor = SensorType.deviceUsage,
                endDate = OffsetDateTime.now().plusDays(1))
            assertEquals(1, upload.upload(studyId, participantId, UUID.randomUUID(), listOf(reread)))
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("SELECT count(*) FROM upload_buffer WHERE study_id = ?").use {
                    it.setObject(1, studyId)
                    it.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                }
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    private fun seedParticipant(studyId: UUID, participantId: String) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'iOS erasure replay')").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, 'ENROLLED')").use {
                it.setObject(1, studyId)
                it.setString(2, participantId)
                it.setObject(3, UUID.randomUUID())
                it.executeUpdate()
            }
        }
    }

    private fun drain() {
        val dependencies = MoveToEventStorageTaskDependencies(storage, Mockito.mock(StudyManager::class.java))
        val mover = object : MoveToIosEventStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }
        RLSRequestContext.withSystemContext {
            try {
                MoveToIosEventStorageTask::class.java.getDeclaredMethod("moveToEventStorage").apply {
                    isAccessible = true
                }.invoke(mover)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }

    private fun sample(id: UUID): SensorDataSample {
        val recorded = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        return SensorDataSample(id, recorded, 60.0,
            """{"totalIncomingCalls":1,"totalOutgoingCalls":2,"totalPhoneDuration":3.0,"totalUniqueContacts":2}""",
            """{"model":"iPhone","name":"test","systemName":"iOS","systemVersion":"26"}""",
            "UTC", SensorType.phoneUsage, recorded, recorded.plusSeconds(60))
    }
}
