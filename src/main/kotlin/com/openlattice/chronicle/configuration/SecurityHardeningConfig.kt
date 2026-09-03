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

import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException

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
 *    - X-XSS-Protection: 1; mode=block
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

    public companion object {
        private val logger = LoggerFactory.getLogger(SecurityHardeningConfig::class.java)

        // Maximum request body size: 10MB (adjust based on your needs)
        public const val MAX_REQUEST_SIZE_BYTES: Long = 10 * 1024 * 1024

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
                        "Blocked TRACE request from IP: ${LogSanitizer.sanitizeIp(request.remoteAddr)}, " +
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

                // Enable XSS filter in browsers (legacy, but still useful)
                response.setHeader("X-XSS-Protection", "1; mode=block")

                // Prevent caching of sensitive responses
                // Individual endpoints can override if caching is needed
                if (!response.containsHeader("Cache-Control")) {
                    response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    response.setHeader("Pragma", "no-cache")
                    response.setHeader("Expires", "0")
                }

                // Referrer policy to limit information leakage
                response.setHeader("Referrer-Policy", "no-referrer")

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
     */
    // reason: security request-validation filter — each early return rejects a distinct malicious
    // input (null-byte URI/query/param, oversized param) and the param-map iteration nesting is
    // inherent; the boundary catch must turn any parsing failure into a 400 and never leak. Keeping
    // the guard-clause structure preserves the exact reject-or-pass behavior.
    @Suppress("NestedBlockDepth", "ReturnCount", "TooGenericExceptionCaught")
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public fun requestValidationFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                // Check for null bytes in URI
                if (request.requestURI.contains('\u0000')) {
                    val sanitizedUri = LogSanitizer.sanitizeUri(request.requestURI.replace('\u0000', '?'))
                    // Interpolated values are sanitized via LogSanitizer; the rule's metavariable-regex
                    // matches the variable name "uri", not unsanitized request input. Documented FP.
                    logger.warn( // nosemgrep: chronicle-log-injection
                        "Blocked request with null byte in URI from IP: " +
                            "${LogSanitizer.sanitizeIp(request.remoteAddr)}, URI: $sanitizedUri"
                    )
                    response.sendError(HttpStatus.BAD_REQUEST.value(), "Invalid request URI")
                    return
                }

                // Check for null bytes in query string
                request.queryString?.let { queryString ->
                    if (queryString.contains('\u0000')) {
                        logger.warn("Blocked request with null byte in query string from IP: ${LogSanitizer.sanitizeIp(request.remoteAddr)}")
                        response.sendError(HttpStatus.BAD_REQUEST.value(), "Invalid query string")
                        return
                    }
                }

                // Check parameter values for null bytes and excessive length
                try {
                    request.parameterMap.forEach { (paramName, values) ->
                        values.forEach { value ->
                            if (value.contains('\u0000')) {
                                logger.warn(
                                    "Blocked request with null byte in parameter " +
                                        "'${LogSanitizer.sanitize(paramName)}' from IP: " +
                                        LogSanitizer.sanitizeIp(request.remoteAddr)
                                )
                                response.sendError(HttpStatus.BAD_REQUEST.value(),
                                    "Invalid parameter value")
                                return
                            }
                            if (value.length > MAX_PARAMETER_LENGTH) {
                                logger.warn(
                                    "Blocked request with oversized parameter " +
                                        "'${LogSanitizer.sanitize(paramName)}' (${value.length} chars) " +
                                        "from IP: ${LogSanitizer.sanitizeIp(request.remoteAddr)}"
                                )
                                response.sendError(HttpStatus.BAD_REQUEST.value(),
                                    "Parameter value too long")
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "Error validating request parameters from IP: " +
                            "${LogSanitizer.sanitizeIp(request.remoteAddr)}: " +
                            LogSanitizer.sanitize(e.message)
                    )
                    response.sendError(HttpStatus.BAD_REQUEST.value(), "Invalid request parameters")
                    return
                }

                filterChain.doFilter(request, response)
            }
        }
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
                if (contentLength > MAX_REQUEST_SIZE_BYTES) {
                    logger.warn(
                        "Blocked oversized request (${contentLength} bytes) from IP: " +
                            "${LogSanitizer.sanitizeIp(request.remoteAddr)}, " +
                            "URI: ${LogSanitizer.sanitizeRequestPath(request.requestURI)}"
                    )
                    response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "Request body too large. Maximum size: ${MAX_REQUEST_SIZE_BYTES / (1024 * 1024)}MB")
                    return
                }

                // For chunked transfer encoding (Content-Length absent/is -1),
                // wrap the input stream to enforce the size limit during reads.
                if (contentLength == -1L && request.contentType != null) {
                    val wrappedRequest = SizeLimitedRequestWrapper(request, MAX_REQUEST_SIZE_BYTES)
                    filterChain.doFilter(wrappedRequest, response)
                } else {
                    filterChain.doFilter(request, response)
                }
            }
        }
    }
}

/**
 * Request wrapper that enforces a size limit on the input stream.
 * Prevents chunked transfer encoding from bypassing Content-Length-based size checks.
 */
private class SizeLimitedRequestWrapper(
    request: HttpServletRequest,
    private val maxBytes: Long
) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream {
        val original = super.getInputStream()
        return object : ServletInputStream() {
            private var bytesRead: Long = 0

            override fun read(): Int {
                if (bytesRead >= maxBytes) {
                    throw IOException("Request body exceeded maximum size of ${maxBytes / (1024 * 1024)}MB")
                }
                val b = original.read()
                if (b != -1) bytesRead++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= maxBytes) {
                    throw IOException("Request body exceeded maximum size of ${maxBytes / (1024 * 1024)}MB")
                }
                val allowedLen = minOf(len.toLong(), maxBytes - bytesRead).toInt()
                val result = original.read(b, off, allowedLen)
                if (result > 0) bytesRead += result
                return result
            }

            override fun isFinished(): Boolean = original.isFinished
            override fun isReady(): Boolean = original.isReady
            override fun setReadListener(listener: ReadListener?) = original.setReadListener(listener)
        }
    }
}
