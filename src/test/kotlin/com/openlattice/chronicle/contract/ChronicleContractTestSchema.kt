package com.openlattice.chronicle.contract

import com.geekbeast.postgres.PostgresTableDefinition
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import java.util.Properties
import java.util.UUID
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresDataTables
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.upgrades.FlywayMigrationService
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/**
 * Single source for the Testcontainers schema/migration bootstrap shared by the
 * contract suites (CollectionModuleCoverageMatrixDbTest, PayloadFixtureIngestionTest)
 * and FlywayMigrationCorpusTest. Any bootstrap change (new framework table, role
 * grant, migration-runner change) belongs HERE — the suites must never validate
 * against divergent schemas.
 *
 * The migration corpus is executed by REAL Flyway (the same flyway-core the production
 * FlywayMigrationService runs), from the same classpath location — not a hand-rolled
 * file loop — so statement splitting, ordering, and naming validation are
 * production-faithful.
 */
object ChronicleContractTestSchema {

    /**
     * Production database image with pg_tde — the single test-side pin.
     * Plain `postgres:16` containers previously used here drifted from prod; keep every
     * Postgres testcontainer on this constant. Pinned by the 18.6.1-4 index digest from
     * toolchain-manifest.yaml (Testcontainers rejects tag@digest, so the tag lives there).
     */
    const val PROD_POSTGRES_IMAGE = "percona/percona-distribution-postgresql@sha256:27a1dba816fcc4cec88f216d54e3ec86db4c1d4b16799f49800055c41606e11e"
    const val TEST_PASSWORD = "testpass"
    const val BOOTSTRAP_MAXIMUM_POOL_SIZE = 2
    const val RUNTIME_USERNAME = "chronicle_app"
    const val RUNTIME_PASSWORD = "chronicle-app-test-password"
    const val LOCK_TIMEOUT = "15s"
    const val STATEMENT_TIMEOUT = "30s"

    fun prodPostgresContainer(databaseName: String, preload: String? = null): PostgreSQLContainer<*> =
        PostgreSQLContainer(
            DockerImageName.parse(PROD_POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres")
        ).apply {
            withDatabaseName(databaseName)
            withUsername("testuser")
            withPassword(TEST_PASSWORD)
            // The image otherwise initializes SQL_ASCII and counts UTF-8 bytes as characters.
            withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8")
            // Explicit command, mirroring TdePrincipalKeyRotationTest: the Percona image's
            // entrypoint misbehaves under the PostgreSQLContainer default command wiring.
            // Bound every test connection, including direct barrier/observer connections.
            // Flyway clears these limits in its migration session.
            val command = mutableListOf(
                "postgres", "-c", "fsync=off", "-c", "lock_timeout=$LOCK_TIMEOUT",
                "-c", "statement_timeout=$STATEMENT_TIMEOUT",
            )
            preload?.let { command.addAll(listOf("-c", "shared_preload_libraries=$it")) }
            withCommand(*command.toTypedArray())
            // The Percona image routes server logs through a logging collector, so the
            // default log-message wait never matches; wait on the port, then on pg_isready
            // so start() only returns once queries are accepted ("starting up" window) —
            // @ClassRule users connect immediately after start.
            waitingFor(
                WaitAllStrategy(WaitAllStrategy.Mode.WITH_OUTER_TIMEOUT)
                    .withStrategy(Wait.forListeningPort())
                    .withStrategy(Wait.forSuccessfulCommand("pg_isready"))
                    .withStartupTimeout(Duration.ofMinutes(3))
            )
        }

    fun waitForQueryReady(container: PostgreSQLContainer<*>, timeout: Duration = Duration.ofMinutes(2)) {
        val deadline = Instant.now().plus(timeout)
        var last: Exception? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                container.createConnection("").use { conn ->
                    conn.createStatement().use { it.execute("SELECT 1") }
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("Postgres container never became query-ready", last)
    }

    /**
     * All PostgresTableDefinition fields from ChroniclePostgresTables via reflection,
     * mirroring the approach used by PostgresTablesPod in production. `fields` and
     * `declaredFields` overlap for public declared fields, hence distinctBy.
     */
    fun allChronicleTableDefinitions(): List<PostgresTableDefinition> =
        (ChroniclePostgresTables::class.java.fields.asSequence() +
            ChroniclePostgresTables::class.java.declaredFields.asSequence())
            .filter { f: Field -> Modifier.isStatic(f.modifiers) && Modifier.isFinal(f.modifiers) }
            .filter { f: Field -> PostgresTableDefinition::class.java.isAssignableFrom(f.type) }
            .map { f: Field ->
                f[null] as PostgresTableDefinition
            }
            .distinctBy { it.name }
            .toList()

    /**
     * Framework base schema (everything the rhizome PostgresTables framework creates
     * in production, constraints included), the event-storage tables, and the
     * `chronicle` JDBC application role that the migration corpus GRANT/REVOKEs against.
     */
    fun applyFrameworkSchema(conn: Connection) {
        bootstrapApplicationRoles(conn)
        conn.createStatement().use { stmt ->
            for (table in allChronicleTableDefinitions()) {
                stmt.execute(table.createTableQuery())
            }
            // Match the production framework definitions; V1 adds the usage-event identity constraint.
            stmt.execute(PostgresEventTables.CHRONICLE_USAGE_EVENTS.createTableQuery())
            stmt.execute(PostgresDataTables.CHRONICLE_USAGE_STATS.createTableQuery())
            stmt.execute(PostgresDataTables.AUDIT.createTableQuery())
            stmt.execute(FlywayMigrationService.ENSURE_CHRONICLE_ROLE_SQL)
        }
    }

    /** Real Flyway with the exact production configuration (single source: FlywayMigrationService). */
    fun flywayFor(jdbcUrl: String, username: String, password: String): Flyway =
        FlywayMigrationService.baseConfiguration()
            .dataSource(jdbcUrl, username, password)
            .load()

    fun migrate(container: PostgreSQLContainer<*>): MigrateResult =
        flywayFor(container.jdbcUrl, container.username, container.password).migrate()

    /** Framework bootstrap + full Flyway corpus, exactly like a production fresh install. */
    fun applyFrameworkSchemaAndMigrations(container: PostgreSQLContainer<*>) {
        container.createConnection("").use { conn ->
            applyFrameworkSchema(conn)
        }
        val result = migrate(container)
        check(result.success) { "Flyway corpus failed to apply: ${result.warnings}" }
    }

    /** Restore the standard public schema only inside a suite's private container. */
    fun clearSchema(container: PostgreSQLContainer<*>) {
        container.createConnection("").use { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION pg_database_owner; GRANT USAGE ON SCHEMA public TO PUBLIC")
            }
        }
    }

    /** Each case exercises the full fresh schema and its real default privileges. */
    fun resetSchemaAndMigrations(container: PostgreSQLContainer<*>) {
        clearSchema(container)
        applyFrameworkSchemaAndMigrations(container)
    }

    /**
     * Run the shipped role bootstrap before schema creation so Flyway's REVOKEs remain effective.
     */
    fun bootstrapApplicationRoles(conn: Connection) {
        FlywayMigrationService.prepareMigrationConnection(conn)
        val script = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docker/init-db-roles.sql") }
            .first { Files.isRegularFile(it) }
        conn.createStatement().use { stmt ->
            stmt.execute(Files.readString(script))
            stmt.execute("ALTER ROLE $RUNTIME_USERNAME LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$RUNTIME_PASSWORD'")
        }
    }

    fun createDatabase(container: PostgreSQLContainer<*>, prefix: String): String {
        val database = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
        container.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $database") }
        }
        return container.jdbcUrl.substringBeforeLast('/') + "/$database"
    }

    fun storageConfigurations(container: PostgreSQLContainer<*>, maximumPoolSize: Int): Map<String, PostgresConfiguration> {
        fun configuration(username: String, password: String, size: Int) = PostgresConfiguration(
            Properties().apply {
                setProperty("jdbcUrl", container.jdbcUrl)
                setProperty("username", username)
                setProperty("password", password)
                setProperty("maximumPoolSize", size.toString())
            }, initializeTables = false, initializeIndices = false,
        )
        val runtime = configuration(RUNTIME_USERNAME, RUNTIME_PASSWORD, maximumPoolSize)
        val owner = configuration(container.username, container.password, BOOTSTRAP_MAXIMUM_POOL_SIZE)
        owner.hikariConfiguration.setProperty("minimumIdle", "0")
        return mapOf("default" to runtime, "chronicle" to runtime, "platform_read" to runtime,
            ChronicleStorageConfiguration.DEFAULT_BOOTSTRAP_STORAGE to owner)
    }

    /**
     * One shared prod-image container, bootstrapped exactly once with
     * [applyFrameworkSchemaAndMigrations], for the read-only matrix suite and the
     * fixture-ingestion suite. The ingestion suite only inserts rows (per-family
     * participants, ON CONFLICT semantics) and the matrix suite only reads
     * pg_catalog/information_schema, so sharing is order-independent. Intentionally
     * never stopped here: Testcontainers' Ryuk reaps it when the test JVM exits.
     */
    val sharedPostgres: PostgreSQLContainer<*> by lazy {
        val container = prodPostgresContainer("chronicle_contract_test")
        container.start()
        waitForQueryReady(container)
        applyFrameworkSchemaAndMigrations(container)
        container
    }
}
