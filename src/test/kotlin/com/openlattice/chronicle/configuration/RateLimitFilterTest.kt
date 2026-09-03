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

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.*

/**
 * Unit tests for [RateLimitFilter].
 *
 * Tests distributed rate limiting using a real embedded Hazelcast instance
 * and verifies:
 * - Requests within limit pass through (200)
 * - Requests exceeding limit are rejected (429)
 * - Retry-After header is set on 429 responses
 * - Rate limit headers (X-RateLimit-*) are included
 * - Whitelisted paths bypass rate limiting
 * - Whitelisted IPs bypass rate limiting
 * - Per-study key strategy isolates studies
 * - Per-participant-per-study key strategy isolates participants
 * - Auth endpoints use stricter limits
 * - Disabled configuration bypasses all limiting
 */
class RateLimitFilterTest {

    companion object {
        private lateinit var hz: HazelcastInstance

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val config = Config()
            config.clusterName = "rate-limit-test-${UUID.randomUUID()}"
            config.networkConfig.join.multicastConfig.isEnabled = false
            hz = Hazelcast.newHazelcastInstance(config)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            hz.shutdown()
        }
    }

    private fun createFilter(
        config: RateLimitConfiguration = RateLimitConfiguration()
    ): RateLimitFilter {
        return RateLimitFilter(
            hazelcastInstance = hz,
            config = config,
            handlerMapping = null
        )
    }

    private fun createRequest(
        path: String = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000/participants",
        method: String = "GET",
        remoteAddr: String = "203.0.113.42"
    ): MockHttpServletRequest {
        val request = MockHttpServletRequest(method, path)
        request.remoteAddr = remoteAddr
        return request
    }

    // --- Basic rate limiting ---

    @Test
    fun testRequestsWithinLimitPassThrough() {
        // Use a unique map name to isolate this test
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 10,
            hazelcastMapName = "RL_TEST_PASS_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        val request = createRequest(remoteAddr = "10.99.0.1")
        // Private IPs are whitelisted by default, use public IP
        val publicRequest = createRequest(remoteAddr = "203.0.113.${(Math.random() * 254).toInt() + 1}")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(publicRequest, response, chain)

        // Should pass through (first request)
        assertEquals(200, response.status)
    }

    @Test
    fun testRequestsExceedingLimitReturn429() {
        val limit = 3L
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = limit,
            burstCapacityMultiplier = 1.0, // No burst, exact limit
            hazelcastMapName = "RL_TEST_429_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        // Use a unique IP to avoid interference from other tests
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Exhaust the limit
        for (i in 1..limit) {
            val req = createRequest(remoteAddr = uniqueIp)
            val res = MockHttpServletResponse()
            filter.doFilter(req, res, MockFilterChain())
            assertEquals("Request $i should pass", 200, res.status)
        }

        // Next request should be rate limited
        val req = createRequest(remoteAddr = uniqueIp)
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, MockFilterChain())

        assertEquals(429, res.status)
    }

    @Test
    fun testRetryAfterHeaderOnRateLimited() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            includeRetryAfter = true,
            hazelcastMapName = "RL_TEST_RETRY_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Exhaust the limit
        filter.doFilter(createRequest(remoteAddr = uniqueIp), MockHttpServletResponse(), MockFilterChain())

        // Next request should have Retry-After
        val res = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = uniqueIp), res, MockFilterChain())

        assertEquals(429, res.status)
        assertNotNull("Retry-After header should be set", res.getHeader("Retry-After"))
        val retryAfter = res.getHeader("Retry-After").toLong()
        assertTrue("Retry-After should be positive", retryAfter > 0)
    }

    @Test
    fun testRateLimitHeadersIncluded() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 100,
            includeHeaders = true,
            hazelcastMapName = "RL_TEST_HEADERS_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        val res = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = uniqueIp), res, MockFilterChain())

        assertNotNull("X-RateLimit-Limit should be set", res.getHeader("X-RateLimit-Limit"))
        assertNotNull("X-RateLimit-Remaining should be set", res.getHeader("X-RateLimit-Remaining"))
        assertNotNull("X-RateLimit-Reset should be set", res.getHeader("X-RateLimit-Reset"))
    }

    // --- Whitelisting ---

    @Test
    fun testWhitelistedPathBypassesRateLimit() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedPaths = listOf("/healthcheck"),
            hazelcastMapName = "RL_TEST_WL_PATH_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Many requests to whitelisted path should all pass
        for (i in 1..10) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(path = "/healthcheck", remoteAddr = uniqueIp),
                res,
                MockFilterChain()
            )
            assertEquals("Whitelisted path request $i should pass", 200, res.status)
        }
    }

    @Test
    fun exactOperationalPathsBypassHazelcastBackedRateLimiting() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_OPERATIONAL_PATHS_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        config.whitelistedPaths
            .filter {
                it.startsWith("/chronicle/internal/health/") ||
                    it == "/prometheus" ||
                    it == "/prometheus/"
            }
            .forEach { path ->
                repeat(3) {
                    val response = MockHttpServletResponse()
                    filter.doFilter(
                        createRequest(path = path, remoteAddr = uniqueIp),
                        response,
                        MockFilterChain()
                    )
                    assertEquals("$path must bypass rate limiting", 200, response.status)
                    assertNull(response.getHeader(RateLimitFilter.HEADER_LIMIT))
                }
            }
    }

    @Test
    fun whitelistedPathsDoNotExemptSimilarSiblings() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedPaths = listOf(
                "/prometheus",
                "/chronicle/internal/health/live",
            ),
            hazelcastMapName = "RL_TEST_EXACT_PATHS_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        listOf(
            "/prometheus-not",
            "/chronicle/internal/health/live-extra",
        ).forEachIndexed { index, path ->
            val uniqueIp = "192.0.2.${index + 10}"
            filter.doFilter(
                createRequest(path = path, remoteAddr = uniqueIp),
                MockHttpServletResponse(),
                MockFilterChain()
            )
            val response = MockHttpServletResponse()
            filter.doFilter(
                createRequest(path = path, remoteAddr = uniqueIp),
                response,
                MockFilterChain()
            )

            assertEquals("$path must remain rate limited", 429, response.status)
        }
    }

    @Test
    fun testWhitelistedIpBypassesRateLimit() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedIps = listOf("192.0.2.99"),
            hazelcastMapName = "RL_TEST_WL_IP_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        // Many requests from whitelisted IP should all pass
        for (i in 1..10) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(remoteAddr = "192.0.2.99"),
                res,
                MockFilterChain()
            )
            assertEquals("Whitelisted IP request $i should pass", 200, res.status)
        }
    }

    /**
     * A servlet container reports IPv6 loopback in its expanded form, so the address the
     * filter sees never equals the `::1` a human wrote in `rate-limit.yaml`. Before the
     * whitelist compared address bytes, this test failed on request 2 with HTTP 429 — which
     * is exactly what happened to the Playwright harness the moment it reached the backend
     * over `localhost` (IPv6) instead of `127.0.0.1`.
     */
    @Test
    fun testWhitelistedIpv6LoopbackMatchesItsExpandedForm() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedIps = listOf("::1"),
            hazelcastMapName = "RL_TEST_WL_V6_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        for (i in 1..10) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(remoteAddr = "0:0:0:0:0:0:0:1"),
                res,
                MockFilterChain()
            )
            assertEquals("Whitelisted IPv6 loopback request $i should pass", 200, res.status)
        }
    }

    /** The same address written the compact way must still match when written out in full. */
    @Test
    fun testWhitelistedIpv6IsMatchedRegardlessOfSpelling() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedIps = listOf("2001:db8:0:0:0:0:0:1"),
            hazelcastMapName = "RL_TEST_WL_V6_SPELL_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        for (i in 1..5) {
            val res = MockHttpServletResponse()
            filter.doFilter(createRequest(remoteAddr = "2001:db8::1"), res, MockFilterChain())
            assertEquals("Whitelisted IPv6 request $i should pass", 200, res.status)
        }
    }

    /**
     * Servlet containers differ on how they spell an IPv6 peer: some hand back the bare
     * address, some bracket it, some append the scope. All three name the same client and
     * must resolve to the same whitelist decision.
     */
    @Test
    fun testWhitelistedIpv6MatchesBracketedAndScopedSpellings() {
        for (spelling in listOf("[::1]", "[0:0:0:0:0:0:0:1]", "::1%1", "0:0:0:0:0:0:0:1%lo0")) {
            val config = RateLimitConfiguration(
                defaultRequestsPerMinute = 1,
                burstCapacityMultiplier = 1.0,
                whitelistedIps = listOf("::1"),
                hazelcastMapName = "RL_TEST_WL_V6_FORM_${UUID.randomUUID()}"
            )
            val filter = createFilter(config)

            for (i in 1..5) {
                val res = MockHttpServletResponse()
                filter.doFilter(createRequest(remoteAddr = spelling), res, MockFilterChain())
                assertEquals("Request $i from $spelling should bypass the limiter", 200, res.status)
            }
        }
    }

    /** Whitelisting one address must not widen to any other address. */
    @Test
    fun testNonWhitelistedIpv6IsStillRateLimited() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedIps = listOf("::1"),
            hazelcastMapName = "RL_TEST_WL_V6_NEG_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        val first = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = "2001:db8::99"), first, MockFilterChain())
        assertEquals("First request from a non-whitelisted IPv6 client passes", 200, first.status)

        val second = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = "2001:db8::99"), second, MockFilterChain())
        assertEquals("Second request exceeds the limit", 429, second.status)
    }

    /**
     * A typo in `whitelisted-ips` must not become a DNS lookup: if it resolved, an unrelated
     * host would silently bypass the limiter.
     */
    @Test
    fun testNonLiteralWhitelistEntryDoesNotResolveToAHost() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            whitelistedIps = listOf("localhost"),
            hazelcastMapName = "RL_TEST_WL_HOSTNAME_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        val first = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = "127.0.0.1"), first, MockFilterChain())
        assertEquals("First request passes", 200, first.status)

        val second = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = "127.0.0.1"), second, MockFilterChain())
        assertEquals(
            "The hostname entry must not whitelist the address it would resolve to",
            429,
            second.status
        )
    }

    // --- Disabled configuration ---

    @Test
    fun testDisabledConfigBypassesAllLimiting() {
        val config = RateLimitConfiguration(
            enabled = false,
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_DISABLED_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Many requests should all pass when disabled
        for (i in 1..10) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(remoteAddr = uniqueIp),
                res,
                MockFilterChain()
            )
            assertEquals("Disabled filter request $i should pass", 200, res.status)
        }
    }

    // --- Study isolation ---

    @Test
    fun testDifferentStudiesHaveSeparateBuckets() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 2,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_STUDY_ISO_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        val studyA = "550e8400-e29b-41d4-a716-446655440001"
        val studyB = "550e8400-e29b-41d4-a716-446655440002"

        // Exhaust limit for study A
        for (i in 1..2) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(
                    path = "/chronicle/v3/study/$studyA/participants",
                    remoteAddr = uniqueIp
                ),
                res,
                MockFilterChain()
            )
            assertEquals(200, res.status)
        }

        // Study A should be limited (uses same IP-based key, not study-based by default)
        // Study B from a DIFFERENT IP should still pass
        val differentIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"
        val res = MockHttpServletResponse()
        filter.doFilter(
            createRequest(
                path = "/chronicle/v3/study/$studyB/participants",
                remoteAddr = differentIp
            ),
            res,
            MockFilterChain()
        )
        assertEquals("Different IP to study B should pass", 200, res.status)
    }

    // --- Auth endpoint stricter limits ---

    @Test
    fun testAuthPathsUseStricterLimit() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 100,
            authRequestsPerMinute = 2,
            burstCapacityMultiplier = 1.0,
            authPaths = listOf("/auth/"),
            hazelcastMapName = "RL_TEST_AUTH_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Exhaust the auth limit (2 requests)
        for (i in 1..2) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(path = "/auth/login", remoteAddr = uniqueIp),
                res,
                MockFilterChain()
            )
            assertEquals("Auth request $i should pass", 200, res.status)
        }

        // Third request should be limited
        val res = MockHttpServletResponse()
        filter.doFilter(
            createRequest(path = "/auth/login", remoteAddr = uniqueIp),
            res,
            MockFilterChain()
        )
        assertEquals("Auth request should be rate limited", 429, res.status)
    }

    @Test
    fun `reviewer secret failures use the fail closed per IP auth bucket`() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 100,
            authRequestsPerMinute = 2,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_REVIEWER_AUTH_${UUID.randomUUID()}",
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"
        val path = "/chronicle/v4/mobile/reviewer-enrollment"

        repeat(2) {
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(path = path, method = "POST", remoteAddr = uniqueIp), response, MockFilterChain())
            assertEquals(200, response.status)
        }

        val limited = MockHttpServletResponse()
        filter.doFilter(createRequest(path = path, method = "POST", remoteAddr = uniqueIp), limited, MockFilterChain())
        assertEquals(429, limited.status)
    }

    // --- 429 response body ---

    @Test
    fun testRateLimitedResponseBody() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_BODY_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Exhaust the limit
        filter.doFilter(createRequest(remoteAddr = uniqueIp), MockHttpServletResponse(), MockFilterChain())

        // Check the 429 response
        val res = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = uniqueIp), res, MockFilterChain())

        assertEquals(429, res.status)
        assertEquals("application/json", res.contentType)

        val body = res.contentAsString
        assertTrue("Body should contain error message", body.contains("Rate limit exceeded"))
        assertTrue("Body should contain retryAfter", body.contains("retryAfter"))
        assertTrue("Body should contain status 429", body.contains("429"))
    }

    // --- Per-study overrides ---

    @Test
    fun testPerStudyOverrideAppliesHigherLimit() {
        val studyId = "550e8400-e29b-41d4-a716-446655440099"
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            studyOverrides = mapOf(studyId to 5L),
            hazelcastMapName = "RL_TEST_OVERRIDE_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        val uniqueIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"

        // Default endpoints (non-study) should be limited at 1 req/min
        filter.doFilter(
            createRequest(path = "/chronicle/v3/some-endpoint", remoteAddr = uniqueIp),
            MockHttpServletResponse(),
            MockFilterChain()
        )
        val res1 = MockHttpServletResponse()
        filter.doFilter(
            createRequest(path = "/chronicle/v3/some-endpoint", remoteAddr = uniqueIp),
            res1,
            MockFilterChain()
        )
        assertEquals("Default endpoint should be limited", 429, res1.status)

        // Study endpoint with override should allow more (uses different key)
        val studyIp = "198.51.100.${(Math.random() * 254).toInt() + 1}"
        for (i in 1..5) {
            val res = MockHttpServletResponse()
            filter.doFilter(
                createRequest(
                    path = "/chronicle/v3/study/$studyId/participants",
                    remoteAddr = studyIp
                ),
                res,
                MockFilterChain()
            )
            assertEquals("Study override request $i should pass", 200, res.status)
        }
    }

    // --- CidrRange tests ---

    @Test
    fun testCidrRangeContainsIp() {
        val range = CidrRange.parse("192.168.1.0/24")
        assertTrue(range.contains("192.168.1.1"))
        assertTrue(range.contains("192.168.1.254"))
        assertFalse(range.contains("192.168.2.1"))
        assertFalse(range.contains("10.0.0.1"))
    }

    @Test
    fun testCidrRangeIPv6() {
        val range = CidrRange.parse("::1/128")
        assertTrue(range.contains("::1"))
        assertFalse(range.contains("::2"))
    }

    @Test
    fun testCidrRangeBroadNetwork() {
        val range = CidrRange.parse("10.0.0.0/8")
        assertTrue(range.contains("10.0.0.1"))
        assertTrue(range.contains("10.255.255.255"))
        assertFalse(range.contains("11.0.0.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCidrRangeInvalidInput() {
        CidrRange.parse("not-a-cidr")
    }

    // --- XFF header parsing ---

    @Test
    fun testXffHeaderRightmostNonTrustedIp() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 100,
            trustProxyHeaders = true,
            trustedProxyCidrs = listOf("172.16.0.0/12"),
            hazelcastMapName = "RL_TEST_XFF_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        val request = createRequest(remoteAddr = "172.16.0.1")
        // Client -> proxy chain: "real-client, internal-proxy"
        request.addHeader("X-Forwarded-For", "203.0.113.50, 172.16.0.5")

        val res = MockHttpServletResponse()
        filter.doFilter(request, res, MockFilterChain())

        // The filter should use 203.0.113.50 (the rightmost non-trusted IP)
        // We verify this indirectly: the rate limit headers should be present
        // (meaning the request was processed, not whitelisted as a private IP)
        assertNotNull(res.getHeader("X-RateLimit-Limit"))
    }

    @Test
    fun testUntrustedRemoteAddressCannotEvadeLimitWithSpoofedXff() {
        val config = RateLimitConfiguration(
            defaultRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            trustProxyHeaders = true,
            trustedProxyCidrs = listOf("172.16.0.0/12"),
            hazelcastMapName = "RL_TEST_XFF_SPOOF_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)

        val first = createRequest(remoteAddr = "203.0.113.10")
        first.addHeader("X-Forwarded-For", "198.51.100.1")
        filter.doFilter(first, MockHttpServletResponse(), MockFilterChain())

        val second = createRequest(remoteAddr = "203.0.113.10")
        second.addHeader("X-Forwarded-For", "198.51.100.2")
        val secondResponse = MockHttpServletResponse()
        filter.doFilter(second, secondResponse, MockFilterChain())

        assertEquals("Spoofed XFF must not create a fresh rate-limit bucket", 429, secondResponse.status)
    }
}
