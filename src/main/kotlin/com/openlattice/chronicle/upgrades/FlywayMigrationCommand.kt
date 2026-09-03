package com.openlattice.chronicle.upgrades

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Owner-credential, one-shot Flyway entry point for hardened deployments.
 *
 * The long-running backend connects as chronicle_app and must never receive DDL ownership.
 * Operators run this command in a short-lived container attached only to the database network.
 */
public object FlywayMigrationCommand {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty()) { "FlywayMigrationCommand does not accept command-line arguments" }

        val jdbcUrl = requiredEnvironment("POSTGRES_MIGRATION_JDBC_URL")
        require(jdbcUrl.startsWith("jdbc:postgresql://") && '\n' !in jdbcUrl && '\r' !in jdbcUrl) {
            "POSTGRES_MIGRATION_JDBC_URL must be a PostgreSQL JDBC URL"
        }
        val user = requiredEnvironment("POSTGRES_MIGRATION_USER")
        require(user.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,62}"))) {
            "POSTGRES_MIGRATION_USER is not a valid PostgreSQL role name"
        }
        val passwordPath = Path.of(requiredEnvironment("POSTGRES_MIGRATION_PASSWORD_FILE"))
        require(passwordPath.isAbsolute && Files.isRegularFile(passwordPath)) {
            "POSTGRES_MIGRATION_PASSWORD_FILE must identify a mounted regular file"
        }
        val password = Files.readString(passwordPath).trimEnd('\r', '\n')
        require(password.isNotEmpty()) { "PostgreSQL migration password file is empty" }

        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            FlywayMigrationService.prepareMigrationConnection(connection)
        }
        val result = FlywayMigrationService.baseConfiguration()
            .dataSource(jdbcUrl, user, password)
            .load()
            .migrate()
        println(
            "Flyway owner migration complete: ${result.migrationsExecuted} applied, " +
                "schema version ${result.targetSchemaVersion ?: "unchanged"}"
        )
    }

    private fun requiredEnvironment(name: String): String {
        return requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
            "$name is required"
        }
    }
}
