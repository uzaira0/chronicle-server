package com.openlattice.chronicle.pods.servlet

import com.auth0.jwt.JWT
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import jakarta.servlet.http.Cookie
import org.junit.Assert.assertNotNull
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.configuration.PreAuthenticationRateLimitFilter
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter
import org.mockito.Mockito
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.configuration.ObservabilityFilter
import com.openlattice.chronicle.configuration.REFERRER_POLICY_HEADER
import com.openlattice.chronicle.configuration.RequestHardeningFilters
import com.openlattice.chronicle.configuration.SecurityHardeningConfig
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ConcurrentEditFilter
import com.openlattice.chronicle.filters.CreateReceiptFilter
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.observability.ApiMetricsFilter
import com.openlattice.chronicle.security.MetricsAuthenticationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.filter.CharacterEncodingFilter
import org.springframework.web.filter.CorsFilter
import org.springframework.test.util.AopTestUtils
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.filters.JwtBlocklistFilter
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.users.ConfiguredUserListingService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class HardeningLiveChainTest : ChronicleServerTests() {
    @Test
    fun activitySessionProbeReachesJwtValidationInTheBuiltChain() {
        val chain = testServer.context.getBean(SecurityFilterChain::class.java)
        val token = activeFixtureToken(testUser1)
        val request = MockHttpServletRequest("GET", ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH).apply {
            setCookies(Cookie(AuthTokenController.AUTH_COOKIE_NAME, token), Cookie(AuthTokenController.CSRF_COOKIE_NAME, "probe-csrf"))
            addHeader(ChronicleCookieOrBearerTokenResolver.CSRF_HEADER, "probe-csrf")
            addHeader(ChronicleHeaders.USER_ACTIVITY_HEADER, ChronicleHeaders.USER_ACTIVITY_HEADER_VALUE)
        }
        val response = MockHttpServletResponse()
        FilterChainProxy(chain).doFilter(request, response, MockFilterChain())
        assertEquals(200, response.status)
        assertNotNull(response.getHeader(JwtBlocklist.IDLE_DEADLINE_HEADER))
    }

    @Test
    fun authenticationFailureLimiterPrecedesEveryCredentialFilterInTheBuiltChain() {
        val filters = testServer.context.getBean(SecurityFilterChain::class.java).filters
        val limiter = filters.indexOfFirst { it is PreAuthenticationRateLimitFilter }
        assertTrue("Authentication failure limiter must be registered", limiter >= 0)
        val credentialFilters = filters.filter {
            it is MetricsAuthenticationFilter || it is ApiKeyAuthenticationFilter ||
                it is BearerTokenAuthenticationFilter || it is MobileApiSignatureFilter || it is ParticipantFormAccessFilter
        }
        assertTrue(credentialFilters.isNotEmpty())
        credentialFilters.forEach { assertTrue(it.javaClass.simpleName, limiter < filters.indexOf(it)) }
    }

    @Test
    fun invalidApiKeysAreThrottledByTheBuiltSecurityChain() {
        val chain = testServer.context.getBean(SecurityFilterChain::class.java)
        val keys = Mockito.spy(AopTestUtils.getUltimateTargetObject<ApiKeyService>(testServer.context.getBean(ApiKeyService::class.java)))
        val authorization = testServer.context.getBean(AuthorizationManager::class.java)
        val instrumented = DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, chain.filters.map { filter ->
            if (filter is ApiKeyAuthenticationFilter) ApiKeyAuthenticationFilter(keys, authorization) else filter
        })
        val config = testServer.context.getBean(RateLimitConfiguration::class.java)
        val capacity = (config.authRequestsPerMinute * config.burstCapacityMultiplier).toInt()
        val path = "/chronicle/v4/study/${UUID.randomUUID()}/participant/abuse-probe/status"
        repeat(capacity + 1) { attempt ->
            val request = MockHttpServletRequest("GET", path).apply {
                remoteAddr = "203.0.113.147"
                addHeader(API_KEY_HEADER, "invalid-${UUID.randomUUID()}")
            }
            val response = MockHttpServletResponse()
            FilterChainProxy(instrumented).doFilter(request, response, MockFilterChain())
            assertEquals(if (attempt < capacity) 401 else 429, response.status)
        }
        Mockito.verify(keys, Mockito.times(capacity)).authenticateApiKey(Mockito.anyString())
    }

    @Test fun hardeningFiltersFollowTheirOwnersOrderExactlyOnce() {
        val context = testServer.context
        val chain = context.getBean(SecurityFilterChain::class.java)
        val hardening = context.getBean(RequestHardeningFilters::class.java)
        assertEquals(5, hardening.ordered.size)
        assertEquals(hardening.ordered, chain.filters.filter { it in hardening.ordered })
    }

    /** H-blocklist-wiring: the pod-built chain carries the blocklist filter, so a logged-out bearer stops working. */
    @Test fun loggedOutBearerIsRejectedByTheRunningServer() {
        assertEquals(1, testServer.context.getBean(SecurityFilterChain::class.java).filters.count { it is JwtBlocklistFilter })
        val token = requireNotNull(testServer.context.getBean(ConfiguredUserListingService::class.java).issueDashboardToken(testUser2.id))
        fun studies() = http(HttpRequest.newBuilder(URI("$origin${StudyApi.BASE}"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").GET())
        assertEquals(200, studies().statusCode())
        val logout = http(HttpRequest.newBuilder(URI("$authBase/logout"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").POST(HttpRequest.BodyPublishers.noBody()))
        assertEquals(204, logout.statusCode())
        assertEquals(401, studies().statusCode())
    }

    @Test fun locallyIssuedDashboardAndTestingTokensCarryTheirUserSubject() {
        val users = testServer.context.getBean(ConfiguredUserListingService::class.java)
        val decoder = testServer.context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder::class.java)
        users.jwtTokens.forEach { (id, tokens) ->
            tokens.forEach { assertEquals(id, JWT.decode(it).subject) }
        }
        for (token in listOf(users.issueDashboardToken(testUser1.id), users.issueTestingToken(testUser1.id))) {
            assertEquals(testUser1.id, decoder.decode(requireNotNull(token)).subject)
        }
    }

    /** H-auth-controller: anonymous login and session requests pass the chain and are answered by their handlers. */
    @Test fun anonymousLoginAndSessionRequestsReachAuthTokenController() {
        fun login(body: String) = http(HttpRequest.newBuilder(URI("$authBase/dashboard-login"))
            .header("X-Forwarded-For", "203.0.113.149")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").POST(HttpRequest.BodyPublishers.ofString(body)))
        val mapper = ObjectMappers.getJsonMapper()
        val missing = login("{}")
        assertEquals(400, missing.statusCode())
        assertEquals("missing 'password' field", mapper.readTree(missing.body()).get("error").asText())
        val wrong = login("""{"password":"deliberately-wrong-live-chain-password"}""")
        assertEquals(401, wrong.statusCode())
        assertEquals("invalid dashboard password", mapper.readTree(wrong.body()).get("error").asText())
        val session = http(HttpRequest.newBuilder(URI("$authBase/session")).GET())
        assertEquals(200, session.statusCode())
        val body = mapper.readTree(session.body())
        assertEquals(false, body.get("authenticated").asBoolean())
        assertEquals("awaiting-sso", body.get("status").asText())
    }

    private val origin = "http://localhost:$testHttpPort"
    private val authBase = origin + ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH.substringBeforeLast('/')
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private fun http(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(request.timeout(java.time.Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())

    @Test fun typedRegistrationsPreserveTheExactLiveFilterOrder() {
        val context = testServer.context
        val registrations = context.getBeanProvider(SecurityFilterRegistration::class.java).orderedStream().toList()
        assertEquals(listOf(
            context.getBean("requestFilterRegistration"), context.getBean("apiKeyFilterRegistration"),
            context.getBean("sessionFilterRegistration"),
        ), registrations)
        val expected: List<Class<*>> = listOf(ObservabilityFilter::class.java, ApiMetricsFilter::class.java,
            MetricsAuthenticationFilter::class.java, ApiKeyAuthenticationFilter::class.java,
            ParticipantFormAccessFilter::class.java, ConcurrentEditFilter::class.java, CreateReceiptFilter::class.java)
        val actual = context.getBean(SecurityFilterChain::class.java).filters.map { it.javaClass }
        assertEquals(expected, actual.filter { it in expected })
    }

    @Test fun registeredChainEnforcesSizeValidationHeadersBrowserAuthenticationAndCors() {
        val chain = testServer.context.getBean("securityFilterChain") as SecurityFilterChain
        assertTrue(chain.filters.any { it is CorsFilter })
        val encodingIndex = chain.filters.indexOfFirst { it is CharacterEncodingFilter }
        val config = testServer.context.getBean(SecurityHardeningConfig::class.java)
        assertTrue(chain.filters.indexOf(config.requestValidationFilter()) > encodingIndex)
        fun send(request: MockHttpServletRequest): MockHttpServletResponse {
            val response = MockHttpServletResponse()
            FilterChainProxy(chain).doFilter(request, response, MockFilterChain())
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
            // The legacy XSS auditor is itself an injection vector; Spring Security sends 0 too.
            assertEquals("0", response.getHeader("X-XSS-Protection"))
            return response
        }
        val oversized = MockHttpServletRequest("POST", "/chronicle/v3/observability/browser-errors").apply {
            setContent(ByteArray(UploadLimits.MAX_REQUEST_SIZE_BYTES.toInt() + 1))
        }
        assertEquals(413, send(oversized).status)
        assertEquals(400, send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply { addParameter("name", "bad\u0000text") }).status)
        assertEquals(400, send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply { addParameter("name", "one", "two") }).status)
        val unauthenticated = send(MockHttpServletRequest("POST", "/chronicle/v3/observability/browser-errors"))
        assertEquals(401, unauthenticated.status)
        assertEquals("no-referrer", unauthenticated.getHeader(REFERRER_POLICY_HEADER))
        // Spring's own DPoP path would answer with a DPoP challenge; the guard refuses the scheme before it runs.
        val dpop = send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply {
            addHeader("Authorization", "dpop eyJhbGciOiJSUzI1NiJ9.e30.sig")
            addHeader("DPoP", "eyJ0eXAiOiJkcG9wK2p3dCJ9.e30.sig")
        })
        assertEquals(401, dpop.status)
        assertEquals("Bearer", dpop.getHeader("WWW-Authenticate"))
        for (method in listOf("OPTIONS", "GET")) {
            val response = send(MockHttpServletRequest(method, "/chronicle/v3/studies").apply {
                addHeader(HttpHeaders.ORIGIN, "https://foreign.example")
                if (method == "OPTIONS") addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            })
            assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        }
    }
}
