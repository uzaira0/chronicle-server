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

import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RateLimitConfiguration] defaults.
 */
class RateLimitConfigurationTest {

    @Test
    fun testDefaultConfigurationValues() {
        val config = RateLimitConfiguration()

        assertTrue("Rate limiting should be enabled by default", config.enabled)
        assertEquals(10L, config.authRequestsPerMinute)
        assertEquals(100L, config.readRequestsPerMinute)
        assertEquals(60L, config.writeRequestsPerMinute)
        assertEquals(1.5, config.burstCapacityMultiplier, 0.01)
    }

    @Test
    fun testDefaultWhitelistedPaths() {
        val config = RateLimitConfiguration()

        assertTrue(config.whitelistedPaths.contains("/healthcheck"))
        assertTrue(config.whitelistedPaths.contains("/actuator/health"))
        assertTrue(config.whitelistedPaths.contains("/actuator/prometheus"))
        assertTrue(config.whitelistedPaths.contains("/chronicle/internal/health/live"))
        assertTrue(config.whitelistedPaths.contains("/chronicle/internal/health/ready"))
        assertTrue(config.whitelistedPaths.contains("/prometheus"))
        assertTrue(config.whitelistedPaths.contains("/prometheus/"))
    }

    @Test
    fun testDefaultWhitelistedIps() {
        val config = RateLimitConfiguration()

        assertTrue(config.whitelistedIps.contains("127.0.0.1"))
        assertTrue(config.whitelistedIps.contains("::1"))
    }

    @Test
    fun testConfigurationKey() {
        assertEquals("rate-limit.yaml", RateLimitConfiguration.key.uri)
    }
    /**
     * The rate limiter and every audit/log IP reference trust the same proxies when the
     * deployment sets none. The shipped policy must not widen that to a broad private range.
     */
    @Test
    fun shippedPolicyTrustsOnlyLoopbackProxiesLikeTheCodeDefault() {
        val shipped = checkNotNull(javaClass.getResourceAsStream("/rate-limit.yaml")).use {
            ObjectMappers.getYamlMapper().readValue(it, RateLimitConfiguration::class.java)
        }

        assertEquals(listOf("127.0.0.0/8", "::1/128"), shipped.trustedProxyCidrs)
        assertEquals(RateLimitConfiguration().trustedProxyCidrs, shipped.trustedProxyCidrs)
    }
}
