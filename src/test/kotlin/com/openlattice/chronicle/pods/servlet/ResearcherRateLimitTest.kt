package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.RateLimitTestClock
import com.openlattice.chronicle.awaitSessionsBlockedBy
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.services.participantaccess.lockDeviceEvidence
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import com.openlattice.chronicle.study.Study
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpHeaders

/** Real HTTP exercises credential resolution, both quotas, and the session/participants handlers. */
class ResearcherRateLimitTest : ChronicleServerTests() {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val origin = "http://localhost:$testHttpPort"
    private val sessionPath = ChronicleCookieOrBearerTokenResolver.AUTH_SESSION_PATH
    private val loginPath = sessionPath.substringBeforeLast('/') + "/dashboard-login"
    private val csrfToken = "burst-csrf"
    private val capacity: Int get() = testServer.context.getBean(RateLimitConfiguration::class.java)
        .let { (it.authRequestsPerMinute * it.burstCapacityMultiplier).toInt() }

    private fun send(path: String, ip: String, credential: Pair<String, String>? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$origin$path")).header("X-Forwarded-For", ip)
            .header(ChronicleCookieOrBearerTokenResolver.CSRF_HEADER, csrfToken)
        credential?.let { request.header(it.first, it.second) }
        if (path == loginPath) {
            request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"password":"deliberately-wrong-drill-password"}"""))
        } else {
            request.GET()
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun mixedAuthenticatedBurstBypassesBothQuotasEvenAfterWrongPasswordsExhaustTheIp() {
        val study = clientUser1.studyApi.createStudy(Study(title = "shared dashboard burst", contact = "test@openlattice.com"))
        val token = activeFixtureToken(testUser1)
        val cookie = HttpHeaders.COOKIE to
            "${AuthTokenController.AUTH_COOKIE_NAME}=$token; ${AuthTokenController.CSRF_COOKIE_NAME}=$csrfToken"
        val ip = "203.0.113.159"
        val participantsPath = "/chronicle/v3/study/$study/participants"

        fun burst(credential: Pair<String, String>) {
            repeat(200) { index ->
                val path = if (index % 2 == 0) sessionPath else participantsPath
                val response = send(path, ip, credential)
                assertEquals("mixed request $index $path: ${response.body()}", 200, response.statusCode())
            }
        }

        assertTrue(ObjectMappers.getJsonMapper().readTree(send(sessionPath, ip, cookie).body()).get("authenticated").asBoolean())
        burst(cookie)
        val wrongPasswords = (0..capacity).map { send(loginPath, ip).statusCode() }
        assertEquals("Authenticated requests must not spend the first login attempt", 401, wrongPasswords.first())
        assertTrue("Wrong-password attempts must still reach 429", 429 in wrongPasswords)
        burst(cookie)
        burst("Authorization" to "Bearer $token")
    }

    @Test
    fun invalidExpiredAndRevokedCookiesStillReachTheAuthenticationLimit() {
        val blocklist = testServer.context.getBean(com.openlattice.chronicle.authorization.JwtBlocklist::class.java)
        val revoked = activeFixtureToken(testUser2)
        blocklist.blockTokenByValue(revoked, java.time.Instant.now().plusSeconds(60))
        val config = testServer.context.getBean(ChronicleAuthConfiguration::class.java).configurations.first()
        val expired = com.auth0.jwt.JWT.create().withSubject(testUser2.id)
            .withIssuer(config.issuer).withAudience(config.audience)
            .withIssuedAt(java.time.Instant.EPOCH).withExpiresAt(java.time.Instant.EPOCH.plusSeconds(1))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(config.secret))
        listOf("bad-cookie", expired, revoked).forEachIndexed { index, token ->
            val statuses = (0..capacity).map {
                send(sessionPath, "203.0.113.${160 + index}", HttpHeaders.COOKIE to "${AuthTokenController.AUTH_COOKIE_NAME}=$token")
                    .statusCode()
            }
            assertEquals(200, statuses.first())
            assertEquals(429, statuses.last())
        }
    }

    private fun batteryUpload(enrollment: UsageEnrollmentFixture, ip: String, apiKey: String = enrollment.replay.proposedApiKey) =
        http.send(HttpRequest.newBuilder(URI("$origin${StudyApi.V4_BASE}/${enrollment.replay.studyId}${StudyApi.PARTICIPANT_PATH}" +
            "/${enrollment.replay.participantId}${StudyApi.ANDROID_PATH}${StudyApi.BATTERY_PATH}"))
            .header("X-Forwarded-For", ip)
            .header(API_KEY_HEADER, apiKey)
            .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, enrollment.replay.device.randomDeviceId)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                [{"id":"${UUID.randomUUID()}","timestamp":"2026-10-29T12:00:00Z","timezone":"America/Santiago",
                  "levelPercent":50,"chargingState":"CHARGING","plugType":"AC","temperatureDeciC":250,
                  "voltageMillivolts":4000,"health":"GOOD"}]
            """.trimIndent())).build(), HttpResponse.BodyHandlers.ofString())

    /** Keep Bucket4j's real Hazelcast storage and refund logic, with no token refill during this test. */
    private fun withFrozenQuotaClock(run: () -> Unit) {
        testServer.context.getBean(RateLimitTestClock::class.java).whileFrozen(run)
    }

    /**
     * 25 phones behind one NAT address upload concurrently, many times the address's pre-authentication
     * quota in total, while one phone's write is held mid-transaction and then killed. A live device key is never
     * counted, so every valid upload succeeds and the held one spends nothing; keys the server does not know
     * still exhaust the quota exactly.
     */
    @Test
    fun twentyFivePhonesBehindOneAddressAreNotThrottledWhileUnknownKeysAre() {
        val study = clientUser1.studyApi.createStudy(Study(title = "one NAT, 25 phones", contact = "test@openlattice.com"))
        prepareUsageFixture(clientUser1, study)
        val settings = clientUser1.studyApi.getStudySetting(study, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        clientUser1.studyApi.updateStudySettings(study, StudySettingType.DataCollection, settings.copy(modules = settings.modules +
            (CollectionModuleId.BATTERY_TELEMETRY to CollectionDefaults.moduleSetting(CollectionModuleId.BATTERY_TELEMETRY, enabled = true))))
        val phones = (1..25).map {
            val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
            clientUser1.studyApi.registerParticipant(study, participant)
            enrollUsageFixture(study, participant.participantId, TestDataFactory.androidDevice())
        }
        withFrozenQuotaClock {
            assertKilledHeldUploadSpendsNothing(phones)
        }
    }

    /** A killed write (503) kept its token under the old reserve-and-refund limiter; a completed one never told them apart. */
    private fun assertKilledHeldUploadSpendsNothing(phones: List<UsageEnrollmentFixture>) {
        val ip = "203.0.113.171"
        val unknownKey = UUID.randomUUID().toString().replace("-", "").let { "ck_${it.take(8)}_$it" }
        val rounds = capacity / phones.size + 2
        val concurrentPhones = (1..rounds).flatMap { phones.drop(1) }
        val executor = Executors.newFixedThreadPool(capacity)
        try {
            val held = phones.first().replay
            val (others, heldStatus) = hds.connection.use { barrier ->
                barrier.autoCommit = false
                try {
                    val deviceId = RandomDeviceIdUtils.deriveRandomDeviceId(held.studyId, held.participantId, held.device.randomDeviceId)
                    lockDeviceEvidence(barrier, deviceId)
                    val heldUpload = submitBatteryUpload(executor, phones.first(), ip)
                    awaitSessionsBlockedBy(barrier, 1)
                    val others = concurrentUploads(executor, concurrentPhones, ip)
                    assertFalse("the held write is still in flight", heldUpload.isDone)
                    // Spend one token while the valid request is held. Its completion or failure must not return it.
                    assertEquals(401, batteryUpload(phones.last(), ip, unknownKey).statusCode())
                    terminateHeldUpload(barrier)
                    barrier.commit()
                    others to heldUpload.get(60, TimeUnit.SECONDS)
                } finally {
                    barrier.rollback()
                }
            }
            assertTrue("uploads exceed the quota of $capacity", others.size + 1 > capacity)
            assertEquals("every valid upload is admitted", listOf(200), others.distinct())
            assertEquals("the killed held upload", 503, heldStatus)
        } finally {
            executor.shutdownNow()
        }
        val unknown = (0 until capacity).map { batteryUpload(phones.last(), ip, unknownKey).statusCode() }
        assertEquals(401, unknown.first())
        assertEquals("one attempt was spent while the valid request was held", capacity - 1, unknown.count { it == 401 })
        assertEquals(429, unknown.last())
    }
    private fun submitBatteryUpload(executor: java.util.concurrent.ExecutorService, phone: UsageEnrollmentFixture, ip: String) =
        executor.submit(Callable { batteryUpload(phone, ip).statusCode() })

    private fun concurrentUploads(executor: java.util.concurrent.ExecutorService, phones: List<UsageEnrollmentFixture>, ip: String) =
        phones.map { phone -> submitBatteryUpload(executor, phone, ip) }
            .map { it.get(120, TimeUnit.SECONDS) }

    private fun terminateHeldUpload(barrier: java.sql.Connection) = barrier.prepareStatement("""
        SELECT pg_terminate_backend(waiting.pid) FROM pg_stat_activity waiting
        WHERE pg_backend_pid() = ANY(pg_blocking_pids(waiting.pid))
    """.trimIndent()).use { statement ->
        statement.executeQuery().use { rows ->
            assertTrue("one held upload was killed", rows.next() && rows.getBoolean(1))
            assertFalse("no unrelated session is killed", rows.next())
        }
    }

}
