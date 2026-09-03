package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.util.UUID

class RuntimeOwnedTablesMigrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_runtime_tables_upgrade")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)

            val result = flywayTargeting("83").migrate()
            check(result.success) { "Flyway failed to establish the V83 upgrade boundary: ${result.warnings}" }
            check(result.targetSchemaVersion == "83") {
                "Expected the pre-V84 schema boundary, got ${result.targetSchemaVersion}"
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
    fun testV84AdoptsLegacyServiceTablesWithoutLosingDataAndAppliesRls() {
        val authorizedStudyId = UUID.randomUUID()
        val unauthorizedStudyId = UUID.randomUUID()
        val blob = byteArrayOf(0x01, 0x23, 0x45, 0x67)

        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE researcher_phone_numbers (
                        principal_id TEXT NOT NULL,
                        phone_number TEXT NOT NULL,
                        verified BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (principal_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE researcher_notification_settings (
                        study_id UUID NOT NULL,
                        principal_id TEXT NOT NULL,
                        settings JSONB NOT NULL DEFAULT '{}'::jsonb,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (study_id, principal_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE local_blob_store (
                        key TEXT,
                        object BYTEA,
                        PRIMARY KEY (key)
                    )
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                """
                INSERT INTO researcher_phone_numbers (principal_id, phone_number, verified)
                VALUES (?, ?, TRUE)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "legacy-owner")
                statement.setString(2, "+15551234567")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO researcher_notification_settings (study_id, principal_id, settings)
                VALUES (?, ?, ?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, authorizedStudyId)
                statement.setString(2, "legacy-owner")
                statement.setString(3, """{"AUDIT_EVENT":["EMAIL"]}""")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("INSERT INTO local_blob_store (key, object) VALUES (?, ?)").use { statement ->
                statement.setString(1, "legacy-blob")
                statement.setBytes(2, blob)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val migration = flywayTargeting("84").migrate()
        assertTrue(migration.success)
        assertEquals(1, migration.migrationsExecuted)
        assertEquals("84", migration.targetSchemaVersion)

        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT phone_number, verified
                    FROM researcher_phone_numbers
                    WHERE principal_id = 'legacy-owner'
                    """.trimIndent(),
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("+15551234567", resultSet.getString("phone_number"))
                    assertTrue(resultSet.getBoolean("verified"))
                }
                statement.executeQuery(
                    "SELECT object FROM local_blob_store WHERE key = 'legacy-blob'",
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                    assertArrayEquals(blob, resultSet.getBytes("object"))
                }
                statement.execute(
                    """
                    INSERT INTO researcher_phone_numbers (principal_id, phone_number)
                    VALUES ('legacy-other', '+15557654321')
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                """
                SELECT settings -> 'AUDIT_EVENT' ->> 0 AS delivery_type
                FROM researcher_notification_settings
                WHERE study_id = ? AND principal_id = 'legacy-owner'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, authorizedStudyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("EMAIL", resultSet.getString("delivery_type"))
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO researcher_notification_settings (study_id, principal_id)
                VALUES (?, 'legacy-other'), (?, 'legacy-owner')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, authorizedStudyId)
                statement.setObject(2, unauthorizedStudyId)
                assertEquals(2, statement.executeUpdate())
            }

            connection.prepareStatement(
                """
                SELECT
                    set_config('app.current_user_id', ?, false),
                    set_config('app.authorized_studies', ?, false),
                    set_config('app.is_admin', 'false', false)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "legacy-owner")
                statement.setString(2, authorizedStudyId.toString())
                statement.executeQuery().use { resultSet -> assertTrue(resultSet.next()) }
            }

            connection.createStatement().use { it.execute("SET ROLE chronicle_app") }
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM researcher_phone_numbers").use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(1, resultSet.getInt(1))
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM researcher_notification_settings").use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(1, resultSet.getInt(1))
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM local_blob_store").use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(1, resultSet.getInt(1))
                    }
                }

                val exception = assertThrows(SQLException::class.java) {
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            INSERT INTO researcher_phone_numbers (principal_id, phone_number)
                            VALUES ('forbidden-principal', '+15550000000')
                            """.trimIndent(),
                        )
                    }
                }
                assertEquals("42501", exception.sqlState)
            } finally {
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        }
    }
}
