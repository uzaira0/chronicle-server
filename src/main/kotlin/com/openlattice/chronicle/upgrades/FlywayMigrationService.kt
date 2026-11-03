package com.openlattice.chronicle.upgrades

import com.geekbeast.hazelcast.PreHazelcastUpgradeService
import com.openlattice.chronicle.storage.StorageResolver
import com.geekbeast.postgres.PostgresTables
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/**
 * Applies the fresh V1 and future versioned migrations before Hazelcast starts.
 * Registers Rhizome's framework tables through the explicit bootstrap owner before Flyway.
 * Baseline 0 records that bootstrap without skipping V1. Migration and validation failures
 * abort startup; platform and event storage must share the same physical database.
 */
public class FlywayMigrationService(
    private val storageResolver: StorageResolver,
    private val frameworkTables: Collection<PostgresTables>,
) : PreHazelcastUpgradeService {

    public companion object {
        private val logger = LoggerFactory.getLogger(FlywayMigrationService::class.java)
        public const val MIGRATION_LOCATION: String = "classpath:db/migration"
        public const val MIGRATION_SESSION_SQL: String =
            "SET statement_timeout = 0; SET lock_timeout = 0; SET idle_in_transaction_session_timeout = 0"

        /**
         * The baseline GRANT/REVOKEs against the `chronicle` JDBC application role.
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
            // Self-host Postgres bounds every statement and lock wait server-wide; a migration
            // that rewrites a large table must never be cancelled halfway through an upgrade.
            .initSql(MIGRATION_SESSION_SQL)
            .validateMigrationNaming(true)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .baselineDescription("framework-bootstrap")

        internal fun prepareMigrationConnection(conn: Connection) {
            conn.createStatement().use { it.execute(ENSURE_CHRONICLE_ROLE_SQL) }
        }
    }

    override fun runUpgrade() {
        storageResolver.requireDefaultDeletionStorageColocated()
        storageResolver.databaseRoles.validate()
        migrate()
        storageResolver.databaseRoles.validate()
    }

    internal fun migrate() {
        storageResolver.requireDefaultDeletionStorageColocated()
        storageResolver.databaseRoles.registerTables(frameworkTables)
        val dataSource: DataSource = storageResolver.getBootstrapStorage()
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
