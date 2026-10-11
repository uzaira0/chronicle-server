package com.openlattice.chronicle.filters

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Through the server's whole security filter chain: the bearer-token filter runs after the API-key filter
 * and would replace the key's identity with the person's (and their admin rights), so a key sent together
 * with a bearer token or an auth cookie is refused before any handler.
 */
class ApiKeyMixedCredentialsTests : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val base = "http://localhost:$testHttpPort/chronicle/v3/study"

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun keySentWithABearerTokenOrAuthCookieIsRefusedBeforeAnyHandler() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "mixed credentials", contact = "test@openlattice.com"))
        val userToken = activeFixtureToken(testUser1)
        val adminToken = activeFixtureToken(adminUser)
        val created = send(
            HttpRequest.newBuilder(URI("$base/$studyId/api-keys"))
                .header("Authorization", "Bearer $userToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"name":"chain","scope":"READ_ONLY","expiresInDays":30}""")),
        )
        assertEquals(created.body(), 200, created.statusCode())
        val rawKey = ObjectMapper().readTree(created.body()).get("rawKey").asText()
        fun getStudy() = HttpRequest.newBuilder(URI("$base/$studyId")).header("X-Api-Key", rawKey).GET()

        assertEquals("the key alone reads its study", 200, send(getStudy()).statusCode())

        val withBearer = send(getStudy().header("Authorization", "Bearer $adminToken"))
        assertEquals(withBearer.body(), 400, withBearer.statusCode())
        val error = ObjectMapper().readTree(withBearer.body())
        assertEquals("Request failed", error.get("message").asText())
        java.util.UUID.fromString(error.get("errorId").asText())

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
