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

import com.hazelcast.config.MapConfig
import com.hazelcast.core.HazelcastInstance
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.beans.factory.annotation.Autowired
import jakarta.inject.Inject
import jakarta.servlet.Filter

/**
 * Spring configuration for distributed rate limiting using Bucket4j with Hazelcast.
 *
 * This configuration:
 * 1. Creates and configures the Hazelcast map for rate limit bucket storage
 * 2. Creates the rate limit filter bean
 * 3. Logs the rate limiting configuration on startup
 *
 * Rate limiting protects against:
 * - Brute force attacks on authentication endpoints
 * - DoS attacks from individual clients
 * - API abuse and excessive usage
 *
 * The filter runs at HIGHEST_PRECEDENCE + 20, after:
 * - Security filters (TRACE blocking, headers, validation)
 * - Mobile API signature verification
 *
 * But before:
 * - Spring Security authentication
 * - Controller processing
 *
 * Configuration is loaded from rate-limit.yaml:
 * ```yaml
 * enabled: true
 * default-requests-per-minute: 100
 * auth-requests-per-minute: 5
 * whitelisted-ips:
 *   - "127.0.0.1"
 * whitelisted-paths:
 *   - "/healthcheck"
 * ```
 *
 * @author uzaira0
 */
@Configuration
public open class RateLimitConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(RateLimitConfig::class.java)
    }

    @Inject
    private lateinit var hazelcastInstance: HazelcastInstance

    @Autowired(required = false)
    private var rateLimitConfiguration: RateLimitConfiguration? = null

    @Autowired(required = false)
    private var handlerMapping: RequestMappingHandlerMapping? = null

    /**
     * Creates the distributed rate limit filter.
     *
     * The filter is ordered at HIGHEST_PRECEDENCE + 20 to run after
     * basic security filters but before Spring Security authentication.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public fun rateLimitFilter(): Filter {
        val config = rateLimitConfiguration ?: RateLimitConfiguration()

        if (!config.enabled) {
            logger.info("Rate limiting is DISABLED")
            return NoOpFilter()
        }

        // Configure Hazelcast map for rate limit buckets
        configureHazelcastMap(config)

        logger.info(
            """
            Rate limiting is ENABLED:
              - Default limit: {} req/min
              - Health endpoint limit: {} req/min
              - Read endpoint limit: {} req/min (per study)
              - Write endpoint limit: {} req/min (per participant per study)
              - Admin endpoint limit: {} req/min
              - Auth endpoint limit: {} req/min (per IP)
              - Sensitive endpoint limit: {} req/min
              - Burst capacity: {}x
              - Whitelisted IPs: {}
              - Whitelist private networks: {}
              - Whitelisted paths: {}
              - Client IP header: {}
              - Trust proxy headers: {}
              - Per-study overrides: {}
            """.trimIndent(),
            config.defaultRequestsPerMinute,
            config.healthRequestsPerMinute,
            config.readRequestsPerMinute,
            config.writeRequestsPerMinute,
            config.adminRequestsPerMinute,
            config.authRequestsPerMinute,
            config.sensitiveRequestsPerMinute,
            config.burstCapacityMultiplier,
            config.whitelistedIps.size,
            config.whitelistPrivateNetworks,
            config.whitelistedPaths,
            config.clientIpHeader,
            config.trustProxyHeaders,
            config.studyOverrides.size
        )

        return RateLimitFilter(
            hazelcastInstance = hazelcastInstance,
            config = config,
            handlerMapping = handlerMapping
        )
    }

    /**
     * Configures the Hazelcast map for rate limit bucket storage.
     * Sets appropriate TTL and eviction policies.
     */
    // reason: boundary catch — map may already exist with a different config; log and continue startup
    @Suppress("TooGenericExceptionCaught")
    private fun configureHazelcastMap(config: RateLimitConfiguration) {
        try {
            val mapConfig = MapConfig(config.hazelcastMapName)
                .setTimeToLiveSeconds(config.entryTtlSeconds.toInt())
                .setBackupCount(1)
                .setAsyncBackupCount(0)
                .setStatisticsEnabled(false)

            hazelcastInstance.config.addMapConfig(mapConfig)

            logger.debug(
                "Configured Hazelcast map '{}' for rate limiting with TTL: {} seconds",
                config.hazelcastMapName,
                config.entryTtlSeconds
            )
        } catch (e: Exception) {
            // Map may already exist with different config - log warning but continue
            logger.warn(
                "Could not configure Hazelcast map '{}': {}. Using existing configuration.",
                config.hazelcastMapName,
                e.message
            )
        }
    }

    /**
     * A no-op filter that simply passes requests through without modification.
     * Used when rate limiting is disabled.
     */
    private class NoOpFilter : Filter {
        override fun doFilter(
            request: jakarta.servlet.ServletRequest,
            response: jakarta.servlet.ServletResponse,
            chain: jakarta.servlet.FilterChain
        ) {
            chain.doFilter(request, response)
        }
    }
}
