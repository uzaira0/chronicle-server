package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.annotation.PostConstruct
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks rotation status of all secrets used by Chronicle.
 *
 * On startup, logs warnings for any secret that has not been rotated in >90 days.
 * Periodically rechecks (daily). Exposes an internal health endpoint for monitoring.
 *
 * Tracked secrets:
 *   - JWT signing secret
 *   - HMAC mobile signing key
 *   - API keys (per-study, checked via database)
 *   - TLS certificates (PostgreSQL SSL)
 *   - TDE principal key (PostgreSQL pg_tde at-rest encryption key; rotated by
 *     scripts/rotate-tde-principal-key.sh, which stamps the Flyway-owned
 *     secret_rotation_tracking table)
 *
 * HIPAA §164.312(a)(2)(iv) — Encryption key management
 */
@RestController
public class SecretRotationService private constructor(
    private val storageResolver: StorageResolver,
    private val clock: Clock,
    private val metricsFile: Path,
) {

    public constructor(storageResolver: StorageResolver) : this(
        storageResolver = storageResolver,
        clock = Clock.systemUTC(),
        metricsFile = Path.of(METRICS_FILE),
    )

    public companion object {
        private val logger = LoggerFactory.getLogger(SecretRotationService::class.java)

        /** Default maximum age in days before a secret is considered overdue for rotation. */
        public const val ROTATION_MAX_AGE_DAYS: Long = 90

        /**
         * Per-secret overrides for [ROTATION_MAX_AGE_DAYS]. The TDE principal key rotates on a
         * yearly cadence (rotation re-wraps the internal keys, it does not re-encrypt the data),
         * so a 90-day window would warn for most of the year; HIPAA §164.312(a)(2)(iv) calls for
         * periodic key rotation, not 90-day. Secrets with no entry use [ROTATION_MAX_AGE_DAYS].
         */
        public val MAX_AGE_DAYS_BY_SECRET: Map<String, Long> = mapOf(
            "tde_principal_key" to 365L
        )

        /** The overdue threshold (days) for [secretName], honoring per-secret overrides. */
        public fun maxAgeDaysFor(secretName: String): Long =
            MAX_AGE_DAYS_BY_SECRET[secretName] ?: ROTATION_MAX_AGE_DAYS

        internal fun forTest(
            storageResolver: StorageResolver,
            clock: Clock,
            metricsFile: Path,
        ): SecretRotationService = SecretRotationService(storageResolver, clock, metricsFile)

        /** Daily check interval in milliseconds (24 hours). */
        public const val CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

        /**
         * Secret names tracked by this service.
         * The rotation date for each is determined by different mechanisms:
         *   - Operator-managed secrets/certificates: stored in secret_rotation_tracking
         *   - API keys: oldest currently usable key in api_keys
         */
        /**
         * Path for Prometheus textfile collector metrics.
         * node_exporter reads this via --collector.textfile.directory.
         */
        private const val METRICS_FILE = "/var/log/chronicle/secret-rotation-metrics.prom"

        public val TRACKED_SECRETS: List<String> = listOf(
            "jwt_signing_secret",
            "hmac_mobile_signing_key",
            "api_keys",
            "tls_postgres_cert",
            "tde_principal_key"
        )
    }

    /**
     * Holds the last-known rotation date for each secret.
     * If a secret has never been rotated (or we cannot determine the date),
     * it is stored as [Instant.EPOCH].
     */
    private val rotationDates = ConcurrentHashMap<String, Instant>()

    /** Timestamp of the last successful check. */
    @Volatile
    private var lastCheckTime: Instant? = null

    /** Normalized failure code for the latest database refresh, never a raw exception message. */
    @Volatile
    private var refreshError: String? = "not_checked"

    /** Normalized failure code for the latest textfile publication attempt. */
    @Volatile
    private var metricsWriteError: String? = "not_written"

    // -----------------------------------------------------------------------
    // Startup check
    // -----------------------------------------------------------------------

    @PostConstruct
    public fun onStartup() {
        logger.info("Running initial secret rotation status check")
        refreshRotationStatus()
        logOverdueWarnings()
    }

    // -----------------------------------------------------------------------
    // Periodic check (daily)
    // -----------------------------------------------------------------------

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    public fun periodicCheck() {
        refreshRotationStatus()
        logOverdueWarnings()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a map of secret names to their rotation status.
     * Each entry includes: last_rotated, age_days, overdue (boolean).
     */
    public fun checkRotationStatus(): Map<String, SecretStatus> {
        val checkedAt = clock.instant()
        val snapshot = synchronized(rotationDates) { rotationDates.toMap() }
        return TRACKED_SECRETS.associateWith { secretName ->
            // A future ledger value can result from clock skew or bad operator input. Never
            // turn it into a negative age that remains green until wall time catches up; treat
            // it exactly like an unknown date so monitoring fails closed.
            val lastRotated = snapshot[secretName]
                ?.takeIf { it != Instant.EPOCH && !it.isAfter(checkedAt) }
            val ageDays = ChronoUnit.DAYS.between(lastRotated ?: Instant.EPOCH, checkedAt)
            SecretStatus(
                lastRotated = lastRotated,
                ageDays = ageDays,
                overdue = ageDays > maxAgeDaysFor(secretName)
            )
        }
    }

    // -----------------------------------------------------------------------
    // Health endpoint
    // -----------------------------------------------------------------------

    @GetMapping(
        path = ["/internal/health/secrets"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun secretsHealth(): ResponseEntity<Map<String, Any>> {
        val status = checkRotationStatus()
        val overdueSecrets = status.filter { it.value.overdue }
        val refreshFailure = refreshError
        val metricsFailure = metricsWriteError
        val allCurrent = overdueSecrets.isEmpty() && refreshFailure == null && metricsFailure == null

        val secrets = status.map { (name, s) ->
            linkedMapOf<String, Any?>(
                "name" to name,
                "last_rotated" to (s.lastRotated?.toString() ?: "unknown"),
                "age_days" to s.ageDays,
                "overdue" to s.overdue,
                "max_age_days" to maxAgeDaysFor(name)
            )
        }

        val body = linkedMapOf<String, Any>(
            "status" to if (allCurrent) "PASS" else "WARN",
            "last_check" to (lastCheckTime?.toString() ?: "never"),
            "refresh_ok" to (refreshFailure == null),
            "metrics_write_ok" to (metricsFailure == null),
            "secrets" to secrets
        )

        if (overdueSecrets.isNotEmpty()) {
            body["overdue_secrets"] = overdueSecrets.keys.sorted()
        }
        refreshFailure?.let { body["refresh_error"] = it }
        metricsFailure?.let { body["metrics_write_error"] = it }

        // Return 200 for PASS, 200 for WARN (not a hard failure, just advisory)
        // Monitoring systems can alert on the "WARN" status field
        return ResponseEntity.ok(body)
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Refreshes one complete status snapshot. The tracking table is created only by Flyway
     * migration V83. A failed refresh publishes an all-unknown snapshot instead of retaining
     * stale green values from a previous successful check.
     */
    // reason: boundary catch — a failed database refresh must be normalized into fail-closed
    // health/metrics state without taking down the application
    @Suppress("TooGenericExceptionCaught")
    private fun refreshRotationStatus() {
        val nextDates = TRACKED_SECRETS.associateWith { Instant.EPOCH }.toMutableMap()
        try {
            storageResolver.getPlatformStorage().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT secret_name, last_rotated FROM secret_rotation_tracking"
                    ).use { rs ->
                        while (rs.next()) {
                            val name = rs.getString("secret_name")
                            val timestamp = rs.getTimestamp("last_rotated")
                            if (name in TRACKED_SECRETS && name != "api_keys") {
                                nextDates[name] = timestamp?.toInstant() ?: Instant.EPOCH
                            }
                        }
                    }
                }

                // API-key age comes from the oldest usable key on every refresh. A tracking
                // ledger stamp must not hide an older credential that remains valid.
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT MIN(created_at) AS oldest_key
                        FROM api_keys
                        WHERE NOT revoked AND (expires_at IS NULL OR expires_at > NOW())
                        """.trimIndent()
                    ).use { rs ->
                        if (rs.next()) {
                            nextDates["api_keys"] = rs.getTimestamp("oldest_key")?.toInstant() ?: Instant.EPOCH
                        }
                    }
                }
            }

            replaceRotationDates(nextDates)
            lastCheckTime = clock.instant()
            refreshError = null
        } catch (ex: Exception) {
            replaceRotationDates(TRACKED_SECRETS.associateWith { Instant.EPOCH })
            refreshError = normalizedFailureCode(ex, "RefreshFailure")
            logger.error("Failed to refresh secret rotation status; failure_code={}", refreshError)
        }
        writePrometheusMetrics()
    }

    private fun replaceRotationDates(snapshot: Map<String, Instant>) {
        synchronized(rotationDates) {
            rotationDates.clear()
            rotationDates.putAll(snapshot)
        }
    }

    /**
     * Writes Prometheus exposition format metrics to a .prom file for the
     * node_exporter textfile collector. Bridges the HTTP health endpoint to the
     * Prometheus alert rules that reference chronicle_secret_rotation_age_days.
     *
     * Metrics produced:
     *   - chronicle_secret_rotation_days{secret="..."} — days since last rotation
     *   - chronicle_secret_rotation_overdue{secret="..."} — 1.0 if overdue, 0.0 if ok
     *   - chronicle_secret_rotation_age_days{secret_name="..."} — alias matching alert-rules.yml label
     */
    // reason: boundary catch — metrics file write is best-effort observability (IO/permission
    // failures must not break the rotation check); logs and continues
    @Suppress("TooGenericExceptionCaught")
    private fun writePrometheusMetrics() {
        val target = metricsFile.toAbsolutePath().normalize()
        val tmpFile = target.resolveSibling("${target.fileName}.tmp")
        try {
            val status = checkRotationStatus()
            val sb = StringBuilder()

            sb.appendLine("# HELP chronicle_secret_rotation_days Days since last rotation of a tracked secret.")
            sb.appendLine("# TYPE chronicle_secret_rotation_days gauge")
            for ((name, s) in status) {
                sb.appendLine("chronicle_secret_rotation_days{secret=\"$name\"} ${s.ageDays}")
            }

            sb.appendLine("# HELP chronicle_secret_rotation_overdue Whether a secret is overdue for rotation (1=overdue, 0=ok).")
            sb.appendLine("# TYPE chronicle_secret_rotation_overdue gauge")
            for ((name, s) in status) {
                val value = if (s.overdue) "1" else "0"
                sb.appendLine("chronicle_secret_rotation_overdue{secret=\"$name\"} $value")
            }

            // Also emit with secret_name label to match alert-rules.yml expr
            sb.appendLine("# HELP chronicle_secret_rotation_age_days Days since last rotation (matches alert-rules.yml label).")
            sb.appendLine("# TYPE chronicle_secret_rotation_age_days gauge")
            for ((name, s) in status) {
                sb.appendLine("chronicle_secret_rotation_age_days{secret_name=\"$name\"} ${s.ageDays}")
            }

            sb.appendLine("# HELP chronicle_secret_rotation_check_timestamp_seconds Unix timestamp of last rotation check.")
            sb.appendLine("# TYPE chronicle_secret_rotation_check_timestamp_seconds gauge")
            sb.appendLine("chronicle_secret_rotation_check_timestamp_seconds ${lastCheckTime?.epochSecond ?: 0}")

            sb.appendLine("# HELP chronicle_secret_rotation_check_success Whether the latest database refresh succeeded.")
            sb.appendLine("# TYPE chronicle_secret_rotation_check_success gauge")
            sb.appendLine(
                "chronicle_secret_rotation_check_success " +
                    if (refreshError == null && lastCheckTime != null) "1" else "0"
            )

            Files.createDirectories(checkNotNull(target.parent) { "Metrics path has no parent directory" })
            Files.writeString(tmpFile, sb.toString(), CREATE, TRUNCATE_EXISTING, WRITE)
            try {
                Files.move(tmpFile, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile, target, REPLACE_EXISTING)
            }

            metricsWriteError = null
            logger.debug("Wrote secret rotation metrics to {}", target)
        } catch (ex: Exception) {
            metricsWriteError = normalizedFailureCode(ex, "MetricsWriteFailure")
            logger.warn(
                "Failed to write Prometheus metrics to {}; failure_code={}",
                target,
                metricsWriteError,
            )
            try {
                Files.deleteIfExists(tmpFile)
            } catch (cleanupFailure: Exception) {
                logger.debug(
                    "Failed to remove incomplete secret-rotation metrics file; failure_code={}",
                    normalizedFailureCode(cleanupFailure, "MetricsCleanupFailure"),
                )
            }
        }
    }

    private fun normalizedFailureCode(exception: Exception, fallback: String): String =
        (exception::class.simpleName ?: fallback).take(120)

    /**
     * Logs warnings for each secret that is overdue for rotation.
     */
    private fun logOverdueWarnings() {
        val status = checkRotationStatus()
        for ((name, s) in status) {
            if (s.overdue) {
                if (s.lastRotated == null) {
                    logger.warn(
                        "SECRET ROTATION OVERDUE: '{}' has NEVER been rotated (or rotation date is unknown). " +
                                "Maximum age is {} days. Update the secret_rotation_tracking table after rotating.",
                        name, maxAgeDaysFor(name)
                    )
                } else {
                    logger.warn(
                        "SECRET ROTATION OVERDUE: '{}' was last rotated {} ({} days ago). " +
                                "Maximum age is {} days. Rotate immediately.",
                        name, s.lastRotated, s.ageDays, maxAgeDaysFor(name)
                    )
                }
            }
        }
    }

    /**
     * Status of a single tracked secret.
     */
    public data class SecretStatus(
        val lastRotated: Instant?,
        val ageDays: Long,
        val overdue: Boolean
    )
}
