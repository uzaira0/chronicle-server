package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.acknowledgeUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.replayUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentReplay
import com.openlattice.chronicle.UsageEnrollmentFixtures.newUsageEnrollment
import com.openlattice.chronicle.UsageEnrollmentFixtures.storedUsageTimestamps
import com.openlattice.chronicle.UsageEnrollmentFixtures.submitUsageEnrollment
import com.openlattice.chronicle.UsageEnrollmentFixtures.usageSettingsWithoutUserIdentification
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.lockDeviceEvidence
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEnrollmentCancellationTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.newJsonMapper()
    private val http = HttpClient.newHttpClient()

    private fun request(
        path: String,
        method: String = "GET",
        body: String = "",
        user: Principal = testUser1,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3$path"))
            .header("Authorization", "Bearer ${activeFixtureToken(user)}")
            .header("Content-Type", "application/json")
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun pendingEnrollments(studyId: UUID): List<String> {
        val response = request("/study/$studyId/pending-enrollments")
        assertEquals(response.body(), 200, response.statusCode())
        val participants = mapper.readTree(response.body())
        assertTrue(response.body(), participants.isArray)
        return participants.map { it.asText() }
    }

    @Test
    fun cancellationUnblocksSettingsWithoutErasingDataOrChangingEnrollment() {
        val api = clientUser1.studyApi
        val studyId = api.createStudy(Study(title = "Pending enrollment cancellation", contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        val participantId = participant.participantId
        api.registerParticipant(studyId, participant)
        val device = TestDataFactory.androidDevice()
        val mobile = enrollUsageFixture(studyId, participantId, device).mobileApi
        val template = TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1).single() as ChronicleUsageEvent
        val event = template.copy(timestamp = template.timestamp.withNano(0))
        val data = ChronicleData(listOf(event))
        assertEquals(1, mobile.uploadAndroidUsageEventDataV4(studyId, participantId, device.randomDeviceId, data).acceptedIds.size)
        val accessService = testServer.context.getBean(ParticipantFormAccessService::class.java)
        val code = RLSRequestContext.withContext(RLSConnectionContext(testUser1.id, setOf(studyId), isAdmin = false)) {
            accessService.createAccessCode(studyId, participantId, ParticipantFormKind.ENROLLMENT,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "cancellation-test").accessCode
        }
        val pendingDevice = TestDataFactory.androidDevice()
        val pendingEnrollment = beginUsageEnrollmentFixture(studyId, participantId, pendingDevice, accessCode = code)
        val anotherPendingDevice = TestDataFactory.androidDevice()
        beginUsageEnrollmentFixture(studyId, participantId, anotherPendingDevice)
        replayUsageEnrollmentFixture(pendingEnrollment)
        val base = "/study/$studyId"
        val cancel = "$base/participant/$participantId/pending-enrollment/cancel"
        val settingsPath = "$base/settings/type/DataCollection"
        val revised = usageSettingsWithoutUserIdentification(api, studyId)
        val revision = request(settingsPath).headers().firstValue("etag").get()
        fun patch(): HttpResponse<String> = request(
            settingsPath,
            method = "PATCH",
            body = mapper.writeValueAsString(revised),
            headers = mapOf("If-Match" to revision),
        )

        val frozen = patch()
        assertEquals(409, frozen.statusCode())
        val problem = mapper.readTree(frozen.body())
        assertEquals(listOf(participantId), problem.path("blockingParticipantIds").map { it.asText() })
        assertTrue(problem.path("message").asText().contains(participantId))
        assertTrue(problem.path("message").asText().contains("cancel", ignoreCase = true))
        assertEquals(listOf(participantId), pendingEnrollments(studyId))
        assertEquals(403, request("$base/pending-enrollments", user = testUser2).statusCode())
        assertEquals(403, request(cancel, "POST", user = testUser2).statusCode())
        assertEquals(204, request(cancel, "POST").statusCode())
        assertEquals(204, request(cancel, "POST").statusCode())
        assertEquals(200, patch().statusCode())
        assertEquals(emptyList<String>(), pendingEnrollments(studyId))
        assertEquals(ParticipationStatus.ENROLLED, api.getStudyParticipants(studyId).single().participationStatus)
        val replayRejected = assertThrows(RhizomeRetrofitCallException::class.java) {
            replayUsageEnrollmentFixture(pendingEnrollment)
        }
        assertCancelled(replayRejected)
        val publicApi = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE).create(StudyApi::class.java)
        val revoked = assertThrows(RhizomeRetrofitCallException::class.java) {
            publicApi.getEnrollmentPreviewV4(studyId, participantId, code)
        }
        assertEquals(401, revoked.code)
        assertTrue(accessService.resolveEnrollmentAccessCodeForRequest(code, studyId, participantId) == null)
        assertEquals(setOf(event.timestamp.toInstant()), storedUsageTimestamps(studyId, participantId))
        assertPreservedDataAndEnrollmentEvidence(studyId, participantId)
    }

    /** The phone's uncertain cancel: the attempt was bound, its key never installed. */
    @Test
    fun cancellationBeforeKeyInstallationPreventsLateInstall() {
        val (studyId, participantId) = registeredUsageParticipant("Cancel before key installation")
        val replay = newUsageEnrollment(studyId, participantId, TestDataFactory.androidDevice()).first
        bindWithoutInstallingKey(replay)

        // Mere absence is never acknowledged: another device or an attempt never bound stays a permanent refusal.
        assertEquals(410, cancelAttempt(replay, sourceRandomDeviceId = "another-installation").statusCode())
        assertEquals(410, cancelAttempt(replay.copy(enrollmentAttemptId = UUID.randomUUID().toString())).statusCode())

        val revocations = auditEventCount(studyId, AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE)
        val receipt = cancelAttempt(replay)
        assertEquals(receipt.body(), 202, receipt.statusCode())
        assertEquals(replay.enrollmentAttemptId, mapper.readTree(receipt.body()).path("requestId").asText())
        assertEquals(revocations + 1, auditEventCount(studyId, AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE))
        val lateInstall = assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(replay) }
        assertCancelled(lateInstall)
        assertEquals(0, keysWithMaterialOf(replay))

        // The phone that lost the first answer replays the exact request and gets the same receipt.
        val replayed = cancelAttempt(replay)
        assertEquals(replayed.body(), 202, replayed.statusCode())
        assertEquals(replay.enrollmentAttemptId, mapper.readTree(replayed.body()).path("requestId").asText())
        // Only the first revocation is an audited event; the replay repeats its receipt, not its effect.
        assertEquals(revocations + 1, auditEventCount(studyId, AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE))
        assertEquals(ParticipationStatus.ENROLLED, clientUser1.studyApi.getStudyParticipants(studyId).single().participationStatus)
    }

    /** The phone's cancel of an attempt whose key installed revokes the key and binding; it never withdraws. */
    @Test
    fun phoneCancelAfterTheKeyInstalledRevokesTheAttemptAndNeverWithdraws() {
        val (studyId, participantId) = registeredUsageParticipant("Install before cancellation")
        val enrollment = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice())
        assertEquals(1, keysWithMaterialOf(enrollment.replay))

        val receipt = cancelAttempt(enrollment.replay)
        assertEquals(receipt.body(), 202, receipt.statusCode())
        assertEquals(enrollment.replay.enrollmentAttemptId, mapper.readTree(receipt.body()).path("requestId").asText())
        assertEquals(receipt.statusCode(), cancelAttempt(enrollment.replay).statusCode())

        assertRefusedAsCancelled(enrollment)
        assertNothingWithdrawn(studyId)
    }

    /** The dashboard's cancel revokes an installed key too, so the phone's next call is refused as cancelled. */
    @Test
    fun dashboardCancelAfterTheKeyInstalledRefusesThePhoneAsCancelled() {
        val (studyId, participantId) = registeredUsageParticipant("Dashboard cancel after key installation")
        val enrollment = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice())

        assertEquals(204, request("/study/$studyId/participant/$participantId/pending-enrollment/cancel", "POST").statusCode())

        assertRefusedAsCancelled(enrollment)
        // The phone that never saw its enroll answer ends the attempt with the cancel receipt.
        val receipt = cancelAttempt(enrollment.replay)
        assertEquals(receipt.body(), 202, receipt.statusCode())
        assertEquals(enrollment.replay.enrollmentAttemptId, mapper.readTree(receipt.body()).path("requestId").asText())
        assertNothingWithdrawn(studyId)
    }

    /**
     * Pilot report: a reinstall loses the pending attempt's key. Attempts are independent, so the new link enrolls
     * either way; the cancel clears the pending list (otherwise retained 24 hours) and revokes the lost installation's key.
     */
    @Test
    fun aReinstalledPhoneEnrollsWithANewLinkAfterTheDashboardCancelsItsPendingAttempt() {
        val (studyId, participantId) = registeredUsageParticipant("Reinstall during a pending enrollment")
        val base = "/study/$studyId"
        val lost = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice())
        assertEquals(listOf(participantId), pendingEnrollments(studyId))

        assertEquals(204, request("$base/participant/$participantId/pending-enrollment/cancel", "POST").statusCode())
        assertEquals(emptyList<String>(), pendingEnrollments(studyId))
        assertRefusedAsCancelled(lost)
        val invitation = request("$base/participant/$participantId/form-access-codes", "POST",
            body = mapper.writeValueAsString(mapOf("formKind" to ParticipantFormKind.ENROLLMENT)))
        assertEquals(invitation.body(), 200, invitation.statusCode())
        val code = mapper.readTree(invitation.body()).path("accessCode").asText()
        // The reinstalled app is a new installation: a new random device id and a new key.
        val reinstalled = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice(), accessCode = code)
        assertEquals(listOf(participantId), pendingEnrollments(studyId))
        acknowledgeUsageFixture(reinstalled)

        assertEquals(emptyList<String>(), pendingEnrollments(studyId))
        assertEquals(1, liveKeysWithMaterialOf(reinstalled.replay))
        val events = TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1)
        assertEquals(1, reinstalled.mobileApi.uploadAndroidUsageEventDataV4(
            studyId, participantId, reinstalled.replay.device.randomDeviceId, events).acceptedIds.size)
        assertRefusedAsCancelled(lost)
        assertNothingWithdrawn(studyId)
    }

    /** An enrollment the phone acknowledged is real: it ends only through withdrawal, never through a cancel. */
    @Test
    fun cancelAfterTheEnrollmentAcknowledgmentIsRefused() {
        val (studyId, participantId) = registeredUsageParticipant("Cancel after acknowledgment")
        val enrollment = enrollUsageFixture(studyId, participantId, TestDataFactory.androidDevice())

        assertEquals(204, request("/study/$studyId/participant/$participantId/pending-enrollment/cancel", "POST").statusCode())
        val refused = cancelAttempt(enrollment.replay)
        assertEquals(refused.body(), 409, refused.statusCode())
        assertEquals(1, liveKeysWithMaterialOf(enrollment.replay))
        assertNothingWithdrawn(studyId)
    }

    /** phone2 OwnerTodo 1: a 410 cancel is final on the phone, so the enroll still in flight must never bind. */
    @Test
    fun aCancelledUnboundAttemptRefusesItsDelayedEnroll() {
        val (studyId, participantId) = registeredUsageParticipant("Cancel before the enroll arrives")
        val replay = newUsageEnrollment(studyId, participantId, TestDataFactory.androidDevice()).first

        assertEquals(410, cancelAttempt(replay).statusCode())
        val delayed = assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(replay) }
        assertCancelled(delayed)
        assertEquals(0, keysWithMaterialOf(replay))

        // The refused attempt consumed nothing: a new attempt with the same invitation enrolls.
        val retry = newUsageEnrollment(studyId, participantId, replay.device, accessCode = replay.enrollmentCode).first
        assertEquals(retry.proposedApiKey, submitUsageEnrollment(retry).apiKey)
    }

    /** The bind commits, then a dashboard cancel takes the device lock before the key installs. */
    @Test
    fun anEnrollThatLosesTheDeviceLockToADashboardCancelIsAnsweredAsCancelled() {
        val (studyId, participantId) = registeredUsageParticipant("Cancel between bind and key installation")
        val replay = newUsageEnrollment(studyId, participantId, TestDataFactory.androidDevice()).first
        val executor = Executors.newSingleThreadExecutor()
        try {
            hds.connection.use { barrier ->
                barrier.autoCommit = false
                // Device registration runs after the bind commits and before the key installs: holding its table
                // parks the enroll there while the cancel runs.
                barrier.createStatement().use { it.execute("LOCK TABLE devices IN EXCLUSIVE MODE") }
                val enroll = executor.submit<RhizomeRetrofitCallException> {
                    assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(replay) }
                }
                awaitWaiters(barrier, 1)
                assertEquals(204, request("/study/$studyId/participant/$participantId/pending-enrollment/cancel", "POST").statusCode())
                barrier.rollback()
                assertCancelled(enroll.get(15, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
        assertEquals(0, keysWithMaterialOf(replay))
        assertNothingWithdrawn(studyId)
    }

    /**
     * Every route the revoked key reaches (status poll, ack, upload, Leave study) answers 410 with the cancel
     * code. A Leave study names a request id of its own, never the attempt's.
     */
    private fun assertRefusedAsCancelled(enrollment: UsageEnrollmentFixture) {
        val replay = enrollment.replay
        val leave = cancelAttempt(replay.copy(enrollmentAttemptId = UUID.randomUUID().toString()))
        assertEquals(leave.body(), 410, leave.statusCode())
        assertEquals(EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE, mapper.readTree(leave.body()).path("code").asText())
        assertCancelled(assertThrows(RhizomeRetrofitCallException::class.java) { acknowledgeUsageFixture(enrollment) })
        val status = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${StudyApi.V4_BASE}/${replay.studyId}" +
                "${StudyApi.PARTICIPANT_PATH}/${replay.participantId}${StudyApi.STATUS_PATH}"))
                .header(ChronicleHeaders.API_KEY_HEADER, replay.proposedApiKey)
                .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, replay.device.randomDeviceId)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(status.body(), 410, status.statusCode())
        assertEquals(EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE, mapper.readTree(status.body()).path("code").asText())
        val events = TestDataFactory.chronicleUsageEvents(replay.studyId, replay.participantId, count = 1)
        assertCancelled(assertThrows(RhizomeRetrofitCallException::class.java) {
            enrollment.mobileApi.uploadAndroidUsageEventDataV4(
                replay.studyId, replay.participantId, replay.device.randomDeviceId, events)
        })
        assertEquals(0, liveKeysWithMaterialOf(enrollment.replay))
        assertCancelled(assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(enrollment.replay) })
    }

    /** The enroll of a cancelled attempt is answered as cancelled, so the phone shows that state rather than a failure. */
    private fun assertCancelled(refused: RhizomeRetrofitCallException) {
        assertEquals(refused.body, 410, refused.code)
        assertEquals(EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE, mapper.readTree(refused.body).path("code").asText())
    }

    private fun assertNothingWithdrawn(studyId: UUID) {
        assertEquals(ParticipationStatus.ENROLLED, clientUser1.studyApi.getStudyParticipants(studyId).single().participationStatus)
        listOf("mobile_withdrawal_requests", "data_deletion_operations").forEach { table ->
            assertEquals(table, 0, studyRowCount(table, studyId))
        }
    }

    private fun studyRowCount(table: String, studyId: UUID): Int = hds.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id = ?").use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun liveKeysWithMaterialOf(replay: UsageEnrollmentReplay): Int = hds.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM api_keys WHERE key_hash = ? AND NOT revoked").use { statement ->
            statement.setString(1, ApiKeyService.proposedMobileApiKeyHash(replay.proposedApiKey))
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun registeredUsageParticipant(title: String): Pair<UUID, String> {
        val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        return studyId to participant.participantId
    }

    /** The invitation binds the attempt and commits; the key insert then fails, as a crash between the two would. */
    private fun bindWithoutInstallingKey(replay: UsageEnrollmentReplay) {
        val keyHash = checkNotNull(ApiKeyService.proposedMobileApiKeyHash(replay.proposedApiKey))
        val fault = "fail_key_install_${UUID.randomUUID().toString().replace("-", "")}"
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION $fault() RETURNS trigger LANGUAGE plpgsql AS " +
                    "\$\$ BEGIN RAISE EXCEPTION 'injected key installation failure'; END \$\$")
                statement.execute("CREATE TRIGGER $fault BEFORE INSERT ON api_keys FOR EACH ROW " +
                    "WHEN (NEW.key_hash = '$keyHash') EXECUTE FUNCTION $fault()")
            }
        }
        try {
            assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(replay) }
        } finally {
            hds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER $fault ON api_keys")
                    statement.execute("DROP FUNCTION $fault()")
                }
            }
        }
    }

    private fun cancelAttempt(
        replay: UsageEnrollmentReplay,
        sourceRandomDeviceId: String = replay.device.randomDeviceId,
    ): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH}"))
            .header(ChronicleHeaders.API_KEY_HEADER, replay.proposedApiKey)
            .header(ChronicleHeaders.WITHDRAWAL_REQUEST_ID_HEADER, replay.enrollmentAttemptId)
            .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, sourceRandomDeviceId)
            .DELETE().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun keysWithMaterialOf(replay: UsageEnrollmentReplay): Int = hds.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM api_keys WHERE key_hash = ?").use { statement ->
            statement.setString(1, ApiKeyService.proposedMobileApiKeyHash(replay.proposedApiKey))
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    @Test
    fun acknowledgmentWinningTheDeviceLockSurvivesConcurrentCancellation() =
        raceAcknowledgmentAndCancellation(RaceWinner.ACKNOWLEDGMENT)

    @Test
    fun cancellationWinningTheDeviceLockRejectsConcurrentAcknowledgment() =
        raceAcknowledgmentAndCancellation(RaceWinner.CANCELLATION)

    private enum class RaceWinner { ACKNOWLEDGMENT, CANCELLATION }

    private fun raceAcknowledgmentAndCancellation(winner: RaceWinner) {
        val api = clientUser1.studyApi
        val studyId = api.createStudy(Study(title = "Enrollment race", contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        api.registerParticipant(studyId, participant)
        val device = TestDataFactory.androidDevice()
        val enrollment = beginUsageEnrollmentFixture(studyId, participant.participantId, device)
        hds.connection.use { barrier ->
            barrier.autoCommit = false
            lockDeviceEvidence(barrier, RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participant.participantId, device.randomDeviceId))
            val executor = Executors.newFixedThreadPool(2)
            try {
                fun acknowledge() {
                    if (winner == RaceWinner.ACKNOWLEDGMENT) acknowledgeUsageFixture(enrollment) else {
                        val rejected = assertThrows(RhizomeRetrofitCallException::class.java) {
                            acknowledgeUsageFixture(enrollment)
                        }
                        assertEquals(400, rejected.code)
                    }
                }
                fun cancel() {
                    val path = "/study/$studyId/participant/${participant.participantId}/pending-enrollment/cancel"
                    assertEquals(204, request(path, "POST").statusCode())
                }
                val first = executor.submit {
                    when (winner) {
                        RaceWinner.ACKNOWLEDGMENT -> acknowledge()
                        RaceWinner.CANCELLATION -> cancel()
                    }
                }
                awaitWaiters(barrier, 1)
                val second = executor.submit {
                    when (winner) {
                        RaceWinner.ACKNOWLEDGMENT -> cancel()
                        RaceWinner.CANCELLATION -> acknowledge()
                    }
                }
                awaitWaiters(barrier, 2)
                barrier.commit()
                first.get(15, TimeUnit.SECONDS)
                second.get(15, TimeUnit.SECONDS)
            } finally {
                barrier.rollback()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        }
        assertEnrollmentRevoked(studyId, participant.participantId, winner == RaceWinner.CANCELLATION)
    }

    private fun assertEnrollmentRevoked(studyId: UUID, participantId: String, revoked: Boolean) {
        hds.connection.use { connection ->
            connection.prepareStatement("""
                SELECT revoked_at IS NOT NULL FROM participant_form_access_codes
                WHERE study_id = ? AND participant_id = ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(revoked, rows.getBoolean(1))
                }
            }
        }
    }

    /** Waits until [expected] sessions queue on a lock the [barrier] holds. */
    private fun awaitWaiters(barrier: Connection, expected: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        barrier.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE pg_backend_pid() = ANY(pg_blocking_pids(pid))",
        ).use { statement ->
            while (System.nanoTime() < deadline) {
                val queued = statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
                if (queued == expected) return
                Thread.sleep(10)
            }
        }
        throw AssertionError("Expected $expected operations queued on the barrier's lock")
    }

    private fun assertPreservedDataAndEnrollmentEvidence(studyId: UUID, participantId: String) {
        hds.connection.use { connection ->
            connection.prepareStatement("""
                SELECT count(*) FROM participant_form_access_codes code
                JOIN participant_collection_acknowledgment ack ON ack.evidence_access_code_id = code.access_code_id
                WHERE code.study_id = ? AND code.participant_id = ? AND code.revoked_at IS NULL
            """.trimIndent()).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            }
            connection.prepareStatement("SELECT count(*) FROM data_deletion_operations WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
            }
        }
    }
}
