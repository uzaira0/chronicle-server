package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.JwtBlocklist
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Checks authenticated JWT tokens against the blocklist.
 * Runs after BearerTokenAuthenticationFilter to inspect already-authenticated tokens.
 * Fails closed if Hazelcast is unavailable. Revocation is an authentication
 * control, so accepting a token whose revocation state cannot be checked would
 * allow explicitly revoked credentials during a blocklist outage.
 */
public class JwtBlocklistFilter(private val jwtBlocklist: JwtBlocklist) : OncePerRequestFilter() {

    // Use a different name than 'logger' to avoid KT-56386 clash with OncePerRequestFilter.logger
    private val log = LoggerFactory.getLogger(JwtBlocklistFilter::class.java)

    // reason: JWT-revocation security filter — the nested checks (JTI block, value-hash block,
    // global revoke-before timestamp) are sequential token-validation gates that must each be able
    // to reject-and-return; restructuring this auth-critical path risks altering the revocation order
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication

        if (authentication is JwtAuthenticationToken) {
            try {
                val jwt = authentication.token

                // Check individual token block (by JTI)
                val jti = jwt.id
                if (jti != null && jwtBlocklist.isBlocked(jti)) {
                    rejectToken(response, "Token has been revoked")
                    return
                }

                // Check token value hash
                if (jwtBlocklist.isBlockedByValue(jwt.tokenValue)) {
                    rejectToken(response, "Token has been revoked")
                    return
                }

                // Check "revoke all before" timestamp
                val revokeAllBefore = jwtBlocklist.getRevokeAllTimestamp()
                if (revokeAllBefore != null) {
                    val issuedAt = jwt.issuedAt
                    if (issuedAt != null && issuedAt.isBefore(revokeAllBefore)) {
                        rejectToken(response, "Token was issued before a global revocation event")
                        return
                    }
                }
            } catch (ex: RuntimeException) {
                log.error("JWT blocklist check failed, rejecting request", ex)
                rejectRevocationUnavailable(response)
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun rejectToken(response: HttpServletResponse, reason: String) {
        SecurityContextHolder.clearContext()
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.writer.write("""{"error":"token_revoked","message":"$reason"}""")
        response.writer.flush()
    }

    private fun rejectRevocationUnavailable(response: HttpServletResponse) {
        SecurityContextHolder.clearContext()
        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        response.contentType = "application/json"
        response.writer.write(
            """{"error":"token_revocation_unavailable","message":"Token revocation status could not be verified"}"""
        )
        response.writer.flush()
    }
}
