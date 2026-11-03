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
import com.openlattice.chronicle.util.ClientIpResolver

/**
 * Configuration data class for distributed rate limiting settings.
 *
 * Loaded from rate-limit.yaml configuration file.
 *
 * Example configuration:
 * ```yaml
 * enabled: true
 * auth-requests-per-minute: 10
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
     * Rate for credential checks and failed unauthenticated attempts: requests per minute per IP.
     * Stricter to prevent brute force attacks.
     */
    @param:JsonProperty("auth-requests-per-minute")
    val authRequestsPerMinute: Long = 10,

    /** Rate for the enrollment preview: requests per minute per participant per IP. */
    @param:JsonProperty("read-requests-per-minute")
    val readRequestsPerMinute: Long = 100,

    /** Rate for enrollment and participant form code exchange: requests per minute per participant or IP. */
    @param:JsonProperty("write-requests-per-minute")
    val writeRequestsPerMinute: Long = 60,

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
     * CIDR ranges of trusted direct reverse proxies (e.g., Traefik, nginx).
     * When parsing X-Forwarded-For, IPs matching these ranges are skipped
     * and the rightmost non-trusted IP is used as the client IP.
     * This prevents X-Forwarded-For spoofing attacks.
     *
     * Do not use broad private ranges here. If F5 or another upstream proxy is
     * part of the XFF chain, add its explicit CIDR in deployment config.
     * CHRONICLE_TRUSTED_PROXY_CIDRS replaces this list; without it, only loopback
     * proxies are trusted, the same default [ClientIpResolver] applies.
     */
    @param:JsonProperty("trusted-proxy-cidrs")
    val trustedProxyCidrs: List<String> = ClientIpResolver.LOOPBACK_PROXY_CIDRS
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("rate-limit.yaml")
    }

    override fun getKey(): ConfigurationKey = Companion.key
}
