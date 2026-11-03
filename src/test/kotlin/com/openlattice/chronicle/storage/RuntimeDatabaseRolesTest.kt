package com.openlattice.chronicle.storage

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.pods.tables.PostgresTablesPod
import com.openlattice.chronicle.pods.tables.PostgresEventTablesPod
import com.openlattice.chronicle.pods.tables.PostgresDataTablesPod
import com.openlattice.chronicle.upgrades.FlywayMigrationService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID

class RuntimeDatabaseRolesTest {
    @Test
    fun `runtime validates its physical login without configuring an owner pool`() {
        val configurations = ChronicleContractTestSchema.storageConfigurations(ChronicleContractTestSchema.sharedPostgres, 2)
            .filterKeys { it != ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE }
        DataSourceManager(configurations, HealthCheckRegistry(), MetricRegistry()).use { manager ->
            val storage = StorageResolver(manager, ChronicleStorageConfiguration())
            storage.databaseRoles.validate()
            assertFalse(manager.dataSources.containsKey(ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE))
            storage.getPlatformStorage().connection.use { connection ->
                assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(connection, "SELECT session_user"))
            }
        }
    }

    @Test
    fun `startup rejects a privileged physical runtime login despite its request role`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val configuration = PostgresConfiguration(Properties().apply {
            setProperty("jdbcUrl", postgres.jdbcUrl)
            setProperty("username", postgres.username)
            setProperty("password", postgres.password)
            setProperty("maximumPoolSize", "2")
            setProperty("minimumIdle", "0")
        }, initializeTables = false, initializeIndices = false)
        DataSourceManager(
            mapOf("default" to configuration, "chronicle" to configuration,
                ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE to configuration),
            HealthCheckRegistry(), MetricRegistry(),
        ).use { manager ->
            val storage = StorageResolver(manager, ChronicleStorageConfiguration())
            assertThrows(IllegalStateException::class.java) { FlywayMigrationService(storage, emptyList()).runUpgrade() }
        }
    }

    @Test
    fun `restricted physical login cannot regain owner privileges after RESET ROLE`() = withRestrictedStorage { storage ->
        FlywayMigrationService(storage, emptyList()).runUpgrade()
        storage.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { it.execute("RESET ROLE") }
            assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(connection, "SELECT session_user"))
            assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(connection, "SELECT current_user"))
            val owner = ChronicleContractTestSchema.sharedPostgres.username
            assertEquals("42501", assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.execute("SET ROLE $owner") }
            }.sqlState)
            assertEquals("42501", assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.execute("ALTER TABLE studies DISABLE ROW LEVEL SECURITY") }
            }.sqlState)
            assertEquals("42501", assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.executeQuery("SELECT * FROM audit") }
            }.sqlState)
        }
    }

    @Test
    fun `runtime routing rejects the bootstrap alias before a connection or study map is borrowed`() =
        withRestrictedStorage { storage ->
            val alias = ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE
            assertThrows(IllegalArgumentException::class.java) { storage.getDataSource(alias) }
            assertThrows(IllegalArgumentException::class.java) { storage.associateStudyWithStorage(UUID.randomUUID(), alias) }
        }

    @Test
    fun `bootstrap scope shares one owner slot with only its explicitly configured runtime pools`() {
        val customAlias = "study-events"
        withRestrictedStorage(ownerSlots = 1, additionalAliases = setOf(customAlias)) { storage ->
            storage.databaseRoles.validate()
            val bootstrap = storage.getBootstrapStorage()
            bootstrap.connection.use { owner ->
                val backend = scalar(owner, "SELECT pg_backend_pid()::text")
                PinnedPlatformConnection.bootstrapSharing(bootstrap, owner, storage.databaseRoles.runtimePools()) {
                    assertBootstrapBorrow(backend, storage.getPlatformStorage())
                    assertBootstrapBorrow(backend, storage.getDataSource(customAlias).dataSource)
                    assertBootstrapBorrow(backend, storage.getBootstrapStorage())
                    assertUnconfiguredPoolIsIndependent(backend)
                    assertFalse(owner.isClosed)
                    assertEquals(1, bootstrap.hikariPoolMXBean.activeConnections)
                }
            }
            storage.getPlatformStorage().connection.use {
                assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(it, "SELECT current_user"))
            }
        }
    }

    @Test
    fun `an otherwise restricted login cannot inherit or set an owner or bypass role`() = withRestrictedStorage { storage ->
        val owner = ChronicleContractTestSchema.sharedPostgres.username
        val app = ChronicleContractTestSchema.RUNTIME_USERNAME
        storage.getBootstrapStorage().connection.use { connection ->
            val bypassRole = scalar(connection,
                "SELECT rolname FROM pg_roles WHERE rolbypassrls AND NOT rolsuper ORDER BY rolname LIMIT 1")
            val unsafeRoles = listOf(owner, bypassRole) + RuntimeDatabaseRoles.UNSAFE_BUILTIN_ROLES
            for (role in unsafeRoles) {
                connection.createStatement().use { it.execute("GRANT $role TO $app") }
                try {
                    assertThrows(IllegalStateException::class.java) { storage.databaseRoles.validate() }
                } finally {
                    connection.createStatement().use { it.execute("REVOKE $role FROM $app") }
                }
            }
        }
        storage.databaseRoles.validate()
    }

    @Test
    fun `fresh startup registers framework tables and migrates only through the separate owner pool`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val jdbcUrl = ChronicleContractTestSchema.createDatabase(postgres, "physical_role_bootstrap")
        DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)
            .use(ChronicleContractTestSchema::bootstrapApplicationRoles)
        val configurations = ChronicleContractTestSchema.storageConfigurations(postgres, 2)
        configurations.values.forEach { it.hikariConfiguration.setProperty("jdbcUrl", jdbcUrl) }
        val alias = ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE
        val owner = configurations.getValue(alias).copy(initializeTables = true, initializeIndices = true)
        DataSourceManager(configurations + (alias to owner), HealthCheckRegistry(), MetricRegistry()).use { manager ->
            val storage = StorageResolver(manager, ChronicleStorageConfiguration())
            val tables = listOf(PostgresTablesPod().postgresTables(), PostgresEventTablesPod().postgresEventTables(),
                PostgresDataTablesPod().postgresDataTables())
            FlywayMigrationService(storage, tables).runUpgrade()
            storage.getBootstrapStorage().connection.use { connection ->
                assertEquals("1", scalar(connection, "SELECT version FROM flyway_schema_history WHERE success AND type = 'SQL'"))
            }
            storage.getPlatformStorage().connection.use { connection ->
                assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(connection, "SELECT session_user"))
            }
        }
    }

    private fun withRestrictedStorage(
        ownerSlots: Int = ChronicleContractTestSchema.BOOTSTRAP_MAXIMUM_POOL_SIZE,
        additionalAliases: Set<String> = emptySet(),
        block: (StorageResolver) -> Unit,
    ) {
        val configurations = ChronicleContractTestSchema.storageConfigurations(ChronicleContractTestSchema.sharedPostgres, 2)
            .toMutableMap()
        additionalAliases.forEach { configurations[it] = configurations.getValue(DataSourceManager.DEFAULT_DATASOURCE) }
        configurations.getValue(ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE).hikariConfiguration.apply {
            setProperty("maximumPoolSize", ownerSlots.toString())
            setProperty("connectionTimeout", "300")
        }
        DataSourceManager(configurations, HealthCheckRegistry(), MetricRegistry()).use { manager ->
            block(StorageResolver(manager, ChronicleStorageConfiguration()))
        }
    }

    private fun assertBootstrapBorrow(backend: String, storage: HikariDataSource) {
        storage.connection.use { nested ->
            assertEquals(backend, scalar(nested, "SELECT pg_backend_pid()::text"))
            assertEquals(ChronicleContractTestSchema.sharedPostgres.username, scalar(nested, "SELECT session_user"))
        }
    }

    private fun assertUnconfiguredPoolIsIndependent(ownerBackend: String) {
        val configuration = ChronicleContractTestSchema.storageConfigurations(ChronicleContractTestSchema.sharedPostgres, 2)
            .getValue(DataSourceManager.DEFAULT_DATASOURCE)
        HikariDataSource(HikariConfig(configuration.hikariConfiguration)).use { unconfigured ->
            PinnedPlatformConnection.resolve(unconfigured).connection.use { independent ->
                assertNotEquals(ownerBackend, scalar(independent, "SELECT pg_backend_pid()::text"))
                assertEquals(ChronicleContractTestSchema.RUNTIME_USERNAME, scalar(independent, "SELECT session_user"))
            }
        }
    }

    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getString(1) }
    }
}
