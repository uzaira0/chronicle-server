package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.download.SurveyDownloadFixture
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.StudyDisclosureGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SynchronousDownloadErasureTest : ServerSweepTestFixture() {
    @Test fun aReadReplicaBehindThePrimaryGenerationCannotDiscloseAnOldSnapshot() {
        val study = UUID.randomUUID()
        val replicaSchema = "read_replica_" + UUID.randomUUID().toString().replace("-", "")
        val currentGeneration = UUID.randomUUID()
        seed(study, "replica")
        pool("disclosure-primary").use { primary ->
            primary.connection.use { seedReplica(it, replicaSchema, study, currentGeneration) }
            try {
                pool("disclosure-replica", replicaSchema).use { replica ->
                    val storage = mock<StorageResolver>()
                    whenever(storage.getPlatformStorage()).thenReturn(primary)
                    whenever(storage.getPlatformReadStorage()).thenReturn(replica)
                    val now = OffsetDateTime.now()
                    fun rows() = DataDownloadService(storage).getParticipantsAppUsageSurveyData(
                        study, emptySet(), now.minusDays(1), now.plusDays(1), ZoneOffset.UTC,
                    )
                    assertReplicaRejects(rows())
                    setPoolGeneration(replica, study, currentGeneration)
                    assertEquals(SurveyDownloadFixture.ROW_COUNT, rows().count())
                }
            } finally {
                dropReplicaSchema(primary, replicaSchema)
            }
        }
    }

    @Test fun restoreReassertionCannotReuseTheGenerationCapturedByAnOldCursor() {
        val study = UUID.randomUUID()
        seed(study, "restored")
        pool("restored-generation").use { pool ->
            pool.connection.use { SurveyDownloadFixture.insertRows(it, study, "restored", SurveyDownloadFixture.ROW_COUNT) }
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            val now = OffsetDateTime.now()
            val cursor = DataDownloadService(storage).getParticipantsAppUsageSurveyData(
                study, emptySet(), now.minusDays(1), now.plusDays(1), ZoneOffset.UTC,
            ).iterator()
            try {
                cursor.next()
                val original = pool.connection.use { StudyDisclosureGeneration(it).read(study) }
                val operation = DataDeletionOrchestrator(storage, mock()).quarantineParticipant(
                    study, "restored", DataDeletionMode.WITHDRAW_AND_ERASE, "test", UUID.randomUUID(),
                )
                pool.connection.use { reassertRestoredGeneration(it, study, operation, original) }
                assertThrows(NoSuchElementException::class.java) { cursor.asSequence().count() }
            } finally { (cursor as AutoCloseable).close() }
        }
    }

    private fun seedReplica(connection: Connection, schema: String, study: UUID, currentGeneration: UUID) {
        connection.createStatement().use {
            it.execute("CREATE SCHEMA $schema")
            it.execute("CREATE TABLE $schema.studies (LIKE public.studies INCLUDING ALL)")
            it.execute("CREATE TABLE $schema.app_usage_survey (LIKE public.app_usage_survey INCLUDING ALL)")
        }
        SurveyDownloadFixture.insertRows(connection, study, "replica", SurveyDownloadFixture.ROW_COUNT)
        connection.prepareStatement("INSERT INTO $schema.studies SELECT * FROM studies WHERE study_id = ?").use {
            it.setObject(1, study); it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO $schema.app_usage_survey SELECT * FROM app_usage_survey WHERE study_id = ?").use {
            it.setObject(1, study); it.executeUpdate()
        }
        setGeneration(connection, study, currentGeneration)
    }

    private fun assertReplicaRejects(rows: Iterable<Map<String, Any>>) {
        var stale: Iterator<Map<String, Any>>? = null
        try { assertThrows(IllegalStateException::class.java) { stale = rows.iterator() } }
        finally { (stale as? AutoCloseable)?.close() }
    }

    private fun setPoolGeneration(pool: HikariDataSource, study: UUID, generation: UUID) {
        pool.connection.use { setGeneration(it, study, generation) }
    }

    private fun dropReplicaSchema(pool: HikariDataSource, schema: String) {
        pool.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") } }
    }

    private fun reassertRestoredGeneration(connection: Connection, study: UUID, operation: UUID, original: UUID) {
        connection.autoCommit = false
        setGeneration(connection, study, original)
        connection.prepareStatement("UPDATE data_deletion_operations SET status = status WHERE operation_id = ?").use {
            it.setObject(1, operation); it.executeUpdate()
        }
        connection.commit()
        org.junit.Assert.assertNotEquals(original, StudyDisclosureGeneration(connection).read(study))
    }

    private fun setGeneration(connection: Connection, study: UUID, generation: UUID) {
        connection.prepareStatement("UPDATE studies SET ${StudyDisclosureGeneration.COLUMN} = ? WHERE study_id = ?").use {
            it.setObject(1, generation); it.setObject(2, study); it.executeUpdate()
        }
    }

    @Test fun erasureCancelsExistingStudySnapshotsButFreshAndOtherStudyReadersContinue() {
        val study = UUID.randomUUID()
        val controlStudy = UUID.randomUUID()
        val erased = "erased"
        val retained = "retained"
        seed(study, erased)
        seed(controlStudy, retained)
        pool("sync-erasure").use { pool ->
            pool.connection.use {
                SurveyDownloadFixture.insertRows(it, study, retained, SurveyDownloadFixture.ROW_COUNT)
                SurveyDownloadFixture.insertRows(it, controlStudy, retained, SurveyDownloadFixture.ROW_COUNT)
            }
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            val downloads = DataDownloadService(storage)
            val now = OffsetDateTime.now()
            fun rows(id: UUID) = downloads.getParticipantsAppUsageSurveyData(
                id, setOf(retained), now.minusDays(1), now.plusDays(1), ZoneOffset.UTC,
            )
            val active = rows(study).iterator()
            val control = rows(controlStudy).iterator()
            try {
                active.next(); control.next()
                DataDeletionOrchestrator(storage, mock()).quarantineParticipant(
                    study, erased, DataDeletionMode.WITHDRAW_AND_ERASE, "test", UUID.randomUUID(),
                )
                assertThrows(NoSuchElementException::class.java) { active.asSequence().count() }
                assertEquals(SurveyDownloadFixture.ROW_COUNT - 1, control.asSequence().count())
                assertEquals("a new snapshot captures the current disclosure generation", SurveyDownloadFixture.ROW_COUNT, rows(study).count())
            } finally {
                (active as AutoCloseable).close(); (control as AutoCloseable).close()
            }
        }
    }
}
