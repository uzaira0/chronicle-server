package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.contract.JwtSessionContract
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Durable JWT revocations and activity, with Hazelcast coordination.
 * Blocked tokens are stored with TTL matching their remaining validity,
 * so entries auto-expire when the token would have expired anyway.
 */
public class JwtBlocklist(hazelcastInstance: HazelcastInstance,
    private val storageResolver: StorageResolver? = null) {

    public data class ActivityTiming(val receivedAt: Instant, val age: Duration = Duration.ZERO) {
        internal fun isWithin(window: Duration): Boolean = !age.isNegative && age < window
    }

    internal companion object {
        const val SESSION_IDLE_EXPIRED_MESSAGE = "Session has expired due to inactivity"
        const val IDLE_DEADLINE_HEADER = "X-Session-Idle-Expires-At"
        private const val ACTIVITY_MAP = "JWT_SESSION_ACTIVITY"
        private const val ACTIVITY_KEY_PREFIX = "SESSION_ACTIVITY:"
        private val REVOKE_ALL = JwtSessionContract.revokeAllBeforeKey
        internal const val EXPIRED_STATE_PURGE_LIMIT = 100
        private val logger = LoggerFactory.getLogger(JwtBlocklist::class.java)
    }

    private val blocklist: IMap<String, Long> = HazelcastMap.JWT_BLOCKLIST.getMap(hazelcastInstance)
    private val activity: IMap<String, Long> = hazelcastInstance.getMap(ACTIVITY_MAP)

    /**
     * Block a JWT by its JTI (JWT ID) or token hash.
     * @param tokenId The JTI claim or SHA-256 hash of the token
     * @param expiresAt When the token expires (entries auto-evict after this)
     */
    public fun blockToken(tokenId: String, expiresAt: Instant): Boolean {
        val ttlSeconds = expiresAt.epochSecond - Instant.now().epochSecond
        if (ttlSeconds > 0) {
            writeState(blocklist, tokenId, expiresAt.epochSecond, expiresAt)
            logger.warn("Blocked JWT: {}... (TTL: {}s)", tokenId.take(8), ttlSeconds)
            return true
        }
        logger.warn("JWT already expired, skipping blocklist entry: {}...", tokenId.take(8))
        return false
    }

    /**
     * Block a token using its raw value (SHA-256 hashed for storage).
     */
    public fun blockTokenByValue(tokenValue: String, expiresAt: Instant): Boolean {
        return blockToken(sha256(tokenValue), expiresAt)
    }

    /**
     * Check if a token is blocked.
     */
    public fun isBlocked(tokenId: String): Boolean = readState(blocklist, tokenId) != null

    /**
     * Check if a token value is blocked (by SHA-256 hash).
     */
    public fun isBlockedByValue(tokenValue: String): Boolean = isBlocked(sha256(tokenValue))

    public fun revokePrincipalBefore(principalId: String, timestamp: Instant = Instant.now()) {
        writeState(blocklist, principalRevocationKey(principalId), timestamp.epochSecond, null)
    }

    private fun principalRevocationKey(principalId: String): String = "PRINCIPAL_REVOCATION:" + sha256(principalId)

    private fun readState(map: IMap<String, Long>, key: String): Long? {
        val storage = storageResolver ?: return map[key]
        return RLSRequestContext.withSystemContext {
            storage.getDefaultPlatformStorage().dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT state_value FROM jwt_session_state WHERE state_group = ? AND state_key = ? AND (expires_at IS NULL OR expires_at > " +
                    "now())").use { statement ->
                    statement.setString(1, map.name); statement.setString(2, key)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
                }
            }
        }
    }

    private fun writeState(map: IMap<String, Long>, key: String, value: Long, expiresAt: Instant?) {
        storageResolver?.let { storage ->
            RLSRequestContext.withSystemContext {
                storage.getDefaultPlatformStorage().dataSource.connection.use { connection ->
                    connection.prepareStatement("""
                        INSERT INTO jwt_session_state (state_group, state_key, state_value, expires_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (state_group, state_key) DO UPDATE SET
                            state_value = GREATEST(jwt_session_state.state_value, EXCLUDED.state_value),
                            expires_at = CASE WHEN jwt_session_state.expires_at IS NULL OR EXCLUDED.expires_at IS NULL
                                THEN NULL ELSE GREATEST(jwt_session_state.expires_at, EXCLUDED.expires_at) END
                    """.trimIndent()).use { statement ->
                        statement.setString(1, map.name); statement.setString(2, key); statement.setLong(3, value)
                        statement.setObject(4, expiresAt?.atOffset(ZoneOffset.UTC))
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("""
                        DELETE FROM jwt_session_state WHERE (state_group, state_key) IN (
                            SELECT state_group, state_key FROM jwt_session_state
                            WHERE expires_at <= now() ORDER BY expires_at
                            LIMIT ? FOR UPDATE SKIP LOCKED
                        )
                    """.trimIndent()).use { statement ->
                        statement.setInt(1, EXPIRED_STATE_PURGE_LIMIT)
                        statement.executeUpdate()
                    }
                }
            }
        }
        if (expiresAt == null) map.set(key, value)
        else map.set(key, value, maxOf(1, expiresAt.epochSecond - Instant.now().epochSecond), TimeUnit.SECONDS)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The "revoke all" cutoff a restore writes; tokens issued at or before it are rejected. */
    public fun getRevokeAllTimestamp(): Instant? {
        val epoch = readState(blocklist, REVOKE_ALL) ?: return null
        return Instant.ofEpochSecond(epoch)
    }

    public fun credentialRejectionReason(
        jti: String?, tokenValue: String, issuedAt: Instant?, expiresAt: Instant?, idleWindow: Duration,
        now: () -> Instant = { Instant.now() }, subject: String? = null,
    ): String? {
        if ((jti != null && isBlocked(jti)) || isBlockedByValue(tokenValue)) return "Token has been revoked"
        val principalCutoff = subject?.let { readState(blocklist, principalRevocationKey(it)) }
        if (principalCutoff != null && (issuedAt == null || issuedAt.epochSecond <= principalCutoff)) {
            return "Token was issued before a privilege change"
        }
        val activityKey = sessionActivityIdentity(jti, tokenValue)
        if (!isSessionActive(activityKey, issuedAt, expiresAt, idleWindow, now())) {
            return SESSION_IDLE_EXPIRED_MESSAGE
        }
        return null
    }

    public fun isSessionActive(tokenValue: String, issuedAt: Instant?, expiresAt: Instant?, idleWindow: Duration,
        now: Instant = Instant.now()): Boolean {
        if (issuedAt == null || expiresAt == null || !now.isBefore(expiresAt)) return false
        if (isBlockedByValue(tokenValue)) return false
        val cutoff = getRevokeAllTimestamp()
        if (cutoff != null && !issuedAt.isAfter(cutoff)) return false
        val lastActivity = readState(activity, activityKey(tokenValue))?.let(Instant::ofEpochSecond) ?: issuedAt
        return Duration.between(lastActivity, now) < idleWindow
    }

    /** Only an explicit user-input signal may advance the shared idle deadline. */
    public fun touchActivity(tokenValue: String, issuedAt: Instant?, expiresAt: Instant?, idleWindow: Duration,
        timing: ActivityTiming = ActivityTiming(Instant.now())): Boolean {
        if (!timing.isWithin(idleWindow)) return false
        val key = activityKey(tokenValue)
        check(activity.tryLock(key, 1, TimeUnit.SECONDS, 5, TimeUnit.SECONDS)) { "Session activity store is unavailable" }
        try {
            if (!isSessionActive(tokenValue, issuedAt, expiresAt, idleWindow, timing.receivedAt)) return false
            val previous = readState(activity, key) ?: requireNotNull(issuedAt).epochSecond
            writeState(activity, key, maxOf(previous, timing.receivedAt.minus(timing.age).epochSecond), expiresAt)
            return true
        } finally {
            activity.unlock(key)
        }
    }

    private fun activityKey(tokenValue: String): String = ACTIVITY_KEY_PREFIX + sha256(tokenValue)

    public fun sessionIdleDeadline(jti: String?, tokenValue: String, issuedAt: Instant?,
        expiresAt: Instant?, idleWindow: Duration): Instant? {
        val key = activityKey(sessionActivityIdentity(jti, tokenValue))
        val lastActivity = readState(activity, key)?.let(Instant::ofEpochSecond) ?: issuedAt ?: return null
        return minOf(lastActivity.plus(idleWindow), expiresAt ?: return null)
    }

    internal fun sessionActivityIdentity(jti: String?, tokenValue: String): String =
        jti?.takeIf(String::isNotBlank)?.let { "JTI:$it" } ?: tokenValue
}
