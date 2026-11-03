package com.openlattice.chronicle.filters

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.testing.isolatedHazelcastConfig
import org.junit.Assert.assertEquals
import java.time.temporal.ChronoUnit
import java.time.Clock
import java.time.ZoneOffset
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import com.openlattice.chronicle.controllers.AuthTokenController
import com.hazelcast.core.Hazelcast
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.study.StudyApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class JwtIdleSessionTest {
    @Test
    fun activitySessionProbeUsesNormalJwtAndCsrfValidation() {
        val resolver = ChronicleCookieOrBearerTokenResolver()
        val paths = listOf(ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH,
            ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH.replace("session", "s%65ssion"),
            ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH.removePrefix(StudyApi.SERVICE),
            ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH.replace("auth", "a%75th;route=1")
                .replace("session", "s%65ssion"))
        for ((path, activity) in paths.flatMap { path -> listOf(false, true).map { path to it } }) {
            val request = MockHttpServletRequest("GET", path).apply {
                setCookies(jakarta.servlet.http.Cookie(AuthTokenController.AUTH_COOKIE_NAME, "activity-token"),
                    jakarta.servlet.http.Cookie(AuthTokenController.CSRF_COOKIE_NAME, "activity-csrf"))
                addHeader(ChronicleCookieOrBearerTokenResolver.CSRF_HEADER, "activity-csrf")
                if (activity) {
                    addHeader(ChronicleHeaders.USER_ACTIVITY_HEADER, ChronicleHeaders.USER_ACTIVITY_HEADER_VALUE)
                }
            }
            assertEquals(if (activity) "activity-token" else null, resolver.resolve(request))
            if (activity) {
                request.removeHeader(ChronicleCookieOrBearerTokenResolver.CSRF_HEADER)
                assertEquals(null, resolver.resolve(request))
            }
        }
    }

    @Test
    fun onlyRequestsWithExplicitActivityRenewIdleDeadline() {
        val hz = Hazelcast.newHazelcastInstance(isolatedHazelcastConfig("idle-background"))
        try {
            val store = JwtBlocklist(hz)
            val issued = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val expiry = issued.plus(Duration.ofHours(8))
            val window = Duration.ofMinutes(15)
            val cases = mapOf(
                null to 15L,
                ChronicleHeaders.USER_ACTIVITY_HEADER_VALUE to 29L,
                Duration.ofMinutes(13).toMillis().toString() to 16L,
                "-1" to 15L,
                "NaN" to 15L,
                window.toMillis().toString() to 15L,
            )
            for ((signal, deadlineMinute) in cases) {
                val jwt = Jwt.withTokenValue("background-token-$signal")
                    .header("alg", "HS256").subject("researcher").issuedAt(issued).expiresAt(expiry)
                    .claim("jti", "background-jti-$signal").build()
                assertEquals(200, send(store, window, jwt, 14, signal))
                assertEquals(issued.plusSeconds(deadlineMinute * 60),
                    store.sessionIdleDeadline(jwt.id, jwt.tokenValue, issued, expiry, window))
                assertEquals(if (deadlineMinute > 15) 200 else 401, send(store, window, jwt, 15, signal))
                if (deadlineMinute <= 15) {
                    // Past the idle bound, even explicit activity is refused and cannot move the deadline back out.
                    assertEquals(401, send(store, window, jwt, 15, ChronicleHeaders.USER_ACTIVITY_HEADER_VALUE))
                    assertEquals(issued.plusSeconds(deadlineMinute * 60),
                        store.sessionIdleDeadline(jwt.id, jwt.tokenValue, issued, expiry, window))
                }
            }
        } finally {
            SecurityContextHolder.clearContext()
            hz.shutdown()
        }
    }

    @Test
    fun sharedActivityExpiresIdleTokensBeforeAbsoluteExpiryAndKeepsActiveTokens() {
        val hz = Hazelcast.newHazelcastInstance(isolatedHazelcastConfig("idle"))
        try {
            val store = JwtBlocklist(hz)
            val otherNode = JwtBlocklist(hz)
            val issued = Instant.now()
            val expiry = issued.plus(Duration.ofHours(8))
            val window = Duration.ofMinutes(15)
            assertTrue(store.isSessionActive("JTI:idle-jti", issued, expiry, window, issued))
            assertTrue(store.isSessionActive("JTI:active-jti", issued, expiry, window, issued))
            assertTrue("active sessions are not revoked tokens", HazelcastMap.JWT_BLOCKLIST.getMap(hz).isEmpty)
            assertTrue(otherNode.touchActivity("JTI:active-jti", issued, expiry, window,
                JwtBlocklist.ActivityTiming(issued.plusSeconds(14 * 60))))
            assertFalse(otherNode.isSessionActive("JTI:idle-jti", issued, expiry, window, issued.plusSeconds(16 * 60)))
            assertTrue(store.isSessionActive("JTI:active-jti", issued, expiry, window, issued.plusSeconds(20 * 60)))
            assertFalse(store.isSessionActive("never-seen", issued, expiry, window, issued.plusSeconds(16 * 60)))
            for ((token, minute, expected) in listOf(Triple("idle", 16L, 401), Triple("active", 20L, 200))) {
                val jwt = Jwt.withTokenValue("captured-$token")
                    .header("alg", "HS256").subject("workstation-user").issuedAt(issued).expiresAt(expiry)
                    .claim("jti", "$token-jti").build()
                SecurityContextHolder.getContext().authentication =
                    JwtAuthenticationToken(jwt)
                val response = MockHttpServletResponse()
                JwtBlocklistFilter(otherNode, window, Clock.fixed(issued.plusSeconds(minute * 60), ZoneOffset.UTC))
                    .doFilter(MockHttpServletRequest("GET", "/chronicle/v3/study"), response,
                        FilterChain { _, res -> (res as HttpServletResponse).status = 200 })
                assertEquals("$token is still within its absolute expiry", expected, response.status)
                if (expected == 401) {
                    val error = ObjectMappers.getJsonMapper().readTree(response.contentAsByteArray)
                    assertEquals("Unauthorized", error.path("error").asText())
                    assertEquals("token_revoked", error.path("code").asText())
                    assertEquals("Session has expired due to inactivity", error.path("message").asText())
                }
            }
        } finally {
            SecurityContextHolder.clearContext()
            hz.shutdown()
        }
    }
    private fun send(store: JwtBlocklist, window: Duration, jwt: Jwt, minute: Long, signal: String?): Int {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("GET", "/chronicle/v3/study")
        if (signal != null) request.addHeader(ChronicleHeaders.USER_ACTIVITY_HEADER, signal)
        val now = requireNotNull(jwt.issuedAt).plusSeconds(minute * 60)
        JwtBlocklistFilter(store, window, Clock.fixed(now, ZoneOffset.UTC))
            .doFilter(request, response, FilterChain { _, _ -> })
        return response.status
    }

}
