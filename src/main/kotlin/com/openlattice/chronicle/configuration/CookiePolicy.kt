package com.openlattice.chronicle.configuration

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import java.time.Duration

/** Cookie purposes retain the browser scope and redirect requirements of their callers. */
internal enum class CookiePurpose(
    val path: String = "/chronicle",
    val httpOnly: Boolean = true,
    val sameSite: String = "Strict",
    val requiresSecure: Boolean = false,
) {
    AUTH_SESSION,
    CSRF(httpOnly = false),
    OIDC_HANDSHAKE(sameSite = "Lax"),
    // The existing __Host- cookie requires Secure, Path=/ and no Domain, including under local auth overrides.
    PARTICIPANT_SESSION(path = "/", requiresSecure = true),
}

/** The shared writer for session, CSRF and OIDC cookies, including their deletion headers. */
public class CookiePolicy(private val environment: Environment) {
    internal fun addCookie(
        response: HttpServletResponse,
        request: HttpServletRequest?,
        purpose: CookiePurpose,
        name: String,
        value: String,
        maxAge: Duration,
    ) {
        val secure = purpose.requiresSecure ||
            (environment.getProperty("chronicle.security.cookie.secure")?.toBooleanStrictOrNull()
                ?: (request?.let(::isSecureRequest) ?: true))
        if (purpose == CookiePurpose.PARTICIPANT_SESSION) {
            // Retain the participant endpoint's header serialization and duration precision.
            val cookie = ResponseCookie.from(name, value).httpOnly(purpose.httpOnly).secure(secure)
                .sameSite(purpose.sameSite).path(purpose.path).maxAge(maxAge).build()
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
            return
        }
        val cookie = Cookie(name, value)
        cookie.isHttpOnly = purpose.httpOnly
        cookie.secure = secure
        cookie.path = purpose.path
        cookie.maxAge = maxAge.seconds.coerceIn(-1, Int.MAX_VALUE.toLong()).toInt()
        cookie.setAttribute("SameSite", purpose.sameSite)
        response.addCookie(cookie)
    }

    internal fun clearCookie(
        response: HttpServletResponse,
        request: HttpServletRequest,
        purpose: CookiePurpose,
        name: String,
    ) = addCookie(response, request, purpose, name, "", Duration.ZERO)

    /** Keep the established proxy fallback: an absent header is secure, an explicit non-HTTPS header is not. */
    public fun isSecureRequest(request: HttpServletRequest): Boolean = request.isSecure ||
        (request.getHeader("X-Forwarded-Proto")?.equals("https", ignoreCase = true) ?: true)
}
