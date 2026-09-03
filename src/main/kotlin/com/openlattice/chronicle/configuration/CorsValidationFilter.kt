/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.beans.factory.annotation.Autowired
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Defense-in-depth CORS validation filter.
 *
 * This filter provides an additional layer of origin validation beyond what
 * Spring Security's CORS handling provides. It runs early in the filter chain
 * to block requests from disallowed origins before they consume more resources.
 *
 * PURPOSE:
 * - Defense-in-depth: Validate origins even if Spring Security CORS is misconfigured
 * - Early rejection: Block bad requests before authentication processing
 * - Security logging: Log blocked cross-origin request attempts
 * - Null origin blocking: Specifically block "null" origin (sandboxed iframes)
 *
 * FILTER ORDER:
 * This filter runs at HIGHEST_PRECEDENCE + 4, which is:
 * - After: TRACE blocking, security headers, request validation, size limits
 * - Before: Parameter pollution filter, mobile API signature filter, Spring Security
 *
 * BEHAVIOR:
 * - Same-origin requests (no Origin header): Allowed to pass through
 * - Cross-origin requests from allowed origins: Allowed to pass through
 * - Cross-origin requests from disallowed origins: Blocked with 403 Forbidden
 * - Requests with "null" origin: Blocked with 403 Forbidden
 * - Preflight OPTIONS requests: Handled by Spring Security CORS, not blocked here
 *
 * @author uzaira0
 */
@Configuration
public open class CorsValidationFilterConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(CorsValidationFilterConfig::class.java)
    }

    @Autowired(required = false)
    private var corsConfiguration: CorsConfiguration? = null

    /**
     * Creates the CORS validation filter bean.
     *
     * The filter is ordered at HIGHEST_PRECEDENCE + 4 to run early but after
     * basic request validation filters.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 4)
    public fun corsValidationFilter(): Filter {
        val config = corsConfiguration ?: CorsConfiguration()

        if (!config.enabled) {
            logger.info("CORS validation filter: CORS is disabled, filter will pass all requests")
            return NoOpFilter()
        }

        logger.info("CORS validation filter initialized with ${config.getEffectiveAllowedOrigins().size} allowed origins")

        return CorsValidationFilter(config)
    }

    /**
     * A no-op filter that simply passes requests through without modification.
     */
    private class NoOpFilter : Filter {
        override fun doFilter(
            request: jakarta.servlet.ServletRequest,
            response: jakarta.servlet.ServletResponse,
            chain: FilterChain
        ) {
            chain.doFilter(request, response)
        }
    }
}

/**
 * The actual CORS validation filter implementation.
 */
public open class CorsValidationFilter(
    private val corsConfig: CorsConfiguration
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(CorsValidationFilter::class.java)

    internal companion object {

        /**
         * The "null" origin is sent by browsers in certain contexts:
         * - Sandboxed iframes without allow-same-origin
         * - Redirects from one origin to another
         * - Local HTML files (file://)
         * - data: URLs
         *
         * For security, we block "null" origin as it's often associated with
         * potentially malicious contexts.
         */
        private const val NULL_ORIGIN = "null"

        /**
         * Error message for blocked origins.
         */
        private const val FORBIDDEN_ORIGIN_MESSAGE = "Cross-origin request from disallowed origin"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val origin = request.getHeader("Origin")

        // No Origin header means same-origin request - allow it
        if (origin.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        // OPTIONS preflight requests are handled by Spring Security CORS
        // We let them through to ensure proper preflight handling
        if (HttpMethod.OPTIONS.matches(request.method)) {
            filterChain.doFilter(request, response)
            return
        }

        // Block "null" origin (sandboxed contexts, potentially malicious)
        if (origin == NULL_ORIGIN) {
            logBlockedRequest(request, origin, "null origin")
            sendForbidden(response)
            return
        }

        // Validate origin against allowlist
        if (!corsConfig.isOriginAllowed(origin)) {
            logBlockedRequest(request, origin, "not in allowlist")
            sendForbidden(response)
            return
        }

        // Origin is allowed - continue with the request
        filterChain.doFilter(request, response)
    }

    /**
     * Logs a blocked cross-origin request attempt.
     */
    private fun logBlockedRequest(request: HttpServletRequest, origin: String, reason: String) {
        log.warn(
            "Blocked cross-origin request: origin='{}', reason='{}', " +
                    "method='{}', uri='{}', remoteAddr='{}', userAgent='{}'",
            sanitizeLogValue(origin),
            reason,
            request.method,
            sanitizeLogValue(request.requestURI),
            request.remoteAddr,
            sanitizeLogValue(request.getHeader("User-Agent") ?: "unknown")
        )
    }

    /**
     * Sends a 403 Forbidden response.
     */
    private fun sendForbidden(response: HttpServletResponse) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "application/json"
        response.writer.write("""{"error": "$FORBIDDEN_ORIGIN_MESSAGE"}""")
        response.writer.flush()
    }

    /**
     * Sanitizes a value for safe logging (prevents log injection).
     */
    private fun sanitizeLogValue(value: String): String {
        return value
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(200) // Limit length
    }
}
