package com.openlattice.chronicle.survey

import org.junit.Assert.assertThrows

import java.util.Optional
import com.fasterxml.jackson.databind.node.ObjectNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.junit.Assert
import com.openlattice.chronicle.client.assertHttpStatus
import org.junit.Test
import org.springframework.http.HttpStatus

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
class QuestionnaireTests : ChronicleServerTests() {
    private val chronicleClient: ChronicleClient = clientUser1

    @Test
    fun testCreateQuestionnaire() {
        val studyApi = chronicleClient.studyApi
        val surveyApi = chronicleClient.surveyApi

        val questionnaire = questionnaire()
        val studyId = studyApi.createStudy(TestDataFactory.study())
        val questionnaireId = surveyApi.createQuestionnaire(studyId, questionnaire)

        val created = surveyApi.getQuestionnaire(studyId, questionnaireId)
        Assert.assertEquals(questionnaire.title, created.title)
        Assert.assertEquals(questionnaire.description, created.description)
        Assert.assertEquals(questionnaire.active, created.active)
        Assert.assertArrayEquals(questionnaire.questions.toTypedArray(), created.questions.toTypedArray())
        Assert.assertNotNull(created.dateCreated)
    }

    @Test
    fun testCreateQuestionnaireActiveFlagAndDefaultControlDeviceScheduling() {
        val studyId = chronicleClient.studyApi.createStudy(TestDataFactory.study())
        prepareUsageFixture(chronicleClient, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        chronicleClient.studyApi.registerParticipant(studyId, participant)
        val device = TestDataFactory.androidDevice()
        val sourceRandomDeviceId = device.randomDeviceId
        val deviceKey = enrollUsageFixture(studyId, participant.participantId, device).replay.proposedApiKey
        val mapper = ObjectMappers.getJsonMapper()
        val http = HttpClient.newHttpClient()
        val questionnaireUri = URI("http://localhost:$testHttpPort/chronicle/v3/survey/$studyId/questionnaire")
        val expectedActiveById = listOf(false, true, null).associate { active ->
            val body = mapper.valueToTree<ObjectNode>(
                questionnaire().copy(active = active ?: true, recurrenceRule = "FREQ=DAILY"),
            )
            if (active == null) body.remove("active")
            val response = http.send(
                HttpRequest.newBuilder(questionnaireUri)
                    .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                    .header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            Assert.assertEquals(response.body(), 200, response.statusCode())
            mapper.readValue(response.body(), UUID::class.java) to (active ?: true)
        }
        val storedActiveById = chronicleClient.surveyApi.getStudyQuestionnaires(studyId)
            .associate { it.id to it.active }
        val expectedScheduledIds = expectedActiveById.filterValues { it }.keys
        val reminderUri = URI(
            "http://localhost:$testHttpPort/chronicle/v4/study/$studyId/participant/${participant.participantId}/reminders",
        )
        fun reminders(method: String) = http.send(
            HttpRequest.newBuilder(reminderUri).header(API_KEY_HEADER, deviceKey)
                .header("X-Chronicle-Random-Device-Id", sourceRandomDeviceId)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // The schedule is a read; no request mints codes for reminders.
        Assert.assertEquals(405, reminders("POST").statusCode())
        val scheduledIdsByMethod = listOf("GET").associateWith { method ->
            val response = reminders(method)
            Assert.assertEquals(method, 200, response.statusCode())
            val configuration = mapper.readValue(response.body(), MobileReminderConfiguration::class.java)
            configuration.forms.filter { it.formKind == ParticipantFormKind.QUESTIONNAIRE }
                .map { it.resourceId }.toSet()
        }

        Assert.assertEquals(expectedActiveById, storedActiveById)
        scheduledIdsByMethod.forEach { (method, ids) -> Assert.assertEquals(method, expectedScheduledIds, ids) }
    }

    @Test
    fun testUpdateQuestionnaire() {
        val studyApi = chronicleClient.studyApi
        val surveyApi = chronicleClient.surveyApi

        val studyId = studyApi.createStudy(TestDataFactory.study())
        val questionnaire = questionnaire()
        val questionnaireId = surveyApi.createQuestionnaire(studyId, questionnaire)

        val loaded = chronicleClient.editRevisionApi.questionnaire(studyId, questionnaireId).execute()
        Assert.assertEquals(HttpStatus.NO_CONTENT.value(), loaded.code())
        val revision = requireNotNull(loaded.headers()[EditRevisionApi.ETAG])
        val snapshot = surveyApi.getQuestionnaire(studyId, questionnaireId)
        Assert.assertEquals(questionnaire.questions, snapshot.questions)

        val update = QuestionnaireUpdate(
            title = "Updated Title",
            description = null,
            recurrenceRule = Optional.of(RecurrenceRule("FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0").toString()),
            active = false,
            questions = null
        )
        surveyApi.updateQuestionnaire(studyId, questionnaireId, update, revision)
        assertHttpStatus(HttpStatus.PRECONDITION_FAILED) {
            surveyApi.updateQuestionnaire(studyId, questionnaireId, update, revision)
        }
        val updated = surveyApi.getQuestionnaire(studyId, questionnaireId)

         Assert.assertEquals( update.title, updated.title)
        //description field in update object, therefore description shouldn't be updated
        Assert.assertEquals(updated.description, questionnaire.description)
        Assert.assertEquals(update.recurrenceRule?.orElse(null), updated.recurrenceRule)
        Assert.assertEquals(updated.active, update.active)
        Assert.assertEquals(updated.questions, questionnaire.questions) //questions field in update object is null, so questions shouldn't be updated

    }

    @Test
    fun testDeleteQuestionnaire() {
        val studyApi = chronicleClient.studyApi
        val surveyApi = chronicleClient.surveyApi

        val studyId = studyApi.createStudy(TestDataFactory.study())
        val questionnaire = questionnaire()
        val questionnaireId = surveyApi.createQuestionnaire(studyId, questionnaire)

        surveyApi.deleteQuestionnaire(studyId, questionnaireId)
        assertHttpStatus(HttpStatus.NOT_FOUND) {
            surveyApi.getQuestionnaire(studyId, questionnaireId)
        }
    }

    @Test
    fun testGetStudyQuestionnaires() {
        val studyApi = chronicleClient.studyApi
        val surveyApi = chronicleClient.surveyApi

        val studyId = studyApi.createStudy(TestDataFactory.study())

        surveyApi.createQuestionnaire(studyId, questionnaire())
        surveyApi.createQuestionnaire(studyId, questionnaire())

        val questionnaires = surveyApi.getStudyQuestionnaires(studyId)
        Assert.assertEquals(questionnaires.size, 2)
    }




    @Test
    fun testDuplicateQuestionsShouldThrow() {
        assertThrows(Exception::class.java) {
            Questionnaire(
                id = null,
                title = "Bad questionnaire",
                description = "Questionnaire with duplicate titles",
                recurrenceRule = null,
                active = false,
                questions = listOf(
                    Question("title1"),
                    Question("title1")
                ),
                dateCreated = null
            )
        }
    }

    @Test
    fun testEmptyQuestionsListShouldThrow(){
        assertThrows(Exception::class.java) {
            Questionnaire(
                id = null,
                title = "Bad questionnaire",
                description = "Questionnaire with no questions",
                recurrenceRule = null,
                active = false,
                questions = listOf(),
                dateCreated = null
            )
        }
    }

    @Test
    fun testQuestionnaireSubmission() {
        val studyApi = chronicleClient.studyApi
        val surveyApi = chronicleClient.surveyApi

        val studyId = studyApi.createStudy(TestDataFactory.study())
        val participant = TestDataFactory.participant()
        studyApi.registerParticipant(studyId, participant)

        val questionnaireId = surveyApi.createQuestionnaire(studyId, questionnaire())

        val response1 = QuestionnaireResponse(questionTitle = "Question 1", setOf("answer1", "answer2"))
        val response2 = QuestionnaireResponse(questionTitle = "Question 2", setOf("answer3"))
        val responses = listOf(response1, response2)

        surveyApi.submitQuestionnaireResponses(studyId, participant.participantId, questionnaireId, responses)
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/survey/$studyId/questionnaire/$questionnaireId"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").DELETE().build()
        val rejected = client.send(request, HttpResponse.BodyHandlers.ofString())
        Assert.assertEquals(rejected.body(), 409, rejected.statusCode())
        Assert.assertEquals(questionnaireId, surveyApi.getQuestionnaire(studyId, questionnaireId).id)
    }

    private fun questionnaire(): Questionnaire {
        return Questionnaire(
            id = null,
            title = "Test questionnaire",
            description = "test questionnaire",
            questions = listOf(
                Question(title = "Question 1"),
                Question(title = "Question 2", choices = setOf("choice 1", "choice 2", "choice 3"))
            ),
            dateCreated = null,
            recurrenceRule = null
        )
    }
}
