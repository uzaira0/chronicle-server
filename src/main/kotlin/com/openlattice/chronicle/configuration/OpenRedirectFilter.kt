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

import com.openlattice.chronicle.util.RedirectValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

/**
 * Filter configuration to prevent open redirect vulnerabilities.
 *
 * This filter intercepts all calls to [HttpServletResponse.sendRedirect] and validates
 * the redirect URL against a whitelist of allowed domains. If the redirect URL is not
 * allowed, the filter redirects to a safe fallback URL instead.
 *
 * Open redirect attacks allow attackers to craft URLs that appear to originate from
 * a trusted domain but redirect users to malicious sites. This is commonly used in
 * phishing attacks to steal credentials.
 *
 * Configuration:
 * ```yaml
 * chronicle:
 *   security:
 *     redirect:
 *       allowed-domains: login.example.edu,sso.example.edu
 *       fallback-url: /
 *       strict-host-matching: true
 * ```
 *
 * @see RedirectValidator
 */
@Configuration
public open class OpenRedirectFilterConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(OpenRedirectFilterConfig::class.java)

        /**
         * Default fallback URL when a redirect is blocked.
         */
        public const val DEFAULT_FALLBACK_URL: String = "/"

    }

    @Value("\${chronicle.security.redirect.allowed-domains:}")
    private lateinit var allowedDomainsConfig: String

    @Value("\${chronicle.security.redirect.fallback-url:/}")
    private lateinit var fallbackUrl: String

    @Value("\${chronicle.security.redirect.strict-host-matching:true}")
    private var strictHostMatching: Boolean = true

    /**
     * Creates the RedirectValidator bean with configured allowed domains.
     */
    @Bean
    public fun redirectValidator(): RedirectValidator {
        val domains = allowedDomainsConfig
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        if (domains.isEmpty()) {
            logger.info("Configuring redirect validator with no external domains; only same-origin redirects are allowed")
        } else {
            logger.info("Configuring redirect validator with allowed domains: {}", domains)
        }

        return RedirectValidator.Builder()
            .allowDomains(domains)
            .allowRelativePaths(true)
            .strictHostMatching(strictHostMatching)
            .build()
    }

    /**
     * Filter that wraps the response to intercept and validate all redirect calls.
     *
     * This filter is registered with high precedence to ensure it catches all redirects
     * before they are sent to the client.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public fun openRedirectFilter(redirectValidator: RedirectValidator): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                // Wrap the response to intercept sendRedirect calls
                val wrappedResponse = RedirectValidatingResponseWrapper(
                    request,
                    response,
                    redirectValidator,
                    fallbackUrl
                )
                filterChain.doFilter(request, wrappedResponse)
            }
        }
    }
}

/**
 * Response wrapper that intercepts and validates redirect URLs.
 *
 * All calls to [sendRedirect] are validated against the [RedirectValidator].
 * Invalid redirects are replaced with the fallback URL.
 */
public open class RedirectValidatingResponseWrapper(
    private val request: HttpServletRequest,
    response: HttpServletResponse,
    private val validator: RedirectValidator,
    private val fallbackUrl: String
) : HttpServletResponseWrapper(response) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(RedirectValidatingResponseWrapper::class.java)
    }

    /**
     * Validates and performs the redirect.
     *
     * If the redirect URL is valid (same-origin or whitelisted domain), the redirect
     * proceeds normally. If invalid, the redirect goes to the fallback URL instead.
     *
     * @param location The redirect URL
     */
    override fun sendRedirect(location: String?) {
        val safeLocation = if (validator.isValidRedirect(request, location)) {
            location!!
        } else {
            logger.warn(
                "Blocked open redirect attempt from IP: {}, requested redirect to: {}, using fallback: {}",
                request.remoteAddr,
                sanitizeForLogging(location),
                fallbackUrl
            )
            fallbackUrl
        }

        super.sendRedirect(safeLocation)
    }

    /**
     * Sanitizes a URL for safe logging by removing control characters
     * and truncating long strings.
     */
    private fun sanitizeForLogging(url: String?): String {
        if (url == null) return "<null>"
        return url
            .replace(Regex("[\r\n\t\u0000]"), "?")
            .take(200)
            .let { if (url.length > 200) "$it..." else it }
    }
}

/**
 * Request wrapper that can be used to validate redirect parameters in query strings.
 *
 * This is useful for validating redirect URLs passed as query parameters
 * (e.g., ?redirect=/dashboard or ?returnUrl=https://example.com).
 *
 * Usage:
 * ```kotlin
 * @GetMapping("/login")
 * fun login(
 *     @RequestParam("redirect", required = false) redirectUrl: String?,
 *     request: HttpServletRequest,
 *     response: HttpServletResponse,
 *     validator: RedirectValidator
 * ) {
 *     // Authenticate user...
 *
 *     // Safe redirect after authentication
 *     val safeUrl = validator.getSafeRedirectUrl(request, redirectUrl, "/dashboard")
 *     response.sendRedirect(safeUrl)
 * }
 * ```
 */
public open class RedirectParameterValidatingRequestWrapper(
    request: HttpServletRequest,
    private val validator: RedirectValidator,
    private val redirectParameterNames: Set<String> = setOf("redirect", "returnUrl", "redirectUrl", "return_url", "next")
) : HttpServletRequestWrapper(request) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(RedirectParameterValidatingRequestWrapper::class.java)
    }

    /**
     * Returns the parameter value, validating redirect parameters.
     *
     * If the parameter is a redirect parameter and contains an invalid URL,
     * returns null instead of the malicious URL.
     */
    override fun getParameter(name: String?): String? {
        val value = super.getParameter(name)

        if (name != null && name.lowercase() in redirectParameterNames.map { it.lowercase() }) {
            if (value != null && !validator.isValidRedirect(request as HttpServletRequest, value)) {
                logger.warn(
                    "Blocked malicious redirect parameter '{}' from IP: {}: {}",
                    name,
                    request.remoteAddr,
                    sanitizeForLogging(value)
                )
                return null
            }
        }

        return value
    }

    /**
     * Returns the parameter values, validating redirect parameters.
     */
    override fun getParameterValues(name: String?): Array<String>? {
        val values = super.getParameterValues(name) ?: return null

        if (name != null && name.lowercase() in redirectParameterNames.map { it.lowercase() }) {
            return values.filter { value ->
                val isValid = validator.isValidRedirect(request as HttpServletRequest, value)
                if (!isValid) {
                    logger.warn(
                        "Blocked malicious redirect parameter '{}' from IP: {}: {}",
                        name,
                        request.remoteAddr,
                        sanitizeForLogging(value)
                    )
                }
                isValid
            }.toTypedArray().ifEmpty { null }
        }

        return values
    }

    private fun sanitizeForLogging(url: String?): String {
        if (url == null) return "<null>"
        return url
            .replace(Regex("[\r\n\t\u0000]"), "?")
            .take(200)
            .let { if (url.length > 200) "$it..." else it }
    }
}
