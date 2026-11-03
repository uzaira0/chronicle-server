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

import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.util.ClientIpRecord
import com.openlattice.chronicle.util.LogSanitizer
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.util.StringUtils
import org.springframework.web.filter.OncePerRequestFilter

internal const val REFERRER_POLICY_HEADER = "Referrer-Policy"
internal const val PARTICIPANT_REFERRER_POLICY = "no-referrer"

/**
 * Security hardening configuration for Spring Security and HTTP handling.
 *
 * This configuration addresses several security concerns:
 *
 * 1. HTTP TRACE METHOD (Cross-Site Tracing - XST):
 *    - TRACE method is disabled to prevent XST attacks
 *    - XST can be used to steal cookies and authentication tokens
 *
 * 2. SECURITY HEADERS:
 *    - Adds defense-in-depth headers (backup to nginx/load balancer)
 *    - X-Content-Type-Options: nosniff
 *    - X-Frame-Options: DENY
 *    - X-XSS-Protection: 0 (the legacy auditor is off, as Spring Security sets it)
 *    - Cache-Control for sensitive responses
 *
 * 3. REQUEST VALIDATION:
 *    - Rejects requests with null bytes (path traversal attacks)
 *    - Validates request parameters for malicious content
 *
 * 4. REQUEST SIZE LIMITS:
 *    - Limits request body size to prevent memory exhaustion DoS
 *    - See maxRequestSize configuration
 */
@Configuration
public open class SecurityHardeningConfig {

    @Bean
    public fun requestHardeningFilters(
        @Qualifier("securityHeadersFilter") securityHeadersFilter: Filter,
        @Qualifier("traceMethodBlockingFilter") traceMethodBlockingFilter: Filter,
        @Qualifier("requestSizeLimitFilter") requestSizeLimitFilter: Filter,
        @Qualifier("requestValidationFilter") requestValidationFilter: Filter,
        @Qualifier("parameterPollutionFilter") parameterPollutionFilter: Filter,
    ): RequestHardeningFilters = RequestHardeningFilters(listOf(
        securityHeadersFilter,
        traceMethodBlockingFilter,
        requestSizeLimitFilter,
        requestValidationFilter,
        parameterPollutionFilter,
    ))

    public companion object {
        private val logger = LoggerFactory.getLogger(SecurityHardeningConfig::class.java)

        // Maximum parameter value length
        public const val MAX_PARAMETER_LENGTH: Int = 10000
    }

    /**
     * Filter to block HTTP TRACE method requests.
     * TRACE can be exploited in Cross-Site Tracing (XST) attacks to steal
     * authentication cookies and tokens.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public fun traceMethodBlockingFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                if (HttpMethod.TRACE.matches(request.method)) {
                    logger.warn(
                        "Blocked TRACE request from IP: ${ClientIpRecord.logReference(request)}, " +
                            "URI: ${LogSanitizer.sanitizeRequestPath(request.requestURI)}"
                    )
                    response.status = HttpStatus.METHOD_NOT_ALLOWED.value()
                    response.setHeader("Allow", "GET, POST, PUT, DELETE, PATCH, OPTIONS")
                    return
                }
                filterChain.doFilter(request, response)
            }
        }
    }

    /**
     * Filter to add security headers to all responses.
     * These headers provide defense-in-depth protection and are
     * a backup to headers set at the load balancer/reverse proxy level.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public fun securityHeadersFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                // Prevent MIME type sniffing
                response.setHeader("X-Content-Type-Options", "nosniff")

                // Prevent clickjacking
                response.setHeader("X-Frame-Options", "DENY")

                // Turn the legacy XSS auditor off: its blocking mode can itself be abused.
                response.setHeader("X-XSS-Protection", "0")

                // Prevent caching of sensitive responses
                // Individual endpoints can override if caching is needed
                if (!response.containsHeader("Cache-Control")) {
                    response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    response.setHeader("Pragma", "no-cache")
                    response.setHeader("Expires", "0")
                }

                // Referrer policy to limit information leakage
                response.setHeader(REFERRER_POLICY_HEADER, PARTICIPANT_REFERRER_POLICY)

                // Permissions Policy (formerly Feature-Policy)
                response.setHeader("Permissions-Policy",
                    "accelerometer=(), camera=(), geolocation=(), gyroscope=(), " +
                    "magnetometer=(), microphone=(), payment=(), usb=(), " +
                    "browsing-topics=(), clipboard-read=(), clipboard-write=(), " +
                    "display-capture=(), identity-credentials-get=(), " +
                    "idle-detection=(), serial=(), hid=()")

                filterChain.doFilter(request, response)
            }
        }
    }

    /**
     * Filter to validate request parameters and reject malicious input.
     * Blocks requests with:
     * - Null bytes (path traversal indicator)
     * - Excessively long parameter values (potential buffer overflow)
     * - DPoP authorization: Spring Security enables DPoP whenever its classes are present;
     *   Chronicle issues only Bearer tokens and never issues DPoP-bound tokens
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public fun requestValidationFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain,
            ) {
                if (StringUtils.startsWithIgnoreCase(request.getHeader(HttpHeaders.AUTHORIZATION), OAuth2AccessToken.TokenType.DPOP.value)) {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, OAuth2AccessToken.TokenType.BEARER.value)
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unsupported authorization scheme")
                    return
                }
                val error = requestValidationError(request)
                if (error != null) {
                    SecurityHardeningConfig.logger.warn("{} from {}", error, ClientIpRecord.logReference(request))
                    response.sendError(HttpStatus.BAD_REQUEST.value(), error)
                    return
                }
                filterChain.doFilter(request, response)
            }
        }
    }

    private fun requestValidationError(request: HttpServletRequest): String? = when {
        request.requestURI.contains('\u0000') -> "Invalid request URI"
        request.queryString?.contains('\u0000') == true -> "Invalid query string"
        else -> parameterValidationError(request)
    }

    // Parameter decoding can fail before a map is available; reject it without logging request data.
    @Suppress("TooGenericExceptionCaught")
    private fun parameterValidationError(request: HttpServletRequest): String? = try {
        request.parameterMap.values.asSequence().flatMap { it.asSequence() }.firstNotNullOfOrNull { value ->
            when {
                value.contains('\u0000') -> "Invalid parameter value"
                value.length > MAX_PARAMETER_LENGTH -> "Parameter value too long"
                else -> null
            }
        }
    } catch (_: Exception) {
        "Invalid request parameters"
    }

    /**
     * Filter to enforce request body size limits.
     * Prevents memory exhaustion attacks via large request bodies.
     *
     * Note: This is defense-in-depth. Primary size limits should be
     * configured at the load balancer/reverse proxy level.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    public fun requestSizeLimitFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                val contentLength = request.contentLengthLong

                // Check Content-Length header if present
                if (contentLength > UploadLimits.MAX_REQUEST_SIZE_BYTES) {
                    logger.warn(
                        "Blocked oversized request (${contentLength} bytes) from IP: " +
                            "${ClientIpRecord.logReference(request)}, " +
                            "URI: ${LogSanitizer.sanitizeRequestPath(request.requestURI)}"
                    )
                    response.sendError(HttpStatus.CONTENT_TOO_LARGE.value(),
                        "Request body too large. Maximum size: ${UploadLimits.MAX_REQUEST_SIZE_BYTES / (1024 * 1024)}MB")
                    return
                }

                // For chunked transfer encoding (Content-Length absent/is -1),
                // wrap the input stream to enforce the size limit during reads.
                try {
                    val bounded = if (contentLength < 0) SizeLimitedRequestWrapper(request, UploadLimits.MAX_REQUEST_SIZE_BYTES) else request
                    filterChain.doFilter(bounded, response)
                } catch (error: RequestBodyTooLargeException) {
                    if (response.isCommitted) throw error
                    response.sendError(HttpStatus.CONTENT_TOO_LARGE.value(), "Request body too large")
                }
            }
        }
    }
}
