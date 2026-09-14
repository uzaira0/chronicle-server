package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Study settings were a read-merge-write with no cross-client precondition, so two dashboards that
 * read the same map and then wrote different setting types clobbered each other silently.
 *
 * These drive the row-locked write body directly ([applyLockedSettingsMutation]) against a real
 * PostgreSQL schema. [StudyService] is a CALLS_REAL_METHODS mock so the revision read/bump and the
 * study update run the production SQL — those three methods use only the supplied connection.
 */
class StudySettingsRevisionPreconditionTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var studyService: StudyService

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_settings_revision")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            studyService = Mockito.mock(StudyService::class.java, Mockito.CALLS_REAL_METHODS)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `if match header parses quoted weak and wildcard forms`() {
        assertEquals(7L, parseSettingsRevisionPrecondition("\"7\""))
        assertEquals(7L, parseSettingsRevisionPrecondition("7"))
        assertEquals(7L, parseSettingsRevisionPrecondition("W/\"7\""))
        assertNull(parseSettingsRevisionPrecondition(null))
        assertNull(parseSettingsRevisionPrecondition("  "))
        assertNull("* means no precondition", parseSettingsRevisionPrecondition("*"))
        val malformed = assertThrows(ResponseStatusException::class.java) {
            parseSettingsRevisionPrecondition("\"not-a-revision\"")
        }
        assertEquals(HttpStatus.BAD_REQUEST.value(), malformed.statusCode.value())
    }

    @Test
    fun `matching revision succeeds and increments the revision`() {
        val studyId = newStudy()
        assertEquals(0L, currentRevision(studyId))

        val update = inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = 0L, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.AndroidSensor, sensorSetting())
            }
        }

        assertEquals(1L, update.settingsRevision)
        assertEquals(1L, currentRevision(studyId))
        assertNotNull(loadSettings(studyId)[StudySettingType.AndroidSensor])
    }

    @Test
    fun `stale revision is rejected with the current revision and settings`() {
        val studyId = newStudy()
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.AndroidSensor, sensorSetting())
            }
        }

        val mismatch = assertThrows(StudySettingsRevisionMismatchException::class.java) {
            inTransaction { transaction ->
                applyLockedSettingsMutation(transaction, studyId, expectedRevision = 0L, studyService) { prior ->
                    mergeStudySetting(prior, StudySettingType.Notifications, notificationSetting())
                }
            }
        }

        assertEquals(1L, mismatch.currentRevision)
        assertNotNull(
            "the 412 body must carry the current settings so the client can re-render",
            mismatch.currentSettings[StudySettingType.AndroidSensor],
        )
        assertNull(mismatch.currentSettings[StudySettingType.Notifications])
        assertEquals(1L, currentRevision(studyId))
    }

    @Test
    fun `a write with no precondition keeps the legacy behaviour`() {
        val studyId = newStudy()
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.AndroidSensor, sensorSetting())
            }
        }
        // Second write from a client that never read the new revision still succeeds.
        val update = inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.Notifications, notificationSetting())
            }
        }
        assertEquals(2L, update.settingsRevision)
        val settings = loadSettings(studyId)
        assertNotNull(settings[StudySettingType.AndroidSensor])
        assertNotNull(settings[StudySettingType.Notifications])
    }

    @Test
    fun `two setting types written concurrently both persist`() {
        val studyId = newStudy()
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(
                writeTask(barrier, studyId, StudySettingType.AndroidSensor, sensorSetting()),
                writeTask(barrier, studyId, StudySettingType.Notifications, notificationSetting()),
            )
            executor.invokeAll(writes, 60, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val settings = loadSettings(studyId)
        assertNotNull("concurrent write of another type must not be clobbered", settings[StudySettingType.AndroidSensor])
        assertNotNull(settings[StudySettingType.Notifications])
        assertEquals(2L, currentRevision(studyId))
    }

    private fun writeTask(
        barrier: CyclicBarrier,
        studyId: UUID,
        settingType: StudySettingType,
        setting: com.openlattice.chronicle.study.StudySetting,
    ): Callable<Unit> = Callable {
        barrier.await(30, TimeUnit.SECONDS)
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, settingType, setting)
            }
        }
        Unit
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = postgres.createConnection("").use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    private fun newStudy(): UUID {
        val studyId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title, settings) VALUES (?, 'revision', '{}'::jsonb)",
            ).use { statement ->
                statement.setObject(1, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        return studyId
    }

    private fun currentRevision(studyId: UUID): Long = postgres.createConnection("").use { connection ->
        studyService.getStudySettingsRevision(connection, studyId)
    }

    private fun loadSettings(studyId: UUID): StudySettings = postgres.createConnection("").use { connection ->
        connection.autoCommit = false
        try {
            loadLockedStudySettings(connection, studyId)
        } finally {
            connection.rollback()
        }
    }

    private fun sensorSetting() = AndroidSensorSetting(setOf(AndroidSensorType.accelerometer))

    private fun notificationSetting() = StudyNotificationSettings(
        labFriendlyName = "lab",
        studyFriendlyName = "study",
    )
}
