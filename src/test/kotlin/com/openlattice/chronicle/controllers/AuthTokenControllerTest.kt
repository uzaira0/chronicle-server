package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleRoleClaims
import com.openlattice.chronicle.services.auth.RefreshTokenService
import com.openlattice.chronicle.users.UserListingService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class AuthTokenControllerTest {

    private companion object {
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
    private val refreshTokenService = Mockito.mock(RefreshTokenService::class.java)
    private val environment = Mockito.mock(Environment::class.java).also {
        Mockito.`when`(it.activeProfiles).thenReturn(emptyArray())
    }
    private val controller = AuthTokenController(
        jwtDecoder,
        chronicleAuthConfiguration,
        objectMapper,
        userListingService,
        refreshTokenService,
        environment,
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
    fun testTestingLoginReturnsCookiesAndBootstrapMetadata() {
        val enabledController = AuthTokenController(
            jwtDecoder,
            chronicleAuthConfiguration.copy(testingLoginEnabled = true),
            objectMapper,
            userListingService,
            refreshTokenService,
            environment,
        )
        val jwt = createJwt("testing-token", "user-789")
        Mockito.`when`(userListingService.issueTestingToken("test-user")).thenReturn("testing-token")
        Mockito.`when`(jwtDecoder.decode("testing-token")).thenReturn(jwt)

        val request = MockHttpServletRequest()
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
        val captured = CopyOnWriteArrayList<String>()
        val appender: Appender = object : AbstractAppender(
            "dashboard-login-ip-redaction-capture",
            null,
            null,
            true,
            Property.EMPTY_ARRAY,
        ) {
            override fun append(event: LogEvent) {
                captured.add(event.message.formattedMessage)
            }
        }.also { it.start() }
        val coreLogger = LogManager.getLogger(AuthTokenController::class.java) as Logger
        coreLogger.addAppender(appender)

        try {
            val request = MockHttpServletRequest().apply { remoteAddr = rawClientIp }
            val result = dashboardController(null).dashboardLogin(
                mapOf("password" to DASHBOARD_PASSWORD),
                request,
                MockHttpServletResponse(),
            )

            assertEquals(401, result.statusCode.value())
        } finally {
            coreLogger.removeAppender(appender)
            appender.stop()
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
        refreshTokenService,
        environment,
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
}
