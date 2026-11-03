package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

class FlywayMigrationCommandTest {
    private val workDirectory = Path.of(checkNotNull(System.getenv("TMPDIR")))

    @Test
    fun oneShotCommandBootstrapsAFreshDatabaseAndCanBeRepeated() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val jdbcUrl = ChronicleContractTestSchema.createDatabase(postgres, "one_shot_migration")
        val passwordFile = Files.createTempFile(workDirectory, "migration-", ".password")
        try {
            Files.writeString(passwordFile, postgres.password)
            repeat(2) {
                val (exit, output) = runMigrationCommand(migrationEnvironment(jdbcUrl, postgres.username, passwordFile))
                assertEquals(output, 0, exit)
                assertTrue(output, output.contains("Owner migration and restore continuity complete"))
            }
            DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    val rows = statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")
                    assertTrue(rows.next())
                    assertEquals("framework baseline plus every shipped migration", 1 + shippedMigrationCount, rows.getInt(1))
                }
            }
        } finally {
            Files.deleteIfExists(passwordFile)
        }
    }

    /**
     * The selfhost entrypoint hands this command whatever the operator's .env produced. Each mistake must stop
     * it before any connection is opened, with a non-zero exit and the variable to fix, never a half migration.
     */
    @Test
    fun misconfiguredMigrationEnvironmentStopsBeforeConnecting() {
        val password = Files.createTempFile(workDirectory, "migration-", ".password")
        val emptyPassword = Files.createTempFile(workDirectory, "migration-empty-", ".password")
        try {
            Files.writeString(password, "owner-password\n")
            Files.writeString(emptyPassword, "\r\n")
            // Port 1 refuses: if any case got past validation it would fail differently and loudly.
            val valid = migrationEnvironment("jdbc:postgresql://127.0.0.1:1/chronicle", "chronicle_owner", password)
            val cases = listOf(
                Triple(valid, listOf("--force"), "does not accept command-line arguments"),
                Triple(valid - "POSTGRES_MIGRATION_JDBC_URL", emptyList(), "POSTGRES_MIGRATION_JDBC_URL is required"),
                Triple(valid + ("POSTGRES_MIGRATION_JDBC_URL" to "jdbc:mysql://db/chronicle"), emptyList(),
                    "must be a PostgreSQL JDBC URL"),
                Triple(valid + ("POSTGRES_MIGRATION_JDBC_URL" to "jdbc:postgresql://db/chronicle\nuser=postgres"), emptyList(),
                    "must be a PostgreSQL JDBC URL"),
                Triple(valid + ("POSTGRES_MIGRATION_USER" to "owner; DROP ROLE chronicle_app"), emptyList(),
                    "is not a valid PostgreSQL role name"),
                Triple(valid + ("POSTGRES_MIGRATION_USER" to " "), emptyList(), "POSTGRES_MIGRATION_USER is required"),
                Triple(valid + ("POSTGRES_MIGRATION_PASSWORD_FILE" to "relative.password"), emptyList(),
                    "must identify a mounted regular file"),
                Triple(valid + ("POSTGRES_MIGRATION_PASSWORD_FILE" to workDirectory.toString()), emptyList(),
                    "must identify a mounted regular file"),
                Triple(valid + ("POSTGRES_MIGRATION_PASSWORD_FILE" to emptyPassword.toString()), emptyList(),
                    "PostgreSQL migration password file is empty"),
                Triple(valid + ("POSTGRES_MIGRATION_PROFILE" to "kubernetes"), emptyList(),
                    "POSTGRES_MIGRATION_PROFILE is not a configured deployment profile"),
            )
            for ((environment, args, message) in cases) {
                val (exit, output) = runMigrationCommand(environment, args)
                assertNotEquals(output, 0, exit)
                assertTrue("expected '$message' in: $output", output.contains(message))
                assertTrue(output, !output.contains("Owner migration and restore continuity complete"))
            }
        } finally {
            Files.deleteIfExists(password)
            Files.deleteIfExists(emptyPassword)
        }
    }

    private fun migrationEnvironment(jdbcUrl: String, user: String, passwordFile: Path) = mapOf(
        "POSTGRES_MIGRATION_JDBC_URL" to jdbcUrl,
        "POSTGRES_MIGRATION_USER" to user,
        "POSTGRES_MIGRATION_PASSWORD_FILE" to passwordFile.toString(),
    )

    /** Runs the command as the selfhost entrypoint does, a separate JVM, keeping this JVM's coverage agent. */
    private fun runMigrationCommand(environment: Map<String, String>, args: List<String> = emptyList()): Pair<Int, String> {
        val agents = ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-javaagent:") && "jacoco" in it }
        val command = ProcessBuilder(
            listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) + agents +
                listOf("-cp", System.getProperty("java.class.path"), FlywayMigrationCommand::class.java.name) + args,
        ).redirectErrorStream(true)
        command.environment().keys.removeAll { it.startsWith("POSTGRES_MIGRATION_") }
        command.environment().putAll(environment)
        val output = Files.createTempFile(workDirectory, "migration-", ".log")
        try {
            val process = command.redirectOutput(output.toFile()).start()
            try {
                assertTrue("Migration command did not finish", process.waitFor(60, TimeUnit.SECONDS))
                return process.exitValue() to Files.readString(output)
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
