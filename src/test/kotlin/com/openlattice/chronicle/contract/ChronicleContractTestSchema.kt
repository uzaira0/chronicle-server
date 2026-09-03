package com.openlattice.chronicle.contract

import com.geekbeast.postgres.PostgresTableDefinition
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
     * Production database image (Percona PG 17.5 + pg_tde) — the single test-side pin.
     * Plain `postgres:16` containers previously used here drifted from prod; keep every
     * Postgres testcontainer on this constant.
     */
    const val PROD_POSTGRES_IMAGE = "percona/percona-distribution-postgresql:18.4-5"

    fun prodPostgresContainer(databaseName: String): PostgreSQLContainer<*> =
        PostgreSQLContainer(
            DockerImageName.parse(PROD_POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres")
        ).apply {
            withDatabaseName(databaseName)
            withUsername("testuser")
            withPassword("testpass")
            // Explicit command, mirroring TdePrincipalKeyRotationTest: the Percona image's
            // entrypoint misbehaves under the PostgreSQLContainer default command wiring.
            withCommand("postgres", "-c", "fsync=off")
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
                f.isAccessible = true
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
        conn.createStatement().use { stmt ->
            for (table in allChronicleTableDefinitions()) {
                stmt.execute(table.createTableQuery())
            }
            stmt.execute(PostgresEventTables.CHRONICLE_USAGE_EVENTS.createTableQuery())
            stmt.execute(PostgresEventTables.CHRONICLE_USAGE_STATS.createTableQuery())
            stmt.execute(PostgresEventTables.IOS_SENSOR_DATA.createTableQuery())
            stmt.execute(PostgresEventTables.PREPROCESSED_USAGE_EVENTS.createTableQuery())
            // Production creates the final audit store from PostgresDataTables, whose full-row
            // primary key makes V54's ON CONFLICT drain idempotent. The event-table definition has
            // the same columns but no key and would give migration tests weaker semantics.
            stmt.execute(PostgresDataTables.AUDIT.createTableQuery())
            stmt.execute("CREATE ROLE chronicle")
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
        container.createConnection("").use { conn -> applyFrameworkSchema(conn) }
        val result = migrate(container)
        check(result.success) { "Flyway corpus failed to apply: ${result.warnings}" }
        container.createConnection("").use { conn -> applyRestrictedApplicationRoleGrants(conn) }
    }

    /**
     * Mirrors the post-schema grants from docker/init-db-roles.sql.
     *
     * V60 creates the no-login chronicle_app role for migration tests, while production's role
     * bootstrap grants that role DML after the restored schema exists. Applying the same grants
     * here lets contract tests exercise background work under the real restricted role instead of
     * accidentally relying on the Testcontainers superuser. Revoke mutable access to append-only
     * audit tables last, matching the production script's final defense-in-depth boundary.
     */
    private fun applyRestrictedApplicationRoleGrants(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("GRANT USAGE ON SCHEMA public TO chronicle_app")
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO chronicle_app")
            stmt.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO chronicle_app")
            stmt.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO chronicle_app")
            stmt.execute(
                "REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs, study_settings_audit, " +
                    "participant_collection_acknowledgment, mobile_withdrawal_requests FROM chronicle_app"
            )
            stmt.execute(
                "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON " +
                    "data_collection_settings_revisions FROM chronicle_app"
            )
            stmt.execute("REVOKE ALL ON restore_continuity_reconciliations FROM chronicle_app")
            stmt.execute(
                "REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_app"
            )
            stmt.execute("REVOKE CREATE ON SCHEMA public FROM chronicle_app")
        }
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
