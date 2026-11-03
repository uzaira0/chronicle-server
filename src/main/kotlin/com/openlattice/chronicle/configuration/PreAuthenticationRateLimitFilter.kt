package com.openlattice.chronicle.configuration

import com.hazelcast.core.HazelcastInstance
import io.github.bucket4j.TimeMeter
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.sql.SQLException
import java.time.Duration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException

/**
 * Counts per IP only requests that carry no valid credential. A valid researcher JWT, a live API key outside
 * credential bootstrap, or a live participant form session on a form route or GET session recovery is never counted
 * per IP, here or by an endpoint's IP limit; every other request reserves an IP token, kept only when it fails.
 * Credentials are validated here by their owners' read-only checks, never trusted because a header or cookie is
 * present, so guessed, revoked, erased and cancelled keys and unknown, expired and revoked sessions stay limited.
 * A credential the database cannot check is unverified and counted.
 */
public class PreAuthenticationRateLimitFilter(
    hazelcast: HazelcastInstance,
    private val config: RateLimitConfiguration,
    private val jwtDecoder: JwtDecoder,
    private val jwtBlocklist: JwtBlocklist,
    authConfig: ChronicleAuthConfiguration,
    private val apiKeyService: ApiKeyService,
    private val participantForms: ParticipantFormAccessService,
    bucketClock: TimeMeter? = null,
) : RateLimitFilter(hazelcast, config, null, preAuthentication = true, bucketClock = bucketClock) {
    private val resolver = ChronicleCookieOrBearerTokenResolver()
    private val idleWindow = Duration.ofMinutes(authConfig.sessionIdleMinutes)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (config.enabled && hasValidCredential(request)) request.setAttribute(VALIDATED_CREDENTIAL, true)
        super.doFilterInternal(request, response, filterChain)
    }

    // Bootstrap checks codes and secrets before API-key authentication; a live key cannot exempt those guesses.
    private fun hasValidCredential(request: HttpServletRequest): Boolean =
        checked { hasValidJwt(request) } || checked { hasLiveApiKey(request) } ||
            checked { ParticipantFormAccessFilter.hasAdmittedSession(request, participantForms) }

    private fun hasLiveApiKey(request: HttpServletRequest): Boolean =
        MobileApiSignatureFilter.enrollmentMetricEndpoint(request) == null &&
            ApiKeyAuthenticationFilter.presentedApiKey(request)?.let(apiKeyService::findLiveApiKey) != null

    /**
     * Every credential branch above goes through here: a credential the database cannot check right now is
     * unverified, so the request is counted, never exempted or failed here.
     */
    private inline fun checked(check: () -> Boolean): Boolean = try {
        check()
    } catch (_: SQLException) {
        false
    }

    private fun hasValidJwt(request: HttpServletRequest): Boolean {
        val token = resolver.resolveCredential(request) ?: return false
        val jwt = try {
            jwtDecoder.decode(token)
        } catch (_: JwtException) {
            return false
        }
        return jwtBlocklist.credentialRejectionReason(
            jwt.id, jwt.tokenValue, jwt.issuedAt, jwt.expiresAt, idleWindow, subject = jwt.subject,
        ) == null
    }
}
