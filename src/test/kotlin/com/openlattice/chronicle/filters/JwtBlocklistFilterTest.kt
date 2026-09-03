package com.openlattice.chronicle.filters

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.authorization.JwtBlocklist
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.*

class JwtBlocklistFilterTest {

    companion object {
        private lateinit var hz: HazelcastInstance
        private lateinit var blocklist: JwtBlocklist
        private lateinit var filter: JwtBlocklistFilter

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val config = Config()
            config.clusterName = "jwt-filter-test-${UUID.randomUUID()}"
            config.networkConfig.join.multicastConfig.isEnabled = false
            hz = Hazelcast.newHazelcastInstance(config)
            blocklist = JwtBlocklist(hz)
            filter = JwtBlocklistFilter(blocklist)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            SecurityContextHolder.clearContext()
            hz.shutdown()
        }

        private fun createJwt(
            tokenValue: String = "test-token-${UUID.randomUUID()}",
            jti: String? = UUID.randomUUID().toString(),
            issuedAt: Instant = Instant.now().minusSeconds(60),
            expiresAt: Instant = Instant.now().plusSeconds(3600)
        ): Jwt {
            val builder = Jwt.withTokenValue(tokenValue)
                .header("alg", "HS256")
                .subject("test-user")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
            if (jti != null) {
                builder.claim("jti", jti)
            }
            return builder.build()
        }
    }

    private fun setAuthentication(jwt: Jwt) {
        val auth = JwtAuthenticationToken(jwt)
        SecurityContextHolder.getContext().authentication = auth
    }

    @Test
    fun testAllowsUnblockedToken() {
        val jwt = createJwt()
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNotNull("Unblocked token should pass through the filter chain", chain.request)
        assertEquals(200, response.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testRejectsTokenBlockedByJti() {
        val jti = UUID.randomUUID().toString()
        val jwt = createJwt(jti = jti)
        blocklist.blockToken(jti, Instant.now().plusSeconds(3600))
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNull("Blocked-by-JTI token should NOT pass through", chain.request)
        assertEquals(401, response.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testRejectsTokenBlockedByValue() {
        val tokenValue = "blocked-token-value-${UUID.randomUUID()}"
        val jwt = createJwt(tokenValue = tokenValue, jti = null)
        blocklist.blockTokenByValue(tokenValue, Instant.now().plusSeconds(3600))
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNull("Blocked-by-value token should NOT pass through", chain.request)
        assertEquals(401, response.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testRejectsTokenIssuedBeforeRevokeAll() {
        val issuedAt = Instant.now().minusSeconds(7200)
        val revokeTime = Instant.now().minusSeconds(3600)
        val jwt = createJwt(jti = null, issuedAt = issuedAt)
        blocklist.revokeAllBefore(revokeTime)
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNull("Token issued before revoke-all should NOT pass through", chain.request)
        assertEquals(401, response.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testAllowsTokenIssuedAfterRevokeAll() {
        val revokeTime = Instant.now().minusSeconds(7200)
        val issuedAt = Instant.now().minusSeconds(60)
        val jwt = createJwt(
            tokenValue = "after-revoke-${UUID.randomUUID()}",
            jti = null,
            issuedAt = issuedAt
        )
        blocklist.revokeAllBefore(revokeTime)
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNotNull("Token issued after revoke-all should pass through", chain.request)
        assertEquals(200, response.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testPassesThroughWhenNoAuthentication() {
        SecurityContextHolder.clearContext()

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNotNull("Unauthenticated request should pass through", chain.request)
        assertEquals(200, response.status)
    }

    @Test
    fun testRejectsAuthenticatedRequestWhenBlocklistUnavailable() {
        val localConfig = Config()
        localConfig.clusterName = "jwt-filter-unavailable-test-${UUID.randomUUID()}"
        localConfig.networkConfig.join.multicastConfig.isEnabled = false
        val localHz = Hazelcast.newHazelcastInstance(localConfig)
        val localFilter = JwtBlocklistFilter(JwtBlocklist(localHz))
        localHz.shutdown()

        val jwt = createJwt()
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        localFilter.doFilter(request, response, chain)

        assertNull("Token should NOT pass through when revocation state is unavailable", chain.request)
        assertEquals(503, response.status)
        assertTrue(
            "Response body should report revocation unavailability",
            response.contentAsString.contains("token_revocation_unavailable")
        )
        assertNull(
            "SecurityContext should be cleared after revocation-store outage",
            SecurityContextHolder.getContext().authentication
        )
    }

    @Test
    fun testRejectedResponseIsJson() {
        val jti = UUID.randomUUID().toString()
        val jwt = createJwt(jti = jti)
        blocklist.blockToken(jti, Instant.now().plusSeconds(3600))
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals("application/json", response.contentType)
        assertTrue(
            "Response body should contain error field",
            response.contentAsString.contains("token_revoked")
        )
        SecurityContextHolder.clearContext()
    }

    @Test
    fun testSecurityContextClearedOnRejection() {
        val jti = UUID.randomUUID().toString()
        val jwt = createJwt(jti = jti)
        blocklist.blockToken(jti, Instant.now().plusSeconds(3600))
        setAuthentication(jwt)

        val request = MockHttpServletRequest("GET", "/api/data")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertNull(
            "SecurityContext should be cleared after rejection",
            SecurityContextHolder.getContext().authentication
        )
    }
}
