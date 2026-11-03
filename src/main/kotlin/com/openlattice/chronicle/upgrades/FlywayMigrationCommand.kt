package com.openlattice.chronicle.upgrades

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.mapstores.stats.TransactionOnlyParticipantStatsCache
import com.openlattice.chronicle.pods.tables.PostgresTablesPod
import com.openlattice.chronicle.pods.tables.PostgresDataTablesPod
import com.openlattice.chronicle.pods.tables.PostgresEventTablesPod
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler
import com.openlattice.chronicle.storage.ChronicleStorage
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext

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

        val properties = poolProperties().apply {
            setProperty("jdbcUrl", jdbcUrl)
            setProperty("username", user)
            setProperty("password", password)
        }
        migrate(properties)
    }

    private fun poolProperties(): Properties {
        val resource = checkNotNull(javaClass.getResourceAsStream("/bootstrap-pools.json"))
        val profiles = resource.use { ObjectMappers.newJsonMapper().readValue<Map<String, Properties>>(it) }
        val profile = System.getenv("POSTGRES_MIGRATION_PROFILE") ?: "default"
        return requireNotNull(profiles[profile]) { "POSTGRES_MIGRATION_PROFILE is not a configured deployment profile" }
    }

    private fun migrate(properties: Properties) {
        val owner = PostgresConfiguration(properties, initializeTables = true, initializeIndices = true)
        val runtime = owner.copy(initializeTables = false, initializeIndices = false)
        DataSourceManager(mapOf(
            DataSourceManager.DEFAULT_DATASOURCE to runtime,
            ChronicleStorage.CHRONICLE.id to runtime,
            ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE to owner,
        ), HealthCheckRegistry(), MetricRegistry()).use { manager ->
            val storage = StorageResolver(manager, ChronicleStorageConfiguration())
            val tables = listOf(PostgresTablesPod().postgresTables(), PostgresDataTablesPod().postgresDataTables(),
                PostgresEventTablesPod().postgresEventTables())
            FlywayMigrationService(storage, tables).migrate()
            val erasure = DataDeletionOrchestrator(storage, PostgresAuditingManager(storage), TransactionOnlyParticipantStatsCache)
            RLSRequestContext.withSystemContext { RestoreContinuityReconciler(storage, erasure).reconcile() }
        }
        println("Owner migration and restore continuity complete")
    }

    private fun requiredEnvironment(name: String): String {
        return requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
            "$name is required"
        }
    }
}
