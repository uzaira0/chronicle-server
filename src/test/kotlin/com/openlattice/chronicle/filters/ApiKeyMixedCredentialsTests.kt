package com.openlattice.chronicle.filters

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.generated.BrowserDownloadContracts
import com.openlattice.chronicle.generated.BrowserErrorContracts
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.observability.BrowserErrorKind
import java.util.Optional
import java.util.UUID
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.AppUsageSurveySetting
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyLifecycleApi
import com.openlattice.chronicle.webhooks.WebhookApi
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.openlattice.chronicle.configuration.MobileApiSignatureFilterTest
import com.openlattice.chronicle.util.API_KEY_HEADER
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Through the server's whole security filter chain: the bearer-token filter runs after the API-key filter
 * and would replace the key's identity with the person's (and their admin rights), so a key sent together
 * with a bearer token or an auth cookie is refused before any handler.
 */
class ApiKeyMixedCredentialsTests : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val base = "http://localhost:$testHttpPort/chronicle/v3/study"
    private val mapper = ObjectMappers.newJsonMapper()

    private fun createKey(studyId: UUID, scope: ApiKeyScope): String {
        val created = send(
            HttpRequest.newBuilder(URI("$base/$studyId/api-keys"))
                .header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(ApiKeyCreateRequest("chain", scope, 30)))),
        )
        assertEquals(created.body(), 200, created.statusCode())
        return mapper.readTree(created.body()).get("rawKey").asText()
    }

    private fun readModifyWrite(path: String, key: String, update: Any): HttpResponse<String> {
        fun request() = HttpRequest.newBuilder(URI(path)).header(API_KEY_HEADER, key)
        val before = send(request().GET())
        assertEquals(before.body(), 200, before.statusCode())
        val revision = before.headers().firstValue(EditRevisionApi.ETAG).orElseThrow()
        val head = send(request().method("HEAD", HttpRequest.BodyPublishers.noBody()))
        assertEquals(head.body(), 204, head.statusCode())
        assertEquals(revision, head.headers().firstValue(EditRevisionApi.ETAG).orElseThrow())
        val patched = send(request().header(EditRevisionApi.IF_MATCH, revision).header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(update))))
        assertEquals(patched.body(), 200, patched.statusCode())
        val after = send(request().GET())
        assertEquals(after.body(), 200, after.statusCode())
        org.junit.Assert.assertNotEquals(revision, after.headers().firstValue(EditRevisionApi.ETAG).orElseThrow())
        return after
    }

    @Test
    fun writeKeyReadsAndEditsParticipantAnnotations() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "key annotations", contact = "test@openlattice.com"))
        val participant = TestDataFactory.participant()
        clientUser1.studyApi.registerParticipant(studyId, participant)
        val result = readModifyWrite("$base/$studyId/participant/${participant.participantId}/annotations",
            createKey(studyId, ApiKeyScope.WRITE), ParticipantAnnotationsUpdate(Optional.of("updated by key")))
        assertEquals("updated by key", mapper.readTree(result.body()).get("participantNotes").asText())
    }

    @Test
    fun writeKeyReadsAndEditsQuestionnaire() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "key questionnaire", contact = "test@openlattice.com"))
        val id = clientUser1.surveyApi.createQuestionnaire(studyId,
            Questionnaire(null, "Original", null, questions = listOf(Question("Question")), recurrenceRule = null))
        val path = "http://localhost:$testHttpPort/chronicle/v3/survey/$studyId/questionnaire/$id"
        val update = QuestionnaireUpdate("Updated", null, active = null, questions = null)
        val result = readModifyWrite(path, createKey(studyId, ApiKeyScope.WRITE), update)
        assertEquals("Updated", mapper.readTree(result.body()).get("title").asText())
    }

    @Test
    fun writeKeyCreatesAQuestionnaireAndReplaysItsOwnCommittedReceipt() {
        val study = clientUser1.studyApi.createStudy(Study(title = "key receipt", contact = "test@openlattice.com"))
        val rawKey = createKey(study, ApiKeyScope.WRITE)
        val intent = UUID.randomUUID().toString()
        val payload = mapper.writeValueAsString(Questionnaire(null, "Created by key", null,
            questions = listOf(Question("Question")), recurrenceRule = null))
        fun request() = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/survey/$study/questionnaire"))
            .header(API_KEY_HEADER, rawKey).header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, intent)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload))
        val created = send(request())
        assertEquals(created.body(), 200, created.statusCode())
        val replay = send(request())
        assertEquals(replay.body(), 200, replay.statusCode())
        assertEquals(created.body(), replay.body())
        assertEquals("true", replay.headers().firstValue(CreateReceiptFilter.REPLAYED_HEADER).orElseThrow())
        assertEquals(1, clientUser1.surveyApi.getStudyQuestionnaires(study).size)
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    @Test fun webhookAndCloneCreatesReplayTheCommittedResultAndRequireAnIntentKey() {
        val study = clientUser1.studyApi.createStudy(Study(title = "replayed creates", contact = "test@openlattice.com"))
        val creates = mapOf(
            WebhookApi.WEBHOOKS_PATH to WebhookCreateRequest("https://example.org/fixture", MobileApiSignatureFilterTest.SECRET,
                setOf(WebhookEventType.DATA_SUBMITTED)),
            StudyLifecycleApi.CLONE_PATH to StudyCloneRequest(),
        )
        for ((path, body) in creates) {
            val payload = mapper.writeValueAsString(body)
            val key = UUID.randomUUID().toString()
            fun request() = HttpRequest.newBuilder(URI("$base/$study$path"))
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload))
            assertEquals(428, send(request()).statusCode())
            val created = send(request().header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, key))
            assertEquals(created.body(), 200, created.statusCode())
            val replayed = send(request().header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, key))
            assertEquals(replayed.body(), 200, replayed.statusCode())
            assertEquals(created.body(), replayed.body())
            assertEquals("true", replayed.headers().firstValue(CreateReceiptFilter.REPLAYED_HEADER).orElseThrow())
        }
        val listed = send(HttpRequest.newBuilder(URI("$base/$study${WebhookApi.WEBHOOKS_PATH}"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").GET())
        assertEquals(listed.body(), 200, listed.statusCode())
        assertEquals(1, mapper.readTree(listed.body()).size())
    }

    @Test fun browserFailureCountersAcceptTheBrowserCookieAndCsrfContract() {
        val token = activeFixtureToken(testUser1)
        val path = URI("http://localhost:$testHttpPort${BrowserErrorContracts.BROWSER_ERROR_REPORT_PATH}")
        for (kind in BrowserErrorKind.entries) {
            val request = HttpRequest.newBuilder(path).header("Content-Type", "application/json")
                .header("Cookie", "${AuthTokenController.AUTH_COOKIE_NAME}=$token; ${AuthTokenController.CSRF_COOKIE_NAME}=fixture-csrf")
                .header(ChronicleHeaders.RESEARCHER_CSRF_HEADER, "fixture-csrf")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("kind" to kind))))
            val response = send(request)
            assertEquals(response.body(), 204, response.statusCode())
        }
    }

    @Test
    fun settingsWritesRequireARevisionAndRejectUnconditionalWildcards() {
        val study = clientUser1.studyApi.createStudy(Study(title = "required revision", contact = "test@openlattice.com"))
        val path = "$base/$study/settings/type/AppUsageSurvey"
        val auth = "Bearer ${activeFixtureToken(testUser1)}"
        val revision = send(HttpRequest.newBuilder(URI("$base/$study/settings")).header("Authorization", auth).GET())
            .headers().firstValue(EditRevisionApi.ETAG).orElseThrow()
        for (precondition in listOf(null, "*", revision)) {
            val request = HttpRequest.newBuilder(URI(path)).header("Authorization", auth).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(AppUsageSurveySetting())))
            if (precondition != null) request.header(EditRevisionApi.IF_MATCH, precondition)
            val result = send(request)
            assertEquals(result.body(), if (precondition == revision) 200 else 428, result.statusCode())
        }
    }

    @Test
    fun researcherCredentialsCannotReadAnotherStudysParticipantInstrumentSettings() {
        val study = clientUser1.studyApi.createStudy(Study(title = "settings ACL", contact = "test@openlattice.com"))
        val questionnaire = clientUser1.surveyApi.createQuestionnaire(study,
            Questionnaire(null, "Scoped instrument", null, questions = listOf(Question("Question")), recurrenceRule = null))
        val unknownStudy = UUID.randomUUID()
        for (path in listOf("survey/$study/app-usage-frequency", "time-use-diary/$study/settings",
                "survey/$study/questionnaire/$questionnaire", "survey/$study/questionnaire")) {
            val url = URI("http://localhost:$testHttpPort/chronicle/v3/$path")
            assertEquals(200, send(HttpRequest.newBuilder(url).header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").GET()).statusCode())
            val denied = send(HttpRequest.newBuilder(url).header("Authorization", "Bearer ${activeFixtureToken(testUser2)}").GET())
            assertEquals(denied.body(), 403, denied.statusCode())
            val unknownUrl = URI(url.toString().replace(study.toString(), unknownStudy.toString()))
            val unknown = send(HttpRequest.newBuilder(unknownUrl)
                .header("Authorization", "Bearer ${activeFixtureToken(testUser2)}").GET())
            assertEquals("Unknown studies must have the same authorization denial: ${unknown.body()}", 403, unknown.statusCode())
        }
    }

    @Test
    fun removedImportRoutesAreNotRegistered() {
        for (route in listOf("studies", "participants", "permissions", "participant-stats", "app-usage-survey", "system-apps", "time-use-diary")) {
            val response = send(HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/import/$route"))
                .header("Authorization", "Bearer ${activeFixtureToken(adminUser)}")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")))
            assertEquals(response.body(), 404, response.statusCode())
        }
    }

    @Test
    fun nativePostDownloadsWithAnExpiredSessionGoToSignIn() {
        val study = UUID.randomUUID()
        val questionnaire = UUID.randomUUID()
        val paths = listOf("study/$study/participants/data", "time-use-diary/$study/data",
            "time-use-diary/$study/participants/data", "survey/$study/questionnaire/$questionnaire/data")
        for (path in paths) {
            val response = send(HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", "${AuthTokenController.AUTH_COOKIE_NAME}=expired; ${AuthTokenController.CSRF_COOKIE_NAME}=csrf")
                .POST(HttpRequest.BodyPublishers.ofString("${BrowserDownloadContracts.BROWSER_DOWNLOAD_CSRF_FIELD}=csrf")))
            assertEquals(response.body(), 303, response.statusCode())
            // The dashboard is served under the same /chronicle prefix as this API; a bare /login is a 404 there.
            assertEquals("/chronicle" + BrowserDownloadContracts.BROWSER_DOWNLOAD_REAUTHENTICATION_PATH,
                response.headers().firstValue("Location").orElseThrow())
        }
    }

    @Test
    fun keySentWithABearerTokenOrAuthCookieIsRefusedBeforeAnyHandler() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "mixed credentials", contact = "test@openlattice.com"))
        val userToken = activeFixtureToken(testUser1)
        val adminToken = activeFixtureToken(adminUser)
        val rawKey = createKey(studyId, ApiKeyScope.READ_ONLY)
        fun getStudy() = HttpRequest.newBuilder(URI("$base/$studyId")).header(API_KEY_HEADER, rawKey).GET()

        assertEquals("the key alone reads its study", 200, send(getStudy()).statusCode())

        val withBearer = send(getStudy().header("Authorization", "Bearer $adminToken"))
        assertEquals(withBearer.body(), 400, withBearer.statusCode())
        val error = ObjectMapper().readTree(withBearer.body())
        assertEquals("Request failed", error.get("message").asText())
        val errorId = error.path("errorId").asText()
        assertTrue(errorId.startsWith("ERR-"))
        UUID.fromString(errorId.removePrefix("ERR-"))

        val withCookie = send(
            getStudy()
                .header("Cookie", "chronicle_auth=$adminToken; ol_csrf_token=csrf-value")
                .header("X-CSRF-Token", "csrf-value"),
        )
        assertEquals(withCookie.body(), 400, withCookie.statusCode())

        assertEquals("a person's token alone still works", 200,
            send(HttpRequest.newBuilder(URI("$base/$studyId")).header("Authorization", "Bearer $userToken").GET()).statusCode())
    }
}
