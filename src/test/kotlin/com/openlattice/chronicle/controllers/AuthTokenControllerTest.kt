package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.CookiePolicy
import com.fasterxml.jackson.databind.ObjectMapper
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.generated.SessionMetadataFields
import com.openlattice.chronicle.filters.JwtBlocklistFilter
import jakarta.servlet.http.Cookie
import org.springframework.mock.web.MockFilterChain
import org.springframework.security.core.context.SecurityContextHolder
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleRoleClaims
import com.openlattice.chronicle.users.UserListingService
import com.openlattice.chronicle.util.ClientIpRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.time.Duration

class AuthTokenControllerTest {

    private companion object {
        private const val INTERNAL_SECRET = "test-internal-proxy-secret-with-32-bytes"
        private val mobileSecurity = MobileSecurityConfiguration(internalWebSecret = INTERNAL_SECRET)
        private const val DASHBOARD_PASSWORD = "selfhost-dashboard-test-password"

        // A real hash produced by `caddy hash-password`, exactly the format Caddy's
        // basic_auth reads out of DASHBOARD_PASSWORD_HASH — the point being that the
        // backend verifies the SAME hash the proxy does, not a second credential.
        private const val DASHBOARD_PASSWORD_HASH =
            "\$2a\$14\$10nx6F6Ya1b6YPmLEk31COOF39BaM4CnncfE6RqoqCQmIXqC0lPMe"
    }

    private val jwtDecoder = Mockito.mock(JwtDecoder::class.java)
    private val chronicleAuthConfiguration = ChronicleAuthConfiguration()
    private val objectMapper = ObjectMapper()
    private val userListingService = Mockito.mock(UserListingService::class.java)
    private val environment = Mockito.mock(Environment::class.java).also {
        Mockito.`when`(it.activeProfiles).thenReturn(emptyArray())
    }
    private val audit = RecordingAuditingManager()
    private val auditService = AuditService(audit)

    /** The last audit row is [type] from the caller's keyed reference with exactly [data] and the failure [reason]. */
    private fun assertAuthAudited(type: AuditEventType, reason: String?, data: Map<String, String>) {
        val event = audit.events.last()
        assertEquals(type, event.eventType)
        assertEquals("Authentication", event.description)
        assertEquals(ClientIpRecord.staffSurfaceReference(MockHttpServletRequest()), event.request?.clientIpRef)
        assertEquals(data + listOfNotNull(reason?.let { "reason" to it }), event.data - "success" - "accessedPHI")
    }
    private val jwtBlocklist = Mockito.spy(JwtBlocklist(org.mockito.kotlin.mock<HazelcastInstance>().also {
        val state = org.mockito.kotlin.mock<IMap<String, Long>>()
        Mockito.`when`(it.getMap<String, Long>(org.mockito.kotlin.any()))
            .thenReturn(state)
    })).also {
        Mockito.doReturn(true).`when`(it).isSessionActive(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
    private val controller = AuthTokenController(
        jwtDecoder,
        chronicleAuthConfiguration,
        objectMapper,
        userListingService,
        environment,
        auditService,
        jwtBlocklist,
        cookiePolicy = CookiePolicy(environment),
    )

    @Test
    fun testSetAuthCookieRejectsMissingToken() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = controller.setAuthCookie(emptyMap(), request, response)

        assertEquals(400, result.statusCode.value())
        assertEquals("missing 'token' field", result.body?.get("error"))
    }

    @Test
    fun testSetAuthCookieWritesSecureCookiesAndAuthenticatedSession() {
        val jwt = createJwt("testing-token", "user-123")
        Mockito.`when`(jwtDecoder.decode("testing-token")).thenReturn(jwt)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = controller.setAuthCookie(mapOf("token" to "testing-token"), request, response)

        assertEquals(200, result.statusCode.value())
        assertEquals(true, result.body?.get("authenticated"))
        assertEquals("cookie-bootstrap", result.body?.get("authMode"))
        assertEquals("Chronicle testing session", result.body?.get("providerLabel"))
        assertEquals("authenticated", result.body?.get("status"))

        val authCookie = response.cookies.firstOrNull { it.name == AuthTokenController.AUTH_COOKIE_NAME }
        val csrfCookie = response.cookies.firstOrNull { it.name == AuthTokenController.CSRF_COOKIE_NAME }

        assertNotNull(authCookie)
        assertNotNull(csrfCookie)
        assertTrue(authCookie!!.isHttpOnly)
        assertTrue(authCookie.secure)
        assertEquals("/chronicle", authCookie.path)
        assertEquals("Strict", authCookie.getAttribute("SameSite"))
        assertFalse(csrfCookie!!.isHttpOnly)
        assertTrue(csrfCookie.secure)
        assertEquals("/chronicle", csrfCookie.path)
        assertEquals("Strict", csrfCookie.getAttribute("SameSite"))
        assertEquals(csrfCookie.value, result.body?.get("csrfToken"))
    }

    @Test
    fun testGetSessionReturnsAwaitingSsoWhenNoAuthenticationExists() {
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn("testing-token")

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = controller.getSession(request, response)

        assertEquals(200, result.statusCode.value())
        assertEquals(false, result.body?.get("authenticated"))
        assertEquals("Chronicle testing session", result.body?.get("providerLabel"))
        assertEquals("awaiting-sso", result.body?.get("status"))
        assertEquals(true, result.body?.get("testingLoginEnabled"))
        assertEquals("sso-session", result.body?.get("tokenSource"))
    }

    @Test
    fun testGetSessionReturnsAuthenticatedMetadataWhenJwtPrincipalExists() {
        val jwt = createJwt("existing-token", "user-456")
        val idleDeadline = Instant.now().plusSeconds(300)
        Mockito.doReturn(idleDeadline).`when`(jwtBlocklist).sessionIdleDeadline(jwt.id, jwt.tokenValue,
            jwt.issuedAt, jwt.expiresAt, java.time.Duration.ofMinutes(chronicleAuthConfiguration.sessionIdleMinutes))
        val request = MockHttpServletRequest()
        val authToken = JwtAuthenticationToken(jwt)
        authToken.isAuthenticated = true
        request.setUserPrincipal(authToken)
        request.setCookies(jakarta.servlet.http.Cookie(AuthTokenController.CSRF_COOKIE_NAME, "existing-csrf"))
        val response = MockHttpServletResponse()

        val result = controller.getSession(request, response)

        assertEquals(200, result.statusCode.value())
        assertEquals(true, result.body?.get("authenticated"))
        assertEquals("existing-csrf", result.body?.get("csrfToken"))
        assertEquals("user-456", result.body?.get("subject"))
        assertEquals(idleDeadline.toEpochMilli(), result.body?.get("idleExpiresAt"))
        assertEquals(Duration.ofMinutes(chronicleAuthConfiguration.sessionIdleMinutes).toMillis(),
            result.body?.get(SessionMetadataFields.IDLE_WINDOW_MS))
        assertEquals("sso-session", result.body?.get("tokenSource"))
        assertEquals(0, response.cookies.size)
    }

    @Test
    fun testTestingLoginRejectsDisabledBridge() {
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn(null)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = controller.testingLogin(null, request, response)

        assertEquals(403, result.statusCode.value())
        assertEquals(false, result.body?.get("authenticated"))
        assertEquals("Chronicle testing session", result.body?.get("providerLabel"))
        assertEquals("awaiting-sso", result.body?.get("status"))
    }

    @Test
    fun testingLoginRefusesMissingForgedAndUnconfiguredInternalMarkersBeforeIssuingTokens() {
        for (secret in listOf(INTERNAL_SECRET, "")) {
            val enabled = AuthTokenController(jwtDecoder, chronicleAuthConfiguration.copy(testingLoginEnabled = true),
                objectMapper, userListingService, environment, auditService, jwtBlocklist,
                MobileSecurityConfiguration(internalWebSecret = secret),
                cookiePolicy = CookiePolicy(environment),
            )
            for (marker in listOf(null, "true", "forged", INTERNAL_SECRET.takeIf { secret.isEmpty() })) {
                val request = MockHttpServletRequest()
                marker?.let { request.addHeader(MobileSecurityConfiguration.INTERNAL_WEB_HEADER, it) }
                val response = MockHttpServletResponse()
                assertEquals(403, enabled.testingLogin(null, request, response).statusCode.value())
                assertTrue(response.cookies.isEmpty())
                Mockito.verifyNoInteractions(userListingService, jwtDecoder)
            }
        }
    }

    @Test
    fun testTestingLoginReturnsCookiesAndBootstrapMetadata() {
        val enabledController = AuthTokenController(
            jwtDecoder,
            chronicleAuthConfiguration.copy(testingLoginEnabled = true),
            objectMapper,
            userListingService,
                environment,
            auditService,
            jwtBlocklist,
            mobileSecurity,
            cookiePolicy = CookiePolicy(environment),
        )
        val jwt = createJwt("testing-token", "user-789")
        Mockito.`when`(userListingService.issueTestingToken("test-user")).thenReturn("testing-token")
        Mockito.`when`(jwtDecoder.decode("testing-token")).thenReturn(jwt)

        val request = MockHttpServletRequest().also { it.addHeader(MobileSecurityConfiguration.INTERNAL_WEB_HEADER, INTERNAL_SECRET) }
        val response = MockHttpServletResponse()

        val result = enabledController.testingLogin(mapOf("userId" to "test-user"), request, response)

        assertEquals(200, result.statusCode.value())
        assertEquals(true, result.body?.get("authenticated"))
        assertEquals("testing-token", result.body?.get("authToken"))
        assertEquals("Chronicle testing session", result.body?.get("providerLabel"))
        assertEquals("testing-login", result.body?.get("tokenSource"))
        assertEquals(2, response.cookies.size)
    }

    @Test
    fun testingLoginAuditsSuccessAndEveryRejectionWithoutCredentialPayload() {
        val request = MockHttpServletRequest()
        controller.testingLogin(null, request, MockHttpServletResponse())
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "disabled", mapOf("method" to "testing"))
        audit.observations.clear()
        val enabled = AuthTokenController(jwtDecoder, chronicleAuthConfiguration.copy(testingLoginEnabled = true),
            objectMapper, userListingService, environment, auditService, jwtBlocklist, mobileSecurity,
            cookiePolicy = CookiePolicy(environment),
        )
        request.addHeader(MobileSecurityConfiguration.INTERNAL_WEB_HEADER, INTERNAL_SECRET)
        val token = "credentialPayloadSentinel"
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn(token)
        Mockito.`when`(jwtDecoder.decode(token)).thenReturn(createJwt(token, "issued-user"))
        assertEquals(200, enabled.testingLogin(null, request, MockHttpServletResponse()).statusCode.value())
        assertAuthAudited(AuditEventType.LOGIN, null, mapOf("method" to "testing", "subject" to "issued-user"))
        audit.observations.clear()
        Mockito.`when`(environment.activeProfiles).thenReturn(arrayOf("production"))
        assertEquals(404, enabled.testingLogin(null, request, MockHttpServletResponse()).statusCode.value())
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "production_disabled", mapOf("method" to "testing"))
        audit.observations.clear()
        Mockito.`when`(environment.activeProfiles).thenReturn(emptyArray())
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn(null)
        assertEquals(403, enabled.testingLogin(null, request, MockHttpServletResponse()).statusCode.value())
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "no_local_user", mapOf("method" to "testing"))
        audit.observations.clear()
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn(token)
        Mockito.`when`(jwtDecoder.decode(token)).thenThrow(org.springframework.security.oauth2.jwt.BadJwtException("credentialPayloadSentinel"))
        assertEquals(500, enabled.testingLogin(null, request, MockHttpServletResponse()).statusCode.value())
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "invalid_token", mapOf("method" to "testing"))
    }

    @Test
    fun testDashboardLoginAcceptsCorrectPasswordAndWritesSecureCookies() {
        val jwt = createJwt("dashboard-token", "local-admin")
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn("dashboard-token")
        Mockito.`when`(jwtDecoder.decode("dashboard-token")).thenReturn(jwt)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = dashboardController(DASHBOARD_PASSWORD_HASH)
            .dashboardLogin(mapOf("password" to DASHBOARD_PASSWORD), request, response)

        assertEquals(200, result.statusCode.value())
        assertEquals(true, result.body?.get("authenticated"))
        assertEquals("dashboard-login", result.body?.get("tokenSource"))
        assertEquals("local-admin", result.body?.get("subject"))
        assertNotNull(result.body?.get("csrfToken"))
        // The raw JWT stays in the httpOnly cookie; unlike testing-login it is never echoed.
        assertNull(result.body?.get("authToken"))

        val authCookie = response.cookies.firstOrNull { it.name == AuthTokenController.AUTH_COOKIE_NAME }
        val csrfCookie = response.cookies.firstOrNull { it.name == AuthTokenController.CSRF_COOKIE_NAME }

        assertNotNull(authCookie)
        assertNotNull(csrfCookie)
        assertTrue(authCookie!!.isHttpOnly)
        assertTrue(authCookie.secure)
        assertEquals("/chronicle", authCookie.path)
        assertEquals("Strict", authCookie.getAttribute("SameSite"))
        assertFalse(csrfCookie!!.isHttpOnly)
        assertTrue(csrfCookie.secure)
        assertEquals("/chronicle", csrfCookie.path)
        assertEquals("Strict", csrfCookie.getAttribute("SameSite"))
        assertEquals(csrfCookie.value, result.body?.get("csrfToken"))
        assertAuthAudited(AuditEventType.LOGIN, null, mapOf("method" to "dashboard", "subject" to "local-admin"))
    }

    @Test
    fun testDashboardLoginRejectsWrongPassword() {
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn("dashboard-token")

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = dashboardController(DASHBOARD_PASSWORD_HASH)
            .dashboardLogin(mapOf("password" to "not-the-dashboard-password"), request, response)

        assertEquals(401, result.statusCode.value())
        assertEquals(false, result.body?.get("authenticated"))
        assertEquals("invalid dashboard password", result.body?.get("error"))
        assertEquals(0, response.cookies.size)
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "password_mismatch", mapOf("method" to "dashboard"))
    }

    @Test
    fun testDashboardLoginFailsClosedWhenNoHashIsConfigured() {
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn("dashboard-token")

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        // Unset and blank both reject, and with the SAME body a wrong password gets — the
        // endpoint must never reveal that it has no password configured.
        listOf(null, "", "   ").forEach { hash ->
            val result = dashboardController(hash).dashboardLogin(
                mapOf("password" to DASHBOARD_PASSWORD),
                request,
                response,
            )

            assertEquals(401, result.statusCode.value())
            assertEquals(false, result.body?.get("authenticated"))
            assertEquals("invalid dashboard password", result.body?.get("error"))
        }
        assertEquals(0, response.cookies.size)
    }

    @Test
    fun testDashboardLoginLogsStableIpReferenceNeverRawClientIp() {
        val rawClientIp = "203.0.113.77"
        val capture = ScopedLogCapture(AuthTokenController::class.java, pattern = "%m")
        val captured = capture.messages

        try {
            val request = MockHttpServletRequest().apply { remoteAddr = rawClientIp }
            val result = dashboardController(null).dashboardLogin(
                mapOf("password" to DASHBOARD_PASSWORD),
                request,
                MockHttpServletResponse(),
            )

            assertEquals(401, result.statusCode.value())
        } finally {
            capture.close()
        }

        val log = captured.joinToString("\n")
        assertTrue("expected a stable client reference in log, got: $log", log.contains("ip:"))
        assertFalse("raw client IP leaked into logs: $log", log.contains(rawClientIp))
    }

    @Test
    fun testDashboardLoginFailsClosedWhenConfiguredHashIsNotBcrypt() {
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn("dashboard-token")

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        // An operator who pasted the cleartext password into DASHBOARD_PASSWORD_HASH must
        // not end up with a working login against that cleartext.
        val result = dashboardController(DASHBOARD_PASSWORD)
            .dashboardLogin(mapOf("password" to DASHBOARD_PASSWORD), request, response)

        assertEquals(401, result.statusCode.value())
        assertEquals("invalid dashboard password", result.body?.get("error"))
        assertEquals(0, response.cookies.size)
    }

    @Test
    fun testDashboardLoginRejectsCorrectPasswordWhenNoSessionCanBeMinted() {
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn(null)

        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        // Same 401 and same body as a wrong password: a misconfigured user list must not
        // become an oracle that confirms the guess was right.
        val result = dashboardController(DASHBOARD_PASSWORD_HASH)
            .dashboardLogin(mapOf("password" to DASHBOARD_PASSWORD), request, response)

        assertEquals(401, result.statusCode.value())
        assertEquals(false, result.body?.get("authenticated"))
        assertEquals("invalid dashboard password", result.body?.get("error"))
        assertEquals(0, response.cookies.size)
    }

    @Test
    fun testDashboardLoginRejectsMissingBodyWithoutServerError() {
        val controllerWithHash = dashboardController(DASHBOARD_PASSWORD_HASH)

        listOf(null, emptyMap(), mapOf("password" to "")).forEach { body ->
            val request = MockHttpServletRequest()
            val response = MockHttpServletResponse()

            val result = controllerWithHash.dashboardLogin(body, request, response)

            assertEquals(400, result.statusCode.value())
            assertEquals(false, result.body?.get("authenticated"))
            assertEquals("missing 'password' field", result.body?.get("error"))
            assertEquals(0, response.cookies.size)
        }
    }

    @Test
    fun logoutRejectsCapturedCookieAndBearerReplay() {
        val revoked = mutableSetOf<String>()
        Mockito.doAnswer { revoked.add(it.getArgument(0)); true }
            .`when`(jwtBlocklist).blockToken(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        Mockito.doAnswer { revoked.add(it.getArgument(0)); true }
            .`when`(jwtBlocklist).blockTokenByValue(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        Mockito.doAnswer { revoked.contains(it.getArgument<String>(0)) }
            .`when`(jwtBlocklist).isBlocked(org.mockito.kotlin.any())
        Mockito.doAnswer { revoked.contains(it.getArgument<String>(0)) }
            .`when`(jwtBlocklist).isBlockedByValue(org.mockito.kotlin.any())
        for (cookie in listOf(true, false)) {
            val token = "captured-$cookie"
            val builder = Jwt.withTokenValue(token).header("alg", "HS256").subject("researcher")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            if (cookie) builder.claim("jti", "session-$cookie")
            val jwt = builder.build()
            Mockito.`when`(jwtDecoder.decode(token)).thenReturn(jwt)
            controller.setAuthCookie(mapOf("token" to token), MockHttpServletRequest(), MockHttpServletResponse())
            val logout = MockHttpServletRequest().apply {
                if (cookie) setCookies(Cookie(AuthTokenController.AUTH_COOKIE_NAME, token))
                else addHeader("Authorization", "Bearer $token")
            }
            assertEquals(204, controller.logout(logout, MockHttpServletResponse()).statusCode.value())
            for (replayAsCookie in listOf(true, false)) {
                SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
                try {
                    val replay = MockHttpServletRequest("GET", "/chronicle/v3/studies").apply {
                        if (replayAsCookie) setCookies(Cookie(AuthTokenController.AUTH_COOKIE_NAME, token))
                        else addHeader("Authorization", "Bearer $token")
                    }
                    val response = MockHttpServletResponse()
                    val chain = MockFilterChain()
                    JwtBlocklistFilter(jwtBlocklist).doFilter(replay, response, chain)
                    assertEquals("logged out token must be rejected", 401, response.status)
                    assertNull(chain.request)
                } finally {
                    SecurityContextHolder.clearContext()
                }
            }
        }
    }

    @Test
    fun sessionCookiesExpireWithTheSessionToken() {
        val jwt = Jwt.withTokenValue("short-token").header("alg", "HS256").subject("researcher")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()
        Mockito.`when`(jwtDecoder.decode("short-token")).thenReturn(jwt)
        val response = MockHttpServletResponse()

        controller.setAuthCookie(mapOf("token" to "short-token"), MockHttpServletRequest(), response)

        for (name in listOf(AuthTokenController.AUTH_COOKIE_NAME, AuthTokenController.CSRF_COOKIE_NAME)) {
            val maxAge = response.cookies.first { it.name == name }.maxAge
            assertTrue("$name Max-Age $maxAge must end with the token", maxAge in 1..600)
        }
    }

    @Test
    fun logoutAuditsTheSubjectItEnds() {
        val jwt = Jwt.withTokenValue("ending-token").header("alg", "HS256").subject("researcher")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()
        Mockito.`when`(jwtDecoder.decode("ending-token")).thenReturn(jwt)
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer ending-token") }

        controller.logout(request, MockHttpServletResponse())

        assertAuthAudited(AuditEventType.LOGOUT, null, mapOf("subject" to "researcher"))
    }

    @Test
    fun testLogoutClearsBothCookies() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        val result = controller.logout(request, response)

        assertEquals(204, result.statusCode.value())
        assertEquals(2, response.cookies.size)

        val authCookie = response.cookies.first { it.name == AuthTokenController.AUTH_COOKIE_NAME }
        val csrfCookie = response.cookies.first { it.name == AuthTokenController.CSRF_COOKIE_NAME }

        assertEquals(0, authCookie.maxAge)
        assertTrue(authCookie.isHttpOnly)
        assertTrue(authCookie.secure)
        assertEquals(0, csrfCookie.maxAge)
        assertFalse(csrfCookie.isHttpOnly)
        assertTrue(csrfCookie.secure)
    }

    private fun dashboardController(passwordHash: String?) = AuthTokenController(
        jwtDecoder,
        chronicleAuthConfiguration.copy(dashboardPasswordHash = passwordHash),
        objectMapper,
        userListingService,
        environment,
        auditService,
        jwtBlocklist,
        cookiePolicy = CookiePolicy(environment),
    )

    private fun createJwt(tokenValue: String, subject: String): Jwt {
        return Jwt.withTokenValue(tokenValue)
            .header("alg", "HS256")
            .subject(subject)
            .claim("email", "$subject@example.org")
            .claim(ChronicleRoleClaims.DEFAULT_ROLE_CLAIM_NAMESPACE, mapOf("roles" to listOf("admin")))
            .claim("name", "Test User")
            .expiresAt(Instant.parse("2026-12-31T23:59:59Z"))
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build()
    }

    @Test fun cookieOnlySessionCreatesCsrfWithoutSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext()
        val jwt = createJwt("cookie-only", "cookie-subject")
        Mockito.`when`(jwtDecoder.decode("cookie-only")).thenReturn(jwt)
        val request = MockHttpServletRequest().apply {
            setCookies(jakarta.servlet.http.Cookie(AuthTokenController.AUTH_COOKIE_NAME, "cookie-only"))
        }
        val response = MockHttpServletResponse()
        val result = controller.getSession(request, response)
        assertEquals(true, result.body?.get("authenticated"))
        assertNotNull(response.cookies.firstOrNull { it.name == AuthTokenController.CSRF_COOKIE_NAME })
    }


    @Test
    fun setCookieRefusesAForgedOrExpiredTokenWithoutWritingCookies() {
        Mockito.`when`(jwtDecoder.decode("forged")).thenThrow(BadJwtException("signature mismatch"))
        val response = MockHttpServletResponse()

        val result = controller.setAuthCookie(mapOf("token" to "forged"), MockHttpServletRequest(), response)

        assertEquals(401, result.statusCode.value())
        assertEquals("invalid token", result.body?.get("error"))
        assertTrue(response.cookies.isEmpty())
    }

    /** Operator override first, then the proxy's scheme: only an explicit "http" from the proxy drops Secure. */
    @Test
    fun cookieSecureFlagFollowsTheOperatorOverrideBeforeTheProxyScheme() {
        val jwt = createJwt("scheme-token", "researcher")
        Mockito.`when`(jwtDecoder.decode("scheme-token")).thenReturn(jwt)
        val cases = listOf(
            Triple("false", "https", false),
            Triple("true", "http", true),
            Triple(null, "http", false),
            Triple(null, "HTTPS", true),
            Triple(null, null, true),
            Triple("not-a-boolean", "http", false),
        )
        for ((override, proto, secure) in cases) {
            val env = Mockito.mock(Environment::class.java)
            Mockito.`when`(env.activeProfiles).thenReturn(emptyArray())
            Mockito.`when`(env.getProperty("chronicle.security.cookie.secure")).thenReturn(override)
            val scoped = AuthTokenController(jwtDecoder, chronicleAuthConfiguration, objectMapper, userListingService,
                env, auditService, jwtBlocklist,
                cookiePolicy = CookiePolicy(env),
            )
            val request = MockHttpServletRequest().apply { proto?.let { addHeader("X-Forwarded-Proto", it) } }
            val response = MockHttpServletResponse()
            scoped.setAuthCookie(mapOf("token" to "scheme-token"), request, response)
            assertEquals(2, response.cookies.size)
            response.cookies.forEach { assertEquals("override=$override proto=$proto ${it.name}", secure, it.secure) }
        }
    }

    /**
     * The cookie a login writes is replayed on every later request through the same [JwtDecoder]. A token that
     * decoder refuses must therefore not become a cookie: before the fix a second, laxer decoder (no issuer,
     * audience or MFA check) accepted it, the login answered 200, and the very next request was 401.
     */
    @Test
    fun loginsNeverIssueASessionCookieTheRequestDecoderRefuses() {
        val secret = "testing-issuer-secret-with-at-least-32-bytes!"
        val minted = com.auth0.jwt.JWT.create().withSubject("local-admin").withIssuer("https://localhost/")
            .withAudience("chronicle").withExpiresAt(Instant.now().plusSeconds(600))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(secret))
        Mockito.`when`(jwtDecoder.decode(minted)).thenThrow(BadJwtException("issuer not accepted by the request decoder"))
        Mockito.`when`(userListingService.issueDashboardToken(null)).thenReturn(minted)
        Mockito.`when`(userListingService.issueTestingToken(null)).thenReturn(minted)
        val config = chronicleAuthConfiguration.copy(
            dashboardPasswordHash = DASHBOARD_PASSWORD_HASH,
            testingLoginEnabled = true,
            configurations = setOf(com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration(
                audience = "chronicle", issuer = "https://localhost/", secret = secret)),
        )
        val scoped = AuthTokenController(jwtDecoder, config, objectMapper, userListingService, environment,
            auditService, jwtBlocklist, mobileSecurity,
            cookiePolicy = CookiePolicy(environment),
        )

        val dashboardResponse = MockHttpServletResponse()
        val dashboard = scoped.dashboardLogin(mapOf("password" to DASHBOARD_PASSWORD), MockHttpServletRequest(),
            dashboardResponse)
        assertEquals(401, dashboard.statusCode.value())
        assertEquals("invalid dashboard password", dashboard.body?.get("error"))
        assertTrue(dashboardResponse.cookies.isEmpty())
        assertAuthAudited(AuditEventType.LOGIN_FAILED, "invalid_token", mapOf("method" to "dashboard"))

        val testingResponse = MockHttpServletResponse()
        val testing = scoped.testingLogin(null, MockHttpServletRequest().apply {
            addHeader(MobileSecurityConfiguration.INTERNAL_WEB_HEADER, INTERNAL_SECRET)
        }, testingResponse)
        assertEquals(500, testing.statusCode.value())
        assertTrue(testingResponse.cookies.isEmpty())
    }

    @Test
    fun logoutOfAnExpiredOrForgedCookieStillClearsItWithoutBlocklisting() {
        Mockito.`when`(jwtDecoder.decode("expired")).thenThrow(BadJwtException("expired"))
        val request = MockHttpServletRequest().apply { setCookies(Cookie(AuthTokenController.AUTH_COOKIE_NAME, "expired")) }
        val response = MockHttpServletResponse()

        assertEquals(204, controller.logout(request, response).statusCode.value())

        assertEquals(setOf(0), response.cookies.map { it.maxAge }.toSet())
        assertEquals(setOf(AuthTokenController.AUTH_COOKIE_NAME, AuthTokenController.CSRF_COOKIE_NAME),
            response.cookies.map { it.name }.toSet())
        Mockito.verify(jwtBlocklist, Mockito.never()).blockToken(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        Mockito.verify(jwtBlocklist, Mockito.never()).blockTokenByValue(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertTrue(audit.events.none { it.eventType == AuditEventType.LOGOUT })
    }

    /** A signed-in tab whose CSRF cookie was cleared gets a fresh pair instead of a session it cannot write with. */
    @Test
    fun authenticatedSessionWithoutCsrfCookieMintsAFreshPair() {
        val jwt = createJwt("bearer-only", "researcher")
        val request = MockHttpServletRequest().apply {
            setUserPrincipal(JwtAuthenticationToken(jwt).also { it.isAuthenticated = true })
        }
        val response = MockHttpServletResponse()

        val result = controller.getSession(request, response)

        assertEquals(true, result.body?.get("authenticated"))
        val csrf = response.cookies.single { it.name == AuthTokenController.CSRF_COOKIE_NAME }
        assertEquals(csrf.value, result.body?.get("csrfToken"))
        assertEquals("bearer-only", response.cookies.single { it.name == AuthTokenController.AUTH_COOKIE_NAME }.value)
    }

    /** A token without an expiry would outlive every idle and revocation window, so it never becomes a session. */
    @Test
    fun tokenWithoutExpiryIsRefusedAndWritesNoCookie() {
        val jwt = Jwt.withTokenValue("no-exp").header("alg", "HS256").subject("researcher").issuedAt(Instant.now()).build()
        Mockito.`when`(jwtDecoder.decode("no-exp")).thenReturn(jwt)
        val response = MockHttpServletResponse()

        val result = controller.setAuthCookie(mapOf("token" to "no-exp"), MockHttpServletRequest(), response)

        assertEquals(401, result.statusCode.value())
        assertEquals("session expired", result.body?.get("error"))
        assertTrue(response.cookies.isEmpty())
    }

    @Test fun cookieSessionChecksTheSubjectsPrivilegeCutoff() {
        val jwt = createJwt("revoked-cookie", "changed-subject")
        Mockito.`when`(jwtDecoder.decode("revoked-cookie")).thenReturn(jwt)
        Mockito.doReturn("privileges changed").`when`(jwtBlocklist).credentialRejectionReason(org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any(
            ),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any(), org.mockito.kotlin.eq("changed-subject"))
        val request = MockHttpServletRequest().apply { setCookies(jakarta.servlet.http.Cookie(AuthTokenController.AUTH_COOKIE_NAME,
            "revoked-cookie")) }
        val result = controller.getSession(request, MockHttpServletResponse())
        assertEquals(false, result.body?.get("authenticated"))
    }

}
