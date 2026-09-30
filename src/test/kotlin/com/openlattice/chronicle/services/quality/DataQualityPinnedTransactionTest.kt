package com.openlattice.chronicle.services.quality

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class DataQualityPinnedTransactionTest {
    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var storage: HikariDataSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("quality_pin")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            storage = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 1
            })
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::storage.isInitialized) storage.close()
            if (Companion::postgres.isInitialized) postgres.stop()
        }
    }

    @Test
    fun `real statistics reader and alert inserts retain the study lock until owner commit`() {
        val studyId = seedStatistics()
        val observed = mutableListOf<String>()
        val service = service { phase ->
            observed += phase
            assertFalse("Deletion lock must remain held during $phase", tryDeletionLock(studyId))
            if (phase == "after inserts") assertEquals(0, alertCount(studyId))
        }

        assertEquals(1, service.generateAlerts(studyId))
        assertEquals(listOf("before statistics", "after statistics", "before inserts", "after inserts"), observed)
        assertTrue("Owner commit releases the lock", tryDeletionLock(studyId))
        assertEquals(1, alertCount(studyId))
    }

    @Test
    fun `insert failure after a real statistics read rolls back the executed alert batch`() {
        val studyId = seedStatistics()
        var inserted = false
        val service = service { phase ->
            if (phase == "after inserts") {
                inserted = true
                throw SQLException("injected failure after the alert insert")
            }
        }

        assertThrows(SQLException::class.java) { service.generateAlerts(studyId) }
        assertTrue("The real insert must execute before failure", inserted)
        assertEquals("Failed evaluation must leave no alerts", 0, alertCount(studyId))
        assertTrue("Rollback releases the lock", tryDeletionLock(studyId))
    }

    private fun service(observe: (String) -> Unit): DataQualityService {
        val observedStorage = object : HikariDataSource() {
            override fun getConnection(): Connection = connectionProxy(storage.connection, observe)
        }
        val resolver = object : StorageResolver(
            Mockito.mock(DataSourceManager::class.java),
            Mockito.mock(ChronicleStorageConfiguration::class.java),
        ) {
            override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource =
                PinnedPlatformConnection.resolve(observedStorage)
        }
        // Only the study metadata is supplied here; the stats reader and its rhizome supplier run unchanged.
        val studyService = object : StudyService(
            resolver, Mockito.mock(AuthorizationManager::class.java), Mockito.mock(CandidateManager::class.java),
            Mockito.mock(EnrollmentManager::class.java), Mockito.mock(SurveysManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java), Mockito.mock(StudyLimitsManager::class.java),
            Mockito.mock(AuditingManager::class.java),
            Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS),
            Mockito.mock(ParticipantStatsCache::class.java), Mockito.mock(WebhookService::class.java),
        ) {
            override fun getStudy(studyId: UUID): Study = Study(
                studyId = studyId, title = "quality pin", contact = "test@example.org",
                settings = Study.initialSettings("quality pin"),
            )
        }
        return DataQualityService(resolver, studyService)
    }

    private fun connectionProxy(connection: Connection, observe: (String) -> Unit): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            if (method.name == "prepareStatement") {
                val sql = args!![0] as String
                val phase = when {
                    sql == StudyService.GET_STUDY_PARTICIPANT_STATS -> "statistics"
                    sql.contains("INSERT INTO data_quality_alerts") -> "inserts"
                    else -> null
                }
                if (phase != null) {
                    return@newProxyInstance Proxy.newProxyInstance(
                        PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java),
                    ) { _, statementMethod, statementArgs ->
                        val executing = statementMethod.name == "executeQuery" || statementMethod.name == "executeBatch"
                        if (executing) observe("before $phase")
                        val statementResult = invoke(result!!, statementMethod, statementArgs)
                        if (executing) observe("after $phase")
                        statementResult
                    }
                }
            }
            result
        } as Connection

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }

    private fun seedStatistics(): UUID {
        val studyId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO participant_stats (study_id, participant_id, android_unique_dates) VALUES (?, 'p1', ?)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setArray(2, connection.createArrayOf("date", arrayOf(LocalDate.now(ZoneOffset.UTC))))
                assertEquals(1, statement.executeUpdate())
            }
        }
        return studyId
    }

    private fun tryDeletionLock(studyId: UUID): Boolean = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "SELECT pg_try_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getBoolean(1)
            }
        }
    }

    private fun alertCount(studyId: UUID): Int = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT count(*) FROM data_quality_alerts WHERE study_id = ?").use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }
    }
}
