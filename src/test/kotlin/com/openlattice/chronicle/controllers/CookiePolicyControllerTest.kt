package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.CookiePolicy
import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleOidcConfiguration
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ExchangeParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ExchangedParticipantFormSession
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.users.UserListingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite
import org.springframework.web.method.support.InvocableHandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.ServletRequestMethodArgumentResolver
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class CookiePolicyControllerTest {
    @Test fun authCookiesHonorOverrideWhileHostParticipantCookiesStaySecure() {
        assertSecureDecision("false", "https", true, false)
        assertSecureDecision("true", "http", false, true)
    }

    @Test fun authCookiesFollowTransportWhileHostParticipantCookiesStaySecure() {
        assertSecureDecision(null, "http", false, false)
        assertSecureDecision(null, "HTTPS", false, true)
        assertSecureDecision(null, null, false, true)
        assertSecureDecision("not-a-boolean", "http", false, false)
        assertSecureDecision(null, "http", true, true)
        assertSecureDecision(null, "other", false, false)
    }

    @Test fun purposesRetainPathsScriptAccessLifetimesAndClearing() {
        val fixture = Fixture(MockEnvironment())
        val request = MockHttpServletRequest()
        val auth = fixture.authSession(request)
        assertEquals(2, auth.cookies.size)
        for (cookie in auth.cookies) {
            assertEquals("/chronicle", cookie.path)
            assertEquals("Strict", cookie.getAttribute("SameSite"))
            assertEquals(cookie.name == AuthTokenController.AUTH_COOKIE_NAME, cookie.isHttpOnly)
            assertTrue(cookie.maxAge in 595..600)
        }
        val participant = fixture.participantSession(request)
        val header = checkNotNull(participant.getHeader("Set-Cookie"))
        for (attribute in listOf("Path=/", "Secure", "HttpOnly", "SameSite=Strict")) {
            assertTrue(header, attribute in header.split("; "))
        }
        assertTrue(header.startsWith("${ParticipantFormAccessFilter.SESSION_COOKIE}=participant-session;"))
        assertTrue(Regex("Max-Age=(\\d+)").find(header)!!.groupValues[1].toInt() in 1795..1800)
        val oidc = fixture.oidcSession(request)
        assertEquals(3, oidc.cookies.size)
        oidc.cookies.forEach {
            assertEquals("Lax", it.getAttribute("SameSite"))
            assertEquals("/chronicle", it.path)
            assertTrue(it.isHttpOnly)
            assertEquals(600, it.maxAge)
        }
        val cleared = MockHttpServletResponse()
        fixture.auth.logout(request, cleared)
        assertEquals(2, cleared.cookies.size)
        cleared.cookies.forEach {
            assertEquals("", it.value)
            assertEquals(0, it.maxAge)
            assertEquals("Strict", it.getAttribute("SameSite"))
            assertEquals("/chronicle", it.path)
        }
    }

    private fun assertSecureDecision(override: String?, proto: String?, directSecure: Boolean, expected: Boolean) {
        val environment = MockEnvironment()
        override?.let { environment.setProperty("chronicle.security.cookie.secure", it) }
        val fixture = Fixture(environment)
        val request = MockHttpServletRequest().apply {
            isSecure = directSecure
            proto?.let { addHeader("X-Forwarded-Proto", it) }
        }
        val clue = "override=$override proto=$proto directSecure=$directSecure"
        fixture.authSession(request).cookies.forEach { assertEquals("auth ${it.name} $clue", expected, it.secure) }
        val participant = checkNotNull(fixture.participantSession(request).getHeader("Set-Cookie"))
        assertTrue("The __Host- cookie must stay Secure regardless of $clue: $participant", "Secure" in participant.split("; "))
        assertTrue(participant, "Path=/" in participant.split("; "))
        assertTrue(participant, "Domain=" !in participant)
        fixture.oidcSession(request).cookies.forEach { assertEquals("OIDC ${it.name} $clue", expected, it.secure) }
    }

    private class Fixture(environment: MockEnvironment) {
        private val access = mock<ParticipantFormAccessService>()
        private val studies = mock<StudyManager>()
        private val decoder = mock<JwtDecoder>()
        private val audit = AuditService(RecordingAuditingManager())
        private val attempt = UUID.randomUUID()
        private val session = ParticipantFormSessionResponse("csrf", UUID.randomUUID(), "participant",
            ParticipantFormKind.APP_USAGE, expiresAt = OffsetDateTime.now().plusMinutes(30))
        private val cookiePolicy = CookiePolicy(environment)
        val auth = AuthTokenController(decoder, ChronicleAuthConfiguration(oidc = ChronicleOidcConfiguration(
            enabled = true, publicBaseUrl = "https://chronicle.example", authorizationUri = "https://identity.example/auth",
            tokenUri = "https://identity.example/token", clientId = "cookie-test", clientSecret = "test-client-secret",
        )), ObjectMapper(), mock<UserListingService>(), environment, audit, mock<JwtBlocklist>(),
            cookiePolicy = cookiePolicy,
        )
        private val participant = ParticipantFormAccessController(access, mock<EnrollmentManager>(), studies,
            mock<ParticipantCollectionAcknowledgmentService>(), audit, mock<AuthorizationManager>(), mock<AuditingManager>(),
            cookiePolicy = cookiePolicy,
        )

        init {
            val jwt = Jwt.withTokenValue("cookie-test-token").header("alg", "HS256").subject("cookie-test")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build()
            whenever(decoder.decode(jwt.tokenValue)).thenReturn(jwt)
            whenever(access.exchangeAccessCode("test-code", attempt))
                .thenReturn(ExchangedParticipantFormSession("participant-session", session))
            whenever(studies.getStudySettings(session.studyId)).thenThrow(NoSuchElementException("missing"))
        }

        fun authSession(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also {
            assertEquals(200, auth.setAuthCookie(mapOf("token" to "cookie-test-token"), request, it).statusCode.value())
        }

        fun oidcSession(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also {
            assertEquals(302, auth.oidcLogin(request, it).statusCode.value())
        }

        fun participantSession(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also {
            // Resolve the actual Servlet request argument exactly as MVC does, keeping this test about cookie policy.
            val method = participant.javaClass.methods.single { candidate -> candidate.name == "exchangeAccessCode" }
            val handler = InvocableHandlerMethod(participant, method)
            handler.setHandlerMethodArgumentResolvers(HandlerMethodArgumentResolverComposite()
                .addResolver(ServletRequestMethodArgumentResolver()))
            handler.invokeForRequest(ServletWebRequest(request, it), null,
                ExchangeParticipantFormAccessCodeRequest("test-code", attempt), it)
        }
    }
}
