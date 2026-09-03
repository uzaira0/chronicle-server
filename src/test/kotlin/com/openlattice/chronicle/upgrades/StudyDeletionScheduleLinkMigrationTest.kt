package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class StudyDeletionScheduleLinkMigrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_study_deletion_schedule_upgrade")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)

            val result = flywayTargeting("84").migrate()
            check(result.success) { "Flyway failed to establish the V84 upgrade boundary: ${result.warnings}" }
            check(result.targetSchemaVersion == "84") {
                "Expected the pre-V85 schema boundary, got ${result.targetSchemaVersion}"
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
    fun testV85AdoptsLedgerAndPreLedgerSchedulesWithoutLosingRestorationState() {
        val linkedStudyId = UUID.randomUUID()
        val legacyStudyId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val mismatchedOperationId = UUID.randomUUID()
        val deleteAfter = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14).withNano(0)

        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO studies (study_id, title, lifecycle_status)
                VALUES (?, 'linked deletion schedule', 'SCHEDULED_FOR_DELETION'),
                       (?, 'pre-ledger deletion schedule', 'SCHEDULED_FOR_DELETION')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, linkedStudyId)
                statement.setObject(2, legacyStudyId)
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_lifecycle_events
                    (event_id, study_id, previous_status, new_status, changed_by, created_at)
                VALUES (?, ?, 'ARCHIVED', 'SCHEDULED_FOR_DELETION', 'upgrade-test', ?),
                       (?, ?, 'ACTIVE', 'SCHEDULED_FOR_DELETION', 'upgrade-test', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, linkedStudyId)
                statement.setObject(3, deleteAfter.minusHours(1))
                statement.setObject(4, UUID.randomUUID())
                statement.setObject(5, legacyStudyId)
                statement.setObject(6, deleteAfter.minusHours(1))
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations
                    (operation_id, study_id, mode, status, requested_by,
                     idempotency_key, registry_version, quarantine_until)
                VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'upgrade-test', ?, 1, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setObject(2, linkedStudyId)
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, deleteAfter)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations
                    (operation_id, study_id, mode, status, requested_by,
                     idempotency_key, registry_version, quarantine_until,
                     cancelled_by, cancelled_at)
                VALUES (?, ?, 'STUDY_ERASURE', 'CANCELLED', 'upgrade-test', ?, 1, ?,
                        'upgrade-test', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, mismatchedOperationId)
                statement.setObject(2, linkedStudyId)
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, deleteAfter)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_deletion_schedule (study_id, scheduled_by, delete_after)
                VALUES (?, 'upgrade-test', ?), (?, 'upgrade-test', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, linkedStudyId)
                statement.setObject(2, deleteAfter)
                statement.setObject(3, legacyStudyId)
                statement.setObject(4, deleteAfter)
                assertEquals(2, statement.executeUpdate())
            }
        }

        val migration = flywayTargeting("85").migrate()
        assertTrue(migration.success)
        assertEquals(1, migration.migrationsExecuted)
        assertEquals("85", migration.targetSchemaVersion)

        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                """
                SELECT operation_id, previous_status
                FROM study_deletion_schedule
                WHERE study_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, linkedStudyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(operationId, resultSet.getObject("operation_id", UUID::class.java))
                    assertEquals("ARCHIVED", resultSet.getString("previous_status"))
                }
                statement.setObject(1, legacyStudyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull(resultSet.getObject("operation_id"))
                    assertEquals("ACTIVE", resultSet.getString("previous_status"))
                }
            }

            val mismatch = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    "UPDATE study_deletion_schedule SET operation_id = ? WHERE study_id = ?",
                ).use { statement ->
                    statement.setObject(1, mismatchedOperationId)
                    statement.setObject(2, legacyStudyId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23503", mismatch.sqlState)
        }
    }
}
