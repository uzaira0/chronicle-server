package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.annotation.PostConstruct
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors TDE (Transparent Data Encryption) health across all encrypted tables.
 *
 * On startup and hourly thereafter, queries PostgreSQL to verify each public
 * application table is actually encrypted via pg_tde_is_encrypted().
 *
 * Exposes GET /internal/health/encryption for monitoring/alerting.
 */
@RestController
public class EncryptionHealthService(
    private val storageResolver: StorageResolver
) {

    public companion object {
        private val logger = LoggerFactory.getLogger(EncryptionHealthService::class.java)

        /**
         * Path for Prometheus textfile collector metrics.
         * node_exporter reads this via --collector.textfile.directory.
         * Same pattern as backup-chronicle.sh writes to backup-verify-metrics.prom.
         */
        private const val METRICS_FILE = "/var/log/chronicle/encryption-health-metrics.prom"

        private const val PUBLIC_TABLE_ENCRYPTION_QUERY = """
            SELECT c.relname, pg_tde_is_encrypted(c.oid::regclass)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
            AND n.nspname = 'public'
            ORDER BY c.relname
        """
    }

    /** Per-table encryption status from the most recent check. */
    private val tableStatus = ConcurrentHashMap<String, Boolean>()

    /** TDE extension version from the most recent check, or null if unavailable. */
    @Volatile
    private var tdeVersion: String? = null

    /** Whether SSL is active on the database connection. */
    @Volatile
    private var sslActive: Boolean = false

    /** Timestamp of the last successful check. */
    @Volatile
    private var lastCheckTime: Instant? = null

    /** Most recent health check failure. A stale successful table map must not mask this. */
    @Volatile
    private var lastCheckError: String? = "encryption health check has not completed"

    // -----------------------------------------------------------------------
    // Startup check
    // -----------------------------------------------------------------------

    @PostConstruct
    public fun onStartup() {
        logger.info("Running initial TDE encryption health check for public application tables")
        runEncryptionCheck()
    }

    // -----------------------------------------------------------------------
    // Periodic check (hourly)
    // -----------------------------------------------------------------------

    @Scheduled(fixedDelay = 3_600_000)
    public fun periodicCheck() {
        val previousStatus = HashMap(tableStatus)
        runEncryptionCheck()

        // Warn on status changes
        for (table in (previousStatus.keys + tableStatus.keys).sorted()) {
            val prev = previousStatus[table]
            val curr = tableStatus[table]
            if (prev != null && prev != curr) {
                logger.warn(
                    "Encryption status CHANGED for table '{}': {} -> {}",
                    table, prev, curr
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Health endpoint
    // -----------------------------------------------------------------------

    @GetMapping(
        path = ["/internal/health/encryption"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun encryptionHealth(): ResponseEntity<Map<String, Any>> {
        val allEncrypted = lastCheckError == null &&
            tdeVersion != null &&
            tableStatus.isNotEmpty() &&
            tableStatus.values.all { it }
        val failedTables = tableStatus.filterValues { !it }.keys

        val body = linkedMapOf<String, Any>(
            "status" to if (allEncrypted) "PASS" else "FAIL",
            "tde_extension_version" to (tdeVersion ?: "unknown"),
            "ssl_active" to sslActive,
            "last_check" to (lastCheckTime?.toString() ?: "never"),
            "tables" to tableStatus.toSortedMap()
        )

        lastCheckError?.let { body["last_error"] = it }

        if (failedTables.isNotEmpty()) {
            body["failed_tables"] = failedTables.sorted()
        }

        val status = if (allEncrypted) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(body)
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    // reason: boundary catch — a scheduled health probe must record any failure and still emit
    // metrics rather than propagate and kill the scheduler thread
    @Suppress("TooGenericExceptionCaught")
    private fun runEncryptionCheck() {
        try {
            storageResolver.getPlatformStorage().connection.use { conn ->
                checkTdeVersion(conn)
                checkSslStatus(conn)
                checkTableEncryption(conn)
            }
            lastCheckError = null
            lastCheckTime = Instant.now()

            val failedCount = tableStatus.count { !it.value }
            if (failedCount > 0) {
                logger.error(
                    "TDE encryption check: {}/{} tables FAILED encryption verification: {}",
                    failedCount, tableStatus.size,
                    tableStatus.filterValues { !it }.keys.sorted()
                )
            } else {
                logger.info(
                    "TDE encryption check PASSED: all {} tables encrypted (pg_tde {})",
                    tableStatus.size, tdeVersion ?: "unknown"
                )
            }

            writePrometheusMetrics()
        } catch (ex: Exception) {
            lastCheckError = ex.message ?: ex.javaClass.simpleName
            logger.error("TDE encryption health check failed with exception", ex)
            writePrometheusMetrics()
        }
    }

    /**
     * Writes Prometheus exposition format metrics to a .prom file for the
     * node_exporter textfile collector. This bridges the gap between the HTTP
     * health endpoint and the Prometheus alert rules that reference metrics like
     * chronicle_tde_table_encrypted and chronicle_tde_unencrypted_tables.
     *
     * Same pattern used by backup-chronicle.sh -> backup-verify-metrics.prom.
     */
    // reason: boundary catch — best-effort metrics file write must never fail the health check
    @Suppress("TooGenericExceptionCaught")
    private fun writePrometheusMetrics() {
        try {
            val encryptedCount = tableStatus.count { it.value }
            val totalCount = tableStatus.size
            val unencryptedCount = totalCount - encryptedCount
            val checkSucceeded = lastCheckError == null
            val allTablesEncrypted = totalCount > 0 && unencryptedCount == 0
            val tdeAvailable = tdeVersion != null
            val healthOk = if (checkSucceeded && allTablesEncrypted && tdeAvailable) 1 else 0
            val checkFailed = if (lastCheckError == null) 0 else 1

            val sb = StringBuilder()
            sb.appendLine("# HELP chronicle_tde_health_ok Whether the latest TDE health check is passing.")
            sb.appendLine("# TYPE chronicle_tde_health_ok gauge")
            sb.appendLine("chronicle_tde_health_ok $healthOk")
            sb.appendLine("# HELP chronicle_tde_check_failed Whether the latest TDE health check failed to complete.")
            sb.appendLine("# TYPE chronicle_tde_check_failed gauge")
            sb.appendLine("chronicle_tde_check_failed $checkFailed")
            sb.appendLine("# HELP chronicle_tde_table_encrypted Whether a TDE table is encrypted (1) or not (0).")
            sb.appendLine("# TYPE chronicle_tde_table_encrypted gauge")
            for (table in tableStatus.keys.sorted()) {
                val value = if (tableStatus[table] == true) "1" else "0"
                sb.appendLine("chronicle_tde_table_encrypted{table=\"$table\"} $value")
            }
            sb.appendLine("# HELP chronicle_tde_tables_total Total number of tables that should be TDE-encrypted.")
            sb.appendLine("# TYPE chronicle_tde_tables_total gauge")
            sb.appendLine("chronicle_tde_tables_total $totalCount")
            sb.appendLine("# HELP chronicle_tde_tables_encrypted Number of tables currently TDE-encrypted.")
            sb.appendLine("# TYPE chronicle_tde_tables_encrypted gauge")
            sb.appendLine("chronicle_tde_tables_encrypted $encryptedCount")
            sb.appendLine("# HELP chronicle_tde_unencrypted_tables Number of tables NOT TDE-encrypted (should be 0).")
            sb.appendLine("# TYPE chronicle_tde_unencrypted_tables gauge")
            sb.appendLine("chronicle_tde_unencrypted_tables $unencryptedCount")
            sb.appendLine("# HELP chronicle_tde_check_timestamp_seconds Unix timestamp of last TDE health check.")
            sb.appendLine("# TYPE chronicle_tde_check_timestamp_seconds gauge")
            sb.appendLine("chronicle_tde_check_timestamp_seconds ${Instant.now().epochSecond}")

            val metricsFile = File(METRICS_FILE)
            metricsFile.parentFile?.mkdirs()
            // Write atomically: write to tmp then rename to avoid partial reads by node_exporter
            val tmpFile = File("${METRICS_FILE}.tmp")
            tmpFile.writeText(sb.toString())
            tmpFile.renameTo(metricsFile)

            logger.debug("Wrote TDE metrics to {}", METRICS_FILE)
        } catch (ex: Exception) {
            logger.warn("Failed to write Prometheus metrics to {}: {}", METRICS_FILE, ex.message)
        }
    }

    // reason: boundary catch wraps any JDBC/driver failure into a clear IllegalStateException;
    // nested depth is the inherent createStatement.use/executeQuery.use JDBC scaffolding
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun checkTdeVersion(conn: Connection) {
        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT extversion FROM pg_extension WHERE extname = 'pg_tde'"
                ).use { rs ->
                    tdeVersion = if (rs.next()) rs.getString(1) else null
                }
            }
            if (tdeVersion == null) {
                error("pg_tde extension is not installed")
            }
        } catch (ex: Exception) {
            tdeVersion = null
            throw IllegalStateException("Could not verify pg_tde extension version", ex)
        }
    }

    // reason: boundary catch — SSL status is a best-effort probe; any failure degrades to false
    @Suppress("TooGenericExceptionCaught")
    private fun checkSslStatus(conn: Connection) {
        try {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").use { rs ->
                    sslActive = rs.next() && rs.getBoolean(1)
                }
            }
        } catch (ex: Exception) {
            logger.warn("Could not query SSL status: {}", ex.message)
            sslActive = false
        }
    }

    // reason: nested depth is the inherent createStatement.use/executeQuery.use/while-rs JDBC
    // scaffolding for the per-table encryption scan
    @Suppress("NestedBlockDepth")
    private fun checkTableEncryption(conn: Connection) {
        val latestStatus = linkedMapOf<String, Boolean>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(PUBLIC_TABLE_ENCRYPTION_QUERY).use { rs ->
                while (rs.next()) {
                    val table = rs.getString(1)
                    val encrypted = rs.getBoolean(2)
                    latestStatus[table] = encrypted
                    if (!encrypted) {
                        logger.error("Table '{}' is NOT encrypted with TDE", table)
                    }
                }
            }
        }
        if (latestStatus.isEmpty()) {
            error("TDE table discovery returned no public application tables")
        }
        tableStatus.clear()
        tableStatus.putAll(latestStatus)
    }
}
