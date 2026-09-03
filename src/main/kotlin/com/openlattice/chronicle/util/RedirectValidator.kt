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
package com.openlattice.chronicle.util

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import jakarta.servlet.http.HttpServletRequest

/**
 * Validates redirect URLs to prevent open redirect vulnerabilities.
 *
 * Open redirect attacks occur when an application accepts user-controlled input
 * for redirect URLs without proper validation. Attackers can craft URLs that
 * redirect users to malicious sites while appearing to originate from a trusted domain.
 *
 * Example attack:
 * ```
 * https://trusted-domain.com/login?redirect=https://evil-site.com/phishing
 * ```
 *
 * This validator ensures redirects only go to:
 * 1. Same-origin paths (relative URLs starting with /)
 * 2. Explicitly whitelisted domains
 * 3. Safe URL schemes (http/https only)
 *
 * Usage:
 * ```kotlin
 * val validator = RedirectValidator(
 *     allowedDomains = setOf("login.example.edu", "sso.example.edu")
 * )
 *
 * // In a controller or filter
 * if (validator.isValidRedirect(request, redirectUrl)) {
 *     response.sendRedirect(redirectUrl)
 * } else {
 *     response.sendRedirect("/")  // Safe fallback
 * }
 * ```
 *
 * @property allowedDomains Set of external domains that are allowed for redirects.
 *                          These should be deployment-specific trusted domains such as an SSO provider.
 * @property allowRelativePaths Whether to allow relative path redirects (default: true).
 *                              Relative paths starting with "/" are generally safe.
 * @property strictHostMatching Whether to require exact host matching (default: true).
 *                              When false, subdomains of allowed domains are permitted.
 */
public open class RedirectValidator(
    private val allowedDomains: Set<String> = emptySet(),
    private val allowRelativePaths: Boolean = true,
    private val strictHostMatching: Boolean = true
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(RedirectValidator::class.java)

        /**
         * URL schemes that are considered safe for redirects.
         * Only http and https are allowed to prevent javascript:, data:, and other
         * potentially dangerous schemes.
         */
        private val SAFE_SCHEMES = setOf("http", "https")

        /**
         * Dangerous URL patterns that should always be blocked.
         * These patterns can be used to bypass validation or execute attacks.
         */
        private val DANGEROUS_PATTERNS = listOf(
            "//",          // Protocol-relative URL (can redirect to any domain)
            "\\\\",        // Backslash variant (IE compatibility issue)
            "/\\",         // Mixed separator attack
            "\\/",         // Escaped separator attack
            "%2f%2f",      // URL-encoded //
            "%5c",         // URL-encoded backslash
            "@",           // URL with credentials (user@host attack)
            "\r",          // CR injection
            "\n",          // LF injection
            "%0d",         // URL-encoded CR
            "%0a",         // URL-encoded LF
            "\u0000",      // Null byte
            "javascript:", // Script execution
            "data:",       // Data URL execution
            "vbscript:",   // VBScript execution (IE)
            "file:"        // Local file access
        )

        /**
         * Default instance with no external domains allowed.
         * Only allows same-origin relative path redirects.
         */
        public val DEFAULT = RedirectValidator()
    }

    /**
     * Validates whether a redirect URL is safe to use.
     *
     * A URL is considered safe if it is:
     * 1. A relative path starting with "/" (if allowRelativePaths is true)
     * 2. An absolute URL to the same origin as the request
     * 3. An absolute URL to an explicitly whitelisted domain
     *
     * The following are always rejected:
     * - Null or blank URLs
     * - URLs with dangerous schemes (javascript:, data:, etc.)
     * - URLs with newline characters (header injection)
     * - Protocol-relative URLs (//evil.com)
     * - URLs with embedded credentials (@)
     *
     * @param request The current HTTP request (used to determine the origin)
     * @param redirectUrl The URL to validate
     * @return true if the redirect is safe, false otherwise
     */
    // reason: security open-redirect validator — guard-clause early returns and the multi-pattern scan are intentional
    // and clearer as one method; restructuring risks weakening the SSRF/open-redirect checks
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    public fun isValidRedirect(request: HttpServletRequest, redirectUrl: String?): Boolean {
        if (redirectUrl.isNullOrBlank()) {
            logger.debug("Rejected redirect: URL is null or blank")
            return false
        }

        // Reject any URL containing percent-encoded percent (%25) to prevent double-encoding bypass.
        // An attacker can send %252F which passes pattern checks, then URI() decodes once to %2F,
        // avoiding the "//" detection.
        if (redirectUrl.contains("%25", ignoreCase = true)) {
            logger.warn("Rejected redirect with double-encoded characters: {}",
                sanitizeForLogging(redirectUrl))
            return false
        }

        // Decode URL in a loop until stable to catch multi-layer encoding attacks
        val decodedUrl = decodeUntilStable(redirectUrl)

        // Check for dangerous patterns on both original and fully-decoded URL
        for (url in listOf(redirectUrl, decodedUrl)) {
            val lowerUrl = url.lowercase()
            // Strip the scheme (e.g. "https://") before checking dangerous patterns,
            // so the legitimate "://" in absolute URLs doesn't trigger the "//" check.
            val schemeEnd = lowerUrl.indexOf("://")
            val urlWithoutScheme = if (schemeEnd >= 0) lowerUrl.substring(schemeEnd + 3) else lowerUrl
            for (pattern in DANGEROUS_PATTERNS) {
                val lowerPattern = pattern.lowercase()
                val checkTarget = if (lowerPattern == "//" || lowerPattern == "%2f%2f") urlWithoutScheme else lowerUrl
                if (checkTarget.contains(lowerPattern)) {
                    logger.warn("Rejected redirect with dangerous pattern '{}': {}",
                        pattern, sanitizeForLogging(redirectUrl))
                    return false
                }
            }
        }

        // Handle relative paths
        if (redirectUrl.startsWith("/")) {
            if (!allowRelativePaths) {
                logger.debug("Rejected relative path redirect (disabled): {}",
                    sanitizeForLogging(redirectUrl))
                return false
            }
            // Additional check: ensure it's truly a relative path, not //evil.com
            if (redirectUrl.length > 1 && redirectUrl[1] == '/') {
                logger.warn("Rejected protocol-relative URL disguised as path: {}",
                    sanitizeForLogging(redirectUrl))
                return false
            }
            return true
        }

        // Parse as absolute URL
        val uri = try {
            URI(redirectUrl)
        } catch (expected: URISyntaxException) {
            logger.warn("Rejected redirect with malformed URL: {}",
                sanitizeForLogging(redirectUrl))
            return false
        }

        // Validate scheme
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in SAFE_SCHEMES) {
            logger.warn("Rejected redirect with unsafe scheme '{}': {}",
                scheme, sanitizeForLogging(redirectUrl))
            return false
        }

        // Get the host from the URL
        val redirectHost = uri.host?.lowercase()
        if (redirectHost.isNullOrBlank()) {
            logger.warn("Rejected redirect with missing host: {}",
                sanitizeForLogging(redirectUrl))
            return false
        }

        // Check if it's same-origin
        val requestHost = request.serverName?.lowercase()
        val requestPort = request.serverPort
        val requestScheme = request.scheme?.lowercase() ?: "http"

        if (isSameOrigin(requestScheme, requestHost, requestPort, uri)) {
            return true
        }

        // Check against allowed domains
        if (isAllowedDomain(redirectHost)) {
            return true
        }

        logger.warn("Rejected redirect to unauthorized domain '{}': {}",
            redirectHost, sanitizeForLogging(redirectUrl))
        return false
    }

    /**
     * Checks if the redirect URL is to the same origin as the request.
     */
    private fun isSameOrigin(
        requestScheme: String,
        requestHost: String?,
        requestPort: Int,
        redirectUri: URI
    ): Boolean {
        if (requestHost == null) return false

        val redirectHost = redirectUri.host?.lowercase() ?: return false
        val redirectPort = if (redirectUri.port == -1) {
            if (redirectUri.scheme == "https") 443 else 80
        } else {
            redirectUri.port
        }

        val effectiveRequestPort = if (requestPort == 80 || requestPort == 443) {
            if (requestScheme == "https") 443 else 80
        } else {
            requestPort
        }

        return redirectHost == requestHost && redirectPort == effectiveRequestPort
    }

    /**
     * Checks if the host is in the allowed domains list.
     */
    private fun isAllowedDomain(host: String): Boolean {
        if (strictHostMatching) {
            return host in allowedDomains.map { it.lowercase() }
        }

        // Allow subdomains of allowed domains
        return allowedDomains.any { allowed ->
            val lowerAllowed = allowed.lowercase()
            host == lowerAllowed || host.endsWith(".$lowerAllowed")
        }
    }

    /**
     * Returns a safe redirect URL, or a fallback if the URL is invalid.
     *
     * @param request The current HTTP request
     * @param redirectUrl The URL to validate
     * @param fallback The fallback URL to use if validation fails (default: "/")
     * @return The validated redirect URL or the fallback
     */
    public fun getSafeRedirectUrl(
        request: HttpServletRequest,
        redirectUrl: String?,
        fallback: String = "/"
    ): String {
        return if (isValidRedirect(request, redirectUrl)) {
            redirectUrl!!
        } else {
            logger.info("Using fallback redirect '{}' instead of invalid URL: {}",
                fallback, sanitizeForLogging(redirectUrl))
            fallback
        }
    }

    /**
     * Decodes a URL repeatedly until the output is stable (no further decoding possible).
     * Limits iterations to prevent infinite loops on malformed input.
     */
    private fun decodeUntilStable(url: String, maxIterations: Int = 5): String {
        var current = url
        repeat(maxIterations) {
            val decoded = try {
                URLDecoder.decode(current, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return current
            }
            if (decoded == current) return current
            current = decoded
        }
        return current
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

    /**
     * Builder for creating RedirectValidator instances with custom configuration.
     */
    public class Builder {
        private var allowedDomains: MutableSet<String> = mutableSetOf()
        private var allowRelativePaths: Boolean = true
        private var strictHostMatching: Boolean = true

        /**
         * Adds a domain to the allowed list.
         */
        public fun allowDomain(domain: String): Builder {
            allowedDomains.add(domain)
            return this
        }

        /**
         * Adds multiple domains to the allowed list.
         */
        public fun allowDomains(vararg domains: String): Builder {
            allowedDomains.addAll(domains)
            return this
        }

        /**
         * Adds multiple domains to the allowed list.
         */
        public fun allowDomains(domains: Collection<String>): Builder {
            allowedDomains.addAll(domains)
            return this
        }

        /**
         * Sets whether relative paths are allowed (default: true).
         */
        public fun allowRelativePaths(allow: Boolean): Builder {
            allowRelativePaths = allow
            return this
        }

        /**
         * Sets whether strict host matching is required (default: true).
         * When false, subdomains of allowed domains are permitted.
         */
        public fun strictHostMatching(strict: Boolean): Builder {
            strictHostMatching = strict
            return this
        }

        /**
         * Builds the RedirectValidator instance.
         */
        public fun build(): RedirectValidator {
            return RedirectValidator(
                allowedDomains = allowedDomains.toSet(),
                allowRelativePaths = allowRelativePaths,
                strictHostMatching = strictHostMatching
            )
        }
    }
}
