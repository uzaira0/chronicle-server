package com.openlattice.chronicle.filters

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.generated.ChronicleHeaders
import java.time.LocalDate
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi

class ParticipantFormAccessFilterTest {
    private val service = mock<ParticipantFormAccessService>()
    private val filter = ParticipantFormAccessFilter(service)
    private val studyId = UUID.randomUUID()
    private val participantId = "participant:test"

    // Another test class on this worker thread may leave an authenticated user behind, which
    // the filter treats as a researcher request and passes through unscoped.
    @Before
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
        var contextSeenInsideChain: RLSConnectionContext? = null
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
            addHeader(ChronicleHeaders.PARTICIPANT_FORM_DATE_HEADER, LocalDate.now().toString())
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

    @Test
    fun portalSubmissionsKeepStableDistinctDailyResourceIdentities() {
        val portal = scope(ParticipantFormKind.PORTAL)
        whenever(service.resolveSession(any(), any(), eq(true))).thenReturn(portal)
        val day = LocalDate.now()
        val dates = listOf(day, day, day.plusDays(1))
        for (kind in ParticipantFormKind.entries.filter { it.requiresSubmissionDate }) {
            val path = when (kind) {
                ParticipantFormKind.TIME_USE_DIARY -> TimeUseDiaryApi.BASE + "/$studyId" +
                    TimeUseDiaryApi.PARTICIPANT_PATH + "/$participantId"
                ParticipantFormKind.QUESTIONNAIRE -> SurveyApi.BASE + "/$studyId" +
                    SurveyApi.PARTICIPANT_PATH + "/$participantId" + SurveyApi.QUESTIONNAIRE_PATH + "/${UUID.randomUUID()}"
                else -> error("Daily form route is missing")
            }
            val observed = mutableListOf<LocalDate?>()
            for (date in dates) {
                val request = request("POST", path).apply {
                    setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session"))
                    addHeader(ParticipantFormAccessFilter.CSRF_HEADER, "csrf")
                    addHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                    addHeader(ChronicleHeaders.PARTICIPANT_FORM_DATE_HEADER, date.toString())
                }
                val chain = MockFilterChain()
                filter.doFilter(request, MockHttpServletResponse(), chain)
                assertSame(request, chain.request)
                assertSame(portal, request.getAttribute(ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE))
                observed.add(request.getAttribute(ParticipantFormAccessFilter.SUBMISSION_DATE_ATTRIBUTE) as? LocalDate)
            }
            assertEquals(dates, observed)
        }
    }

    @Test
    fun dailyMutationRejectsMissingMalformedAndChangedCalendarDates() {
        val day = LocalDate.now()
        val bound = scope(ParticipantFormKind.TIME_USE_DIARY).copy(logicalDate = day)
        whenever(service.resolveSession(any(), any(), eq(true))).thenReturn(bound)
        for (submitted in listOf(null, "not-a-day", day.plusDays(1).toString())) {
            val request = request("POST", TimeUseDiaryApi.BASE + "/$studyId" + TimeUseDiaryApi.PARTICIPANT_PATH +
                "/$participantId").apply {
                setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session"))
                addHeader(ParticipantFormAccessFilter.CSRF_HEADER, "csrf")
                addHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                submitted?.let { addHeader(ChronicleHeaders.PARTICIPANT_FORM_DATE_HEADER, it) }
            }
            val response = MockHttpServletResponse()
            var reachedHandler = false
            filter.doFilter(request, response, FilterChain { _, _ -> reachedHandler = true })
            assertEquals(400, response.status)
            assertFalse(reachedHandler)
        }
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
    /**
     * The survey page asks its study's survey frequency (daily or hourly) with the participant's session. That read is
     * the only thing the session reaches on that route: another study's frequency and any write to it are refused.
     */
    @Test
    fun surveySessionReadsOnlyItsOwnStudysSurveyFrequency() {
        whenever(service.resolveSession(any(), org.mockito.kotlin.anyOrNull(), any())).thenReturn(scope(ParticipantFormKind.APP_USAGE))
        fun send(method: String, study: UUID): Pair<Int, Boolean> {
            val request = request(method, "/chronicle/v3/survey/$study/app-usage-frequency").apply {
                setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
            }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            return response.status to (chain.request != null)
        }
        assertEquals(200 to true, send("GET", studyId))
        assertEquals(401 to false, send("GET", UUID.randomUUID()))
        for (method in listOf("PUT", "POST", "PATCH", "DELETE")) assertEquals(method, 401 to false, send(method, studyId))
    }

    @Test
    fun encodedAndNoncanonicalAnonymousRoutesFailClosed() {
        val encodedStudy = studyId.toString().replaceFirst("-", "%2D")
        listOf(
            surveyPath().replace(studyId.toString(), encodedStudy),
            surveyPath() + "/",
            surveyPath().replace("survey", "%73urvey"),
            surveyPath().replace("/participant/", ";ignored=x/participant/"),
            "/chronicle/v3/survey/$studyId/unknown-route",
        ).forEach { path ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request("GET", path), response, chain)
            assertEquals(path, 401, response.status)
            assertNull(path, chain.request)
        }
    }

    @Test
    fun encodedParticipantPathPreservesLiteralPlusAndValidCapability() {
        val participant = "participant+test"
        whenever(service.resolveSession(any(), eq(null), eq(false)))
            .thenReturn(scope(ParticipantFormKind.APP_USAGE).copy(participantId = participant))
        val request = request("GET", surveyPath().replace(participantId, "participant%2Btest")).apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session-token-value-that-is-long-enough"))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertSame(request, chain.request)
    }

    @Test
    fun nativeFormPathsPreserveBoundParticipantAndStudyIdentity() {
        for (kind in listOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.TIME_USE_DIARY)) {
            val scope = scope(kind).copy(participantId = "P/+1")
            whenever(service.resolveSession(any(), eq(null), eq(false))).thenReturn(scope)
            val base = if (kind == ParticipantFormKind.APP_USAGE) SurveyApi.BASE else TimeUseDiaryApi.BASE
            val suffix = if (kind == ParticipantFormKind.APP_USAGE) SurveyApi.APP_USAGE_PATH else ""
            val path = "$base;route=1/%20${studyId.toString().uppercase()}%20/part%69cipant/P%2F+1$suffix"
                .replace("survey", "%73urvey").replace("time-use-diary", "time%2Duse-diary")
            val request = request("GET", path).apply {
                setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session"))
            }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            assertEquals(path, 200, response.status)
            assertSame(scope, request.getAttribute(ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE))
            assertSame(request, chain.request)
            assertNull(RLSRequestContext.current())
        }
    }

    @Test
    fun decodedSeparatorsNeverMergeDistinctCapabilityParticipants() {
        for ((participant, spelling) in listOf("A/B" to "A%2FB", "A" to "A", "A%2FB" to "A%252FB")) {
            for (bound in listOf("A/B", "A", "A%2FB")) {
                whenever(service.resolveSession(any(), eq(null), eq(false)))
                    .thenReturn(scope(ParticipantFormKind.APP_USAGE).copy(participantId = bound))
                val request = request("GET", surveyPath().replace(participantId, spelling)).apply {
                    setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "session"))
                }
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(request, response, chain)
                assertEquals("$participant/$bound", if (participant == bound) 200 else 401, response.status)
                if (participant == bound) assertSame(request, chain.request) else assertNull(chain.request)
                assertNull(RLSRequestContext.current())
            }
        }
    }

}
