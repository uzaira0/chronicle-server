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
            UploadDiagnosticsUploadService(storageResolver).upload(studyId, PARTICIPANT_ID, deviceId, listOf(event))
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
    ) {
        connection.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use { statement ->
            statement.setString(1, settingsJson(required, settingsVersion))
            statement.setObject(2, studyId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun settingsJson(required: Boolean, settingsVersion: Int): String = mapper.writeValueAsString(
        StudySettings(
            mapOf(
                StudySettingType.DataCollection to AndroidDataCollectionSetting(
                    modules = linkedMapOf(
                        CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                            enabled = true,
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
}
