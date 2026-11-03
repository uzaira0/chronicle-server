package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.contract.seedEnrolledParticipant

import java.util.Optional
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.TransactionOnlyParticipantStatsCache
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.PreparedStatement
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.testcontainers.containers.PostgreSQLContainer

/** Shared migrated database and deterministic writer/deletion race harness. */
abstract class ServerSweepTestFixture {
    companion object {
        private lateinit var database: PostgreSQLContainer<*>
        @BeforeClass @JvmStatic fun start() {
            database = ChronicleContractTestSchema.prodPostgresContainer("chronicle_sweep_concurrency")
            database.start()
            ChronicleContractTestSchema.waitForQueryReady(database)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(database)
        }
        @AfterClass @JvmStatic fun stop() { if (::database.isInitialized) database.stop() }
    }

    protected val postgres: PostgreSQLContainer<*> get() = database

    protected fun pool(name: String, schema: String? = null): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 5
        this.schema = schema
        addDataSourceProperty("ApplicationName", name)
        connectionInitSql = "SET statement_timeout = '3s'; SET deadlock_timeout = '10s'"
    })

    protected fun resolver(hds: HikariDataSource): StorageResolver = object : StorageResolver(
        Mockito.mock(DataSourceManager::class.java), Mockito.mock(ChronicleStorageConfiguration::class.java),
    ) {
        override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource =
            PinnedPlatformConnection.resolve(hds)
    }

    protected fun seed(studyId: UUID, participantId: String, features: Set<StudyFeature> = emptySet()) {
        val setting = AndroidDataCollectionSetting(modules = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true, required = false),
        ), settingsVersion = 1)
        val settings = ObjectMappers.newJsonMapper().writeValueAsString(StudySettings(
            mapOf(StudySettingType.DataCollection to setting),
        ))
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title, settings, modules) VALUES (?, 'sweep', ?::jsonb, ?::jsonb)").use {
                it.setObject(1, studyId); it.setString(2, settings)
                it.setString(3, ObjectMappers.newJsonMapper().writeValueAsString(features.associateWith { emptyMap<String, Any>() }))
                it.executeUpdate()
            }
            seedEnrolledParticipant(connection, studyId, participantId)
        }
    }

    protected fun studyService(storage: StorageResolver, audit: AuditingManager = Mockito.mock(AuditingManager::class.java)): StudyService {
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        val studies = mock<IMap<UUID, Study>>()
        Mockito.`when`(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        return StudyService(storage, Mockito.mock(AuthorizationManager::class.java),
            Mockito.mock(CandidateManager::class.java), Mockito.mock(EnrollmentManager::class.java),
            Mockito.mock(SurveysManager::class.java), Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(StudyLimitsManager::class.java), audit, hazelcast,
            TransactionOnlyParticipantStatsCache, Mockito.mock(WebhookService::class.java))
    }

    protected fun awaitBlocked(name: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock')").use { statement ->
                statement.setString(1, name)
                if (statement.awaitBlocked(deadline)) return
            }
        }
        fail("Expected a queued database writer: $name")
    }

    protected fun queuedRace(
        studyId: UUID,
        row: SweepRaceRow,
        rowId: UUID = studyId,
        write: (StorageResolver) -> Unit,
    ) {
        val writerName = "sweep-writer-${UUID.randomUUID()}"
        val deleterName = "sweep-deleter-${UUID.randomUUID()}"
        val executor = Executors.newFixedThreadPool(2)
        try {
            pool(writerName).use { writerPool ->
                pool(deleterName).use { deletionPool ->
                    runQueuedRace(
                        race = SweepRace(studyId = studyId, row = row, rowId = rowId, write = write),
                        workers = RaceWorkers(writerName = writerName, deleterName = deleterName,
                            writerPool = writerPool, deletionPool = deletionPool, executor = executor),
                    )
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun runQueuedRace(race: SweepRace, workers: RaceWorkers) {
        TestSecurityUtils.initPrincipalsIfNeeded()
        postgres.createConnection("").use { blocker ->
            blocker.autoCommit = false
            blocker.prepareStatement(race.row.select).use {
                it.setObject(1, race.rowId); it.executeQuery().close()
            }
            val writer = workers.executor.submit {
                TestSecurityUtils.authenticate()
                try { race.write(resolver(workers.writerPool)) }
                finally { TestSecurityUtils.clearSecurityContext() }
            }
            try {
                awaitBlocked(workers.writerName)
                val deletion = workers.executor.submit { deleteRaceRow(race, workers.deletionPool) }
                awaitBlocked(workers.deleterName)
                blocker.commit()
                writer.get(5, TimeUnit.SECONDS)
                deletion.get(5, TimeUnit.SECONDS)
            } finally { blocker.rollback() }
        }
    }

    private fun deleteRaceRow(race: SweepRace, deletionPool: HikariDataSource) {
        deletionPool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))").use {
                    it.setObject(1, race.studyId); it.execute()
                }
                connection.prepareStatement(race.row.update).use {
                    it.setObject(1, race.rowId); it.executeUpdate()
                }
                connection.commit()
            } finally { connection.rollback() }
        }
    }

    protected fun assertParticipantAnnotations(hds: HikariDataSource, studyId: UUID, notes: String?, tags: List<String>) =
        hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT participant_notes, participant_tags FROM study_participants WHERE study_id = ?",
            ).use {
                it.setObject(1, studyId)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(notes, rows.getString(1))
                    assertEquals(tags, (rows.getArray(2).array as Array<*>).toList())
                }
            }
        }

    protected fun participantRace(annotations: Boolean) {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        queuedRace(studyId, SweepRaceRow.PARTICIPANT) { storage ->
            if (annotations) studyService(storage).updateParticipantAnnotations(studyId, participantId,
                ParticipantAnnotationsUpdate(participantNotes = Optional.of("note")))
            else studyService(storage).updateParticipationStatus(studyId, participantId, ParticipationStatus.PAUSED, expectedRevision = 0)
        }
    }
}

enum class SweepRaceRow(private val table: String, private val unchangedColumn: String,
                        private val idColumn: String = "study_id") {
    STUDY("studies", "title"),
    PARTICIPANT("study_participants", "participant_notes"),
    ACCESS_CODE("participant_form_access_codes", "revoked_at", "access_code_id");

    val select: String get() = "SELECT * FROM $table WHERE $idColumn = ? FOR UPDATE"
    val update: String get() = "UPDATE $table SET $unchangedColumn = $unchangedColumn WHERE $idColumn = ?"
}

private data class SweepRace(
    val studyId: UUID,
    val row: SweepRaceRow,
    val rowId: UUID,
    val write: (StorageResolver) -> Unit,
)

private data class RaceWorkers(
    val writerName: String,
    val deleterName: String,
    val writerPool: HikariDataSource,
    val deletionPool: HikariDataSource,
    val executor: ExecutorService,
)

private fun PreparedStatement.awaitBlocked(deadline: Long): Boolean {
    while (System.nanoTime() < deadline) {
        val blocked = executeQuery().use { it.next() && it.getBoolean(1) }
        if (blocked) return true
        Thread.sleep(10)
    }
    return false
}
