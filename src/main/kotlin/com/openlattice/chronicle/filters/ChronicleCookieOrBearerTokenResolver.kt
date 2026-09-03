package com.openlattice.chronicle.filters

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves bearer tokens from the Authorization header, the Chronicle httpOnly auth cookie,
 * or the legacy authorization cookie. Cookie-based auth requires a matching CSRF header or
 * legacy query parameter.
 */
public class ChronicleCookieOrBearerTokenResolver : BearerTokenResolver {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ChronicleCookieOrBearerTokenResolver::class.java)

        private const val AUTHORIZATION_COOKIE = "authorization"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer"
        private const val CHRONICLE_AUTH_COOKIE = "chronicle_auth"
        private const val CSRF_COOKIE = "ol_csrf_token"
        private const val CSRF_HEADER = "X-CSRF-Token"

        /** Auth management endpoints handle their own token validation — skip JWT filter. */
        private const val AUTH_PATH_SEGMENT = "/v3/auth/"

        /** JWKS endpoint is unauthenticated — skip JWT filter. */
        private const val JWKS_PATH_SEGMENT = "/.well-known/jwks.json"
    }

    // reason: security-critical token-resolution routing (auth/JWKS path skips, header vs httpOnly
    // vs legacy cookie with CSRF); the guard-clause early returns encode the precedence order and
    // must not be restructured
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override fun resolve(request: HttpServletRequest): String? {
        // CRITICAL: Auth management endpoints (session, testing-login, set-cookie, logout)
        // handle their own token lifecycle. The BearerTokenAuthenticationFilter runs BEFORE
        // Spring Security's permitAll() authorization check. If we return an expired JWT
        // here, the filter rejects with 401 before the controller can respond with
        // { authenticated: false, testingLoginEnabled: true }. Returning null skips the
        // JWT filter entirely for these paths.
        val path = request.requestURI ?: ""
        val servletPath = request.servletPath ?: ""
        if (path.contains(AUTH_PATH_SEGMENT) || servletPath.contains(AUTH_PATH_SEGMENT)) {
            return null
        }

        // JWKS endpoint is public — no JWT needed
        if (path.endsWith(JWKS_PATH_SEGMENT) || servletPath.endsWith(JWKS_PATH_SEGMENT)) {
            return null
        }

        val authorizationHeader = request.getHeader(AUTHORIZATION_HEADER)
        if (!authorizationHeader.isNullOrBlank() && authorizationHeader.startsWith(BEARER_PREFIX)) {
            val parts = authorizationHeader.split(" ")
            return if (parts.size == 2) parts[1] else null
        }

        val httpOnlyToken = getRequestCookie(request, CHRONICLE_AUTH_COOKIE)
        if (!httpOnlyToken.isNullOrBlank()) {
            if (validateCsrf(request)) {
                return httpOnlyToken
            }
            logger.debug("CSRF validation failed for Chronicle auth cookie.")
            return null
        }

        val legacyCookie = getRequestCookie(request, AUTHORIZATION_COOKIE)
        if (!legacyCookie.isNullOrBlank() && legacyCookie.startsWith(BEARER_PREFIX)) {
            if (validateCsrf(request)) {
                val parts = legacyCookie.split(" ")
                return if (parts.size == 2) parts[1] else null
            }
            logger.debug("CSRF validation failed for legacy authorization cookie.")
        }

        return null
    }

    private fun validateCsrf(request: HttpServletRequest): Boolean {
        val csrfFromCookie = getRequestCookie(request, CSRF_COOKIE) ?: return false
        val csrfFromHeader = request.getHeader(CSRF_HEADER)
        return !csrfFromHeader.isNullOrBlank() && csrfFromCookie == csrfFromHeader
    }

    // reason: boundary catch — a malformed/undecodable cookie value of any failure type is logged and treated as absent
    @Suppress("TooGenericExceptionCaught")
    private fun getRequestCookie(request: HttpServletRequest, targetCookie: String): String? {
        val cookies = request.cookies ?: return null

        cookies.forEach { cookie ->
            if (cookie.name == targetCookie) {
                return try {
                    URLDecoder.decode(cookie.value, StandardCharsets.UTF_8)
                } catch (exception: Exception) {
                    logger.error("Unable to decode {} cookie.", targetCookie, exception)
                    null
                }
            }
        }

        return null
    }
}
