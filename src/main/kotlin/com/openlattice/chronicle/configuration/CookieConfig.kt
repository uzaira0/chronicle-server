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
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

/**
 * Cookie security configuration.
 *
 * This configuration ensures all cookies set by the application include
 * proper security attributes to protect against CSRF and cookie theft.
 *
 * SECURITY FEATURES:
 *
 * 1. SameSite Attribute:
 *    - Strict: Cookie only sent with same-site requests (most secure)
 *    - Lax: Cookie sent with same-site + top-level navigations (default)
 *    - None: Cookie sent with all requests (requires Secure flag)
 *
 * 2. Secure Flag:
 *    - When true, cookie only sent over HTTPS
 *    - Required when SameSite=None
 *
 * 3. HttpOnly Flag:
 *    - Prevents JavaScript access to cookie
 *    - Protects against XSS cookie theft
 *
 * 4. Path and Domain:
 *    - Limit cookie scope to minimize exposure
 *
 * CROSS-ORIGIN CONSIDERATIONS:
 * - SameSite=Lax is recommended for most applications
 * - SameSite=None is needed for cross-origin authenticated requests
 *   (e.g., frontend on different domain than API)
 * - SameSite=Strict blocks cookies on all cross-origin requests including
 *   links from other sites
 *
 * @author uzaira0
 */
@Configuration
public open class CookieConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(CookieConfig::class.java)

        /**
         * SameSite attribute values.
         */
        public const val SAME_SITE_STRICT: String = "Strict"
        public const val SAME_SITE_LAX: String = "Lax"
        public const val SAME_SITE_NONE: String = "None"
    }

    /**
     * SameSite cookie policy. Valid values: Strict, Lax, None
     * Default: Lax (provides CSRF protection while allowing normal navigation)
     */
    @Value("\${chronicle.security.cookie.same-site:Lax}")
    private lateinit var sameSitePolicy: String

    /**
     * Whether to set the Secure flag on cookies.
     * Default: true (cookies only sent over HTTPS)
     */
    @Value("\${chronicle.security.cookie.secure:true}")
    private var secureCookies: Boolean = true

    /**
     * Whether to set the HttpOnly flag on cookies.
     * Default: true (cookies not accessible via JavaScript)
     */
    @Value("\${chronicle.security.cookie.http-only:true}")
    private var httpOnlyCookies: Boolean = true

    /**
     * Cookie path. Default: / (entire application)
     */
    @Value("\${chronicle.security.cookie.path:/}")
    private lateinit var cookiePath: String

    /**
     * Cookie domain. Empty means use the request's domain.
     */
    @Value("\${chronicle.security.cookie.domain:}")
    private var cookieDomain: String = ""

    /**
     * Maximum age for cookies in seconds. -1 means session cookie.
     * Default: -1 (session cookie, expires when browser closes)
     */
    @Value("\${chronicle.security.cookie.max-age:-1}")
    private var maxAge: Int = -1

    /**
     * Creates a filter that wraps responses to add security attributes to cookies.
     *
     * This filter runs at HIGHEST_PRECEDENCE + 6, after CORS validation but
     * before application logic.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 6)
    public fun cookieSecurityFilter(): Filter {
        // Validate SameSite policy
        val validPolicies = setOf(SAME_SITE_STRICT, SAME_SITE_LAX, SAME_SITE_NONE)
        val normalizedPolicy = sameSitePolicy.replaceFirstChar { it.uppercase() }

        if (normalizedPolicy !in validPolicies) {
            logger.warn("Invalid SameSite policy '{}', defaulting to Lax", sameSitePolicy)
        }

        val effectivePolicy = if (normalizedPolicy in validPolicies) normalizedPolicy else SAME_SITE_LAX

        // SameSite=None requires Secure flag
        if (effectivePolicy == SAME_SITE_NONE && !secureCookies) {
            logger.warn("SameSite=None requires Secure flag - enabling Secure flag automatically")
        }
        val effectiveSecure = if (effectivePolicy == SAME_SITE_NONE) true else secureCookies

        logger.info(
            "Cookie security filter initialized: SameSite={}, Secure={}, HttpOnly={}, Path={}",
            effectivePolicy, effectiveSecure, httpOnlyCookies, cookiePath
        )

        return CookieSecurityFilter(
            sameSite = effectivePolicy,
            secure = effectiveSecure,
            httpOnly = httpOnlyCookies,
            path = cookiePath,
            domain = cookieDomain.ifBlank { null },
            maxAge = maxAge
        )
    }
}

/**
 * Filter that adds security attributes to all cookies.
 */
public open class CookieSecurityFilter(
    private val sameSite: String,
    private val secure: Boolean,
    private val httpOnly: Boolean,
    private val path: String,
    private val domain: String?,
    private val maxAge: Int
) : OncePerRequestFilter() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(CookieSecurityFilter::class.java)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Wrap the response to intercept cookie setting
        val wrappedResponse = SecureCookieResponseWrapper(
            response = response,
            sameSite = sameSite,
            secure = secure,
            httpOnly = httpOnly,
            path = path,
            domain = domain,
            maxAge = maxAge
        )

        filterChain.doFilter(request, wrappedResponse)
    }
}

/**
 * Response wrapper that intercepts cookie setting to add security attributes.
 */
public open class SecureCookieResponseWrapper(
    private val response: HttpServletResponse,
    private val sameSite: String,
    private val secure: Boolean,
    private val httpOnly: Boolean,
    private val path: String,
    private val domain: String?,
    private val maxAge: Int
) : HttpServletResponseWrapper(response) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(SecureCookieResponseWrapper::class.java)
    }

    override fun addCookie(cookie: Cookie) {
        // Build cookie string with security attributes
        val cookieValue = buildSecureCookieString(cookie)
        addHeader("Set-Cookie", cookieValue)
    }

    /**
     * Builds a cookie string with security attributes.
     * We build the string manually because the Servlet Cookie API doesn't support SameSite.
     */
    private fun buildSecureCookieString(cookie: Cookie): String {
        val builder = StringBuilder()

        // Name=Value
        builder.append(cookie.name)
        builder.append("=")
        builder.append(cookie.value ?: "")

        // Path (use cookie's path or our configured default)
        val effectivePath = cookie.path?.takeIf { it.isNotBlank() } ?: path
        builder.append("; Path=").append(effectivePath)

        // Domain (use cookie's domain, our configured domain, or omit)
        val effectiveDomain = cookie.domain?.takeIf { it.isNotBlank() } ?: domain
        if (effectiveDomain != null) {
            builder.append("; Domain=").append(effectiveDomain)
        }

        // Max-Age (use cookie's max age, our configured value, or omit for session cookie)
        val effectiveMaxAge = if (cookie.maxAge >= 0) cookie.maxAge else maxAge
        if (effectiveMaxAge >= 0) {
            builder.append("; Max-Age=").append(effectiveMaxAge)
        }

        // Secure flag
        if (secure || cookie.secure) {
            builder.append("; Secure")
        }

        // HttpOnly flag (use our config unless cookie explicitly sets it)
        if (httpOnly || cookie.isHttpOnly) {
            builder.append("; HttpOnly")
        }

        // SameSite attribute (not supported by Servlet Cookie API)
        builder.append("; SameSite=").append(sameSite)

        return builder.toString()
    }

    /**
     * Also intercept setHeader to ensure Set-Cookie headers get SameSite attribute.
     */
    override fun setHeader(name: String, value: String) {
        if (name.equals("Set-Cookie", ignoreCase = true)) {
            super.setHeader(name, ensureSameSite(value))
        } else {
            super.setHeader(name, value)
        }
    }

    /**
     * Also intercept addHeader for Set-Cookie headers.
     */
    override fun addHeader(name: String, value: String) {
        if (name.equals("Set-Cookie", ignoreCase = true)) {
            super.addHeader(name, ensureSameSite(value))
        } else {
            super.addHeader(name, value)
        }
    }

    /**
     * Ensures a Set-Cookie header value has the SameSite attribute.
     */
    private fun ensureSameSite(cookieValue: String): String {
        // If SameSite is already present, don't add it again
        if (cookieValue.contains("SameSite", ignoreCase = true)) {
            return cookieValue
        }

        // Add SameSite and optionally Secure
        val builder = StringBuilder(cookieValue)

        if (secure && !cookieValue.contains("Secure", ignoreCase = true)) {
            builder.append("; Secure")
        }

        builder.append("; SameSite=").append(sameSite)

        return builder.toString()
    }
}
