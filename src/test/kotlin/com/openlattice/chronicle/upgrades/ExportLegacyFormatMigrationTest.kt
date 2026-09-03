package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.util.UUID

class ExportLegacyFormatMigrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_export_format_upgrade")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)

            val result = flywayTargeting("76").migrate()
            check(result.success) { "Flyway failed to establish the V76 upgrade boundary: ${result.warnings}" }
            check(result.targetSchemaVersion == "76") {
                "Expected the pre-V77 schema boundary, got ${result.targetSchemaVersion}"
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun flywayTargeting(version: String) =
            FlywayMigrationService.baseConfiguration()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .target(version)
                .load()
    }

    @Test
    fun testV77TerminalizesAndNormalizesInvalidLegacyFormatWithoutLosingCleanupPath() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val leaseToken = UUID.randomUUID()
        val legacyPath = "/var/lib/chronicle/exports/$exportId.yaml"

        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "legacy-export-format-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    download_token, error_message, file_path, lease_token, lease_expires_at
                ) VALUES (
                    ?, ?, 'RUNNING', 'YAML', '{}'::jsonb, 'legacy-export-worker',
                    'legacy-download-token', 'legacy failure', ?, ?, now() + interval '5 minutes'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, exportId)
                statement.setObject(2, studyId)
                statement.setString(3, legacyPath)
                statement.setObject(4, leaseToken)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val migration = flywayTargeting("77").migrate()
        assertTrue(migration.success)
        assertEquals(1, migration.migrationsExecuted)
        assertEquals("77", migration.targetSchemaVersion)

        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                """
                SELECT status, format, isfinite(completed_at) AS completed_at_is_finite,
                       download_token, error_message, file_path, lease_token,
                       lease_expires_at, recovery_count
                FROM export_jobs
                WHERE export_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, exportId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("FAILED", resultSet.getString("status"))
                    assertEquals("CSV", resultSet.getString("format"))
                    assertTrue(resultSet.getBoolean("completed_at_is_finite"))
                    assertNull(resultSet.getString("download_token"))
                    assertTrue(resultSet.getString("error_message").contains("'YAML'"))
                    assertTrue(resultSet.getString("error_message").contains("artifact cleanup required"))
                    assertEquals(legacyPath, resultSet.getString("file_path"))
                    assertNull(resultSet.getObject("lease_token"))
                    assertNull(resultSet.getObject("lease_expires_at"))
                    assertEquals(0, resultSet.getInt("recovery_count"))
                    assertFalse(resultSet.next())
                }
            }

            val invalidUpdate = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    "UPDATE export_jobs SET format = 'YAML' WHERE export_id = ?",
                ).use { statement ->
                    statement.setObject(1, exportId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", invalidUpdate.sqlState)
        }
    }
}
