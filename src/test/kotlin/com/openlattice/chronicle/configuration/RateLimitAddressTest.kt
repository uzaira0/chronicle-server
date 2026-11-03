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

import org.junit.Test
import java.util.UUID
import org.junit.Assert.assertEquals
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletResponse

class RateLimitAddressTest : RateLimitFixture() {
    // --- Whitelisting ---

    @Test
    fun testWhitelistedPathBypassesRateLimit() {
        val config = RateLimitConfiguration(
            authRequestsPerMinute = 1,
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
            authRequestsPerMinute = 1,
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
                }
            }
    }

    @Test
    fun whitelistedPathsDoNotExemptSimilarSiblings() {
        val config = RateLimitConfiguration(
            authRequestsPerMinute = 1,
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
            authRequestsPerMinute = 1,
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
     * filter sees never equals the `::1` a human wrote in `rate-limit.yaml`, so the
     * whitelist compares address bytes. A string compare answers HTTP 429 on request 2 to any
     * client reaching the backend over `localhost` (IPv6) instead of `127.0.0.1`.
     */
    @Test
    fun testWhitelistedIpv6LoopbackMatchesItsExpandedForm() {
        val config = RateLimitConfiguration(
            authRequestsPerMinute = 1,
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
            authRequestsPerMinute = 1,
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
                authRequestsPerMinute = 1,
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
            authRequestsPerMinute = 1,
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
            authRequestsPerMinute = 1,
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

    // --- XFF header parsing ---

    @Test
    fun testXffHeaderRightmostNonTrustedIp() {
        val config = RateLimitConfiguration(
            authRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            trustProxyHeaders = true,
            trustedProxyCidrs = listOf("172.16.0.0/12"),
            hazelcastMapName = "RL_TEST_XFF_${UUID.randomUUID()}"
        )
        val filter = createFilter(config)
        fun status(proxy: String, chain: String): Int {
            val request = createRequest(remoteAddr = proxy)
            request.addHeader("X-Forwarded-For", chain)
            val res = MockHttpServletResponse()
            filter.doFilter(request, res, MockFilterChain())
            return res.status
        }

        // The bucket belongs to 203.0.113.50 (the rightmost non-trusted IP), whichever proxy relays it.
        assertEquals(200, status("172.16.0.1", "203.0.113.50, 172.16.0.5"))
        assertEquals(429, status("172.16.0.2", "203.0.113.50, 172.16.0.6"))
        assertEquals(200, status("172.16.0.1", "203.0.113.51, 172.16.0.5"))
    }

    @Test
    fun testUntrustedRemoteAddressCannotEvadeLimitWithSpoofedXff() {
        val config = RateLimitConfiguration(
            authRequestsPerMinute = 1,
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
