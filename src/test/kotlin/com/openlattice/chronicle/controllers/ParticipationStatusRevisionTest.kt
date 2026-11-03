package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.awaitSessionsBlockedBy
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two dashboard tabs share one login: a status change applies only to the status revision its tab saw. */
class ParticipationStatusRevisionTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.newJsonMapper()
    private val http = HttpClient.newHttpClient()

    private fun send(path: String, method: String = "GET", ifMatch: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3$path"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        ifMatch?.let { builder.header("If-Match", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun observed(studyId: UUID): String {
        val participant = mapper.readTree(send("/study/$studyId/participants").body()).single()
        return "\"${participant.path("statusRevision").asLong()}\""
    }

    private fun status(studyId: UUID): String =
        mapper.readTree(send("/study/$studyId/participants").body()).single().path("participationStatus").asText()

    private fun captureCutoff(studyId: UUID, participantId: String): OffsetDateTime? = hds.connection.use { connection ->
        connection.prepareStatement("SELECT capture_cutoff FROM study_participants WHERE study_id = ? AND participant_id = ?")
            .use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
            }
    }

    private fun statusAudits(studyId: UUID): List<Int> = listOf(
        AuditEventType.UPDATE_PARTICIPATION_STATUS,
        AuditEventType.PARTICIPANT_WITHDRAW,
    ).map { auditEventCount(studyId, it) }

    @Test
    fun staleResumeRetryCannotUndoALaterPause() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Status revisions", contact = "tests@openlattice.com"))
        val participantId = TestDataFactory.participant(ParticipationStatus.ENROLLED)
            .also { clientUser1.studyApi.registerParticipant(studyId, it) }.participantId
        val patch = "/study/$studyId/participant/$participantId/status?participationStatus="
        assertEquals(200, send(patch + "PAUSED", "PATCH", observed(studyId)).statusCode())

        // Tab A resumes; the server commits but A never sees the response and keeps its modal open.
        val seenByA = observed(studyId)
        assertEquals(200, send(patch + "ENROLLED", "PATCH", seenByA).statusCode())
        // Tab B refreshes and pauses again.
        val seenByB = observed(studyId)
        assertEquals(200, send(patch + "PAUSED", "PATCH", seenByB).statusCode())
        val pausedAt = captureCutoff(studyId, participantId)

        val staleRetry = send(patch + "ENROLLED", "PATCH", seenByA)
        assertEquals(staleRetry.body(), 412, staleRetry.statusCode())
        assertEquals("PAUSED", status(studyId))
        assertEquals(pausedAt, captureCutoff(studyId, participantId))

        // An identical replay of the change that already landed is idempotent: no revision move, no write, no audit.
        val current = observed(studyId)
        val audited = statusAudits(studyId)
        assertEquals(200, send(patch + "PAUSED", "PATCH", seenByB).statusCode())
        assertEquals(current, observed(studyId))
        assertEquals(pausedAt, captureCutoff(studyId, participantId))
        assertEquals(audited, statusAudits(studyId))

        assertEquals(428, send(patch + "ENROLLED", "PATCH").statusCode())
        assertEquals("PAUSED", status(studyId))
    }

    private fun processingBlocked(studyId: UUID, participantId: String): Boolean = hds.connection.use { connection ->
        connection.prepareStatement(
            "SELECT processing_blocked_at IS NOT NULL FROM study_participants WHERE study_id = ? AND participant_id = ?",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
    }

    @Test
    fun staleUnblockRetryCannotUndoALaterBlock() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Processing revisions", contact = "tests@openlattice.com"))
        val participantId = TestDataFactory.participant(ParticipationStatus.ENROLLED)
            .also { clientUser1.studyApi.registerParticipant(studyId, it) }.participantId
        val path = "/study/$studyId/participant/$participantId/processing-block"
        val reason = """{"reason":"Participant requested a review"}"""
        assertEquals(428, send(path, "POST", body = reason).statusCode())
        assertEquals(false, processingBlocked(studyId, participantId))
        assertEquals(200, send(path, "POST", observed(studyId), reason).statusCode())

        // Tab A lifts the block; the server commits but A never sees the response.
        val seenByA = observed(studyId)
        assertEquals(200, send(path, "DELETE", seenByA).statusCode())
        // Tab B refreshes and blocks again.
        val seenByB = observed(studyId)
        assertEquals(200, send(path, "POST", seenByB, reason).statusCode())

        val staleRetry = send(path, "DELETE", seenByA)
        assertEquals(staleRetry.body(), 412, staleRetry.statusCode())
        assertEquals(true, processingBlocked(studyId, participantId))

        // An identical replay of the block that already landed is idempotent and moves no revision.
        val current = observed(studyId)
        assertEquals(200, send(path, "POST", seenByB, reason).statusCode())
        assertEquals(current, observed(studyId))
        assertEquals(428, send(path, "DELETE").statusCode())
        assertEquals(true, processingBlocked(studyId, participantId))
    }

    private fun statusAuditTrail(studyId: UUID): List<Triple<String, String?, String?>> = hds.connection.use { connection ->
        connection.prepareStatement("""
            SELECT audit_event_type, data::jsonb ->> 'oldStatus', data::jsonb ->> 'newStatus' FROM audit
            WHERE study_id = ? AND audit_event_type IN (?, ?) ORDER BY event_timestamp
        """.trimIndent()).use { statement ->
            statement.setString(1, studyId.toString())
            statement.setString(2, AuditEventType.UPDATE_PARTICIPATION_STATUS.name)
            statement.setString(3, AuditEventType.PARTICIPANT_WITHDRAW.name)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getString(3))) }
            }
        }
    }

    /** Each status change is one audit row with its old and new status; Mark not enrolled is a withdrawal. */
    @Test
    fun everyStatusChangeIsAuditedOnceWithItsOldAndNewStatus() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Status audit", contact = "tests@openlattice.com"))
        val participantId = TestDataFactory.participant(ParticipationStatus.ENROLLED)
            .also { clientUser1.studyApi.registerParticipant(studyId, it) }.participantId
        val patch = "/study/$studyId/participant/$participantId/status?participationStatus="
        val before = statusAuditTrail(studyId)
        for (status in listOf("PAUSED", "ENROLLED", "COLLECTION_COMPLETED", "NOT_ENROLLED")) {
            assertEquals(200, send(patch + status, "PATCH", observed(studyId)).statusCode())
        }
        val update = AuditEventType.UPDATE_PARTICIPATION_STATUS.name
        assertEquals(listOf(
            Triple(update, "ENROLLED", "PAUSED"),
            Triple(update, "PAUSED", "ENROLLED"),
            Triple(update, "ENROLLED", "COLLECTION_COMPLETED"),
            Triple(AuditEventType.PARTICIPANT_WITHDRAW.name, "COLLECTION_COMPLETED", "NOT_ENROLLED"),
        ), statusAuditTrail(studyId).drop(before.size))
    }

    private class EnrolledDevice(val studyId: UUID, val participantId: String, val enrollment: UsageEnrollmentFixture)

    private fun enrolledDevice(title: String): EnrolledDevice {
        val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        val enrollment = enrollUsageFixture(studyId, participant.participantId, TestDataFactory.androidDevice())
        return EnrolledDevice(studyId, participant.participantId, enrollment)
    }

    /** The phone's status poll, decoded with the production mapper. */
    private fun poll(device: EnrolledDevice): MobileParticipationStatus {
        val response = pollResponse(device)
        assertEquals(response.body(), 200, response.statusCode())
        return ObjectMappers.getJsonMapper().readValue(response.body(), MobileParticipationStatus::class.java)
    }

    private fun pollResponse(device: EnrolledDevice): HttpResponse<String> {
        val replay = device.enrollment.replay
        return http.send(HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${StudyApi.V4_BASE}" +
            "/${device.studyId}${StudyApi.PARTICIPANT_PATH}/${device.participantId}${StudyApi.STATUS_PATH}"))
            .header(API_KEY_HEADER, replay.proposedApiKey)
            .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, replay.device.randomDeviceId)
            .GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun failedStatusSnapshotsAskThePhoneToRetryAndTheNextPollRecovers() {
        val device = enrolledDevice("Status snapshot failures")
        val install = """
            ALTER FUNCTION chronicle_has_participant_erasure(uuid, text) RENAME TO fixture_original_erasure;
            CREATE TABLE fixture_status_failure (study_id uuid, participant_id text, state text, gate bigint);
            GRANT SELECT ON fixture_status_failure TO chronicle_app;
            CREATE FUNCTION chronicle_has_participant_erasure(p_study_id uuid, p_participant_id text) RETURNS boolean
            LANGUAGE plpgsql STABLE AS ${'$'}${'$'}
            DECLARE state text; gate bigint;
            BEGIN
              SELECT f.state, f.gate INTO state, gate FROM fixture_status_failure f
              WHERE f.study_id = p_study_id AND f.participant_id = p_participant_id;
              IF FOUND AND current_setting('transaction_read_only') = 'on' THEN
                IF gate IS NOT NULL THEN PERFORM pg_advisory_xact_lock(gate);
                ELSE RAISE EXCEPTION 'fixture status snapshot failed' USING ERRCODE = state; END IF;
              END IF;
              RETURN fixture_original_erasure(p_study_id, p_participant_id);
            END ${'$'}${'$'};
        """.trimIndent()
        hds.connection.use { connection -> connection.createStatement().use { it.execute(install) } }
        // A failed restore surfaces on its own, or suppressed under the test's failure (AutoCloseable.use).
        AutoCloseable(::restoreStatusFunction).use {
            for (state in listOf("40001", "40P01", "57P01")) {
                armStatusFault(device, state = state)
                val failed = pollResponse(device)
                assertEquals("$state: ${failed.body()}", 503, failed.statusCode())
                assertEquals("5", failed.headers().firstValue("Retry-After").orElse(null))
                assertTrue("a failed poll carries no windows", !failed.body().contains("captureWindows"))
                clearStatusFault()
                assertEquals(ParticipationStatus.ENROLLED, poll(device).participationStatus)
            }
            assertPauseBetweenStatusAndWindowsUsesOneSnapshot(device)
        }
    }

    private fun restoreStatusFunction() = runCatching {
        hds.connection.use { connection -> connection.createStatement().use {
            it.execute("DROP FUNCTION chronicle_has_participant_erasure(uuid, text); " +
                "ALTER FUNCTION fixture_original_erasure(uuid, text) RENAME TO chronicle_has_participant_erasure; " +
                "DROP TABLE fixture_status_failure")
        } }
    }.getOrElse {
        // Every later status read in this JVM would fail without naming its cause.
        throw AssertionError("failedStatusSnapshots fixture did not restore chronicle_has_participant_erasure", it)
    }

    private fun armStatusFault(device: EnrolledDevice, state: String? = null, gate: Long? = null) = hds.connection.use { connection ->
        connection.prepareStatement("INSERT INTO fixture_status_failure VALUES (?, ?, ?, ?)").use {
            it.setObject(1, device.studyId); it.setString(2, device.participantId); it.setString(3, state); it.setObject(4, gate)
            it.executeUpdate()
        }
    }

    private fun clearStatusFault() = hds.connection.use { connection ->
        connection.createStatement().use { it.execute("DELETE FROM fixture_status_failure") }
    }

    private fun lockStatusGate(barrier: java.sql.Connection, gate: Long) =
        barrier.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { it.setLong(1, gate); it.execute() }

    private fun submitStatusPoll(executor: java.util.concurrent.ExecutorService, device: EnrolledDevice) =
        executor.submit(Callable { poll(device) })

    /** Holds the first status query while a pause commits, before the window query starts. */
    private fun assertPauseBetweenStatusAndWindowsUsesOneSnapshot(device: EnrolledDevice) {
        val gate = UUID.randomUUID().mostSignificantBits
        armStatusFault(device, gate = gate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = hds.connection.use { barrier ->
                barrier.autoCommit = false
                try {
                    lockStatusGate(barrier, gate)
                    val pendingPoll = submitStatusPoll(executor, device)
                    awaitSessionsBlockedBy(barrier, 1)
                    val patch = "/study/${device.studyId}/participant/${device.participantId}/status?participationStatus=PAUSED"
                    assertEquals(200, send(patch, "PATCH", observed(device.studyId)).statusCode())
                    pendingPoll
                } finally {
                    barrier.rollback()
                }
            }
            val beforePause = pending.get(60, TimeUnit.SECONDS)
            assertEquals(ParticipationStatus.ENROLLED, beforePause.participationStatus)
            assertEquals("the later window read uses the status query's snapshot", emptyList<Any>(), beforePause.captureWindows)
            val afterPause = poll(device)
            assertEquals(ParticipationStatus.PAUSED, afterPause.participationStatus)
            assertEquals(1, afterPause.captureWindows.count { it.end == null })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun databaseNow(): Instant = hds.connection.use(com.openlattice.chronicle.UsageEnrollmentFixtures::databaseNow).toInstant()

    private fun participantInstant(device: EnrolledDevice, column: String): Instant = hds.connection.use { connection ->
        connection.prepareStatement(
            "SELECT capture_cutoff, processing_blocked_at FROM study_participants WHERE study_id = ? AND participant_id = ?",
        ).use { statement ->
            statement.setObject(1, device.studyId)
            statement.setString(2, device.participantId)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getObject(column, OffsetDateTime::class.java).toInstant() }
        }
    }

    /**
     * Each committed status reaches the phone's poll with the windows it implies, and the poll's
     * `serverTime` is the database clock at the response. Historical windows that start or end on a Santiago midnight
     * or DST transition keep their instants through the wire.
     */
    @Test
    fun theStatusPollPublishesEachCommittedStateOnTheDatabaseClock() {
        val device = enrolledDevice("Status poll windows")
        val santiago = ZoneId.of("America/Santiago")
        val fallBack = checkNotNull(santiago.rules.nextTransition(Instant.parse("2026-03-01T00:00:00Z"))).instant
        val springForward = checkNotNull(santiago.rules.nextTransition(fallBack)).instant
        val seeded = listOf(
            LocalDate.parse("2026-04-03").atStartOfDay(santiago).toInstant()..<fallBack,
            springForward..<LocalDate.parse("2026-09-07").atStartOfDay(santiago).toInstant(),
        )
        seedClosedWindows(device, seeded)
        fun published(): Pair<ParticipationStatus, List<Pair<Instant, Instant?>>> {
            val before = databaseNow()
            val status = poll(device)
            val after = databaseNow()
            assertTrue("serverTime ${status.serverTime} is the database clock", status.serverTime.toInstant() in before..after)
            return status.participationStatus to status.captureWindows.map { it.start.toInstant() to it.end?.toInstant() }
        }
        val history = seeded.map { it.start to it.endExclusive }
        assertEquals(ParticipationStatus.ENROLLED to history, published())

        val patch = "/study/${device.studyId}/participant/${device.participantId}/status?participationStatus="
        assertEquals(200, send(patch + "PAUSED", "PATCH", observed(device.studyId)).statusCode())
        val paused = participantInstant(device, "capture_cutoff")
        assertEquals(ParticipationStatus.PAUSED to history + (paused to null), published())

        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        val (resumed, afterResume) = published()
        assertEquals(ParticipationStatus.ENROLLED, resumed)
        assertEquals(history, afterResume.take(2))
        assertEquals("the pause closes when the participant resumes", paused, afterResume.single { it.first !in seeded.map { w -> w.start } }.first)
        assertTrue(afterResume.all { it.second != null })

        val block = "/study/${device.studyId}/participant/${device.participantId}/processing-block"
        assertEquals(200, send(block, "POST", observed(device.studyId), """{"reason":"Review"}""").statusCode())
        val blocked = participantInstant(device, "processing_blocked_at")
        assertEquals(ParticipationStatus.PAUSED to afterResume + (blocked to null), published())
        assertEquals(200, send(block, "DELETE", observed(device.studyId)).statusCode())
        val (unblocked, afterUnblock) = published()
        assertEquals(ParticipationStatus.ENROLLED, unblocked)
        assertTrue("the block is published closed after the unblock", afterUnblock.any { it.first == blocked && it.second != null })
        assertTrue(afterUnblock.all { it.second != null })

        assertEquals(200, send(patch + "COLLECTION_COMPLETED", "PATCH", observed(device.studyId)).statusCode())
        val completed = participantInstant(device, "capture_cutoff")
        assertEquals(ParticipationStatus.COLLECTION_COMPLETED to afterUnblock + (completed to null), published())
        // The study closes over the completed participant: both open windows are published, in start order.
        hds.connection.use { connection ->
            connection.prepareStatement("UPDATE studies SET lifecycle_status = 'ARCHIVED' WHERE study_id = ?").use {
                it.setObject(1, device.studyId); assertEquals(1, it.executeUpdate())
            }
        }
        val closedAt = hds.connection.use { connection ->
            connection.prepareStatement("SELECT collection_closed_at FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, device.studyId)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant() }
            }
        }
        assertEquals(ParticipationStatus.COLLECTION_COMPLETED to afterUnblock + listOf(completed to null, closedAt to null),
            published())
    }

    /** Stores two past closed capture windows for the participant. */
    private fun seedClosedWindows(device: EnrolledDevice, windows: List<OpenEndRange<Instant>>) = hds.connection.use { connection ->
        connection.prepareStatement("""
            UPDATE study_participants SET closed_capture_windows = tstzmultirange(tstzrange(?, ?, '[)'), tstzrange(?, ?, '[)'))
            WHERE study_id = ? AND participant_id = ?
        """.trimIndent()).use { statement ->
            windows.flatMap { listOf(it.start, it.endExclusive) }.forEachIndexed { index, instant ->
                statement.setObject(index + 1, instant.atOffset(ZoneOffset.UTC))
            }
            statement.setObject(5, device.studyId)
            statement.setString(6, device.participantId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    /**
     * Polls racing pause/resume each describe one committed state: an enrolled participant has no open
     * window, a paused one has exactly one, and it starts at a stored pause.
     */
    @Test
    fun statusPollsRacingPauseAndResumeEachDescribeOneCommittedState() {
        val device = enrolledDevice("Status poll race")
        val patch = "/study/${device.studyId}/participant/${device.participantId}/status?participationStatus="
        val stop = AtomicBoolean(false)
        val torn = ConcurrentLinkedQueue<MobileParticipationStatus>()
        val polls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val pollers = (1..4).map {
                executor.submit {
                    while (!stop.get()) {
                        val status = poll(device)
                        polls.incrementAndGet()
                        val open = status.captureWindows.count { window -> window.end == null }
                        val expected = if (status.participationStatus == ParticipationStatus.PAUSED) 1 else 0
                        if (open != expected) torn += status
                    }
                }
            }
            repeat(100) { index ->
                val next = if (index % 2 == 0) "PAUSED" else "ENROLLED"
                assertEquals(200, send(patch + next, "PATCH", observed(device.studyId)).statusCode())
            }
            stop.set(true)
            pollers.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            stop.set(true)
            executor.shutdownNow()
        }
        assertTrue("polls ran", polls.get() > 0)
        assertEquals("torn status/window snapshots", emptyList<MobileParticipationStatus>(), torn.toList())
    }

    private fun hardStop(device: EnrolledDevice): Pair<Long, Instant?> = hds.connection.use { connection ->
        connection.prepareStatement(
            "SELECT hard_stop_generation, hard_stopped_at FROM study_participants WHERE study_id = ? AND participant_id = ?",
        ).use { statement ->
            statement.setObject(1, device.studyId)
            statement.setString(2, device.participantId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1) to rows.getObject(2, OffsetDateTime::class.java)?.toInstant()
            }
        }
    }

    @Test fun aParticipantInsertCannotForgeAHardStop() {
        val device = enrolledDevice("Hard stop insert ownership")
        hds.connection.use { connection ->
            connection.prepareStatement("INSERT INTO study_participants " +
                "(study_id, participant_id, candidate_id, participation_status, hard_stop_generation, hard_stopped_at) " +
                "VALUES (?, 'forged-stop', ?, 'ENROLLED', 99, now()) RETURNING hard_stop_generation, hard_stopped_at").use {
                it.setObject(1, device.studyId)
                it.setObject(2, com.openlattice.chronicle.contract.seedCandidate(connection))
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(0L, rows.getLong(1)); assertEquals(null, rows.getObject(2))
                }
            }
        }
    }

    /**
     * Mark not enrolled, then Enroll, with no device poll in between. The poll after shows ENROLLED and
     * a closed window; the published hard stop is what tells the phone to drop its pre-stop backlog. A pause and resume
     * is not a hard stop, and no writer can rewind the count.
     */
    @Test
    fun aMarkNotEnrolledThePhoneNeverPolledIsPublishedAsAHardStop() {
        val device = enrolledDevice("Missed hard stop")
        val patch = "/study/${device.studyId}/participant/${device.participantId}/status?participationStatus="
        assertEquals("never stopped", null, poll(device).hardStop)

        assertEquals(200, send(patch + "PAUSED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals("a pause and resume is not a hard stop", null, poll(device).hardStop)
        assertEquals(0L to null, hardStop(device))

        val before = databaseNow()
        assertEquals(200, send(patch + "NOT_ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        val stopped = poll(device)
        assertEquals(ParticipationStatus.ENROLLED, stopped.participationStatus)
        val stop = checkNotNull(stopped.hardStop) { "the missed stop is published" }
        assertEquals(1L, stop.generation)
        assertTrue("the stop is stamped on the database clock", stop.at.toInstant() in before..databaseNow())
        assertEquals(1L to stop.at.toInstant(), hardStop(device))
        assertTrue("the stop's window is published closed", stopped.captureWindows.any {
            it.start.toInstant() == stop.at.toInstant() && it.end != null
        })

        // A stop from a pause counts too; the resume after it and a second pause move nothing.
        assertEquals(200, send(patch + "PAUSED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals(200, send(patch + "NOT_ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        val second = hardStop(device)
        assertEquals(2L, second.first)
        assertTrue(checkNotNull(second.second) > stop.at.toInstant())
        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals(200, send(patch + "PAUSED", "PATCH", observed(device.studyId)).statusCode())
        assertEquals(second, hardStop(device))
        assertEquals(second, poll(device).hardStop?.let { it.generation to it.at.toInstant() })

        // The trigger owns both columns: a direct write cannot rewind or forge them.
        hds.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE study_participants SET hard_stop_generation = 0, hard_stopped_at = NULL
                WHERE study_id = ? AND participant_id = ?
            """.trimIndent()).use {
                it.setObject(1, device.studyId); it.setString(2, device.participantId); assertEquals(1, it.executeUpdate())
            }
        }
        assertEquals(second, hardStop(device))
    }

    private data class Mutation(val path: String, val method: String, val body: String? = null)

    /** Sends both [mutations] with the same revision while the participant row is locked, then releases them together. */
    private fun race(studyId: UUID, participantId: String, mutations: List<Mutation>): List<Int> {
        val revision = observed(studyId)
        val executor = Executors.newFixedThreadPool(mutations.size)
        try {
            val responses = hds.connection.use { barrier ->
                barrier.autoCommit = false
                try {
                    lockParticipantRow(barrier, studyId, participantId)
                    mutations.mapIndexed { index, mutation ->
                        executor.submit(Callable { send(mutation.path, mutation.method, revision, mutation.body).statusCode() })
                            .also { awaitSessionsBlockedBy(barrier, index + 1) }
                    }
                } finally {
                    barrier.rollback()
                }
            }
            val codes = responses.map { it.get(60, TimeUnit.SECONDS) }
            // Each response replayed with the same revision gets the same answer: the winner's is an idempotent no-op.
            assertEquals("replays", codes, mutations.map { send(it.path, it.method, revision, it.body).statusCode() })
            return codes
        } finally {
            executor.shutdownNow()
        }
    }

    private fun lockParticipantRow(barrier: java.sql.Connection, studyId: UUID, participantId: String) =
        barrier.prepareStatement("SELECT 1 FROM study_participants WHERE study_id = ? AND participant_id = ? FOR UPDATE").use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeQuery().use { rows -> check(rows.next()) }
        }

    private fun revision(studyId: UUID): Long = observed(studyId).trim('"').toLong()

    /** Status audit rows by outcome: applied changes, and refused attempts (each refused request writes a failure row). */
    private fun statusAuditOutcomes(studyId: UUID): Pair<Int, Int> = hds.connection.use { connection ->
        connection.prepareStatement("""
            SELECT count(*) FILTER (WHERE data::jsonb ->> 'success' IS DISTINCT FROM 'false'),
                   count(*) FILTER (WHERE data::jsonb ->> 'success' = 'false')
            FROM audit WHERE study_id = ? AND audit_event_type IN (?, ?)
        """.trimIndent()).use { statement ->
            statement.setString(1, studyId.toString())
            statement.setString(2, AuditEventType.UPDATE_PARTICIPATION_STATUS.name)
            statement.setString(3, AuditEventType.PARTICIPANT_WITHDRAW.name)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) to rows.getInt(2) }
        }
    }

    /**
     * Two dashboard actions sent with the same status revision at the same moment. One wins, the other is
     * refused with 412 unless it is the identical change, which is a no-op; the revision moves once, one audit row is
     * written, and the published windows match the winner.
     */
    @Test
    fun simultaneousActionsWithOneRevisionApplyOnce() {
        val reason = """{"reason":"Participant requested a review"}"""
        // A pause and a completion.
        val (studyId, participantId) = TestDataFactory.participant(ParticipationStatus.ENROLLED).let { participant ->
            clientUser1.studyApi.createStudy(Study(title = "Racing status changes", contact = "tests@openlattice.com"))
                .also { clientUser1.studyApi.registerParticipant(it, participant) } to participant.participantId
        }
        val patch = "/study/$studyId/participant/$participantId/status?participationStatus="
        var revision = revision(studyId)
        var audits = statusAuditOutcomes(studyId)
        val stops = listOf(Mutation(patch + "PAUSED", "PATCH"), Mutation(patch + "COLLECTION_COMPLETED", "PATCH"))
        val stopCodes = race(studyId, participantId, stops)
        assertEquals("one wins, one is refused", listOf(200, 412), stopCodes.sorted())
        val winner = if (stopCodes[0] == 200) "PAUSED" else "COLLECTION_COMPLETED"
        assertEquals(winner, status(studyId))
        assertEquals(revision + 1, revision(studyId))
        // One applied change; the refused request and its replay each leave a failure row, the winner's replay none.
        assertEquals(audits.first + 1 to audits.second + 2, statusAuditOutcomes(studyId))
        assertTrue(captureCutoff(studyId, participantId) != null)

        // The identical change twice is one change.
        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(studyId)).statusCode())
        revision = revision(studyId)
        audits = statusAuditOutcomes(studyId)
        assertEquals(listOf(200, 200), race(studyId, participantId, listOf(Mutation(patch + "PAUSED", "PATCH"),
            Mutation(patch + "PAUSED", "PATCH"))))
        assertEquals("PAUSED", status(studyId))
        assertEquals(revision + 1, revision(studyId))
        assertEquals(audits.first + 1 to audits.second, statusAuditOutcomes(studyId))

        // A status change and a processing block share the revision.
        assertEquals(200, send(patch + "ENROLLED", "PATCH", observed(studyId)).statusCode())
        revision = revision(studyId)
        val block = "/study/$studyId/participant/$participantId/processing-block"
        val mixed = race(studyId, participantId, listOf(Mutation(patch + "PAUSED", "PATCH"), Mutation(block, "POST", reason)))
        assertEquals(listOf(200, 412), mixed.sorted())
        assertEquals(revision + 1, revision(studyId))
        val blockWon = mixed[1] == 200
        assertEquals(blockWon, processingBlocked(studyId, participantId))
        assertEquals(if (blockWon) "ENROLLED" else "PAUSED", status(studyId))
    }
}
