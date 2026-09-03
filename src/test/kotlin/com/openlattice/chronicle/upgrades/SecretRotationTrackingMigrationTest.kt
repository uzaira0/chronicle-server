package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer

class SecretRotationTrackingMigrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_rotation_tracking_upgrade")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)

            val result = flywayTargeting("82").migrate()
            check(result.success) { "Flyway failed to establish the V82 upgrade boundary: ${result.warnings}" }
            check(result.targetSchemaVersion == "82") {
                "Expected the pre-V83 schema boundary, got ${result.targetSchemaVersion}"
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
    fun testV83AdoptsLegacyOperatorTableWithoutLosingRotationHistory() {
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE secret_rotation_tracking (
                        secret_name TEXT PRIMARY KEY,
                        last_rotated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        rotated_by TEXT,
                        notes TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO secret_rotation_tracking (secret_name, rotated_by, notes)
                    VALUES ('jwt_signing_secret', 'legacy-rotate-secret.sh', 'preserve-me')
                    """.trimIndent(),
                )
            }
        }

        val migration = flywayTargeting("83").migrate()
        assertTrue(migration.success)
        assertEquals(1, migration.migrationsExecuted)
        assertEquals("83", migration.targetSchemaVersion)

        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO secret_rotation_tracking (secret_name, rotated_by, notes)
                    VALUES ('jwt_signing_secret', 'selfhost/rotate-secret.sh', 'migrated')
                    ON CONFLICT (secret_name) DO UPDATE SET
                        last_rotated = EXCLUDED.last_rotated,
                        rotated_by = EXCLUDED.rotated_by,
                        notes = EXCLUDED.notes
                    """.trimIndent(),
                )
                statement.executeQuery(
                    """
                    SELECT rotated_by, notes
                    FROM secret_rotation_tracking
                    WHERE secret_name = 'jwt_signing_secret'
                    """.trimIndent(),
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("selfhost/rotate-secret.sh", resultSet.getString("rotated_by"))
                    assertEquals("migrated", resultSet.getString("notes"))
                }
            }
        }
    }
}
