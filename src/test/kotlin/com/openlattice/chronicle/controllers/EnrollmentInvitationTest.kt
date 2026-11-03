package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.newUsageEnrollment
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.submitUsageEnrollment
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The QR dialog shows the link already sent, and a study edit during consent, through the real routes. */
class EnrollmentInvitationTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.newJsonMapper()
    private val http = HttpClient.newHttpClient()
    private val publicApi = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE).create(StudyApi::class.java)

    private fun request(path: String, method: String = "GET", body: String = "", token: String = activeFixtureToken(testUser1)) =
        http.send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3$path"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun registeredParticipant(title: String): Pair<UUID, String> {
        val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        return studyId to participant.participantId
    }

    @Test
    fun theLiveCodeIsShownAgainAndOnlyAnExplicitIssueReplacesIt() {
        val (studyId, participantId) = registeredParticipant("Live enrollment code")
        val base = "/study/$studyId/participant/$participantId"
        fun live() = request("$base/enrollment-code")
        fun issue() = mapper.readTree(request("$base/form-access-codes", "POST", """{"formKind":"ENROLLMENT"}""").body())
            .path("accessCode").asText()

        assertEquals(404, live().statusCode())
        val sent = issue()
        repeat(2) {
            val shown = live()
            assertEquals(shown.body(), 200, shown.statusCode())
            assertEquals(sent, mapper.readTree(shown.body()).path("accessCode").asText())
        }
        assertEquals(participantId, publicApi.getEnrollmentPreviewV4(studyId, participantId, sent).manifest.participantId)
        assertEquals(403, request("$base/enrollment-code", token = activeFixtureToken(testUser2)).statusCode())

        val replacement = issue()
        assertNotEquals(sent, replacement)
        assertEquals(replacement, mapper.readTree(live().body()).path("accessCode").asText())
        val revoked = assertThrows(RhizomeRetrofitCallException::class.java) {
            publicApi.getEnrollmentPreviewV4(studyId, participantId, sent)
        }
        assertEquals(401, revoked.code)

        // Once the phone binds it, the code is no longer a link the dialog can show.
        beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice(), accessCode = replacement)
        assertEquals(404, live().statusCode())
    }

    @Test
    fun aStudyEditDuringConsentIsAnsweredAsStudyChangedAndKeepsTheInvitation() {
        val (studyId, participantId) = registeredParticipant("Edited during consent")
        val (replay, _) = newUsageEnrollment(studyId, participantId, TestDataFactory.androidDevice())
        clientUser1.studyApi.updateStudy(studyId, StudyUpdate(title = "Edited during consent (revised)"))

        val changed = assertThrows(RhizomeRetrofitCallException::class.java) { submitUsageEnrollment(replay) }
        assertEquals(409, changed.code)
        assertEquals(EnrollmentAttemptContracts.ENROLLMENT_STUDY_CHANGED_CODE, mapper.readTree(changed.body).path("code").asText())
        assertEquals(true, changed.body.contains(Messages.en("error.enrollment.studyChanged")))

        // Opening the same link again shows the edited study and enrolls.
        val reopened = newUsageEnrollment(studyId, participantId, replay.device, accessCode = replay.enrollmentCode).first
        assertEquals(reopened.proposedApiKey, submitUsageEnrollment(reopened).apiKey)
    }
}
