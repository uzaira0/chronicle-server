package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.StudyDisclosureGeneration
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SlowDownloadCursorTest {
    @Test fun slowConsumerSurvivesTheServerIdleTransactionTimeout() {
        ChronicleContractTestSchema.prodPostgresContainer("slow_download").use { postgres ->
            postgres.start()
            HikariDataSource().use { pool ->
                pool.jdbcUrl = postgres.jdbcUrl
                pool.username = postgres.username
                pool.password = postgres.password
                pool.connectionInitSql = "SET idle_in_transaction_session_timeout = '1s'"
                val study = UUID.randomUUID()
                pool.connection.use { seedCursorTables(it, study) }
                pool.connection.use(::assertFiniteIdleBound)
                val resolver = mock<StorageResolver>()
                whenever(resolver.getPlatformReadStorage()).thenReturn(pool)
                whenever(resolver.getPlatformStorage()).thenReturn(pool)
                val now = OffsetDateTime.now()
                val iterator = DataDownloadService(resolver).getParticipantsAppUsageSurveyData(
                    study, emptySet(), now.minusDays(1), now.plusDays(1), ZoneOffset.UTC,
                ).iterator()
                try {
                    assertEquals("slow-consumer", iterator.next()["participant_id"])
                    Thread.sleep(1500)
                    assertEquals(SurveyDownloadFixture.ROW_COUNT - 1, iterator.asSequence().count())
                } finally { (iterator as AutoCloseable).close() }
            }
        }
    }

    private fun assertFiniteIdleBound(connection: Connection) {
        connection.autoCommit = false
        StreamingReadTransaction.configure(connection)
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery("SELECT current_setting('idle_in_transaction_session_timeout')::interval > interval '0'")
            check(rows.next())
            assertTrue("A slow stream must still have a finite database idle bound", rows.getBoolean(1))
        }
        connection.rollback()
    }

    private fun seedCursorTables(connection: Connection, study: UUID) {
        connection.createStatement().use { it.execute(ChroniclePostgresTables.APP_USAGE_SURVEY.createTableQuery()) }
        connection.createStatement().use {
            it.execute("""
                CREATE TABLE studies (study_id uuid PRIMARY KEY, title text NOT NULL,
                    ${StudyDisclosureGeneration.COLUMN} uuid NOT NULL DEFAULT gen_random_uuid())
            """.trimIndent())
        }
        connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'slow export fixture')").use {
            it.setObject(1, study); it.executeUpdate()
        }
        SurveyDownloadFixture.insertRows(connection, study, "slow-consumer", SurveyDownloadFixture.ROW_COUNT)
    }

}
