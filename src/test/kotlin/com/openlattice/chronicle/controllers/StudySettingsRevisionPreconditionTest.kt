package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.studies.StudySettingsRevisionMismatchException
import com.openlattice.chronicle.services.studies.loadLockedStudySettings
import com.openlattice.chronicle.services.studies.applyLockedSettingsMutation
import com.openlattice.chronicle.services.studies.checkLockedStudyRevision
import com.openlattice.chronicle.services.studies.mergeStudySetting

import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.mapstores.storage.StudyMapstore
import com.openlattice.chronicle.serializers.StudyStreamSerializer
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        for (missing in listOf(null, "  ", "*")) {
            val required = assertThrows(ResponseStatusException::class.java) { parseSettingsRevisionPrecondition(missing) }
            assertEquals(428, required.statusCode.value())
        }
        assertEquals(7L, parseSettingsRevisionPrecondition("\"7--gzip\""))
        assertEquals(7L, parseSettingsRevisionPrecondition("\"7--br\""))
        assertEquals(7L, parseSettingsRevisionPrecondition("W/\"7-gzip\""))
        assertThrows(ResponseStatusException::class.java) { parseSettingsRevisionPrecondition("\"7--compress\"") }
        assertThrows(ResponseStatusException::class.java) { parseSettingsRevisionPrecondition("\"-7\"") }
        val malformed = assertThrows(ResponseStatusException::class.java) {
            parseSettingsRevisionPrecondition("\"not-a-revision\"")
        }
        assertEquals(HttpStatus.BAD_REQUEST.value(), malformed.statusCode.value())
    }

    @Test
    fun committedWriteRejectsAnEditFromTheCacheBeforeEviction() {
        val studyId = newStudy()
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
        }).use { storage ->
            val mapstore = StudyMapstore(storage)
            val cached = checkNotNull(mapstore.load(studyId))
            inTransaction { transaction ->
                applyLockedSettingsMutation(transaction, studyId, cached.settingsRevision, studyService) { prior ->
                    mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
                }
            }
            val serialization = DefaultSerializationServiceBuilder().build()
            try {
                val output = serialization.createObjectDataOutput(256)
                StudyStreamSerializer.serialize(output, cached)
                val stale = StudyStreamSerializer.deserialize(serialization.createObjectDataInput(output.toByteArray()))
                val mismatch = assertThrows(StudySettingsRevisionMismatchException::class.java) {
                    inTransaction { transaction ->
                        applyLockedSettingsMutation(transaction, studyId, stale.settingsRevision, studyService) { it }
                    }
                }
                assertEquals(currentRevision(studyId), mismatch.currentRevision)
                val fresh = checkNotNull(mapstore.load(studyId))
                assertEquals(mismatch.currentRevision, fresh.settingsRevision)
                val freshOutput = serialization.createObjectDataOutput(256)
                StudyStreamSerializer.serialize(freshOutput, fresh)
                assertEquals(fresh, StudyStreamSerializer.deserialize(
                    serialization.createObjectDataInput(freshOutput.toByteArray()),
                ))
            } finally {
                serialization.dispose()
            }
        }
    }

    @Test
    fun `matching revision succeeds and increments the revision`() {
        val studyId = newStudy()
        assertEquals(0L, currentRevision(studyId))

        val update = inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = 0L, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
            }
        }

        assertEquals(1L, update.settingsRevision)
        assertEquals(1L, currentRevision(studyId))
        assertNotNull(loadSettings(studyId)[StudySettingType.Survey])
    }

    @Test
    fun `an unchanged write keeps the revision and a changed one moves it`() {
        val studyId = newStudy()
        val writeSurvey = { expected: Long ->
            inTransaction { transaction ->
                applyLockedSettingsMutation(transaction, studyId, expected, studyService) { prior ->
                    mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
                }
            }.settingsRevision
        }
        assertEquals(1L, writeSurvey(0L))
        assertEquals(1L, writeSurvey(1L))
        inTransaction { transaction -> studyService.updateStudy(transaction, studyId, StudyUpdate(title = "revision")) }
        assertEquals(1L, currentRevision(studyId))
        inTransaction { transaction -> studyService.updateStudy(transaction, studyId, StudyUpdate(title = "renamed")) }
        assertEquals(2L, currentRevision(studyId))
    }

    @Test
    fun everyContentColumnPersistsAndOnlyChangedRowsAdvanceTheRevision() {
        val changes = listOf(
            Triple("title", StudyUpdate(title = "renamed"), "renamed"),
            Triple("description", StudyUpdate(description = "description"), "description"),
            Triple("lat", StudyUpdate(lat = -33.0), -33.0),
            Triple("lon", StudyUpdate(lon = -70.0), -70.0),
            Triple("study_group", StudyUpdate(group = "group"), "group"),
            Triple("study_version", StudyUpdate(version = "version"), "version"),
            Triple("contact", StudyUpdate(contact = "research@example.org"), "research@example.org"),
            Triple("storage", StudyUpdate(storage = "alternate"), "alternate"),
            Triple("settings", StudyUpdate(settings = StudySettings(emptyMap())), "{}"),
            Triple("modules", StudyUpdate(modules = emptyMap()), "{}"),
        )
        val readRow = { column: String, studyId: UUID ->
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("SELECT $column, updated_at FROM studies WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getObject(1).toString() to rows.getObject(2)
                    }
                }
            }
        }
        for ((column, change, expected) in changes) {
            val studyId = newStudy()
            // Start JSON columns with content so replacing either with {} is a real change.
            inTransaction { transaction ->
                studyService.updateStudy(transaction, studyId,
                    StudyUpdate(settings = StudySettings(mapOf(StudySettingType.Survey to sensorSetting())),
                        modules = mapOf(StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>())))
            }
            val before = currentRevision(studyId)
            inTransaction { transaction -> studyService.updateStudy(transaction, studyId, change) }
            assertEquals(column, before + 1, currentRevision(studyId))
            val saved = readRow(column, studyId)
            assertEquals(column, expected.toString(), saved.first)
            inTransaction { transaction ->
                studyService.updateStudy(transaction, studyId, change)
                studyService.updateStudy(transaction, studyId, StudyUpdate())
            }
            assertEquals(column, saved, readRow(column, studyId))
            assertEquals(column, before + 1, currentRevision(studyId))
        }
    }

    @Test
    fun `stale revision is rejected with the current revision and settings`() {
        val studyId = newStudy()
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
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
            mismatch.currentSettings[StudySettingType.Survey],
        )
        assertNull(mismatch.currentSettings[StudySettingType.Notifications])
        assertEquals(1L, currentRevision(studyId))
    }

    @Test
    fun `details check and settings write agree on absent matching and stale revisions`() {
        val studyId = newStudy()
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
            }
        }
        val current = currentRevision(studyId)
        val checks = mapOf<String, (Connection, Long?) -> Unit>(
            "details" to { transaction, expected -> checkLockedStudyRevision(transaction, studyId, expected) },
            "settings" to { transaction, expected ->
                applyLockedSettingsMutation(transaction, studyId, expected, studyService) { it }
            },
        )
        for ((name, check) in checks) {
            for ((expected, stale) in listOf(null to false, current to false, current - 1 to true)) {
                rolledBack { transaction ->
                    if (stale) {
                        val mismatch = assertThrows(name, StudySettingsRevisionMismatchException::class.java) {
                            check(transaction, expected)
                        }
                        assertEquals(name, current, mismatch.currentRevision)
                        assertNotNull(name, mismatch.currentSettings[StudySettingType.Survey])
                    } else {
                        check(transaction, expected)
                    }
                }
            }
        }
        assertEquals(current, currentRevision(studyId))
    }

    @Test
    fun `internal isolated mutations preserve distinct setting types`() {
        val studyId = newStudy()
        inTransaction { transaction ->
            applyLockedSettingsMutation(transaction, studyId, expectedRevision = null, studyService) { prior ->
                mergeStudySetting(prior, StudySettingType.Survey, sensorSetting())
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
        assertNotNull(settings[StudySettingType.Survey])
        assertNotNull(settings[StudySettingType.Notifications])
    }

    @Test
    fun `two setting types written concurrently both persist`() {
        val studyId = newStudy()
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(
                writeTask(barrier, studyId, StudySettingType.Survey, sensorSetting()),
                writeTask(barrier, studyId, StudySettingType.Notifications, notificationSetting()),
            )
            executor.invokeAll(writes, 60, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val settings = loadSettings(studyId)
        assertNotNull("concurrent write of another type must not be clobbered", settings[StudySettingType.Survey])
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

    private fun rolledBack(block: (Connection) -> Unit) = postgres.createConnection("").use { connection ->
        connection.autoCommit = false
        try {
            block(connection)
        } finally {
            connection.rollback()
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

    private fun sensorSetting() = SurveySettings(appUsageThresholdInSeconds = 42)

    private fun notificationSetting() = StudyNotificationSettings(
        labFriendlyName = "lab",
        studyFriendlyName = "study",
    )
}
