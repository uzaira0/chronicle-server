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

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings

/**
 * Configuration data class for distributed rate limiting settings.
 *
 * Loaded from rate-limit.yaml configuration file.
 *
 * Example configuration:
 * ```yaml
 * enabled: true
 * default-requests-per-minute: 100
 * auth-requests-per-minute: 5
 * whitelisted-ips:
 *   - "127.0.0.1"
 * whitelisted-paths:
 *   - "/healthcheck"
 *   - "/actuator/health"
 * ```
 *
 * @author uzaira0
 */
@ReloadableConfiguration(uri = "rate-limit.yaml")
public data class RateLimitConfiguration(
    /**
     * Whether rate limiting is enabled globally.
     * When false, all rate limiting is bypassed.
     */
    @param:JsonProperty("enabled")
    val enabled: Boolean = true,

    /**
     * Default rate limit: requests per minute per IP/user.
     * Applied to endpoints without specific @RateLimit annotation.
     */
    @param:JsonProperty("default-requests-per-minute")
    val defaultRequestsPerMinute: Long = 100,

    /**
     * Rate limit for authentication endpoints: requests per minute per IP.
     * Applied to /auth/[*], /login, /oauth/[*] endpoints.
     * Stricter to prevent brute force attacks.
     */
    @param:JsonProperty("auth-requests-per-minute")
    val authRequestsPerMinute: Long = 10,

    /**
     * Rate limit for sensitive API endpoints: requests per minute per IP/user.
     * Applied to endpoints with @RateLimit(type = RateLimitType.SENSITIVE).
     */
    @param:JsonProperty("sensitive-requests-per-minute")
    val sensitiveRequestsPerMinute: Long = 20,

    /**
     * Rate limit for health/status endpoints: requests per minute.
     * Generous limit for monitoring probes.
     */
    @param:JsonProperty("health-requests-per-minute")
    val healthRequestsPerMinute: Long = 1000,

    /**
     * Rate limit for read endpoints (GET): requests per minute per study.
     * Applied to data retrieval endpoints.
     */
    @param:JsonProperty("read-requests-per-minute")
    val readRequestsPerMinute: Long = 100,

    /**
     * Rate limit for write endpoints (POST upload): requests per minute per participant per study.
     * Applied to data upload endpoints (phone telemetry, sensors, etc.).
     */
    @param:JsonProperty("write-requests-per-minute")
    val writeRequestsPerMinute: Long = 60,

    /**
     * Rate limit for admin endpoints: requests per minute.
     * Applied to administrative operations.
     */
    @param:JsonProperty("admin-requests-per-minute")
    val adminRequestsPerMinute: Long = 20,

    /**
     * Per-study rate limit overrides.
     * Keys are study UUIDs, values are the per-study request-per-minute limit.
     * Studies not in this map use the default limits.
     * Useful for studies with more participants or higher data volumes.
     */
    @param:JsonProperty("study-overrides")
    val studyOverrides: Map<String, Long> = emptyMap(),

    /**
     * Burst capacity multiplier.
     * Allows temporary bursts above the rate limit.
     * For example, 2.0 means up to 2x the limit can be consumed in a burst.
     */
    @param:JsonProperty("burst-capacity-multiplier")
    val burstCapacityMultiplier: Double = 1.5,

    /**
     * List of IP addresses or CIDR ranges that bypass rate limiting.
     * Typically includes:
     * - Internal service IPs
     * - Health check probes
     * - Trusted partners
     *
     * Supports both individual IPs and CIDR notation.
     */
    @param:JsonProperty("whitelisted-ips")
    val whitelistedIps: List<String> = listOf(
        "127.0.0.1",
        "::1"
    ),

    /**
     * Whether RFC1918/private network ranges should bypass rate limiting.
     *
     * Keep this false in production behind F5/Traefik: forwarded client chains
     * commonly contain private proxy hops, and auto-whitelisting private ranges
     * can accidentally exempt real users from abuse protection. Use
     * whitelisted-ips for explicit internal exceptions instead.
     */
    @param:JsonProperty("whitelist-private-networks")
    val whitelistPrivateNetworks: Boolean = false,

    /**
     * List of exact request paths that bypass rate limiting.
     * Typically includes:
     * - Health check endpoints
     * - Metrics endpoints
     * - Static resources
     *
     * Entries are exact paths, not prefixes. This prevents a trusted path such
     * as `/prometheus` from also exempting an attacker-controlled sibling such
     * as `/prometheus-not`.
     */
    @param:JsonProperty("whitelisted-paths")
    val whitelistedPaths: List<String> = listOf(
        "/healthcheck",
        "/actuator/health",
        "/actuator/prometheus",
        "/chronicle/internal/health/live",
        "/chronicle/internal/health/ready",
        "/prometheus",
        "/prometheus/",
    ),

    /**
     * Authentication endpoint path patterns.
     * These paths use the stricter authRequestsPerMinute limit.
     */
    @param:JsonProperty("auth-paths")
    val authPaths: List<String> = listOf(
        "/auth/",
        "/login",
        "/oauth/",
        "/api/auth/",
        "/chronicle/v4/mobile/reviewer-enrollment",
    ),

    /**
     * Header name to extract real client IP from reverse proxy.
     * Common values: X-Forwarded-For, X-Real-IP, CF-Connecting-IP
     */
    @param:JsonProperty("client-ip-header")
    val clientIpHeader: String = "X-Forwarded-For",

    /**
     * Fallback header for client IP if primary header is missing.
     */
    @param:JsonProperty("client-ip-header-fallback")
    val clientIpHeaderFallback: String = "X-Real-IP",

    /**
     * Whether to trust X-Forwarded-For header.
     * Should be true when behind a trusted reverse proxy.
     * Should be false for direct connections to prevent IP spoofing.
     */
    @param:JsonProperty("trust-proxy-headers")
    val trustProxyHeaders: Boolean = true,

    /**
     * Name of the Hazelcast IMap used for rate limit state storage.
     */
    @param:JsonProperty("hazelcast-map-name")
    val hazelcastMapName: String = "RATE_LIMIT_BUCKETS",

    /**
     * TTL for rate limit entries in seconds.
     * Should be at least 60 seconds (1 minute) for per-minute limits.
     */
    @param:JsonProperty("entry-ttl-seconds")
    val entryTtlSeconds: Long = 120,

    /**
     * Whether to include rate limit headers in responses.
     * Headers: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset
     */
    @param:JsonProperty("include-headers")
    val includeHeaders: Boolean = true,

    /**
     * Whether to include Retry-After header when rate limited.
     */
    @param:JsonProperty("include-retry-after")
    val includeRetryAfter: Boolean = true,

    /**
     * CIDR ranges of trusted direct reverse proxies (e.g., Traefik, nginx).
     * When parsing X-Forwarded-For, IPs matching these ranges are skipped
     * and the rightmost non-trusted IP is used as the client IP.
     * This prevents X-Forwarded-For spoofing attacks.
     *
     * Do not use broad private ranges here. If F5 or another upstream proxy is
     * part of the XFF chain, add its explicit CIDR in deployment config.
     */
    @param:JsonProperty("trusted-proxy-cidrs")
    val trustedProxyCidrs: List<String> = listOf(
        "127.0.0.0/8",
        "::1/128"
    )
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("rate-limit.yaml")
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey = key
}

/**
 * Endpoint-specific rate limit configuration.
 * Used to override the default rate limit for specific endpoints.
 */
public data class EndpointRateLimitConfig(
    val requestsPerMinute: Long,
    val burstCapacity: Long
)
