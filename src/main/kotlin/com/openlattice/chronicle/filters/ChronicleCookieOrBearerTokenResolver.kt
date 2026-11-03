package com.openlattice.chronicle.filters

import com.openlattice.chronicle.generated.BrowserDownloadContracts
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.matchingSegments
import com.openlattice.chronicle.util.parsedPath
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves bearer tokens from the Authorization header, the Chronicle httpOnly auth cookie,
 * with cookie authentication bound to its CSRF header or native download form field.
 */
public class ChronicleCookieOrBearerTokenResolver : BearerTokenResolver {

    internal companion object {
        public const val AUTH_SESSION_PATH = "/chronicle/v3/auth/session"
        private val logger = LoggerFactory.getLogger(ChronicleCookieOrBearerTokenResolver::class.java)

        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer"
        private const val CHRONICLE_AUTH_COOKIE = "chronicle_auth"
        private const val CSRF_COOKIE = "ol_csrf_token"
        public const val CSRF_HEADER = ChronicleHeaders.RESEARCHER_CSRF_HEADER

        private val SESSION_URIS = listOf(AUTH_SESSION_PATH, AUTH_SESSION_PATH.removePrefix(StudyApi.SERVICE))
        private val SESSION_PATHS = SESSION_URIS.map(PathPatternParser.defaultInstance::parse)
        /** Auth management endpoints handle their own token validation — skip JWT filter. */
        private val AUTH_PATHS = SESSION_URIS.map {
            PathPatternParser.defaultInstance.parse(it.substringBeforeLast('/') + "/{*remaining}")
        }

        /** JWKS endpoint is unauthenticated — skip JWT filter. */
        private const val JWKS_PATH_SEGMENT = "/.well-known/jwks.json"
        private val JWKS_SEGMENTS = requireNotNull(JWKS_PATH_SEGMENT.parsedPath()).matchingSegments()
    }

    // reason: security-critical token-resolution routing (auth/JWKS path skips, header vs httpOnly
    // with CSRF); the guard-clause early returns encode the precedence order and
    // must not be restructured
    @Suppress("ReturnCount")
    override fun resolve(request: HttpServletRequest): String? {
        // Bootstrap handles expired cookies itself. An explicit activity probe must instead
        // pass normal JWT and CSRF validation before the blocklist filter can renew it.
        val paths = listOfNotNull(request.parsedPath(), request.servletPath.parsedPath())
        if (paths.any { path -> AUTH_PATHS.any { it.matches(path) } } && !isActivityProbe(request, paths)) {
            return null
        }

        // JWKS endpoint is public — no JWT needed
        if (paths.any { it.matchingSegments().takeLast(JWKS_SEGMENTS.size) == JWKS_SEGMENTS }) {
            return null
        }

        return resolveCredential(request, requireCsrf = true)
    }

    /** Reading a credential for quota admission never grants access or substitutes for CSRF validation. */
    internal fun resolveCredential(request: HttpServletRequest, requireCsrf: Boolean = false): String? {
        val authorizationHeader = request.getHeader(AUTHORIZATION_HEADER)
        if (!authorizationHeader.isNullOrBlank() && authorizationHeader.startsWith(BEARER_PREFIX)) {
            return bearerToken(authorizationHeader)
        }
        return resolveCookieToken(request, requireCsrf)
    }

    private fun resolveCookieToken(request: HttpServletRequest, requireCsrf: Boolean): String? {
        val httpOnlyToken = getRequestCookie(request, CHRONICLE_AUTH_COOKIE)
        if (!httpOnlyToken.isNullOrBlank()) {
            if (!requireCsrf || validateCsrf(request)) {
                return httpOnlyToken
            }
            logger.debug("CSRF validation failed for Chronicle auth cookie.")
            return null
        }

        return null
    }

    private fun bearerToken(value: String): String? = value.split(" ").let { parts ->
        if (parts.size == 2) parts[1] else null
    }

    private fun isActivityProbe(request: HttpServletRequest, paths: List<PathContainer>): Boolean =
        request.method == "GET" && paths.any { path -> SESSION_PATHS.any { it.matches(path) } } &&
            request.getHeader(ChronicleHeaders.USER_ACTIVITY_HEADER) != null

    private fun validateCsrf(request: HttpServletRequest): Boolean {
        val csrfFromCookie = getRequestCookie(request, CSRF_COOKIE) ?: return false
        val csrfFromHeader = request.getHeader(CSRF_HEADER) ?: if (request.method == "POST" &&
            request.contentType?.substringBefore(';') == "application/x-www-form-urlencoded") {
            request.getParameter(BrowserDownloadContracts.BROWSER_DOWNLOAD_CSRF_FIELD)
        } else null
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
