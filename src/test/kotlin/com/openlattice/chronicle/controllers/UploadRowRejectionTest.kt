package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.storedUsageTimestamps
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a defect of a row carries the row-level code; the phone quarantines on nothing else. */
class UploadRowRejectionTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.newJsonMapper()

    private data class Enrolled(val studyId: UUID, val participantId: String, val fixture: UsageEnrollmentFixture)

    private fun enrolled(): Enrolled {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Upload row rejection", contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        return Enrolled(studyId, participant.participantId,
            enrollUsageFixture(studyId, participant.participantId, TestDataFactory.androidDevice()))
    }

    private fun event(enrolled: Enrolled): ChronicleUsageEvent {
        val template = TestDataFactory.chronicleUsageEvents(enrolled.studyId, enrolled.participantId, count = 1)
            .single() as ChronicleUsageEvent
        return template.copy(timestamp = template.timestamp.withNano(0), clientEventId = UUID.randomUUID())
    }

    private fun upload(enrolled: Enrolled, vararg events: ChronicleUsageEvent) =
        enrolled.fixture.mobileApi.uploadAndroidUsageEventDataV4(
            enrolled.studyId, enrolled.participantId, enrolled.fixture.replay.device.randomDeviceId, ChronicleData(events.toList()),
        )

    @Test
    fun anUnknownZoneIdIsStoredAsGiven() {
        val enrolled = enrolled()
        val event = event(enrolled).copy(timezone = "Mars/Olympus_Mons")

        assertEquals(setOf(event.clientEventId), upload(enrolled, event).acceptedIds)
        assertEquals(setOf(event.timestamp.toInstant()), storedUsageTimestamps(enrolled.studyId, enrolled.participantId))
        RLSRequestContext.withSystemContext { testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage() }
        val zones = hds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT timezone FROM chronicle_usage_events WHERE study_id::text = ? AND participant_id = ?",
            ).use { statement ->
                statement.setString(1, enrolled.studyId.toString())
                statement.setString(2, enrolled.participantId)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertEquals(listOf("Mars/Olympus_Mons"), zones)
    }

    @Test
    fun aRowDefectCarriesTheRowLevelCode() {
        val enrolled = enrolled()
        val event = event(enrolled)

        val refused = assertThrows(RhizomeRetrofitCallException::class.java) { upload(enrolled, event, event) }
        assertEquals(400, refused.code)
        assertEquals(UploadLimits.UPLOAD_ROW_REJECTED_CODE, mapper.readTree(refused.body).path("code").asText())
    }

    @Test
    fun aRequestLevel400CarriesNoRowLevelCode() {
        val enrolled = enrolled()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${StudyApi.V4_BASE}/${enrolled.studyId}" +
                "/participant/${enrolled.participantId}/android"))
                .header(API_KEY_HEADER, enrolled.fixture.replay.proposedApiKey)
                .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, enrolled.fixture.replay.device.randomDeviceId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""[{"@class":"com.example.FutureSample"}]"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(response.body(), 400, response.statusCode())
        assertTrue(response.body(), mapper.readTree(response.body()).path("code").asText() != UploadLimits.UPLOAD_ROW_REJECTED_CODE)
    }
}
