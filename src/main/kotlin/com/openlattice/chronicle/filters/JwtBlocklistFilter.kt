package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.generated.ChronicleHeaders
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Duration
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
public class JwtBlocklistFilter(private val jwtBlocklist: JwtBlocklist,
    private val idleWindow: Duration = Duration.ofMinutes(15),
    private val clock: Clock = Clock.systemUTC()) : OncePerRequestFilter() {

    // Use a different name than 'logger' to avoid KT-56386 clash with OncePerRequestFilter.logger
    private val log = LoggerFactory.getLogger(JwtBlocklistFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken && !validateToken(request, response, authentication.token)) return
        filterChain.doFilter(request, response)
    }

    // Revocation infrastructure failures must reject the credential before dispatching the request.
    @Suppress("TooGenericExceptionCaught")
    private fun validateToken(request: HttpServletRequest, response: HttpServletResponse, jwt: Jwt): Boolean {
        try {
            val rejection = rejectionReason(request, jwt)
            if (rejection != null) {
                rejectToken(request, response, rejection)
                return false
            }
            jwtBlocklist.sessionIdleDeadline(jwt.id, jwt.tokenValue, jwt.issuedAt, jwt.expiresAt, idleWindow)
                ?.let { response.setHeader(JwtBlocklist.IDLE_DEADLINE_HEADER, it.toEpochMilli().toString()) }
            val cutoff = jwtBlocklist.getRevokeAllTimestamp()
            val issuedAt = jwt.issuedAt
            if (cutoff != null && issuedAt != null && !issuedAt.isAfter(cutoff)) {
                rejectToken(request, response, "Token was issued before a global revocation event")
                return false
            }
            return true
        } catch (exception: RuntimeException) {
            log.error("JWT blocklist check failed, rejecting request", exception)
            rejectRevocationUnavailable(response)
            return false
        }
    }

    private fun rejectionReason(request: HttpServletRequest, jwt: Jwt): String? {
        val rejection = jwtBlocklist.credentialRejectionReason(
            jwt.id, jwt.tokenValue, jwt.issuedAt, jwt.expiresAt, idleWindow, clock::instant, subject = jwt.subject,
        )
        if (rejection != null) return rejection
        val age = request.getHeader(ChronicleHeaders.USER_ACTIVITY_HEADER)?.toLongOrNull() ?: return null
        val timing = JwtBlocklist.ActivityTiming(clock.instant(), Duration.ofMillis(age))
        if (!timing.isWithin(idleWindow)) return null
        val renewed = jwtBlocklist.touchActivity(
            jwtBlocklist.sessionActivityIdentity(jwt.id, jwt.tokenValue), jwt.issuedAt, jwt.expiresAt, idleWindow, timing,
        )
        return if (renewed) null else JwtBlocklist.SESSION_IDLE_EXPIRED_MESSAGE
    }

    private fun rejectToken(request: HttpServletRequest, response: HttpServletResponse, reason: String) {
        SecurityContextHolder.clearContext()
        if (NativeExportDownload.redirectForExpiredForm(request, response)) return
        writeFilterError(response, HttpServletResponse.SC_UNAUTHORIZED, reason, "token_revoked")
    }

    private fun rejectRevocationUnavailable(response: HttpServletResponse) {
        SecurityContextHolder.clearContext()
        writeFilterError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Token revocation status could not be verified", "token_revocation_unavailable")
    }
}
