/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import java.sql.SQLTransientConnectionException
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import org.junit.Test
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import jakarta.servlet.FilterChain
import java.io.IOException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import jakarta.servlet.http.Cookie
import java.time.OffsetDateTime
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.server.PathContainer
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.util.pattern.PathPatternParser

class RateLimitIdentityTest : RateLimitFixture() {
    private val singleAttemptAuthentication = RateLimitConfiguration(authRequestsPerMinute = 1,
        burstCapacityMultiplier = 1.0, hazelcastMapName = "RL_AUTH_BOUNDARY_${UUID.randomUUID()}")
    private val participantRoutes = listOf(
        StudyApi.V4_BASE to StudyApi.ANDROID_PATH,
        SurveyApi.BASE to SurveyApi.APP_USAGE_PATH,
        TimeUseDiaryApi.BASE to "",
    )

    @Test
    fun canonicalStudyAndParticipantSpellingsShareQuota() {
        repeat(32) { sample ->
            val study = if (sample == 0) UUID.fromString("00000001-0001-0001-0001-000000000001") else UUID.randomUUID()
            val participant = "P+subject-$sample"
            val filter = createFilter(RateLimitConfiguration(writeRequestsPerMinute = 1, burstCapacityMultiplier = 1.0,
                hazelcastMapName = "RL_CANONICAL_${UUID.randomUUID()}"), handler = "enrollHandler")
            val spellings = listOf(
                study.toString() to participant.replace("+", "%2B"),
                study.toString().uppercase() to participant.map { "%%%02X".format(it.code) }.joinToString(""),
                study.toString().mapIndexed { index, char ->
                    if (index % 2 == 0) char.uppercaseChar() else char
                }.joinToString("") to participant.replace("P", "%50").replace("+", "%2b"),
                study.toString().split('-').joinToString("-") { it.trimStart('0').ifEmpty { "0" } } to participant,
                study.toString().map { "%%%02X".format(it.code) }.joinToString("") to participant,
                "%20$study%20" to participant,
            )
            val paths = spellings.flatMap { (studyPath, participantPath) ->
                participantRoutes.flatMap { (base, suffix) ->
                    val participantRoute = StudyApi.PARTICIPANT_PATH
                    val encodedBase = base.replace("u", "%75")
                    listOf(
                        "$base/$studyPath$participantRoute/$participantPath$suffix",
                        "$encodedBase;route=1/$studyPath;scope=1" +
                            "${participantRoute.replace("i", "%69")}/$participantPath$suffix",
                    )
                }
            }
            paths.forEachIndexed { index, path ->
                val response = MockHttpServletResponse()
                filter.doFilter(createRequest(path), response, MockFilterChain())
                assertEquals("alternate spelling $index must consume the same quota", if (index == 0) 200 else 429, response.status)
            }
            val otherParticipant = MockHttpServletResponse()
            filter.doFilter(createRequest("${StudyApi.V4_BASE}/$study${StudyApi.PARTICIPANT_PATH}/another${StudyApi.ANDROID_PATH}"),
                otherParticipant, MockFilterChain())
            assertEquals("a different logical participant must retain its own quota", 200, otherParticipant.status)
        }
    }

    @Test
    fun identitySegmentsMatchMvcWithoutTurningAnEncodedSlashIntoASeparator() {
        val study = UUID.randomUUID()
        val pattern = PathPatternParser().parse(
            "${StudyApi.V4_BASE}/{study}${StudyApi.PARTICIPANT_PATH}/{participant}${StudyApi.ANDROID_PATH}",
        )
        val encodedBase = StudyApi.V4_BASE.replace("u", "%75")
        val encodedParticipant = StudyApi.PARTICIPANT_PATH.replace("i", "%69")
        val subjects = listOf("A%2FB" to "A/B", "A" to "A", "A%252FB" to "A%2FB").map { (encoded, decoded) ->
            val path = "$encodedBase;route=1/$study;scope=1$encodedParticipant/$encoded${StudyApi.ANDROID_PATH}"
            val match = pattern.matchAndExtract(PathContainer.parsePath(path))
            assertNotNull("MVC accepts this segment spelling", match)
            assertEquals(decoded, requireNotNull(match).uriVariables["participant"])
            RateLimitSubject.from(createRequest(path)).also {
                assertEquals(study, it.studyId)
                assertEquals(decoded, it.participantId)
            }
        }
        assertNotEquals(subjects[0], subjects[1])
        assertNotEquals(subjects[0], subjects[2])
    }

    @Test
    fun malformedPathIdentitiesStillConsumeTheIpQuota() {
        for (path in listOf("not-a-uuid/participant/P", "%ZZ/participant/P", "${UUID.randomUUID()}/participant/%ZZ")) {
            val filter = createFilter(RateLimitConfiguration(writeRequestsPerMinute = 1,
                burstCapacityMultiplier = 1.0, hazelcastMapName = "RL_MALFORMED_${UUID.randomUUID()}"), handler = "enrollHandler")
            repeat(2) { index ->
                val response = MockHttpServletResponse()
                filter.doFilter(createRequest("${StudyApi.V4_BASE}/$path${StudyApi.ANDROID_PATH}"), response, MockFilterChain())
                assertEquals(if (index == 0) 200 else 429, response.status)
            }
        }
    }

    @Test
    fun authenticatedRequestsReleaseTheIpQuotaBeforeAnOpenControllerResponse() {
        val preAuthentication = createPreAuthenticationFilter(singleAttemptAuthentication)
        val authenticated = createFilter(singleAttemptAuthentication, handler = "authenticatedHandler")
        val first = MockHttpServletResponse()
        val second = MockHttpServletResponse()
        val prior = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext())
        try {
            preAuthentication.doFilter(createRequest(), first, FilterChain { request, response ->
                SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("first", "", "USER")
                authenticated.doFilter(request, response, FilterChain { _, _ ->
                    SecurityContextHolder.clearContext()
                    preAuthentication.doFilter(createRequest(), second, FilterChain { nextRequest, nextResponse ->
                        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("second", "", "USER")
                        authenticated.doFilter(nextRequest, nextResponse, MockFilterChain())
                    })
                    first.status = 403
                })
            })
            assertEquals("A valid neighbour must proceed before the first response finishes", 200, second.status)
            assertEquals(403, first.status)
            SecurityContextHolder.clearContext()
            val third = MockHttpServletResponse()
            preAuthentication.doFilter(createRequest(), third, MockFilterChain())
            assertEquals("An application denial must not consume the authentication quota", 200, third.status)
        } finally {
            SecurityContextHolder.setContext(prior)
        }
    }

    @Test
    fun participantSessionsReleaseTheIpQuotaBeforeTheirControllerRuns() {
        val preAuthentication = createPreAuthenticationFilter(singleAttemptAuthentication)
        val study = UUID.randomUUID()
        val participant = "participant"
        val service = mock<ParticipantFormAccessService>()
        val session = "synthetic-valid-cookie"
        whenever(service.resolveSession(session, null, false)).thenReturn(ParticipantFormAccessScope(
            UUID.randomUUID(), study, participant, ParticipantFormKind.APP_USAGE, null, null, OffsetDateTime.now().plusHours(1),
        ))
        val participants = ParticipantFormAccessFilter(service)
        val path = SurveyApi.BASE + "/$study" + SurveyApi.PARTICIPANT_PATH + "/$participant" + SurveyApi.APP_USAGE_PATH
        fun request() = createRequest(path).apply { setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, session)) }
        val first = MockHttpServletResponse()
        val second = MockHttpServletResponse()
        val prior = SecurityContextHolder.getContext()
        SecurityContextHolder.clearContext()
        try {
            preAuthentication.doFilter(request(), first, FilterChain { incoming, response ->
                participants.doFilter(incoming, response, FilterChain { _, _ ->
                    preAuthentication.doFilter(request(), second, FilterChain { nextRequest, nextResponse ->
                        participants.doFilter(nextRequest, nextResponse, MockFilterChain())
                    })
                })
            })
            assertEquals(200, first.status)
            assertEquals("A capability session must also release before a slow response", 200, second.status)
        } finally {
            SecurityContextHolder.setContext(prior)
        }
    }

    /**
     * Expired survey sessions reopening together behind one NAT exhaust the address; a neighbour's live
     * session still reaches the participant filter, while unknown, expired and revoked sessions stay limited.
     */
    @Test
    fun liveParticipantSessionPassesAnExhaustedAddress() {
        val study = UUID.randomUUID()
        val participant = "participant"
        val live = "synthetic-live-session-cookie"
        val service = mock<ParticipantFormAccessService>()
        whenever(service.isAdmittedSession(live, null, false)).thenReturn(true)
        whenever(service.resolveSession(live, null, false)).thenReturn(ParticipantFormAccessScope(
            UUID.randomUUID(), study, participant, ParticipantFormKind.APP_USAGE, null, null, OffsetDateTime.now().plusHours(1),
        ))
        val preAuthentication = createPreAuthenticationFilter(singleAttemptAuthentication, participantForms = service)
        val participants = ParticipantFormAccessFilter(service)
        val path = SurveyApi.BASE + "/$study" + SurveyApi.PARTICIPANT_PATH + "/$participant" + SurveyApi.APP_USAGE_DAYS_PATH
        fun request(cookie: String) = createRequest(path).apply { setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, cookie)) }
        fun send(cookie: String): MockHttpServletResponse = MockHttpServletResponse().also { response ->
            preAuthentication.doFilter(request(cookie), response, FilterChain { incoming, outgoing ->
                participants.doFilter(incoming, outgoing, MockFilterChain())
            })
        }
        val prior = SecurityContextHolder.getContext()
        SecurityContextHolder.clearContext()
        try {
            assertEquals("an expired session is refused and keeps its token", 401, send("synthetic-expired-session").status)
            repeat(5) { index -> assertEquals("live session $index on the exhausted address", 200, send(live).status) }
            for (dead in listOf("synthetic-expired-session", "synthetic-revoked-session", "garbage")) {
                val throttled = send(dead)
                assertEquals("$dead on the exhausted address", 429, throttled.status)
                assertNotNull(throttled.getHeader(RateLimitFilter.HEADER_RETRY_AFTER))
            }
        } finally {
            SecurityContextHolder.setContext(prior)
        }
        // The limiter's check is the read-only one; only the participant filter's resolve extends the idle clock.
        verify(service, times(5)).resolveSession(live, null, false)
    }

    @Test
    fun liveParticipantSessionRecoveryPassesAnExhaustedAddress() {
        SecurityContextHolder.clearContext()
        val live = "synthetic-live-session-cookie"
        val service = mock<ParticipantFormAccessService>()
        whenever(service.isAdmittedSession(live, null, false)).thenReturn(true)
        val preAuthentication = createPreAuthenticationFilter(singleAttemptAuthentication, participantForms = service)
        val participants = ParticipantFormAccessFilter(service)
        val path = ParticipantFormAccessFilter.SESSION_RECOVERY_PATH
        fun send(route: String, cookie: String, method: String = "GET"): MockHttpServletResponse =
            MockHttpServletResponse().also { response ->
                val request = createRequest(route, method).apply {
                    setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, cookie))
                    addHeader(ParticipantFormAccessFilter.CSRF_HEADER, "ignored-tab-csrf-header")
                }
                preAuthentication.doFilter(request, response, FilterChain { incoming, outgoing ->
                    participants.doFilter(incoming, outgoing, FilterChain { _, result ->
                        (result as MockHttpServletResponse).status = if (cookie == live) 200 else 401
                    })
                })
            }
        assertEquals(401, send(path, "garbage").status)
        for (route in listOf(path, path.removePrefix("/chronicle"))) {
            repeat(3) { assertEquals("live session recovery: $route", 200, send(route, live).status) }
            assertEquals(429, send(route, "garbage").status)
            assertEquals("only GET recovers a session", 429, send(route, live, "POST").status)
        }
        verify(service, never()).resolveSession(any(), anyOrNull(), any())
    }

    /** A live session is not counted per IP by session recovery's endpoint limit either; anonymous recovery is. */
    @Test
    fun liveParticipantSessionIsNotCountedByTheRecoveryEndpointLimit() {
        SecurityContextHolder.clearContext()
        val live = "synthetic-live-session-cookie"
        val service = mock<ParticipantFormAccessService>()
        whenever(service.isAdmittedSession(live, null, false)).thenReturn(true)
        val preAuthentication = createPreAuthenticationFilter(singleAttemptAuthentication, participantForms = service)
        val endpoint = createFilter(singleAttemptAuthentication.copy(writeRequestsPerMinute = 1), "enrollHandler")
        fun send(cookie: String?): MockHttpServletResponse = MockHttpServletResponse().also { response ->
            val request = createRequest(ParticipantFormAccessFilter.SESSION_RECOVERY_PATH).apply {
                cookie?.let { setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, it)) }
            }
            preAuthentication.doFilter(request, response, FilterChain { incoming, outgoing ->
                endpoint.doFilter(incoming, outgoing, MockFilterChain())
            })
        }
        repeat(3) { assertEquals("live session recovery $it", 200, send(live).status) }
        assertEquals(200, send(null).status)
        assertEquals("anonymous recovery keeps the endpoint limit", 429, send(null).status)
        repeat(3) { assertEquals("live session recovery after anonymous exhaustion $it", 200, send(live).status) }
    }

    /** A participant cookie does not exempt researcher login. */
    @Test
    fun aLiveParticipantSessionDoesNotExemptOtherRoutes() {
        val service = mock<ParticipantFormAccessService>()
        whenever(service.isAdmittedSession(any(), anyOrNull(), any())).thenReturn(true)
        val filter = createPreAuthenticationFilter(singleAttemptAuthentication, participantForms = service)
        fun request() = createRequest("/chronicle/v3/auth/dashboard-login", "POST").apply {
            setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "synthetic-live-session-cookie"))
        }
        val refused = MockHttpServletResponse()
        filter.doFilter(request(), refused, FilterChain { _, response -> (response as MockHttpServletResponse).status = 401 })
        assertEquals(401, refused.status)
        val throttled = MockHttpServletResponse()
        filter.doFilter(request(), throttled, FilterChain { _, _ -> error("an exhausted address must not reach login") })
        assertEquals(429, throttled.status)
        verify(service, never()).isAdmittedSession(any(), anyOrNull(), any())
    }

    /**
     * Every credential preflight branch: a credential the database cannot check (restart, pool exhausted) is
     * unverified, so it reserves the address's anonymous quota and authentication decides it; never exempted or a 500.
     */
    @Test
    fun everyCredentialThatCannotBeVerifiedIsCounted() {
        val outage = SQLTransientConnectionException("pool exhausted", "08001")
        val platform = mock<HikariDataSource>()
        whenever(platform.connection).thenAnswer { throw outage }
        val storage = mock<StorageResolver>()
        whenever(storage.getDefaultPlatformStorage()).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, platform))
        val jwt = Jwt.withTokenValue("synthetic-researcher-jwt").header("alg", "RS256").subject("researcher")
            .jti(UUID.randomUUID().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build()
        val decoder = mock<JwtDecoder>()
        whenever(decoder.decode(jwt.tokenValue)).thenReturn(jwt)
        whenever(decoder.decode("invalid")).thenThrow(JwtException("invalid JWT"))
        val apiKeys = mock<ApiKeyService>()
        whenever(apiKeys.findLiveApiKey(any())).thenAnswer { throw outage }
        val forms = mock<ParticipantFormAccessService>()
        whenever(forms.isAdmittedSession(any(), anyOrNull(), any())).thenAnswer { throw outage }
        val sessionPath = SurveyApi.BASE + "/${UUID.randomUUID()}" + SurveyApi.PARTICIPANT_PATH + "/participant" +
            SurveyApi.APP_USAGE_DAYS_PATH
        val branches: Map<String, Pair<() -> MockHttpServletRequest, () -> Unit>> = mapOf(
            "researcher JWT" to Pair({ createRequest().apply { addHeader("Authorization", "Bearer ${jwt.tokenValue}") } },
                { verify(platform, times(2)).connection }),
            "device API key" to Pair({ createRequest().apply { addHeader(API_KEY_HEADER, "ck_fixture_key") } },
                { verify(apiKeys, times(2)).findLiveApiKey("ck_fixture_key") }),
            "participant form session" to Pair({
                createRequest(sessionPath).apply {
                    setCookies(Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "synthetic-live-session-cookie"))
                }
            }, { verify(forms, times(2)).isAdmittedSession(any(), anyOrNull(), any()) }),
            "rejected JWT" to Pair({ createRequest().apply { addHeader("Authorization", "Bearer invalid") } }, {
                verify(decoder, times(2)).decode("invalid")
                verify(platform, times(2)).connection
            }),
        )
        branches.forEach { (branch, case) ->
            val (request, databaseWasAsked) = case
            val filter = createPreAuthenticationFilter(
                singleAttemptAuthentication.copy(hazelcastMapName = "RL_DB_OUTAGE_${UUID.randomUUID()}"),
                apiKeys, forms, decoder, JwtBlocklist(hz, storage),
            )
            val refused = MockHttpServletResponse()
            filter.doFilter(request(), refused, FilterChain { _, response -> (response as MockHttpServletResponse).status = 401 })
            assertEquals("$branch: authentication decides the unverified credential", 401, refused.status)
            val throttled = MockHttpServletResponse()
            filter.doFilter(request(), throttled, FilterChain { _, _ -> error("$branch: an exhausted address must not reach authentication") })
            assertEquals("$branch: the unverified credential consumed the anonymous quota", 429, throttled.status)
            assertNotNull(throttled.getHeader(RateLimitFilter.HEADER_RETRY_AFTER))
            databaseWasAsked()
        }
    }

    @Test
    fun anUncaughtFailureBeforeAuthenticationDoesNotRefundItsReservation() {
        val filter = createPreAuthenticationFilter(singleAttemptAuthentication)
        assertThrows(IOException::class.java) {
            filter.doFilter(createRequest(), MockHttpServletResponse(), FilterChain { _, _ -> throw IOException("fixture failure") })
        }
        val response = MockHttpServletResponse()
        filter.doFilter(createRequest(), response, MockFilterChain())
        assertEquals(429, response.status)
    }

    @Test
    fun successfulClientsBehindOneIpDoNotSpendAuthenticationFailureQuota() {
        val filter = createPreAuthenticationFilter(singleAttemptAuthentication)
        repeat(8) {
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(), response, MockFilterChain())
            assertEquals(200, response.status)
        }
        val rejected = MockHttpServletResponse()
        filter.doFilter(createRequest(), rejected, jakarta.servlet.FilterChain { _, response ->
            (response as jakarta.servlet.http.HttpServletResponse).status = 401
        })
        assertEquals(401, rejected.status)
        val throttled = MockHttpServletResponse()
        filter.doFilter(createRequest(), throttled, MockFilterChain())
        assertEquals(429, throttled.status)
    }

    @Test
    fun authenticatedEndpointsAreNeverLimited() {
        val filter = createFilter(singleAttemptAuthentication, handler = "loginHandler")
        val context = SecurityContextHolder.getContext()
        context.authentication = com.openlattice.chronicle.filters.ApiKeyAuthenticationToken(
            "apikey:${UUID.randomUUID()}", UUID.randomUUID(), UUID.randomUUID(), "participant", UUID.randomUUID(),
            com.openlattice.chronicle.apikey.ApiKeyScope.WRITE, emptyList())
        try {
            repeat(200) { index ->
                val response = MockHttpServletResponse()
                filter.doFilter(createRequest(remoteAddr = "203.0.113.55"), response, MockFilterChain())
                assertEquals("authenticated request $index", 200, response.status)
            }
        } finally { SecurityContextHolder.clearContext() }
        repeat(2) { index ->
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(remoteAddr = "203.0.113.55"), response, MockFilterChain())
            assertEquals("unauthenticated request $index", if (index == 0) 200 else 429, response.status)
        }
    }

    @Test
    fun preAuthenticationEndpointsUseTheStricterPerIpLimit() {
        val filter = createFilter(RateLimitConfiguration(authRequestsPerMinute = 2, burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_AUTH_${UUID.randomUUID()}"))
        val ip = "198.51.100.${(Math.random() * 254).toInt() + 1}"
        repeat(2) { index ->
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(path = "/chronicle/v4/mobile/reviewer-enrollment", method = "POST", remoteAddr = ip),
                response, MockFilterChain())
            assertEquals("Auth request $index should pass", 200, response.status)
        }
        val limited = MockHttpServletResponse()
        filter.doFilter(createRequest(path = "/chronicle/v4/mobile/reviewer-enrollment", method = "POST", remoteAddr = ip),
            limited, MockFilterChain())
        assertEquals(429, limited.status)
        val otherIp = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = "203.0.113.77"), otherIp, MockFilterChain())
        assertEquals("Another IP keeps its own bucket", 200, otherIp.status)
    }
}
