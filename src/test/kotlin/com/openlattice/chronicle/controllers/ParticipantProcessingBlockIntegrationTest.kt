package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.generated.ParticipantProcessingContracts
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantProcessingApi
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.web.server.ResponseStatusException

class ParticipantProcessingBlockIntegrationTest : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val forms = testServer.context.getBean(ParticipantFormAccessService::class.java)
    private val enrollment = testServer.context.getBean(EnrollmentService::class.java)

    private fun change(study: UUID, participant: String, block: Boolean, authorized: Boolean = true): HttpResponse<String> {
        val path = ParticipantProcessingApi.PATH.replace("{studyId}", study.toString()).replace("{participantId}", participant)
        val request = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/study$path"))
            .header("Authorization", "Bearer ${activeFixtureToken(if (authorized) testUser1 else testUser2)}")
            .header("Content-Type", "application/json")
            .header("If-Match", "\"${statusRevision(study, participant)}\"")
        if (block) request.POST(HttpRequest.BodyPublishers.ofString("""{"reason":"Participant requested a review"}""")) else request.DELETE()
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun statusRevision(study: UUID, participant: String): Long =
        clientUser1.studyApi.getStudyParticipants(study).single { it.participantId == participant }.statusRevision

    private fun instrument(study: UUID, token: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${TimeUseDiaryApi.BASE}/$study${TimeUseDiaryApi.SETTINGS_PATH}"))
            .header("Cookie", "${ParticipantFormAccessFilter.SESSION_COOKIE}=$token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun recover(token: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/participant-access/session"))
            .header("Cookie", "${ParticipantFormAccessFilter.SESSION_COOKIE}=$token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun blockHoldsProcessingWithoutChangingStoredDataOrParticipationAndUnblockResumes() {
        val study = clientUser1.studyApi.createStudy(Study(title = "processing review", contact = "test@openlattice.com"))
        val participant = TestDataFactory.participant().copy(participationStatus = ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(study, participant)
        val id = participant.participantId
        val code = RLSRequestContext.withSystemContext {
            forms.createAccessCode(study, id, ParticipantFormKind.PORTAL, null, null, null,
                ParticipantAccessCodeIssuerType.RESEARCHER, "test-researcher")
        }
        val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
        assertEquals(200, recover(session.rawSessionToken).statusCode())
        assertEquals(200, instrument(study, session.rawSessionToken).statusCode())
        hds.connection.use { connection -> connection.prepareStatement("""
            INSERT INTO chronicle_usage_events (study_id, participant_id, app_package_name, interaction_type, event_type,
                event_timestamp, timezone, username, application_label)
            VALUES (?, ?, 'org.example.processing', 'Move to Foreground', 1, now(), 'UTC', '', 'Processing')
        """.trimIndent()).use { it.setString(1, study.toString()); it.setString(2, id); it.executeUpdate() } }
        assertEquals(1, visibleRows(study, id))
        assertEquals(403, change(study, id, true, authorized = false).statusCode())
        val blocked = change(study, id, true)
        assertEquals(blocked.body(), 200, blocked.statusCode())
        assertEquals(200, change(study, id, true).statusCode())
        assertEquals(0, visibleRows(study, id))
        RLSRequestContext.withSystemContext {
            assertEquals(ParticipationStatus.PAUSED, enrollment.getDeviceParticipationStatus(study, id))
        }
        hds.connection.use { connection ->
            connection.autoCommit = false
            try { assertTrue(!ParticipantCollectionGate(connection).isActive(study, id, allowQueuedCaptures = true)) }
            finally { connection.rollback() }
            connection.prepareStatement("SELECT count(*) FROM chronicle_usage_events WHERE study_id = ? AND participant_id = ?").use {
                it.setString(1, study.toString()); it.setString(2, id)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
            }
        }
        val denied = assertThrows(ResponseStatusException::class.java) { forms.recoverSession(session.rawSessionToken) }
        assertEquals(403, denied.statusCode.value())
        assertEquals(ParticipantProcessingApi.PROCESSING_BLOCKED_REASON, denied.reason)
        val blockedInstrument = instrument(study, session.rawSessionToken)
        assertEquals(blockedInstrument.body(), 403, blockedInstrument.statusCode())
        assertTrue(blockedInstrument.body().contains(ParticipantProcessingApi.PROCESSING_BLOCKED_REASON))
        for (response in listOf(blockedInstrument, recover(session.rawSessionToken))) {
            assertEquals(response.body(), 403, response.statusCode())
            assertEquals(ParticipantProcessingContracts.PARTICIPANT_PROCESSING_BLOCKED_CODE,
                ObjectMappers.getJsonMapper().readTree(response.body()).path("code").asText())
        }
        assertEquals(ParticipationStatus.ENROLLED, clientUser1.studyApi.getStudyParticipants(study).single().participationStatus)
        assertEquals(200, change(study, id, false).statusCode())
        assertEquals(200, change(study, id, false).statusCode())
        assertEquals(1, visibleRows(study, id))
        assertNotNull(forms.recoverSession(session.rawSessionToken))
        assertEquals(200, recover(session.rawSessionToken).statusCode())
        assertEquals(200, instrument(study, session.rawSessionToken).statusCode())
        RLSRequestContext.withSystemContext {
            assertEquals(ParticipationStatus.ENROLLED, enrollment.getDeviceParticipationStatus(study, id))
        }
    }

    private fun visibleRows(study: UUID, participant: String): Int =
        clientUser1.testStudyApi.getParticipantsData(
            study, ParticipantDataType.UsageEvents, setOf(participant),
            OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), "json",
        ).size
}
