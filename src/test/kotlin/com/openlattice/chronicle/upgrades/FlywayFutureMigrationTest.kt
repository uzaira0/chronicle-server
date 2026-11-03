package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywayFutureMigrationTest {
    @Test
    fun anUnshippedDifferentV1IsRejectedBeforeReceiptMutation() {
        ChronicleContractTestSchema.prodPostgresContainer("unshipped_v1_receipt_schema").use { postgres ->
            postgres.start()
            val directory = Files.createDirectories(Path.of("build", "test-fixtures", "old-v1-${UUID.randomUUID()}"))
            // An old-format receipt schema, applied by real Flyway under version1; no forged history row.
            Files.writeString(directory.resolve("V1__fresh_schema.sql"), """
                CREATE TABLE public.researcher_create_receipts (
                    principal_id TEXT NOT NULL, route TEXT NOT NULL, idempotency_key TEXT NOT NULL,
                    sealed_response TEXT, PRIMARY KEY (principal_id, route, idempotency_key));
                INSERT INTO public.researcher_create_receipts
                    VALUES ('apikey:historical-user', '/historical-route', 'historical-intent', 'retained-ciphertext');
            """.trimIndent())
            FlywayMigrationService.baseConfiguration().locations("filesystem:$directory")
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()
            val failure = assertThrows(RuntimeException::class.java) {
                ChronicleContractTestSchema.migrate(postgres)
            }
            assertTrue(failure.message.orEmpty().contains("checksum mismatch", ignoreCase = true))
            postgres.createConnection("").use { connection -> assertOldReceiptSchemaUnchanged(connection) }
        }
    }

    @Test
    fun futureVersionsApplyOnceAfterTheFreshBaseline() {
        ChronicleContractTestSchema.prodPostgresContainer("fresh_baseline_future_version").use { postgres ->
            postgres.start()
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            val directory = Files.createTempDirectory("flyway-future-")
            val migration = directory.resolve("V9999__future_version_probe.sql")
            try {
                Files.writeString(migration, "CREATE TABLE future_version_probe (id INTEGER PRIMARY KEY)")
                val flyway = FlywayMigrationService.baseConfiguration()
                    .locations(FlywayMigrationService.MIGRATION_LOCATION, "filesystem:$directory")
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load()
                val result = flyway.migrate()
                assertTrue(result.success)
                assertEquals(1, result.migrationsExecuted)
                assertEquals("9999", result.targetSchemaVersion)
                assertEquals(0, flyway.migrate().migrationsExecuted)
                postgres.createConnection("").use { connection ->
                    assertFutureVersionTableWritable(connection)
                }
            } finally {
                Files.deleteIfExists(migration)
                Files.deleteIfExists(directory)
            }
        }
    }

    private fun assertFutureVersionTableWritable(connection: Connection) {
        connection.createStatement().use { statement ->
            assertEquals(1, statement.executeUpdate("INSERT INTO future_version_probe VALUES (1)"))
        }
    }

    private fun assertOldReceiptSchemaUnchanged(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT sealed_response FROM researcher_create_receipts").use { rows ->
                assertTrue(rows.next()); assertEquals("retained-ciphertext", rows.getString(1)); assertFalse(rows.next())
            }
            statement.executeQuery("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'researcher_create_receipts' AND column_name = 'owner_kind'),
                    to_regclass('public.studies') IS NOT NULL,
                    (SELECT count(*) FROM flyway_schema_history WHERE success)
            """.trimIndent()).use { rows ->
                assertTrue(rows.next()); assertFalse(rows.getBoolean(1)); assertFalse(rows.getBoolean(2))
                assertEquals(1, rows.getInt(3))
            }
        }
    }
}
