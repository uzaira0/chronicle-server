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
// reason: file name reflects the CORS config feature; renaming the file would churn git/build for no behavior gain
@file:Suppress("MatchingDeclarationName")

package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings

/**
 * CORS (Cross-Origin Resource Sharing) configuration.
 *
 * This configuration controls which origins, methods, and headers are permitted
 * for cross-origin requests to the Chronicle API.
 *
 * Configuration is loaded from cors.yaml:
 * ```yaml
 * enabled: true
 * allowed-origins:
 *   - "https://study.example.org"
 * allowed-methods:
 *   - "GET"
 *   - "POST"
 *   - "PUT"
 *   - "DELETE"
 *   - "PATCH"
 *   - "OPTIONS"
 * allowed-headers:
 *   - "Authorization"
 *   - "Content-Type"
 *   - "X-Requested-With"
 * exposed-headers:
 *   - "X-Request-Id"
 * allow-credentials: true
 * max-age-seconds: 3600
 * ```
 *
 * SECURITY NOTES:
 * 1. NEVER use "*" for allowed-origins when allow-credentials is true
 * 2. Always explicitly list trusted origins - no wildcard subdomains
 * 3. Only expose headers that the frontend needs to read
 * 4. Limit allowed methods to those actually used by the API
 * 5. In production, use HTTPS origins only
 *
 * @author uzaira0
 */
@ReloadableConfiguration(uri = "cors.yaml")
public data class CorsConfiguration(
    /**
     * Whether CORS handling is enabled.
     * When false, no CORS headers are added and cross-origin requests may be blocked.
     */
    @param:JsonProperty("enabled")
    val enabled: Boolean = true,

    /**
     * List of allowed origins for cross-origin requests.
     *
     * SECURITY: Do NOT use wildcards with credentials enabled.
     * Each origin must be an exact match (protocol + host + port).
     *
     * Examples:
     * - "https://study.example.org"
     * - "http://localhost:3000" (development only)
     */
    @param:JsonProperty("allowed-origins")
    val allowedOrigins: List<String> = listOf(),

    /**
     * List of allowed HTTP methods for cross-origin requests.
     * TRACE and TRACK are never allowed for security reasons.
     */
    @param:JsonProperty("allowed-methods")
    val allowedMethods: List<String> = listOf(
        "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
    ),

    /**
     * List of allowed request headers that can be sent in cross-origin requests.
     * Only include headers that the frontend actually needs to send.
     */
    @param:JsonProperty("allowed-headers")
    val allowedHeaders: List<String> = listOf(
        "Authorization",
        "Content-Type",
        "Accept",
        "X-Requested-With",
        "Origin",
        "Access-Control-Request-Method",
        "Access-Control-Request-Headers",
        "X-Chronicle-Signature",
        "X-Chronicle-Timestamp",
        "X-Chronicle-Nonce"
    ),

    /**
     * List of response headers that the browser is allowed to expose to JavaScript.
     * Only expose headers that the frontend needs to read.
     */
    @param:JsonProperty("exposed-headers")
    val exposedHeaders: List<String> = listOf(
        "X-Request-Id",
        "X-RateLimit-Limit",
        "X-RateLimit-Remaining",
        "X-RateLimit-Reset"
    ),

    /**
     * Whether credentials (cookies, authorization headers) are allowed.
     *
     * SECURITY: When true, allowed-origins MUST NOT include "*".
     * The browser will reject responses if Access-Control-Allow-Credentials
     * is true and Access-Control-Allow-Origin is "*".
     */
    @param:JsonProperty("allow-credentials")
    val allowCredentials: Boolean = true,

    /**
     * How long (in seconds) the browser should cache preflight responses.
     * Default: 3600 seconds (1 hour).
     *
     * Higher values reduce preflight requests but delay policy changes.
     * Lower values ensure faster policy updates but increase request overhead.
     */
    @param:JsonProperty("max-age-seconds")
    val maxAgeSeconds: Long = 3600,

    /**
     * Development mode allows additional origins for local development.
     *
     * SECURITY: Must be false in production.
     * When true, adds localhost origins automatically.
     */
    @param:JsonProperty("development-mode")
    val developmentMode: Boolean = false,

    /**
     * Additional localhost ports to allow in development mode.
     * Only used when developmentMode is true.
     */
    @param:JsonProperty("development-ports")
    val developmentPorts: List<Int> = listOf(3000, 3001, 5173, 8080)
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("cors.yaml")

        /**
         * HTTP methods that are NEVER allowed for security reasons.
         * TRACE can be used for Cross-Site Tracing (XST) attacks.
         * TRACK is a deprecated alias for TRACE in some servers.
         */
        public val FORBIDDEN_METHODS = setOf("TRACE", "TRACK")

        /**
         * Maximum allowed max-age to prevent indefinite caching.
         * 86400 seconds = 24 hours
         */
        public const val MAX_ALLOWED_AGE: Long = 86400
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey = key

    /**
     * Returns the effective list of allowed origins.
     * In development mode, adds localhost origins.
     */
    public fun getEffectiveAllowedOrigins(): Set<String> {
        val origins = allowedOrigins.toMutableSet()

        if (developmentMode) {
            developmentPorts.forEach { port ->
                origins.add("http://localhost:$port")
                origins.add("http://127.0.0.1:$port")
            }
        }

        return origins
    }

    /**
     * Returns the effective list of allowed methods.
     * Always filters out forbidden methods (TRACE, TRACK).
     */
    public fun getEffectiveAllowedMethods(): Set<String> {
        return allowedMethods
            .map { it.uppercase() }
            .filter { it !in FORBIDDEN_METHODS }
            .toSet()
    }

    /**
     * Returns the effective max-age, capped at MAX_ALLOWED_AGE.
     */
    public fun getEffectiveMaxAge(): Long {
        return minOf(maxAgeSeconds, MAX_ALLOWED_AGE)
    }

    /**
     * Validates the configuration for security issues.
     * @return List of validation error messages, empty if valid.
     */
    public fun validate(): List<String> {
        val errors = mutableListOf<String>()

        // Check for wildcard with credentials
        if (allowCredentials && allowedOrigins.any { it == "*" }) {
            errors.add("CORS: Wildcard origin '*' cannot be used with credentials enabled")
        }

        // Check for forbidden methods
        val forbiddenUsed = allowedMethods.filter { it.uppercase() in FORBIDDEN_METHODS }
        if (forbiddenUsed.isNotEmpty()) {
            errors.add("CORS: Forbidden methods configured: $forbiddenUsed - these will be ignored")
        }

        // Check for empty origins in non-development mode
        if (!developmentMode && allowedOrigins.isEmpty()) {
            errors.add("CORS: No allowed origins configured and development mode is disabled")
        }

        // Check for http origins in non-development mode (warning)
        if (!developmentMode) {
            val httpOrigins = allowedOrigins.filter { it.startsWith("http://") }
            if (httpOrigins.isNotEmpty()) {
                errors.add("CORS: Non-HTTPS origins in production: $httpOrigins")
            }
        }

        return errors
    }

    /**
     * Checks if a given origin is allowed.
     * @param origin The Origin header value to check.
     * @return true if the origin is allowed, false otherwise.
     */
    public fun isOriginAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        if (origin == "null") return false  // Block "null" origin (sandboxed iframes, etc.)

        return origin in getEffectiveAllowedOrigins()
    }
}
