package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.apikey.ApiKeyApi
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import com.openlattice.chronicle.configuration.PreAuthenticationRateLimitFilter
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter
import com.openlattice.chronicle.controllers.ReviewerEnrollmentController
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantSessionSecrets
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.AopTestUtils
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher

/**
 * The pre-authentication per-IP limiter counts only requests without a valid credential.
 * A live device key or participant form session, like a valid researcher JWT, never consumes this quota; unknown,
 * revoked, erased and cancelled keys and unknown, expired and revoked sessions stay limited at the same rate. Real
 * HTTP through the built filter chain, real ApiKeyService, ParticipantFormAccessService and database.
 */
class DeviceKeyPreAuthenticationLimitTest : ChronicleServerTests() {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val origin = "http://localhost:$testHttpPort"
    private val capacity: Int get() = testServer.context.getBean(RateLimitConfiguration::class.java)
        .let { (it.authRequestsPerMinute * it.burstCapacityMultiplier).toInt() }
    private val apiKeys: ApiKeyService
        get() = AopTestUtils.getUltimateTargetObject(testServer.context.getBean(ApiKeyService::class.java))
    private val forms: ParticipantFormAccessService
        get() = testServer.context.getBean(ParticipantFormAccessService::class.java)

    private fun batteryStudy(title: String): UUID {
        val study = clientUser1.studyApi.createStudy(Study(title = title, contact = "test@openlattice.com"))
        prepareUsageFixture(clientUser1, study)
        val settings = clientUser1.studyApi.getStudySetting(study, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        clientUser1.studyApi.updateStudySettings(study, StudySettingType.DataCollection, settings.copy(modules = settings.modules +
            (CollectionModuleId.BATTERY_TELEMETRY to CollectionDefaults.moduleSetting(CollectionModuleId.BATTERY_TELEMETRY, enabled = true))))
        return study
    }

    private fun phone(study: UUID, acknowledged: Boolean = true): UsageEnrollmentFixture {
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(study, participant)
        return if (acknowledged) enrollUsageFixture(study, participant.participantId, TestDataFactory.androidDevice())
        else beginUsageEnrollmentFixture(study, participant.participantId, TestDataFactory.androidDevice())
    }

    /** A battery upload from [phone]'s device, to [pathOwner]'s participant path, presenting [apiKey] (none when null). */
    private fun batteryUpload(
        phone: UsageEnrollmentFixture,
        ip: String,
        apiKey: String? = phone.replay.proposedApiKey,
        pathOwner: UsageEnrollmentFixture = phone,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$origin${StudyApi.V4_BASE}/${pathOwner.replay.studyId}${StudyApi.PARTICIPANT_PATH}" +
            "/${pathOwner.replay.participantId}${StudyApi.ANDROID_PATH}${StudyApi.BATTERY_PATH}"))
            .header("X-Forwarded-For", ip)
            .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, phone.replay.device.randomDeviceId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                [{"id":"${UUID.randomUUID()}","timestamp":"2026-10-29T12:00:00Z","timezone":"America/Santiago",
                  "levelPercent":50,"chargingState":"CHARGING","plugType":"AC","temperatureDeciC":250,
                  "voltageMillivolts":4000,"health":"GOOD"}]
            """.trimIndent()))
        apiKey?.let { request.header(API_KEY_HEADER, it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun unknownKey(): String = UUID.randomUUID().toString().replace("-", "").let { "ck_${it.take(8)}_$it" }

    /** Exactly [capacity] attempts answered [refused], then 429: the quota every unauthenticated client had before. */
    private fun assertLimitedLikeBefore(label: String, refused: Int, attempt: () -> Int) {
        val statuses = (0..capacity).map { attempt() }
        assertEquals("$label: $statuses", List(capacity) { refused } + 429, statuses)
    }

    @Test
    fun twentyFivePhonesArrivingTogetherBehindOneAddressAreAllAdmitted() {
        val study = batteryStudy("one NAT, 25 phones at once")
        val phones = (1..25).map { phone(study) }
        assertTrue("the burst must exceed the address quota of $capacity", phones.size > capacity)
        val (statuses, inFlight) = uploadAllAtOnce(phones, "198.51.100.210")
        assertEquals("every enrolled phone gets its normal response", List(phones.size) { 200 }, statuses)
        assertEquals("all uploads were in flight together", phones.size, inFlight)
    }

    /** Hold every real HTTP request immediately after the limiter, before any can finish authentication. */
    private fun uploadAllAtOnce(phones: List<UsageEnrollmentFixture>, ip: String): Pair<List<Int>, Int> {
        val executor = Executors.newFixedThreadPool(phones.size)
        val arrived = CountDownLatch(phones.size)
        val release = CountDownLatch(1)
        val filters = testServer.context.getBean(SecurityFilterChain::class.java).filters as MutableList<Filter>
        val limiter = filters.indexOfFirst { it is PreAuthenticationRateLimitFilter }
        assertTrue("the real chain contains its pre-authentication limiter", limiter >= 0)
        val gate = Filter { request, response, chain ->
            if ((request as HttpServletRequest).getHeader("X-Forwarded-For") == ip) {
                arrived.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "upload release barrier timed out" }
            }
            chain.doFilter(request, response)
        }
        filters.add(limiter + 1, gate)
        try {
            val uploads = phones.map { phone -> executor.submit(Callable { batteryUpload(phone, ip).statusCode() }) }
            assertTrue("all 25 requests passed the limiter before any authentication completed", arrived.await(30, TimeUnit.SECONDS))
            val inFlight = uploads.count { !it.isDone }
            release.countDown()
            return uploads.map { it.get(120, TimeUnit.SECONDS) } to inFlight
        } finally {
            release.countDown()
            filters.remove(gate)
            executor.shutdownNow()
        }
    }

    @Test
    fun liveDeviceAndResearcherKeysCannotExemptEnrollmentCodeGuesses() {
        val study = batteryStudy("API keys do not authenticate enrollment codes")
        val phone = phone(study)
        val researcher = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE) { activeFixtureToken(testUser1) }
            .create(ApiKeyApi::class.java).createApiKey(study, ApiKeyCreateRequest(name = "bootstrap boundary"))
        val foreignStudy = clientUser1.studyApi.createStudy(Study(title = "foreign enrollment", contact = "test@openlattice.com"))
        val targets = listOf(study, foreignStudy).flatMap { target ->
            val base = "${StudyApi.V4_BASE}/$target${StudyApi.PARTICIPANT_PATH}/${phone.replay.participantId}"
            listOf("GET" to base + StudyApi.ENROLLMENT_PREVIEW_PATH, "POST" to base + StudyApi.ENROLL_PATH)
        }
        listOf(phone.replay.proposedApiKey, researcher.rawKey).forEachIndexed { keyIndex, key ->
            targets.forEachIndexed { routeIndex, (method, path) ->
                assertLimitedLikeBefore("$keyIndex $method $path code guesses", 401) {
                    val request = HttpRequest.newBuilder(URI("$origin$path"))
                        .header("X-Forwarded-For", "198.51.100.${220 + keyIndex * targets.size + routeIndex}")
                        .header(API_KEY_HEADER, key)
                        .header(ChronicleHeaders.ENROLLMENT_CODE_HEADER, "wrong-enrollment-code")
                        .method(method, HttpRequest.BodyPublishers.noBody()).build()
                    http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
                }
            }
        }
    }

    @Test
    fun liveDeviceKeyCannotExemptReviewerSecretGuesses() {
        val study = batteryStudy("API keys do not authenticate reviewer secrets")
        val phone = phone(study)
        // The shared fixture disables reviewer enrollment; enable its real credential filter for this request chain.
        val reviewer = MobileApiSignatureFilter(hazelcastInstance, "reviewer-test-signing-secret",
            reviewerEnrollmentSecret = "reviewer-test-secret", reviewerStudyId = study)
        val chain = testServer.context.getBean(SecurityFilterChain::class.java)
        val enabled = DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, chain.filters.map {
            if (it is MobileApiSignatureFilter) reviewer else it
        })
        assertLimitedLikeBefore("reviewer secret guesses", 401) {
            val request = MockHttpServletRequest("POST", ReviewerEnrollmentController.PATH).apply {
                remoteAddr = "198.51.100.228"
                addHeader(API_KEY_HEADER, phone.replay.proposedApiKey)
                addHeader(ChronicleHeaders.REVIEWER_SECRET_HEADER, "wrong-reviewer-secret")
            }
            val response = MockHttpServletResponse()
            FilterChainProxy(enabled).doFilter(request, response, MockFilterChain())
            response.status
        }
    }

    @Test
    fun unknownKeysStayLimitedWhileValidKeysAndJwtsPassAnExhaustedAddress() {
        val study = batteryStudy("unknown keys from one address")
        val phone = phone(study)
        val ip = "198.51.100.211"
        val prefixGuess = phone.replay.proposedApiKey.dropLast(1) + if (phone.replay.proposedApiKey.last() == 'A') "B" else "A"
        var guesses = 0
        assertLimitedLikeBefore("unknown keys, including a live prefix with wrong material", 401) {
            batteryUpload(phone, ip, if (guesses++ % 2 == 0) unknownKey() else prefixGuess).statusCode()
        }
        assertEquals("malformed key", 429, batteryUpload(phone, ip, "not-a-key").statusCode())
        val token = activeFixtureToken(testUser1)
        repeat(capacity + 1) { index ->
            assertEquals("valid device key $index", 200, batteryUpload(phone, ip).statusCode())
            val session = http.send(HttpRequest.newBuilder(URI("$origin${ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH}"))
                .header("X-Forwarded-For", ip).header("Authorization", "Bearer $token").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals("valid JWT $index: ${session.body()}", 200, session.statusCode())
        }
    }

    @Test
    fun revokedErasedAndCancelledKeysStayLimited() {
        val study = batteryStudy("dead keys from one address")
        val revoked = phone(study)
        val erased = phone(study)
        val cancelled = phone(study, acknowledged = false)
        hds.connection.use { connection ->
            connection.prepareStatement("UPDATE api_keys SET revoked = true WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, study)
                it.setString(2, revoked.replay.participantId)
                assertEquals(1, it.executeUpdate())
            }
            // Erasure's end state: the key row is gone and only its hash tombstone remains.
            connection.autoCommit = false
            connection.prepareStatement("""
                INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at)
                SELECT key_hash, 'WITHDRAW_AND_ERASE', now() + interval '30 days' FROM api_keys
                WHERE study_id = ? AND participant_id = ?
            """.trimIndent()).use {
                it.setObject(1, study)
                it.setString(2, erased.replay.participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM api_keys WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, study)
                it.setString(2, erased.replay.participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.commit()
            connection.autoCommit = true
        }
        val cancel = http.send(HttpRequest.newBuilder(URI("$origin/chronicle/v3/study/$study/participant/" +
            "${cancelled.replay.participantId}/pending-enrollment/cancel"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(cancel.body(), 204, cancel.statusCode())

        assertLimitedLikeBefore("revoked key", 401) { batteryUpload(revoked, "198.51.100.212").statusCode() }
        assertLimitedLikeBefore("erased key", 401) { batteryUpload(erased, "198.51.100.213").statusCode() }
        assertLimitedLikeBefore("cancelled key", 410) { batteryUpload(cancelled, "198.51.100.214").statusCode() }
        listOf(revoked, erased, cancelled).forEach { assertFalse(apiKeys.isLiveApiKey(it.replay.proposedApiKey)) }
    }

    @Test
    fun validKeysDoNotSpendTheBudgetAnonymousRequestsThenHit() {
        val study = batteryStudy("valid keys and anonymous requests from one address")
        val phone = phone(study)
        val neighbour = phone(study)
        val ip = "198.51.100.215"
        repeat(capacity + 5) { index ->
            // A valid key refused by its route (another participant's path) is an application denial, not a guess.
            assertEquals("valid key on a foreign path $index", 403,
                batteryUpload(phone, ip, pathOwner = neighbour).statusCode())
            assertEquals("valid key upload $index", 200, batteryUpload(neighbour, ip).statusCode())
        }
        assertLimitedLikeBefore("anonymous uploads", 401) { batteryUpload(phone, ip, apiKey = null).statusCode() }
    }

    @Test
    fun liveKeyCheckIsReadOnly() {
        val study = batteryStudy("read-only live key check")
        val phone = phone(study)
        fun usage(): List<Any?> = hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT usage_count, last_used_at, expires_at FROM api_keys WHERE study_id = ? AND participant_id = ?",
            ).use {
                it.setObject(1, study)
                it.setString(2, phone.replay.participantId)
                it.executeQuery().use { rows -> check(rows.next()); listOf(rows.getLong(1), rows.getObject(2), rows.getObject(3)) }
            }
        }
        val before = usage()
        repeat(3) { assertTrue(apiKeys.isLiveApiKey(phone.replay.proposedApiKey)) }
        assertEquals("the limiter's check writes no usage or expiry", before, usage())
        assertFalse(apiKeys.isLiveApiKey(unknownKey()))
        assertFalse(apiKeys.isLiveApiKey("not-a-key"))
    }

    /** A survey session opened through the real code exchange, as the reminder link does; returns the raw cookie. */
    private fun surveySession(phone: UsageEnrollmentFixture): String {
        val code = RLSRequestContext.withSystemContext {
            forms.createAccessCode(phone.replay.studyId, phone.replay.participantId, ParticipantFormKind.APP_USAGE,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "pre-auth limit")
        }
        return checkNotNull(forms.exchangeAccessCode(code.accessCode)).rawSessionToken
    }

    private fun sessionGet(path: String, ip: String, session: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("$origin$path"))
            .header("X-Forwarded-For", ip)
            .header("Cookie", "${ParticipantFormAccessFilter.SESSION_COOKIE}=$session").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun updateSession(session: String, assignment: String) = hds.connection.use { connection ->
        connection.prepareStatement("UPDATE participant_form_sessions SET $assignment WHERE session_hash = ?").use {
            it.setBytes(1, ParticipantSessionSecrets.sha256(session))
            assertEquals(1, it.executeUpdate())
        }
    }

    /** Expired survey sessions reopening together behind one NAT must not lock out a neighbour's live session. */
    @Test
    fun liveSurveySessionsPassAnExhaustedAddressWhileDeadSessionsStayLimited() {
        val phone = phone(batteryStudy("survey sessions behind one address"))
        val path = "${SurveyApi.BASE}/${phone.replay.studyId}${SurveyApi.PARTICIPANT_PATH}" +
            "/${phone.replay.participantId}${SurveyApi.APP_USAGE_DAYS_PATH}"
        assertLiveSessionsPassAnExhaustedAddress(phone) { session ->
            sessionGet(path, "198.51.100.216", session)
        }
    }

    @Test
    fun liveSessionRecoveryPassesAnExhaustedAddressWhileDeadSessionsStayLimited() {
        val phone = phone(batteryStudy("session recovery behind one address"))
        assertLiveSessionsPassAnExhaustedAddress(phone) { session ->
            sessionGet(ParticipantFormAccessFilter.SESSION_RECOVERY_PATH, "198.51.100.217", session)
        }
        // Recovery's own per-IP endpoint limit does not count a live session either.
        val writeCapacity = testServer.context.getBean(RateLimitConfiguration::class.java)
            .let { (it.writeRequestsPerMinute * it.burstCapacityMultiplier).toInt() }
        val live = surveySession(phone)
        repeat(writeCapacity + 1) { index ->
            val response = sessionGet(ParticipantFormAccessFilter.SESSION_RECOVERY_PATH, "198.51.100.218", live)
            assertEquals("live recovery $index past the endpoint limit: ${response.body()}", 200, response.statusCode())
        }
    }

    private fun assertLiveSessionsPassAnExhaustedAddress(
        phone: UsageEnrollmentFixture,
        request: (String) -> HttpResponse<String>,
    ) {
        val (live, expired, revoked) = List(3) { surveySession(phone) }
        updateSession(expired, "idle_expires_at = now() - interval '1 minute'")
        updateSession(revoked, "revoked_at = now()")
        assertLimitedLikeBefore("unknown sessions", 401) {
            request(ParticipantSessionSecrets.generateToken()).statusCode()
        }
        repeat(capacity + 1) { index ->
            val response = request(live)
            assertEquals("live session $index: ${response.body()}", 200, response.statusCode())
        }
        assertEquals("expired session", 429, request(expired).statusCode())
        assertEquals("revoked session", 429, request(revoked).statusCode())
        assertEquals("garbage cookie", 429, request("not-a-session").statusCode())
    }

    @Test
    fun surveySessionCheckIsReadOnly() {
        val phone = phone(batteryStudy("read-only survey session check"))
        val session = surveySession(phone)
        fun clock(): List<Any?> = hds.connection.use { connection ->
            connection.prepareStatement("SELECT idle_expires_at, last_seen_at FROM participant_form_sessions WHERE session_hash = ?").use {
                it.setBytes(1, ParticipantSessionSecrets.sha256(session))
                it.executeQuery().use { rows -> check(rows.next()); listOf(rows.getObject(1), rows.getObject(2)) }
            }
        }
        val before = clock()
        val connection = spy(hds.connection)
        val pool = mock<HikariDataSource>()
        val storage = mock<StorageResolver>()
        val audit = mock<AuditingManager>()
        whenever(pool.connection).thenReturn(connection)
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        val checkedForms = participantFormAccessServiceForTest(storage, auditingManager = audit)
        assertTrue(checkedForms.isAdmittedSession(session, null, false))
        verify(connection, never()).commit()
        val sql = argumentCaptor<String>()
        verify(connection, atLeastOnce()).prepareStatement(sql.capture())
        assertTrue("admission prepares only reads", sql.allValues.all { it.trimStart().startsWith("SELECT") })
        val order = inOrder(connection)
        order.verify(connection).rollback()
        order.verify(connection).close()
        verifyNoInteractions(audit)
        repeat(3) { assertTrue(forms.isAdmittedSession(session, null, false)) }
        assertEquals("the limiter's check does not extend the idle clock", before, clock())
        assertFalse(forms.isAdmittedSession(ParticipantSessionSecrets.generateToken(), null, false))
        assertFalse("a mutation needs its CSRF token", forms.isAdmittedSession(session, null, true))
    }
}
