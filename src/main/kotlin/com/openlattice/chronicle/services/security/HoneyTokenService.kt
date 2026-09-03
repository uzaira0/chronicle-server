package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.API_KEYS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_HASH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_PREFIX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REVOKED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCOPE
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Seeds and detects honey token (canary) API keys.
 *
 * Honey tokens are fake API keys seeded in the database with names that look
 * tempting to an attacker (e.g., "honey-internal-service", "honey-legacy-migration").
 * They are permanently revoked at the database level so they never grant access,
 * but any authentication attempt using one triggers a CRITICAL security alert
 * and increments the chronicle_honey_token_triggered_total Prometheus counter.
 *
 * Detection is done by prefix: all honey token prefixes start with "ht_" followed
 * by 6 hex chars, making them identifiable without a database lookup on every request.
 */
@Service
public class HoneyTokenService(
    private val storageResolver: StorageResolver
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(HoneyTokenService::class.java)
        private val secureRandom = SecureRandom()

        /**
         * Honey token key names. Chosen to look like legitimate service accounts
         * that an attacker might try to use after finding them in a config file,
         * environment variable dump, or database breach.
         */
        private val HONEY_TOKEN_NAMES = listOf(
            "honey-internal-service",
            "honey-legacy-migration",
            "honey-backup-agent"
        )

        /** Prefix used to identify honey token keys without a DB lookup. */
        private const val HONEY_PREFIX = "ht_"

        private val CHECK_HONEY_TOKEN_EXISTS_SQL = """
            SELECT COUNT(*) FROM ${API_KEYS.name}
            WHERE ${NAME.name} = ? AND is_honey_token = true
        """.trimIndent()

        /**
         * Insert a honey token. study_id is set to a well-known nil UUID sentinel
         * (inserted by V16__add_honey_token_support migration).
         * The key is inserted as already revoked so it can never authorize anything.
         */
        private val INSERT_HONEY_TOKEN_SQL = """
            INSERT INTO ${API_KEYS.name}
                (${KEY_ID.name}, study_id, ${KEY_HASH.name}, ${KEY_PREFIX.name},
                 ${NAME.name}, ${SCOPE.name}, created_by, expires_at, ${REVOKED.name}, is_honey_token)
            VALUES (?, '00000000-0000-0000-0000-000000000000'::uuid, ?, ?, ?, 'READ_ONLY', 'system:honey-token',
                    now() + interval '100 years', true, true)
        """.trimIndent()

        /**
         * Look up whether a key hash belongs to a honey token.
         * We check by the is_honey_token flag AND by hash match.
         */
        private val LOOKUP_HONEY_BY_HASH_SQL = """
            SELECT ${NAME.name}, ${KEY_PREFIX.name}
            FROM ${API_KEYS.name}
            WHERE ${KEY_HASH.name} = ? AND is_honey_token = true
        """.trimIndent()

        private fun generateHoneyPrefix(): String {
            val bytes = ByteArray(3)
            secureRandom.nextBytes(bytes)
            return HONEY_PREFIX + bytes.joinToString("") { "%02x".format(it) }
        }

        private fun generateRawKey(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            val base62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder(32)
            for (b in bytes) {
                sb.append(base62[(b.toInt() and 0xFF) % base62.length])
            }
            return sb.toString()
        }

        private fun hashKey(rawKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawKey.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * On startup, idempotently seeds 3 honey token API keys.
     * Keys are inserted as revoked so they never pass authentication,
     * but their hashes remain in the table for detection.
     *
     * Uses ContextRefreshedEvent instead of @PostConstruct to ensure that
     * the Flyway migration pass (V16__add_honey_token_support) has already
     * executed and the is_honey_token column exists.
     * Spring Framework (no Boot dep), so ContextRefreshedEvent is the closest
     * "all beans wired" hook available — fires once per refresh.
     */
    // reason: boundary catch — startup seeding must log-and-retry on any failure type, never propagate
    @Suppress("TooGenericExceptionCaught")
    @EventListener(ContextRefreshedEvent::class)
    public fun seedHoneyTokens() {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                RLSConnectionCustomizer.withAdminContext(connection) {
                    for (name in HONEY_TOKEN_NAMES) {
                        connection.prepareStatement(CHECK_HONEY_TOKEN_EXISTS_SQL).use { ps ->
                            ps.setString(1, name)
                            val rs = ps.executeQuery()
                            rs.next()
                            if (rs.getInt(1) > 0) {
                                logger.debug("Honey token '{}' already exists, skipping", name)
                                continue
                            }
                        }

                        val prefix = generateHoneyPrefix()
                        val rawKey = generateRawKey()
                        val fullKey = "ck_${prefix}_$rawKey"
                        val hash = hashKey(fullKey)
                        val keyId = UUID.randomUUID()

                        connection.prepareStatement(INSERT_HONEY_TOKEN_SQL).use { ps ->
                            ps.setObject(1, keyId)
                            ps.setString(2, hash)
                            ps.setString(3, prefix)
                            ps.setString(4, name)
                            ps.executeUpdate()
                        }

                        logger.info("Seeded honey token '{}' with prefix '{}'", name, prefix)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Failed to seed honey tokens (will retry on next startup): {}",
                e.message
            )
        }
    }

    /**
     * Checks if a raw API key is a honey token by its hash.
     * Called from [com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter]
     * on every authentication attempt.
     *
     * If the key matches a honey token, logs a CRITICAL alert and increments
     * the Prometheus counter. Returns true if the key is a honey token.
     */
    public fun checkAndAlert(rawKey: String, sourceIp: String): Boolean {
        val hash = hashKey(rawKey)
        var isHoney = false
        var honeyName = "unknown"

        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                connection.prepareStatement(LOOKUP_HONEY_BY_HASH_SQL).use { ps ->
                    ps.setString(1, hash)
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        isHoney = true
                        honeyName = rs.getString(NAME.name)
                    }
                }
            }
        }

        if (isHoney) {
            logger.error(
                "CRITICAL SECURITY ALERT: Honey token '{}' was used from IP {}. " +
                    "This indicates unauthorized access or credential theft.",
                honeyName, sourceIp
            )
            ChronicleMetrics.honeyTokenTriggeredTotal
                .labels(honeyName, sourceIp)
                .inc()
        }

        return isHoney
    }

    /**
     * Fast prefix-based check: returns true if the raw key's embedded prefix
     * starts with "ht_", indicating it is likely a honey token.
     * Use this as a cheap pre-filter before the hash-based [checkAndAlert].
     */
    public fun isProbablyHoneyToken(rawKey: String): Boolean {
        // Key format: ck_{prefix}_{random}
        // Honey token prefix starts with "ht_"
        if (!rawKey.startsWith("ck_")) return false
        val afterCk = rawKey.substring(3)
        return afterCk.startsWith("ht_")
    }
}
