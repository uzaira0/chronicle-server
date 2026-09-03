package com.openlattice.chronicle.authorization

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.hazelcast.HazelcastMap
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Hazelcast-backed JWT blocklist for token revocation.
 * Blocked tokens are stored with TTL matching their remaining validity,
 * so entries auto-expire when the token would have expired anyway.
 *
 * HIPAA §164.312(d) — Authentication controls must support credential revocation.
 */
public class JwtBlocklist(hazelcastInstance: HazelcastInstance) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(JwtBlocklist::class.java)
    }

    private val blocklist: IMap<String, Long> = HazelcastMap.JWT_BLOCKLIST.getMap(hazelcastInstance)

    /**
     * Block a JWT by its JTI (JWT ID) or token hash.
     * @param tokenId The JTI claim or SHA-256 hash of the token
     * @param expiresAt When the token expires (entries auto-evict after this)
     */
    public fun blockToken(tokenId: String, expiresAt: Instant): Boolean {
        val ttlSeconds = expiresAt.epochSecond - Instant.now().epochSecond
        if (ttlSeconds > 0) {
            blocklist.put(tokenId, expiresAt.epochSecond, ttlSeconds, TimeUnit.SECONDS)
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
    public fun isBlocked(tokenId: String): Boolean = blocklist.containsKey(tokenId)

    /**
     * Check if a token value is blocked (by SHA-256 hash).
     */
    public fun isBlockedByValue(tokenValue: String): Boolean = blocklist.containsKey(sha256(tokenValue))

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Revoke all tokens (nuclear option — e.g., after secret compromise).
     * Sets a special "revoke-all" key with the current timestamp.
     * Tokens issued before this timestamp are rejected.
     */
    public fun revokeAllBefore(timestamp: Instant) {
        blocklist.put("REVOKE_ALL_BEFORE", timestamp.epochSecond, 30, TimeUnit.DAYS)
        logger.warn("REVOKED ALL TOKENS issued before {}", timestamp)
    }

    /**
     * Get the "revoke all" timestamp, if set.
     */
    public fun getRevokeAllTimestamp(): Instant? {
        val epoch = blocklist["REVOKE_ALL_BEFORE"] ?: return null
        return Instant.ofEpochSecond(epoch)
    }

    public fun getBlockedCount(): Long = blocklist.size.toLong()
}
