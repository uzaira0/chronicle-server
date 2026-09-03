package com.openlattice.chronicle.security

import com.openlattice.chronicle.util.SecureCompare
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/**
 * Application-level authentication for the Prometheus scrape endpoint.
 *
 * Network policy and edge routing remain required defense-in-depth. This filter
 * ensures a direct application connection still needs the dedicated scraper
 * credential, which is intentionally separate from user JWTs and mobile keys.
 */
public class MetricsAuthenticationFilter(
    private val expectedUsername: String,
    private val expectedPassword: String,
    private val expectedPasswordFiles: List<Path> = emptyList(),
) : OncePerRequestFilter() {

    internal companion object {
        private const val METRICS_PATH = "/prometheus"
        private const val BASIC_PREFIX = "Basic "
        private const val MAX_AUTHORIZATION_LENGTH = 2048
        private const val MAX_PASSWORD_LENGTH = 1024
        internal const val MIN_PASSWORD_LENGTH = 32

        internal fun parsePasswordFiles(configuredFiles: String): List<Path> {
            if (configuredFiles.isBlank()) return emptyList()
            val entries = configuredFiles.split(',').map(String::trim)
            require(entries.all(String::isNotEmpty)) {
                "Metrics password file configuration contains an empty path"
            }
            return entries.map(Paths::get)
        }
    }

    init {
        require(expectedUsername.isNotBlank()) {
            "Metrics authentication username must not be blank"
        }
        require(loadExpectedPasswords() != null) {
            "Metrics authentication requires readable credentials between " +
                "$MIN_PASSWORD_LENGTH and $MAX_PASSWORD_LENGTH characters"
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val requestPath = request.requestURI
        val servletPath = request.servletPath
        return servletPath != METRICS_PATH &&
            requestPath != METRICS_PATH &&
            !requestPath.startsWith("$METRICS_PATH/") &&
            !requestPath.startsWith("$METRICS_PATH;")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val expectedPasswords = loadExpectedPasswords()
        if (expectedPasswords == null) {
            // A mounted credential becoming unreadable or invalid is an
            // operational outage, not an authentication failure. Never fall
            // back to a stale value cached at application startup.
            response.setHeader("Retry-After", "5")
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            return
        }

        val credentials = decodeBasicCredentials(request.getHeader("Authorization"))
        val authenticated = credentials != null &&
            SecureCompare.equals(credentials.first, expectedUsername) &&
            expectedPasswords.any { expected ->
                SecureCompare.equals(credentials.second, expected)
            }
        if (!authenticated) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Chronicle metrics\"")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            return
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Reads file-backed credentials for every scrape so projected-secret
     * rotation takes effect without restarting the backend. Multiple files
     * provide a bounded current/previous overlap window. If file-backed
     * credentials are configured, the direct property is intentionally
     * ignored so a stale environment variable cannot become a fallback.
     */
    private fun loadExpectedPasswords(): List<String>? {
        if (expectedPasswordFiles.isEmpty()) {
            return expectedPassword.takeIf(::isValidPassword)?.let(::listOf)
        }

        return try {
            expectedPasswordFiles
                .map { passwordFile ->
                    if (Files.size(passwordFile) > MAX_PASSWORD_LENGTH + 2L) {
                        return null
                    }
                    Files.readString(passwordFile, StandardCharsets.UTF_8)
                        .trimEnd('\r', '\n')
                }
                .takeIf { passwords ->
                    passwords.isNotEmpty() && passwords.all(::isValidPassword)
                }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isValidPassword(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH

    private fun decodeBasicCredentials(header: String?): Pair<String, String>? {
        if (
            header == null ||
            header.length > MAX_AUTHORIZATION_LENGTH ||
            !header.startsWith(BASIC_PREFIX)
        ) {
            return null
        }
        return try {
            val decoded = String(
                Base64.getDecoder().decode(header.removePrefix(BASIC_PREFIX)),
                StandardCharsets.UTF_8,
            )
            val delimiter = decoded.indexOf(':')
            if (delimiter <= 0) {
                null
            } else {
                decoded.substring(0, delimiter) to decoded.substring(delimiter + 1)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
