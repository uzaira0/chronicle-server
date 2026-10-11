package com.openlattice.chronicle.controllers

import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.import.ImportStudiesConfiguration
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.sql.SQLException

class ImportControllerConnectionTest {
    companion object {
        @ClassRule @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_import_connections")
    }

    @Test
    fun `repeated valid system app imports release a one connection pool`() = withController { controller, dataSource ->
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO legacy_system_apps VALUES ('sample.system')")
            }
        }
        repeat(3) {
            controller.importSystemApps(config())
            assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
        }
    }

    @Test
    fun `failed system app imports release their borrowed connection`() = withController { controller, dataSource ->
        assertThrows(SQLException::class.java) {
            controller.importSystemApps(config().copy(systemAppsTable = "missing_source"))
        }
        assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
    }

    @Test
    fun `empty diary import releases both insert connections`() = withController { controller, dataSource ->
        repeat(3) {
            controller.importTimeUseDiarySubmissions(config())
            assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
        }
    }

    private fun config() = ImportStudiesConfiguration(
        dataSourceName = "legacy", candidatesTable = "unused", studiesTable = "unused",
        studySettingsTable = "unused", systemAppsTable = "legacy_system_apps",
        timeUseDiaryTable = "legacy_diary", timeUseDiarySummarizedTable = "legacy_summary",
    )

    private fun withController(studies: StudyService = mock(), action: (ImportController, HikariDataSource) -> Unit) {
        TestSecurityUtils.setupSecurityContext()
        HikariDataSource().use { dataSource ->
            dataSource.jdbcUrl = postgres.jdbcUrl
            dataSource.username = postgres.username
            dataSource.password = postgres.password
            dataSource.maximumPoolSize = 1
            dataSource.connectionTimeout = 250
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    listOf(ChroniclePostgresTables.SYSTEM_APPS, ChroniclePostgresTables.TIME_USE_DIARY_SUBMISSIONS,
                        ChroniclePostgresTables.TIME_USE_DIARY_SUMMARIZED).forEach { table ->
                        statement.execute("DROP TABLE IF EXISTS ${table.name}")
                        statement.execute(table.createTableQuery())
                    }
                    statement.execute("CREATE TABLE IF NOT EXISTS legacy_system_apps (app_package_name TEXT)")
                    statement.execute("TRUNCATE legacy_system_apps")
                    statement.execute("DROP TABLE IF EXISTS legacy_diary, legacy_summary")
                    statement.execute(
                        "CREATE TABLE legacy_diary (study_id UUID, organization_id UUID, submission_id UUID, participant_id TEXT, " +
                        "submission_date TIMESTAMPTZ, submission TEXT)")
                    statement.execute("CREATE TABLE legacy_summary (submission_id UUID, data TEXT)")
                }
            }
            val dataSourceManager = mock<DataSourceManager>()
            Mockito.`when`(dataSourceManager.getDataSource("legacy")).thenReturn(dataSource)
            val authorization = mock<AuthorizationManager>()
            Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
            val hazelcast = mock<HazelcastInstance>()
            Mockito.`when`(hazelcast.getMap<String, Any>("USERS")).thenReturn(mock<IMap<String, Any>>())
            val controller = ImportController(
                studies, mock<CandidateService>(), mock<TimeUseDiaryService>(),
                mock<AppDataUploadService>(), dataSourceManager,
                mock<StorageResolver>(), authorization, mock<AuditingManager>(), hazelcast,
            )
            action(controller, dataSource)
        }
    }

    @Test fun summaryFailureRollsBackAndRetriesKeepOneStableSubmission() {
        val studies = mock<StudyService>()
        val study = java.util.UUID.randomUUID()
        val submission = java.util.UUID.randomUUID()
        Mockito.`when`(studies.getStudyId(study)).thenReturn(study)
        withController(studies) { controller, source ->
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute("INSERT INTO legacy_diary VALUES ('$study', '${java.util.UUID.randomUUID()}', '$submission', 'p1', now(), '[]')")
                statement.execute("INSERT INTO legacy_summary VALUES ('$submission', '[]')")
                statement.execute("ALTER TABLE time_use_diary_summarized ADD CONSTRAINT injected_summary_failure CHECK (false)")
            } }
            assertThrows(SQLException::class.java) { controller.importTimeUseDiarySubmissions(config()) }
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM time_use_diary_submissions").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                statement.execute("ALTER TABLE time_use_diary_summarized DROP CONSTRAINT injected_summary_failure")
            } }
            repeat(2) { controller.importTimeUseDiarySubmissions(config()) }
            source.connection.use { connection -> connection.createStatement().use { statement ->
                for (table in listOf("time_use_diary_submissions", "time_use_diary_summarized")) {
                    statement.executeQuery("SELECT submission_id FROM $table").use { rows ->
                        org.junit.Assert.assertTrue(rows.next()); assertEquals(submission, rows.getObject(1, java.util.UUID::class.java))
                        org.junit.Assert.assertFalse(rows.next())
                    }
                }
            } }
        }
    }

}
