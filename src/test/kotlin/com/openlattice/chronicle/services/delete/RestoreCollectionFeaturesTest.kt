package com.openlattice.chronicle.services.delete

import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.storageResolver
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.bootstrapConnection
import com.openlattice.chronicle.services.studies.CollectionSettingsRepository
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.mock
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime

class RestoreCollectionFeaturesTest {
    companion object {
        @BeforeClass @JvmStatic fun start() = RestoreContinuityFixture.start()
        @AfterClass @JvmStatic fun stop() = RestoreContinuityFixture.stop()
    }

    private val fixture = RestoreContinuityFixture()

    @Before fun installFixtureContext() = fixture.installContext()

    private val mapper = ObjectMappers.getJsonMapper()
    private val collection = StudyFeature.CHRONICLE_DATA_COLLECTION
    private val enabledFeatures = mapOf(collection to emptyMap<String, Any>())
    private val publishedFeatures = enabledFeatures + (StudyFeature.CHRONICLE_SURVEYS to emptyMap<String, Any>())

    @Test fun aMissedHardStopSurvivesRestoreOfAnOlderEnrolledBackup() {
        fixture.seedRestoredOlderState()
        val expected = bootstrapConnection().use { connection ->
            for (status in listOf("NOT_ENROLLED", "ENROLLED", "NOT_ENROLLED", "ENROLLED")) {
                connection.prepareStatement("UPDATE study_participants SET participation_status = ? WHERE study_id = ?").use {
                    it.setString(1, status); it.setObject(2, fixture.studyId); it.executeUpdate()
                }
            }
            connection.prepareStatement(restoreResource("restore-checkpoint-capture.sql")).use { it.execute() }
            val stoppedAt = connection.prepareStatement(
                "SELECT hard_stopped_at FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
            }
            simulateOlderEnrolledBackup(connection)
            stoppedAt
        }
        reconciler().reconcile()
        bootstrapConnection().use { connection ->
            connection.prepareStatement("SELECT hard_stop_generation, hard_stopped_at FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(2L, rows.getLong(1))
                    assertEquals(expected, rows.getObject(2, OffsetDateTime::class.java))
                }
            }
        }
    }

    @Test fun anOpenHardStopKeepsItsOriginalWindowAcrossRestore() {
        fixture.seedRestoredOlderState()
        val expected = bootstrapConnection().use { connection ->
            connection.prepareStatement("UPDATE study_participants SET participation_status = 'NOT_ENROLLED' WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId); it.executeUpdate()
            }
            connection.prepareStatement(restoreResource("restore-checkpoint-capture.sql")).use { it.execute() }
            val cutoff = connection.prepareStatement("SELECT capture_cutoff FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
            }
            simulateOlderEnrolledBackup(connection)
            cutoff
        }
        reconciler().reconcile()
        bootstrapConnection().use { connection ->
            connection.prepareStatement("SELECT capture_cutoff FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(expected, rows.getObject(1, OffsetDateTime::class.java))
                }
            }
        }
    }

    @Test fun captureWindowDigestDistinguishesUnboundedEndpoints() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            connection.prepareStatement("UPDATE study_participants " +
                "SET closed_capture_windows = '{(,2026-01-01Z)}'::tstzmultirange WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId); assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(restoreResource("restore-checkpoint-capture.sql")).use { it.execute() }
            connection.autoCommit = false
            try {
                val digests = listOf("(,2026-01-01Z)", "(2026-01-01Z,)").map { window ->
                    connection.prepareStatement("UPDATE chronicle_restore_continuity.restricted_participants " +
                        "SET closed_capture_windows = tstzmultirange(?::tstzrange)").use {
                        it.setString(1, window); assertEquals(1, it.executeUpdate())
                    }
                    fixture.checkpointRepository.computeCheckpointSha256(connection)
                }
                assertNotEquals(digests[0], digests[1])
            } finally { connection.rollback() }
        }
    }

    @Test fun `active study and participant retain completed capture closures after restore`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            for (sql in listOf(
                "UPDATE studies SET lifecycle_status = 'ARCHIVED', collection_closed_at = '2026-01-01Z' WHERE study_id = ?",
                "UPDATE studies SET lifecycle_status = 'ACTIVE' WHERE study_id = ?",
                "UPDATE study_participants SET participation_status = 'PAUSED' WHERE study_id = ?",
                "UPDATE study_participants SET capture_cutoff = '2026-01-01Z' WHERE study_id = ?",
                "UPDATE study_participants SET participation_status = 'ENROLLED' WHERE study_id = ?",
            )) connection.prepareStatement(sql).use {
                it.setObject(1, fixture.studyId); it.executeUpdate()
            }
            connection.prepareStatement(restoreResource("restore-checkpoint-capture.sql")).use { it.execute() }
            val digest = fixture.checkpointRepository.computeCheckpointSha256(connection)
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.executeUpdate("UPDATE chronicle_restore_continuity.restricted_participants " +
                    "SET closed_capture_windows = '{}'::tstzmultirange") }
                assertNotEquals(digest, fixture.checkpointRepository.computeCheckpointSha256(connection))
            } finally { connection.rollback(); connection.autoCommit = true }
            simulateOlderEnrolledBackup(connection)
        }
        reconciler().reconcile()
        bootstrapConnection().use { connection ->
            assertActiveCaptureWindows(connection)
            for (resource in listOf("restore-checkpoint-capture.sql", "restore-restrictions-containment.sql",
                "restore-checkpoint-verify.sql")) {
                connection.prepareStatement(restoreResource(resource)).use { it.execute() }
            }
        }
    }

    private fun assertActiveCaptureWindows(connection: Connection) {
        for (sql in listOf(
            "SELECT lifecycle_status = 'ACTIVE' AND closed_capture_windows @> '2026-01-02Z'::timestamptz " +
                "FROM studies WHERE study_id = ?",
            "SELECT participation_status = 'ENROLLED' AND closed_capture_windows @> '2026-01-02Z'::timestamptz " +
                "FROM study_participants WHERE study_id = ?",
        )) connection.prepareStatement(sql).use {
            it.setObject(1, fixture.studyId)
            it.executeQuery().use { rows -> assertTrue(rows.next()); assertTrue(rows.getBoolean(1)) }
        }
    }

    private fun simulateOlderEnrolledBackup(connection: Connection) {
        connection.createStatement().use { it.execute("SET session_replication_role = replica") }
        try {
            for (sql in listOf(
                "UPDATE studies SET closed_capture_windows = '{}'::tstzmultirange WHERE study_id = ?",
                "UPDATE study_participants SET closed_capture_windows = '{}'::tstzmultirange, " +
                    "participation_status = 'ENROLLED', capture_cutoff = NULL, hard_stop_generation = 0, " +
                    "hard_stopped_at = NULL WHERE study_id = ?",
            )) connection.prepareStatement(sql).use {
                it.setObject(1, fixture.studyId); it.executeUpdate()
            }
        } finally { connection.createStatement().use { it.execute("SET session_replication_role = origin") } }
    }

    @After fun clean() {
        try {
            fixture.cleanFixture()
        } finally {
            fixture.restoreContext()
        }
    }

    @Test fun `restore replays historical feature policy and finishes with published features`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            writePolicy(connection, 1, enabledFeatures)
            writePolicy(connection, 2, emptyMap())
            writePolicy(connection, 3, enabledFeatures)
            // An unrelated feature can change without issuing another collection revision.
            writePolicy(connection, 3, publishedFeatures)
            val repository = CollectionSettingsRepository(connection)
            val before = repository.through(fixture.studyId, 3)
            assertEquals(listOf(1, 2, 3), before.map { it.settingsVersion })
            assertTrue(CollectionModuleId.BATTERY_TELEMETRY in before.first().effectiveEnabledModuleIds())
            assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), before[1].effectiveEnabledModuleIds())
            assertEquals(before.first().modules, before.last().modules)
            val modulesBefore = revisionModules(connection)
            captureAuthorities(connection)
            fixture.sealContinuityCheckpoint(connection)
            // Simulate the public tables from the backup before revisions 2 and 3 existed.
            connection.createStatement().use {
                it.execute("SET session_replication_role = replica")
                try { it.executeUpdate("DELETE FROM data_collection_settings_revisions") }
                finally { it.execute("SET session_replication_role = origin") }
            }
            writePolicy(connection, 1, enabledFeatures)
            reconciler().reconcile()
            assertEquals(modulesBefore, revisionModules(connection))
            assertEquals(before, repository.through(fixture.studyId, 3))
            before.forEach { assertEquals(it, repository.issued(fixture.studyId, it.settingsVersion)) }
            assertEquals(before.last(), repository.latest(fixture.studyId))
            connection.prepareStatement("SELECT modules FROM studies WHERE study_id = ?").use {
                it.setObject(1, fixture.studyId)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(mapper.valueToTree<JsonNode>(publishedFeatures), mapper.readTree(rows.getString(1)))
                }
            }
        }
    }

    @Test fun `missing collection version rejects the study write atomically`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            writePolicy(connection, 1, enabledFeatures)
            val before = CollectionSettingsRepository(connection).issued(fixture.studyId, 1)
            val error = assertThrows(SQLException::class.java) {
                connection.prepareStatement("UPDATE studies SET settings = jsonb_set(settings, '{DataCollection}', " +
                    "(settings -> 'DataCollection') - 'settingsVersion') WHERE study_id = ?").use {
                    it.setObject(1, fixture.studyId)
                    it.executeUpdate()
                }
            }
            assertTrue(error.message.orEmpty().contains("positive server-issued revision"))
            assertEquals(before, CollectionSettingsRepository(connection).latest(fixture.studyId))
        }
    }

    @Test fun `both historical and published modules are covered by the checkpoint digest`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            writePolicy(connection, 1, enabledFeatures)
            captureAuthorities(connection)
            for (table in listOf("data_collection_settings_revisions", "published_data_collection_settings")) {
                val before = fixture.checkpointRepository.computeCheckpointSha256(connection)
                connection.createStatement().use {
                    it.executeUpdate("UPDATE chronicle_restore_continuity.$table SET study_modules = '{}'::jsonb")
                }
                assertNotEquals(table, before, fixture.checkpointRepository.computeCheckpointSha256(connection))
            }
        }
    }

    @Test fun `an issued revision cannot be replayed with conflicting study features`() {
        fixture.seedRestoredOlderState()
        bootstrapConnection().use { connection ->
            writePolicy(connection, 1, enabledFeatures)
            captureAuthorities(connection)
            connection.createStatement().use {
                it.executeUpdate("UPDATE chronicle_restore_continuity.data_collection_settings_revisions " +
                    "SET study_modules = '{}'::jsonb")
            }
            fixture.sealContinuityCheckpoint(connection)
            val before = CollectionSettingsRepository(connection).latest(fixture.studyId)
            val error = assertThrows(IllegalStateException::class.java) { reconciler().reconcile() }
            assertEquals("Restore continuity conflicts with monotonic security authority", error.message)
            assertEquals(before, CollectionSettingsRepository(connection).latest(fixture.studyId))
            assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        }
    }

    private fun captureAuthorities(connection: Connection) {
        fixture.createContinuitySchema(connection)
        connection.createStatement().use {
            it.executeUpdate("INSERT INTO chronicle_restore_continuity.data_collection_settings_revisions " +
                "SELECT study_id, settings_version, setting, study_modules, issued_at FROM data_collection_settings_revisions")
            it.executeUpdate("INSERT INTO chronicle_restore_continuity.published_data_collection_settings " +
                "SELECT study_id, (settings -> 'DataCollection' ->> 'settingsVersion')::integer, " +
                "settings -> 'DataCollection', modules FROM studies " +
                "WHERE settings -> 'DataCollection' ->> 'settingsVersion' ~ '^[1-9][0-9]*$'")
        }
    }

    private fun writePolicy(connection: Connection, version: Int, features: Map<StudyFeature, Map<String, Any>>) {
        val setting: AndroidDataCollectionSetting = CollectionDefaults.androidDataCollectionSetting().copy(settingsVersion = version)
        connection.prepareStatement("UPDATE studies SET settings = ?::jsonb, modules = ?::jsonb WHERE study_id = ?").use {
            it.setString(1, mapper.writeValueAsString(StudySettings(mapOf(StudySettingType.DataCollection to setting))))
            it.setString(2, mapper.writeValueAsString(features))
            it.setObject(3, fixture.studyId)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun revisionModules(connection: Connection): Map<Int, JsonNode> = connection.prepareStatement(
        "SELECT settings_version, study_modules FROM data_collection_settings_revisions WHERE study_id = ? ORDER BY settings_version",
    ).use {
        it.setObject(1, fixture.studyId)
        it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getInt(1), mapper.readTree(rows.getString(2))) } }
    }

    private fun reconciler() = RestoreContinuityReconciler(storageResolver,
        DataDeletionOrchestrator(storageResolver, mock<AuditingManager>()))
}
