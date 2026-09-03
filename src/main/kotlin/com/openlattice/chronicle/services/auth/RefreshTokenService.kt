package com.openlattice.chronicle.services.auth

import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.storage.StorageResolver
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

/**
 * Manages refresh token lifecycle: creation, rotation, revocation, and theft detection.
 *
 * Refresh tokens are stored as SHA-256 hashes in the database. Raw tokens are returned
 * to the client exactly once at creation time. Token families enable theft detection:
 * if a rotated-out token is reused, the entire family is revoked.
 *
 * HIPAA §164.312(d) — credential lifecycle management with revocation.
 */
public class RefreshTokenService(
    private val storageResolver: StorageResolver,
    private val jwtKeyMaterial: JwtKeyMaterial,
    private val issuer: String,
    private val audience: String,
    private val accessTokenExpiryMinutes: Long,
    private val refreshTokenExpiryDays: Long,
    private val requireMfa: Boolean,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(RefreshTokenService::class.java)
        private val secureRandom = SecureRandom()
        private const val RAW_TOKEN_BYTES = 48
        private const val MFA_REAUTHENTICATION_REQUIRED =
            "Interactive reauthentication is required while MFA enforcement is enabled"

        private val INSERT_REFRESH_TOKEN_SQL = """
            INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at, ip_address, user_agent)
            VALUES (?, ?::uuid, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val LOOKUP_BY_HASH_SQL = """
            SELECT id, user_id, token_hash, family_id, expires_at, rotated_at, revoked, created_at, ip_address, user_agent
            FROM refresh_tokens
            WHERE token_hash = ?
        """.trimIndent()

        private val MARK_ROTATED_SQL = """
            UPDATE refresh_tokens SET rotated_at = now() WHERE id = ?
        """.trimIndent()

        private val REVOKE_FAMILY_SQL = """
            UPDATE refresh_tokens SET revoked = true WHERE family_id = ?
        """.trimIndent()

        private val REVOKE_ALL_FOR_USER_SQL = """
            UPDATE refresh_tokens SET revoked = true WHERE user_id = ?::uuid
        """.trimIndent()

        private val CLEANUP_EXPIRED_SQL = """
            DELETE FROM refresh_tokens WHERE expires_at < now() AND revoked = true
        """.trimIndent()
    }

    /**
     * Creates a new refresh token for the given user.
     * Returns a [RefreshTokenResult] containing the raw token (to send to the client)
     * and a freshly minted access token.
     */
    public fun createRefreshToken(
        userId: String,
        ipAddress: String?,
        userAgent: String?,
    ): RefreshTokenResult {
        requireLegacyRefreshAllowed()

        val rawToken = generateRawToken()
        val tokenHash = sha256(rawToken)
        val tokenId = UUID.randomUUID()
        val familyId = UUID.randomUUID()
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(refreshTokenExpiryDays)

        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(INSERT_REFRESH_TOKEN_SQL).use { ps ->
                ps.setObject(1, tokenId)
                ps.setString(2, userId)
                ps.setString(3, tokenHash)
                ps.setObject(4, familyId)
                ps.setObject(5, expiresAt)
                ps.setString(6, ipAddress?.take(45))
                ps.setString(7, userAgent?.take(512))
                ps.executeUpdate()
            }
        }

        val accessToken = mintAccessToken(userId)
        return RefreshTokenResult(
            accessToken = accessToken,
            refreshToken = rawToken,
            expiresIn = accessTokenExpiryMinutes * 60,
        )
    }

    /**
     * Rotates a refresh token: validates the old one, creates a new one in the same family,
     * and returns fresh access + refresh tokens.
     *
     * Token theft detection: if the presented token has already been rotated (reused),
     * the entire token family is revoked immediately.
     */
    // reason: boundary catch rolls back the rotation transaction on any failure before rethrowing;
    // the distinct throws (theft/revoked/expired/invalid) are deliberate validation outcomes
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    public fun rotateRefreshToken(
        rawToken: String,
        ipAddress: String?,
        userAgent: String?,
    ): RefreshTokenResult {
        requireLegacyRefreshAllowed()

        val tokenHash = sha256(rawToken)

        return storageResolver.getPlatformStorage().connection.use { conn ->
            conn.autoCommit = false
            try {
                val record = lookupToken(conn, tokenHash)
                    ?: throw RefreshTokenException("Invalid refresh token")

                // Token theft detection: if already rotated, revoke the entire family
                if (record.rotatedAt != null) {
                    logger.warn(
                        "SECURITY: Refresh token reuse detected for user={}, family={}. " +
                            "Revoking entire token family. Possible token theft.",
                        record.userId, record.familyId
                    )
                    revokeFamily(conn, record.familyId)
                    conn.commit()
                    throw RefreshTokenException("Refresh token has already been used. All sessions in this family have been revoked.")
                }

                // Check revoked
                if (record.revoked) {
                    throw RefreshTokenException("Refresh token has been revoked")
                }

                // Check expired
                if (record.expiresAt.toInstant().isBefore(Instant.now())) {
                    throw RefreshTokenException("Refresh token has expired")
                }

                // Mark old token as rotated
                markRotated(conn, record.id)

                // Create new token in the same family
                val newRawToken = generateRawToken()
                val newTokenHash = sha256(newRawToken)
                val newTokenId = UUID.randomUUID()
                val newExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(refreshTokenExpiryDays)

                conn.prepareStatement(INSERT_REFRESH_TOKEN_SQL).use { ps ->
                    ps.setObject(1, newTokenId)
                    ps.setString(2, record.userId)
                    ps.setString(3, newTokenHash)
                    ps.setObject(4, record.familyId)
                    ps.setObject(5, newExpiresAt)
                    ps.setString(6, ipAddress?.take(45))
                    ps.setString(7, userAgent?.take(512))
                    ps.executeUpdate()
                }

                conn.commit()

                val accessToken = mintAccessToken(record.userId)
                RefreshTokenResult(
                    accessToken = accessToken,
                    refreshToken = newRawToken,
                    expiresIn = accessTokenExpiryMinutes * 60,
                )
            } catch (ex: Exception) {
                try {
                    conn.rollback()
                } catch (rollbackEx: Exception) {
                    // Best-effort rollback: log but do not mask the original failure (rethrown below).
                    logger.warn("Failed to roll back refresh-token rotation transaction.", rollbackEx)
                }
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /**
     * Revokes all refresh tokens in a family (e.g., on token theft detection).
     */
    public fun revokeFamily(familyId: UUID) {
        storageResolver.getPlatformStorage().connection.use { conn ->
            revokeFamily(conn, familyId)
        }
        logger.info("Revoked all refresh tokens in family={}", familyId)
    }

    /**
     * Revokes all refresh tokens for a user (e.g., on password change or account lockout).
     */
    public fun revokeAllForUser(userId: String) {
        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(REVOKE_ALL_FOR_USER_SQL).use { ps ->
                ps.setString(1, userId)
                val count = ps.executeUpdate()
                logger.info("Revoked {} refresh tokens for user={}", count, userId)
            }
        }
    }

    /**
     * Cleans up expired and revoked tokens. Should be called periodically (e.g., daily).
     */
    public fun cleanupExpired(): Int {
        return storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(CLEANUP_EXPIRED_SQL).use { ps ->
                val count = ps.executeUpdate()
                if (count > 0) {
                    logger.info("Cleaned up {} expired refresh tokens", count)
                }
                count
            }
        }
    }

    private fun mintAccessToken(userId: String): String {
        val now = Instant.now()
        val expiry = now.plusSeconds(accessTokenExpiryMinutes * 60)

        val builder = JWT.create()
            .withSubject(userId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withIssuedAt(now)
            .withExpiresAt(expiry)
            .withJWTId(UUID.randomUUID().toString())

        return when {
            jwtKeyMaterial.isRs256() -> {
                val rsaPrivateKey = requireNotNull(jwtKeyMaterial.rsaPrivateKey) {
                    "RSA private key is required for RS256 token signing"
                }
                builder.sign(Algorithm.RSA256(jwtKeyMaterial.rsaPublicKey, rsaPrivateKey))
            }
            jwtKeyMaterial.isHs256() -> {
                val secret = requireNotNull(jwtKeyMaterial.hmacSecret) {
                    "HMAC secret is required for HS256 token signing"
                }
                builder.sign(Algorithm.HMAC256(secret))
            }
            else -> error("Unsupported algorithm: ${jwtKeyMaterial.algorithm}")
        }
    }

    /**
     * The legacy refresh-token schema records only a user id and token-family
     * state. It does not retain the verified authentication methods or
     * assurance context from the interactive login. When MFA is enforced, a
     * refresh therefore cannot truthfully mint a replacement access token.
     *
     * Fail before reading or mutating the refresh-token family. Existing
     * families must reauthenticate interactively instead of being upgraded by
     * assertion.
     */
    private fun requireLegacyRefreshAllowed() {
        if (requireMfa) {
            throw RefreshTokenException(MFA_REAUTHENTICATION_REQUIRED)
        }
    }

    private fun lookupToken(conn: Connection, tokenHash: String): RefreshTokenRecord? {
        conn.prepareStatement(LOOKUP_BY_HASH_SQL).use { ps ->
            ps.setString(1, tokenHash)
            val rs = ps.executeQuery()
            if (!rs.next()) return null

            return RefreshTokenRecord(
                id = rs.getObject("id", UUID::class.java),
                userId = rs.getString("user_id"),
                tokenHash = rs.getString("token_hash"),
                familyId = rs.getObject("family_id", UUID::class.java),
                expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
                rotatedAt = rs.getObject("rotated_at", OffsetDateTime::class.java),
                revoked = rs.getBoolean("revoked"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                ipAddress = rs.getString("ip_address"),
                userAgent = rs.getString("user_agent"),
            )
        }
    }

    private fun markRotated(conn: Connection, tokenId: UUID) {
        conn.prepareStatement(MARK_ROTATED_SQL).use { ps ->
            ps.setObject(1, tokenId)
            ps.executeUpdate()
        }
    }

    private fun revokeFamily(conn: Connection, familyId: UUID) {
        conn.prepareStatement(REVOKE_FAMILY_SQL).use { ps ->
            ps.setObject(1, familyId)
            ps.executeUpdate()
        }
    }

    private fun generateRawToken(): String {
        val bytes = ByteArray(RAW_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Result returned to the client after refresh token operations.
 */
public data class RefreshTokenResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

/**
 * Internal representation of a refresh token database row.
 */
internal data class RefreshTokenRecord(
    val id: UUID,
    val userId: String,
    val tokenHash: String,
    val familyId: UUID,
    val expiresAt: OffsetDateTime,
    val rotatedAt: OffsetDateTime?,
    val revoked: Boolean,
    val createdAt: OffsetDateTime,
    val ipAddress: String?,
    val userAgent: String?,
)

/**
 * Exception thrown for refresh token validation failures.
 */
public class RefreshTokenException(message: String) : RuntimeException(message)
