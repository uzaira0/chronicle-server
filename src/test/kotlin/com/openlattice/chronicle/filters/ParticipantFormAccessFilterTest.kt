package com.openlattice.chronicle.filters

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.OffsetDateTime
import java.util.UUID

class ParticipantFormAccessFilterTest {
    private val service = mock<ParticipantFormAccessService>()
    private val filter = ParticipantFormAccessFilter(service)
    private val studyId = UUID.randomUUID()
    private val participantId = "participant:test"

    @After
    fun clearContexts() {
        SecurityContextHolder.clearContext()
        RLSRequestContext.clear()
    }

    @Test
    fun participantRouteWithoutSessionFailsClosed() {
        val request = request("GET", surveyPath())
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    @Test
    fun validSessionSealsRequestAndDatabaseContextToStudy() {
        val scope = scope(ParticipantFormKind.APP_USAGE)
        whenever(service.resolveSession(eq("session-token-value-that-is-long-enough"), eq(null), eq(false)))
            .thenReturn(scope)
        val request = request("GET", surveyPath()).apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
        }
        val response = MockHttpServletResponse()
        var contextSeenInsideChain: com.openlattice.chronicle.storage.rls.RLSConnectionContext? = null
        var scopeSeenInsideChain: ParticipantFormAccessScope? = null
        val chain = FilterChain { servletRequest, _ ->
            contextSeenInsideChain = RLSRequestContext.current()
            scopeSeenInsideChain = servletRequest.getAttribute(
                ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE
            ) as? ParticipantFormAccessScope
        }

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertSame(scope, scopeSeenInsideChain)
        assertEquals(setOf(studyId), contextSeenInsideChain?.authorizedStudyIds)
        assertTrue(contextSeenInsideChain?.principalId?.startsWith("participant-access:") == true)
        assertNull("RLS request state must not leak after the request", RLSRequestContext.current())
    }

    @Test
    fun sessionForAnotherStudyIsRejected() {
        whenever(service.resolveSession(any(), eq(null), eq(false)))
            .thenReturn(scope(ParticipantFormKind.APP_USAGE).copy(studyId = UUID.randomUUID()))
        val request = request("GET", surveyPath()).apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(401, response.status)
    }

    @Test
    fun mutationPassesDedicatedParticipantCsrfHeaderToVerifier() {
        val csrf = "csrf-token-value-that-is-long-enough"
        val idempotencyKey = UUID.randomUUID()
        whenever(service.resolveSession(any(), eq(csrf), eq(true))).thenReturn(scope(ParticipantFormKind.APP_USAGE))
        val request = request("POST", surveyPath()).apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
            addHeader(ParticipantFormAccessFilter.CSRF_HEADER, csrf)
            addHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, idempotencyKey.toString())
        }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        verify(service).resolveSession("session-token-value-that-is-long-enough", csrf, true)
        assertEquals(idempotencyKey, request.getAttribute(ParticipantFormAccessFilter.IDEMPOTENCY_ATTRIBUTE))
    }

    @Test
    fun mutationWithoutValidIdempotencyKeyIsRejectedBeforeController() {
        val csrf = "csrf-token-value-that-is-long-enough"
        whenever(service.resolveSession(any(), eq(csrf), eq(true))).thenReturn(scope(ParticipantFormKind.APP_USAGE))
        val request = request("POST", surveyPath()).apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
            addHeader(ParticipantFormAccessFilter.CSRF_HEADER, csrf)
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(400, response.status)
    }

    private fun request(method: String, uri: String) = MockHttpServletRequest(method, uri)

    private fun surveyPath(): String =
        "/chronicle/v3/survey/$studyId/participant/$participantId/app-usage"

    private fun scope(kind: ParticipantFormKind) = ParticipantFormAccessScope(
        accessCodeId = UUID.randomUUID(),
        studyId = studyId,
        participantId = participantId,
        formKind = kind,
        resourceId = null,
        logicalDate = null,
        absoluteExpiresAt = OffsetDateTime.now().plusHours(1),
    )
}
