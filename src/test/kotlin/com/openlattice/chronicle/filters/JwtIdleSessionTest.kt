package com.openlattice.chronicle.filters

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.openlattice.chronicle.authorization.JwtBlocklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JwtIdleSessionTest {
    @Test
    fun sharedActivityExpiresIdleTokensBeforeAbsoluteExpiryAndKeepsActiveTokens() {
        val hz = Hazelcast.newHazelcastInstance(Config().apply {
            clusterName = "idle-${UUID.randomUUID()}"
            networkConfig.join.multicastConfig.isEnabled = false
        })
        try {
            val store = JwtBlocklist(hz)
            val otherNode = JwtBlocklist(hz)
            val issued = Instant.now()
            val expiry = issued.plus(Duration.ofHours(8))
            val window = Duration.ofMinutes(15)
            assertTrue(store.isSessionActive("JTI:idle-jti", issued, expiry, window, issued))
            assertTrue(store.isSessionActive("JTI:active-jti", issued, expiry, window, issued))
            org.junit.Assert.assertEquals("active sessions are not revoked tokens", 0L, store.getBlockedCount())
            assertTrue(otherNode.isSessionActive("JTI:active-jti", issued, expiry, window, issued.plusSeconds(14 * 60)))
            assertFalse(otherNode.isSessionActive("JTI:idle-jti", issued, expiry, window, issued.plusSeconds(16 * 60)))
            assertTrue(store.isSessionActive("JTI:active-jti", issued, expiry, window, issued.plusSeconds(20 * 60)))
            assertFalse(store.isSessionActive("never-seen", issued, expiry, window, issued.plusSeconds(16 * 60)))
            for ((token, minute, expected) in listOf(Triple("idle", 16L, 401), Triple("active", 20L, 200))) {
                val jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("captured-$token")
                    .header("alg", "HS256").subject("workstation-user").issuedAt(issued).expiresAt(expiry)
                    .claim("jti", "$token-jti").build()
                org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
                    org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt)
                val response = org.springframework.mock.web.MockHttpServletResponse()
                JwtBlocklistFilter(otherNode, window, java.time.Clock.fixed(issued.plusSeconds(minute * 60), java.time.ZoneOffset.UTC))
                    .doFilter(org.springframework.mock.web.MockHttpServletRequest("GET", "/chronicle/v3/study"), response,
                        jakarta.servlet.FilterChain { _, res -> (res as jakarta.servlet.http.HttpServletResponse).status = 200 })
                org.junit.Assert.assertEquals("$token is still within its absolute expiry", expected, response.status)
                if (expected == 401) {
                    org.junit.Assert.assertEquals("""{"error":"token_revoked","message":"Session has expired due to inactivity"}""", response.contentAsString)
                }
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
            hz.shutdown()
        }
    }
}
