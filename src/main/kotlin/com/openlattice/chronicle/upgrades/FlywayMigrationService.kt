package com.openlattice.chronicle.upgrades

import com.geekbeast.hazelcast.PreHazelcastUpgradeService
import com.openlattice.chronicle.storage.StorageResolver
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * Runs the Flyway migration corpus (`src/main/resources/db/migration`) against the platform
 * storage before Hazelcast starts, replacing the per-class SqlMigrationUpgrade system.
 *
 * Fail-closed by design: any pending migration that cannot be applied, any checksum mismatch,
 * and any validation error propagates and aborts startup. The old upgrade classes caught
 * failures and logged warnings, which let a broken schema serve traffic — that contract is
 * intentionally gone (see docs/db/MIGRATION-LEDGER-AUDIT.md).
 *
 * Ordering: framework tables are created by DataSourceManager's PostgresTables registration,
 * which is a bean-wiring dependency of StorageResolver and therefore of this service — so the
 * corpus always runs against a bootstrapped (non-empty) schema, exactly like the contract test
 * harness. That is why [baseConfiguration] sets `baselineOnMigrate` with baseline version 0:
 * the first migrate against a bootstrapped-but-unmigrated schema records baseline 0 and applies
 * the full corpus from V1. Production databases are instead explicitly baselined at the version
 * recorded in docs/db/MIGRATION-LEDGER-AUDIT.md before this service ever runs, so
 * baselineOnMigrate never fires there.
 *
 * Restored-backup guard: a database restored from a PRE-cutover backup has converged
 * application tables and legacy `upgrades` rows but no `flyway_schema_history` — the one state
 * where baselineOnMigrate would do the wrong thing (mint baseline 0 and replay the entire
 * corpus over converged production data, fabricating ledger provenance). [runUpgrade] refuses
 * that state before migrate runs, mirroring scripts/flyway-migrate.sh's deploy-time refusal:
 * legacy upgrade rows + no Flyway history ⇒ abort with manual-baseline instructions. A fresh
 * install passes the guard because its framework-bootstrapped `upgrades` table is empty.
 *
 * Platform and event storage point at the same physical database in every supported deployment
 * (the corpus spans both), so migrations run once against platform storage.
 */
public class FlywayMigrationService(
    private val storageResolver: StorageResolver,
) : PreHazelcastUpgradeService {

    public companion object {
        private val logger = LoggerFactory.getLogger(FlywayMigrationService::class.java)
        public const val MIGRATION_LOCATION: String = "classpath:db/migration"

        /**
         * The corpus GRANT/REVOKEs against the `chronicle` JDBC application role (V15 onward).
         * Production init scripts own the real login role; test and dev bootstraps get a
         * NOLOGIN placeholder so the corpus is self-sufficient. No-op when the role exists.
         */
        public const val ENSURE_CHRONICLE_ROLE_SQL: String =
            "DO \$\$ BEGIN " +
                "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chronicle') THEN " +
                "CREATE ROLE chronicle; " +
                "END IF; END \$\$;"

        /**
         * Single source of Flyway settings for production startup, the contract test harness,
         * and the corpus gate — divergence here would make tests prove the wrong runner.
         */
        public fun baseConfiguration(): FluentConfiguration = Flyway.configure()
            .locations(MIGRATION_LOCATION)
            .validateMigrationNaming(true)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .baselineDescription("framework-bootstrap")

        internal fun prepareMigrationConnection(conn: Connection) {
            conn.createStatement().use { it.execute(ENSURE_CHRONICLE_ROLE_SQL) }
            refuseUnbaselinedPreCutoverSchema(conn)
        }

        private fun refuseUnbaselinedPreCutoverSchema(conn: Connection) {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT
                        to_regclass('public.flyway_schema_history') IS NOT NULL AS has_history,
                        COALESCE((SELECT count(*) FROM upgrades), 0) AS legacy_rows
                    """.trimIndent()
                ).use { rs ->
                    rs.next()
                    val hasHistory = rs.getBoolean("has_history")
                    val legacyRows = rs.getLong("legacy_rows")
                    check(hasHistory || legacyRows == 0L) {
                        "Database has $legacyRows legacy upgrade-ledger rows but no flyway_schema_history — " +
                            "this looks like a restore from a pre-Flyway-cutover backup. Refusing to " +
                            "auto-baseline at 0 (that would replay the entire migration corpus over " +
                            "converged data). Baseline manually at the version recorded in " +
                            "docs/db/MIGRATION-LEDGER-AUDIT.md before starting the backend."
                    }
                }
            }
        }
    }

    override fun runUpgrade() {
        storageResolver.requireDefaultDeletionStorageColocated()
        val dataSource: DataSource = storageResolver.getPlatformStorage()
        dataSource.connection.use { conn ->
            prepareMigrationConnection(conn)
        }
        val result = baseConfiguration().dataSource(dataSource).load().migrate()
        logger.info(
            "Flyway migration complete: {} applied, schema version {}",
            result.migrationsExecuted,
            result.targetSchemaVersion ?: "unchanged",
        )
    }

}
