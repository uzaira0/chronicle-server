package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.study.ParticipantPurgeApi
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/** API acceptance stopped at HTTP 500 from purge preview; the preview runs against the real schema and RLS roles here. */
class ParticipantPurgePreviewIntegrationTest : ChronicleServerTests() {
    @Test
    fun purgePreviewOfAnEnrolledParticipantCountsEveryRegisteredAsset() {
        val study = clientUser1.studyApi.createStudy(Study(title = "purge preview", contact = "test@openlattice.com"))
        val participant = TestDataFactory.participant().copy(participationStatus = ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(study, participant)
        val path = StudyApi.BASE + "/$study" + StudyApi.PARTICIPANTS_PATH + "/${participant.participantId}" +
            ParticipantPurgeApi.PURGE_PATH + ParticipantPurgeApi.PREVIEW_PATH
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort$path"))
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(response.body(), 200, response.statusCode())
        val summary = ObjectMappers.getJsonMapper().readTree(response.body())
        assertEquals(participant.participantId, summary["participantId"].asText())
        assertEquals(0L, summary["usageEventsCount"].asLong())
    }
}
