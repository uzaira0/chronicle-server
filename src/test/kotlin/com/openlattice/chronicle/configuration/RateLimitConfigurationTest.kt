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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RateLimitConfiguration] defaults and [RateLimit] annotation types.
 */
class RateLimitConfigurationTest {

    @Test
    fun testDefaultConfigurationValues() {
        val config = RateLimitConfiguration()

        assertTrue("Rate limiting should be enabled by default", config.enabled)
        assertEquals(100L, config.defaultRequestsPerMinute)
        assertEquals(10L, config.authRequestsPerMinute)
        assertEquals(20L, config.sensitiveRequestsPerMinute)
        assertEquals(1000L, config.healthRequestsPerMinute)
        assertEquals(100L, config.readRequestsPerMinute)
        assertEquals(60L, config.writeRequestsPerMinute)
        assertEquals(20L, config.adminRequestsPerMinute)
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
    fun testDefaultAuthPaths() {
        val config = RateLimitConfiguration()

        assertTrue(config.authPaths.contains("/auth/"))
        assertTrue(config.authPaths.contains("/login"))
        assertTrue(config.authPaths.contains("/oauth/"))
        assertTrue(config.authPaths.contains("/chronicle/v4/mobile/reviewer-enrollment"))
    }

    @Test
    fun testStudyOverridesEmptyByDefault() {
        val config = RateLimitConfiguration()
        assertTrue(config.studyOverrides.isEmpty())
    }

    @Test
    fun testStudyOverridesCanBeSet() {
        val overrides = mapOf(
            "550e8400-e29b-41d4-a716-446655440000" to 200L,
            "6ba7b810-9dad-11d1-80b4-00c04fd430c8" to 500L
        )
        val config = RateLimitConfiguration(studyOverrides = overrides)

        assertEquals(2, config.studyOverrides.size)
        assertEquals(200L, config.studyOverrides["550e8400-e29b-41d4-a716-446655440000"])
        assertEquals(500L, config.studyOverrides["6ba7b810-9dad-11d1-80b4-00c04fd430c8"])
    }

    @Test
    fun testCustomConfiguration() {
        val config = RateLimitConfiguration(
            enabled = false,
            defaultRequestsPerMinute = 50,
            authRequestsPerMinute = 3,
            healthRequestsPerMinute = 2000,
            readRequestsPerMinute = 200,
            writeRequestsPerMinute = 30,
            adminRequestsPerMinute = 10,
            burstCapacityMultiplier = 2.0
        )

        assertFalse(config.enabled)
        assertEquals(50L, config.defaultRequestsPerMinute)
        assertEquals(3L, config.authRequestsPerMinute)
        assertEquals(2000L, config.healthRequestsPerMinute)
        assertEquals(200L, config.readRequestsPerMinute)
        assertEquals(30L, config.writeRequestsPerMinute)
        assertEquals(10L, config.adminRequestsPerMinute)
        assertEquals(2.0, config.burstCapacityMultiplier, 0.01)
    }

    @Test
    fun testHazelcastConfigDefaults() {
        val config = RateLimitConfiguration()

        assertEquals("RATE_LIMIT_BUCKETS", config.hazelcastMapName)
        assertEquals(120L, config.entryTtlSeconds)
    }

    @Test
    fun testResponseHeaderDefaults() {
        val config = RateLimitConfiguration()

        assertTrue(config.includeHeaders)
        assertTrue(config.includeRetryAfter)
    }

    @Test
    fun testConfigurationKey() {
        assertEquals("rate-limit.yaml", RateLimitConfiguration.key.uri)
    }

    // --- RateLimitType tests ---

    @Test
    fun testRateLimitTypeValues() {
        val types = RateLimitType.values()
        assertTrue(types.contains(RateLimitType.DEFAULT))
        assertTrue(types.contains(RateLimitType.AUTH))
        assertTrue(types.contains(RateLimitType.SENSITIVE))
        assertTrue(types.contains(RateLimitType.HEALTH))
        assertTrue(types.contains(RateLimitType.READ))
        assertTrue(types.contains(RateLimitType.WRITE))
        assertTrue(types.contains(RateLimitType.ADMIN))
        assertTrue(types.contains(RateLimitType.UNLIMITED))
    }

    // --- RateLimitKeyStrategy tests ---

    @Test
    fun testRateLimitKeyStrategyValues() {
        val strategies = RateLimitKeyStrategy.values()
        assertTrue(strategies.contains(RateLimitKeyStrategy.AUTO))
        assertTrue(strategies.contains(RateLimitKeyStrategy.IP))
        assertTrue(strategies.contains(RateLimitKeyStrategy.USER))
        assertTrue(strategies.contains(RateLimitKeyStrategy.USER_AND_IP))
        assertTrue(strategies.contains(RateLimitKeyStrategy.ENDPOINT))
        assertTrue(strategies.contains(RateLimitKeyStrategy.STUDY))
        assertTrue(strategies.contains(RateLimitKeyStrategy.PARTICIPANT_STUDY))
    }

    // --- Tiered limits ordering ---

    @Test
    fun testTieredLimitsOrderingMakesSense() {
        val config = RateLimitConfiguration()

        // Health should be the most generous
        assertTrue(
            "Health limit should be >= read limit",
            config.healthRequestsPerMinute >= config.readRequestsPerMinute
        )

        // Read should be >= write
        assertTrue(
            "Read limit should be >= write limit",
            config.readRequestsPerMinute >= config.writeRequestsPerMinute
        )

        // Write should be >= admin
        assertTrue(
            "Write limit should be >= admin limit",
            config.writeRequestsPerMinute >= config.adminRequestsPerMinute
        )

        // Auth should be the strictest
        assertTrue(
            "Auth limit should be <= admin limit",
            config.authRequestsPerMinute <= config.adminRequestsPerMinute
        )
    }
}
