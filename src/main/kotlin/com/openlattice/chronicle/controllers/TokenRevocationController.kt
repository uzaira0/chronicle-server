package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Admin endpoints for JWT token revocation.
 * HIPAA §164.312(d) — Credential revocation controls.
 * Only registered when JwtBlocklist bean is available (requires Hazelcast).
 */
@RestController
@RequestMapping(value = ["/chronicle/v3/admin/tokens", "/v3/admin/tokens"])
@RateLimit(type = RateLimitType.ADMIN)
public class TokenRevocationController(
    private val jwtBlocklist: JwtBlocklist,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager
) : AuthorizingComponent {

    /**
     * Revoke the current user's own token (logout/self-revoke).
     */
    @PostMapping("/revoke-self")
    public fun revokeSelf(): ResponseEntity<Map<String, Any>> {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "No JWT authentication found"))

        val jwt = auth.token
        val expiresAt = jwt.expiresAt ?: Instant.now().plusSeconds(30 * 86400L)

        val blocked = if (jwt.id != null) {
            jwtBlocklist.blockToken(jwt.id, expiresAt)
        } else {
            jwtBlocklist.blockTokenByValue(jwt.tokenValue, expiresAt)
        }

        val status = if (blocked) "revoked" else "already_expired"
        return ResponseEntity.ok(mapOf(
            "status" to status,
            "message" to if (blocked) "Current token has been revoked" else "Token already expired, no blocklist entry needed"
        ))
    }

    /**
     * Admin-only: Revoke all tokens issued before now.
     * Use after a secret compromise.
     */
    @PostMapping("/revoke-all")
    public fun revokeAll(): ResponseEntity<Map<String, Any>> {
        ensureAdminAccess()

        val now = Instant.now()
        jwtBlocklist.revokeAllBefore(now)

        return ResponseEntity.ok(mapOf(
            "status" to "all_revoked",
            "revokedBefore" to now.toString(),
            "message" to "All tokens issued before $now have been revoked. All users must re-authenticate."
        ))
    }

    /**
     * Admin-only: Get blocklist stats.
     */
    @GetMapping("/blocklist-stats")
    public fun getBlocklistStats(): ResponseEntity<Map<String, Any>> {
        ensureAdminAccess()

        val revokeAll = jwtBlocklist.getRevokeAllTimestamp()
        return ResponseEntity.ok(mapOf(
            "blockedTokens" to jwtBlocklist.getBlockedCount(),
            "globalRevocationTimestamp" to (revokeAll?.toString() ?: "none")
        ))
    }
}
