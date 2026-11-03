package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.CookiePolicy
import com.fasterxml.jackson.databind.ObjectMapper
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleOidcConfiguration
import com.openlattice.chronicle.users.UserListingService
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.core.env.Environment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.web.server.ResponseStatusException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.util.UriComponentsBuilder
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The institutional-SSO login (authorization code + PKCE + nonce) against a local token endpoint. Each
 * callback guard is a distinct way a real IdP round trip goes wrong: the IdP reports an error, the browser
 * lost the state cookie, an attacker replays someone else's code, the token endpoint is down or answers
 * garbage, or the returned token does not match this login's nonce.
 */
class AuthTokenOidcFlowTest {
    private companion object {
        const val STATE = "chronicle_oidc_state"
        const val NONCE = "chronicle_oidc_nonce"
        const val PKCE = "chronicle_oidc_pkce"
        const val ISSUER = "https://idp.example.cl/realms/chronicle"
    }

    private val tokenRequests = CopyOnWriteArrayList<String>()
    private var tokenStatus = 200
    private var tokenBody = ""
    private val tokenEndpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { exchange ->
            tokenRequests += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = tokenBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(tokenStatus, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @After
    fun stopTokenEndpoint() = tokenEndpoint.stop(0)

    private val oidc = ChronicleOidcConfiguration(
        enabled = true,
        providerLabel = "University sign-in",
        publicBaseUrl = "https://chronicle.example.cl/",
        issuer = ISSUER,
        authorizationUri = "https://idp.example.cl/realms/chronicle/protocol/openid-connect/auth",
        tokenUri = "http://127.0.0.1:${tokenEndpoint.address.port}/token",
        clientId = "chronicle-dashboard",
        clientSecret = "client-secret",
        identityProviderHint = "bcm",
    )
    private val jwtDecoder = Mockito.mock(JwtDecoder::class.java)
    private val audit = RecordingAuditingManager()
    private val environment = Mockito.mock(Environment::class.java).also {
        Mockito.`when`(it.activeProfiles).thenReturn(emptyArray())
    }
    private val jwtBlocklist = JwtBlocklist(Mockito.mock(HazelcastInstance::class.java).also {
        Mockito.`when`(it.getMap<String, Long>(org.mockito.kotlin.any())).thenReturn(org.mockito.kotlin.mock<IMap<String, Long>>())
    })

    private fun controller(config: ChronicleOidcConfiguration = oidc) = AuthTokenController(
        jwtDecoder, ChronicleAuthConfiguration(oidc = config), ObjectMapper(), Mockito.mock(UserListingService::class.java),
        environment, AuditService(audit), jwtBlocklist,
        cookiePolicy = CookiePolicy(environment),
    )

    private fun jwt(token: String, nonce: String? = null, issuer: String = ISSUER): Jwt = Jwt.withTokenValue(token)
        .header("alg", "RS256").subject("researcher-cl").issuer(issuer)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
        .also { builder -> nonce?.let { builder.claim("nonce", it) } }.build()

    /** Runs /oidc/login and returns the browser's cookies plus the IdP redirect's query parameters. */
    private fun login(config: ChronicleOidcConfiguration = oidc): Pair<Map<String, Cookie>, Map<String, String>> {
        val response = MockHttpServletResponse()
        val result = controller(config).oidcLogin(MockHttpServletRequest(), response)
        assertEquals(302, result.statusCode.value())
        val query = UriComponentsBuilder.fromUri(checkNotNull(result.headers.location)).build().queryParams
            .toSingleValueMap().mapValues { URLDecoder.decode(it.value, StandardCharsets.UTF_8) }
        return response.cookies.associateBy { it.name } to query
    }

    private fun callback(
        code: String? = "auth-code",
        state: String? = "state-1",
        cookies: Map<String, String> = mapOf(STATE to "state-1", NONCE to "nonce-1", PKCE to "verifier-1"),
        error: String? = null,
        response: MockHttpServletResponse = MockHttpServletResponse(),
        config: ChronicleOidcConfiguration = oidc,
    ) = controller(config).oidcCallback(code, state, error, "user cancelled", MockHttpServletRequest().apply {
        if (cookies.isNotEmpty()) setCookies(*cookies.map { Cookie(it.key, it.value) }.toTypedArray())
    }, response)

    @Test
    fun ssoStaysHiddenUntilEveryEndpointAndCredentialIsConfigured() {
        val incomplete = listOf(
            oidc.copy(enabled = false), oidc.copy(publicBaseUrl = ""), oidc.copy(authorizationUri = ""),
            oidc.copy(tokenUri = ""), oidc.copy(clientId = ""), oidc.copy(clientSecret = ""),
        )
        for (config in incomplete) {
            val response = MockHttpServletResponse()
            // The standard error envelope answers, as the contract's NotFound response documents.
            val login = assertThrows(ResponseStatusException::class.java) {
                controller(config).oidcLogin(MockHttpServletRequest(), response)
            }
            assertEquals(404, login.statusCode.value())
            assertTrue(response.cookies.isEmpty())
            val callback = assertThrows(ResponseStatusException::class.java) {
                controller(config).oidcCallback("code", "state", null, null, MockHttpServletRequest(), MockHttpServletResponse())
            }
            assertEquals(404, callback.statusCode.value())
            val session = controller(config).getSession(MockHttpServletRequest(), MockHttpServletResponse()).body!!
            assertFalse("$config must not advertise a login URL", session.containsKey("loginUrl"))
        }
        val session = controller().getSession(MockHttpServletRequest(), MockHttpServletResponse()).body!!
        assertEquals("/chronicle/v3/auth/oidc/login", session["loginUrl"])
        assertEquals("University sign-in", session["providerLabel"])
        assertEquals("Institutional SSO", controller(oidc.copy(providerLabel = ""))
            .getSession(MockHttpServletRequest(), MockHttpServletResponse()).body!!["providerLabel"])
    }

    @Test
    fun loginBindsStateNonceAndAnS256ChallengeOfTheVerifierCookie() {
        val (cookies, query) = login()

        assertEquals(setOf(STATE, NONCE, PKCE), cookies.keys)
        cookies.values.forEach {
            assertTrue(it.isHttpOnly)
            assertEquals("Lax", it.getAttribute("SameSite"))
            assertEquals(600, it.maxAge)
        }
        assertEquals("code", query["response_type"])
        assertEquals("chronicle-dashboard", query["client_id"])
        assertEquals("https://chronicle.example.cl/chronicle/v3/auth/oidc/callback", query["redirect_uri"])
        assertEquals("openid email profile", query["scope"])
        assertEquals(cookies.getValue(STATE).value, query["state"])
        assertEquals(cookies.getValue(NONCE).value, query["nonce"])
        val verifier = cookies.getValue(PKCE).value
        assertTrue("RFC 7636 verifier is 43+ url-safe chars", verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        assertEquals(challenge, query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("bcm", query["kc_idp_hint"])

        val (second, unhinted) = login(oidc.copy(identityProviderHint = ""))
        assertFalse(unhinted.containsKey("kc_idp_hint"))
        assertTrue("every login gets its own state and verifier",
            second.getValue(STATE).value != cookies.getValue(STATE).value &&
                second.getValue(PKCE).value != verifier)
    }

    @Test
    fun callbackRefusesIdpErrorsMissingParametersAndForeignStateBeforeExchangingTheCode() {
        val idpError = callback(error = "access_denied")
        assertEquals(401, idpError.statusCode.value())
        assertEquals(mapOf("error" to "oidc_login_failed"), idpError.body)
        assertEquals(AuditEventType.LOGIN_FAILED, audit.events.last().eventType)
        assertEquals("idp_error", audit.events.last().data["reason"])

        val refusals = listOf(
            Triple(callback(code = null), 400, "missing_oidc_code_or_state"),
            Triple(callback(state = ""), 400, "missing_oidc_code_or_state"),
            Triple(callback(cookies = emptyMap()), 400, "invalid_oidc_state"),
            Triple(callback(state = "state-2"), 400, "invalid_oidc_state"),
            Triple(callback(state = "state-10"), 400, "invalid_oidc_state"),
            Triple(callback(cookies = mapOf(STATE to "state-1", NONCE to "nonce-1")), 400, "missing_oidc_pkce_verifier"),
        )
        refusals.forEachIndexed { index, (result, status, error) ->
            assertEquals("case $index", status, result.statusCode.value())
            assertEquals("case $index", mapOf("error" to error), result.body)
        }
        assertTrue("no code may reach the token endpoint without a matching state", tokenRequests.isEmpty())
    }

    @Test
    fun tokenEndpointFailuresAnswer502AndNeverSetASessionCookie() {
        val failures = listOf(500 to """{"error":"server_error"}""", 200 to "", 200 to "not json", 200 to """{"token_type":"Bearer"}""")
        for ((status, body) in failures) {
            tokenStatus = status
            tokenBody = body
            val response = MockHttpServletResponse()
            val result = callback(response = response)
            assertEquals("$status $body", 502, result.statusCode.value())
            val expected = if (body.contains("token_type")) "missing_oidc_token" else "oidc_token_exchange_failed"
            assertEquals(mapOf("error" to expected), result.body)
            assertTrue(response.cookies.none { it.name == AuthTokenController.AUTH_COOKIE_NAME })
        }
        val unreachable = oidc.copy(tokenUri = "http://127.0.0.1:1/token")
        assertEquals(mapOf("error" to "oidc_token_exchange_failed"), callback(config = unreachable).body)
    }

    @Test
    fun exchangeSendsTheCodeWithThisLoginsVerifierAndTheClientCredentials() {
        tokenBody = """{"id_token":"id-token","access_token":"access-token"}"""
        Mockito.`when`(jwtDecoder.decode("id-token")).thenReturn(jwt("id-token", nonce = "nonce-1"))

        callback()

        val form = tokenRequests.single().split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
        }
        assertEquals(mapOf(
            "grant_type" to "authorization_code",
            "code" to "auth-code",
            "redirect_uri" to "https://chronicle.example.cl/chronicle/v3/auth/oidc/callback",
            "client_id" to "chronicle-dashboard",
            "client_secret" to "client-secret",
            "code_verifier" to "verifier-1",
        ), form)
    }

    @Test
    fun aTokenThatFailsVerificationOrBelongsToAnotherLoginIsRefused() {
        tokenBody = """{"id_token":"id-token"}"""
        Mockito.`when`(jwtDecoder.decode("id-token")).thenThrow(BadJwtException("bad signature"))
        val invalid = callback()
        assertEquals(401, invalid.statusCode.value())
        assertEquals(mapOf("error" to "invalid_oidc_token"), invalid.body)
        assertEquals("invalid_token", audit.events.last().data["reason"])

        Mockito.reset(jwtDecoder)
        Mockito.`when`(jwtDecoder.decode("id-token")).thenReturn(jwt("id-token", nonce = "someone-elses-nonce"))
        val response = MockHttpServletResponse()
        val replayed = callback(response = response)
        assertEquals(400, replayed.statusCode.value())
        assertEquals(mapOf("error" to "invalid_oidc_nonce"), replayed.body)
        assertEquals("invalid_nonce", audit.events.last().data["reason"])
        assertTrue(response.cookies.isEmpty())

        val noNonceCookie = callback(cookies = mapOf(STATE to "state-1", PKCE to "verifier-1"))
        assertEquals(mapOf("error" to "invalid_oidc_nonce"), noNonceCookie.body)

        Mockito.`when`(jwtDecoder.decode("id-token")).thenReturn(jwt("id-token"))
        assertEquals("an id_token without a nonce claim is refused", mapOf("error" to "invalid_oidc_nonce"), callback().body)

        // The access token is the session cookie, but the id_token's nonce is still checked.
        val config = oidc.copy(cookieTokenClaim = "access_token")
        tokenBody = """{"id_token":"id-token","access_token":"access-token"}"""
        Mockito.`when`(jwtDecoder.decode("access-token")).thenReturn(jwt("access-token"))
        Mockito.`when`(jwtDecoder.decode("id-token")).thenThrow(BadJwtException("expired"))
        val scoped = AuthTokenController(jwtDecoder, ChronicleAuthConfiguration(oidc = config), ObjectMapper(),
            Mockito.mock(UserListingService::class.java), environment, AuditService(audit), jwtBlocklist,
            cookiePolicy = CookiePolicy(environment),
        )
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(STATE, "state-1"), Cookie(NONCE, "nonce-1"), Cookie(PKCE, "verifier-1"))
        }
        assertEquals(mapOf("error" to "invalid_oidc_nonce"),
            scoped.oidcCallback("auth-code", "state-1", null, null, request, MockHttpServletResponse()).body)
    }

    @Test
    fun successfulCallbackSetsTheSessionClearsTheLoginCookiesAndReturnsToTheDashboard() {
        tokenBody = """{"id_token":"id-token","access_token":"access-token"}"""
        Mockito.`when`(jwtDecoder.decode("id-token")).thenReturn(jwt("id-token", nonce = "nonce-1"))
        val response = MockHttpServletResponse()

        val result = callback(response = response)

        assertEquals(302, result.statusCode.value())
        assertEquals("/chronicle", result.headers.location.toString())
        val cookies = response.cookies.groupBy { it.name }
        assertEquals("id-token", cookies.getValue(AuthTokenController.AUTH_COOKIE_NAME).single().value)
        for (name in listOf(STATE, NONCE, PKCE)) {
            val cleared = cookies.getValue(name).single()
            assertEquals(0, cleared.maxAge)
            assertEquals("", cleared.value)
        }
        assertEquals(AuditEventType.LOGIN, audit.events.last().eventType)
        assertEquals("researcher-cl", audit.events.last().data["subject"])
    }

    @Test
    fun sessionTokenFallsBackFromTheConfiguredClaimToAccessThenIdToken() {
        val cases = listOf(
            Triple("id_token", """{"id_token":"t-id","access_token":"t-access"}""", "t-id"),
            Triple("", """{"id_token":"t-id","access_token":"t-access"}""", "t-access"),
            Triple("id_token", """{"access_token":"t-access"}""", "t-access"),
            Triple("custom_token", """{"id_token":"t-id"}""", "t-id"),
            Triple("custom_token", """{"id_token":"t-id","access_token":"t-access"}""", "t-access"),
        )
        for ((claim, body, expected) in cases) {
            tokenBody = body
            Mockito.reset(jwtDecoder)
            listOf("t-id", "t-access").forEach { Mockito.`when`(jwtDecoder.decode(it)).thenReturn(jwt(it, nonce = "nonce-1")) }
            val response = MockHttpServletResponse()
            val scoped = AuthTokenController(jwtDecoder, ChronicleAuthConfiguration(oidc = oidc.copy(cookieTokenClaim = claim)),
                ObjectMapper(), Mockito.mock(UserListingService::class.java), environment, AuditService(audit), jwtBlocklist,
                cookiePolicy = CookiePolicy(environment),
            )
            val request = MockHttpServletRequest().apply {
                setCookies(Cookie(STATE, "state-1"), Cookie(NONCE, "nonce-1"), Cookie(PKCE, "verifier-1"))
            }
            assertEquals(302, scoped.oidcCallback("auth-code", "state-1", null, null, request, response).statusCode.value())
            assertEquals("claim=$claim", expected,
                response.cookies.single { it.name == AuthTokenController.AUTH_COOKIE_NAME }.value)
        }
    }

    @Test
    fun signedInSessionNamesTheProviderOnlyForTheConfiguredIssuer() {
        fun label(config: ChronicleOidcConfiguration, issuer: String): Any? {
            val token = jwt("session", issuer = issuer)
            Mockito.`when`(jwtDecoder.decode("session")).thenReturn(token)
            val scoped = controller(config)
            return scoped.setAuthCookie(mapOf("token" to "session"), MockHttpServletRequest(), MockHttpServletResponse())
                .body?.get("providerLabel")
        }
        assertEquals("University sign-in", label(oidc, ISSUER))
        assertEquals("Institutional SSO", label(oidc.copy(providerLabel = ""), ISSUER))
        assertEquals("Chronicle testing session", label(oidc, "https://localhost/"))
        assertEquals("Chronicle testing session", label(oidc.copy(enabled = false), ISSUER))
        assertNull(controller().getSession(MockHttpServletRequest(), MockHttpServletResponse()).body!!["subject"])
    }
}
