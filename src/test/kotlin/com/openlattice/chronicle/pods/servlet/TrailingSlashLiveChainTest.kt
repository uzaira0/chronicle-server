package com.openlattice.chronicle.pods.servlet

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.rhizome.configuration.websockets.BaseRhizomeServer
import com.geekbeast.rhizome.core.JettyLoam
import com.geekbeast.rhizome.core.Rhizome
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import jakarta.servlet.http.HttpServletRequest
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.DelegatingFilterProxy
import org.springframework.web.filter.UrlHandlerFilter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Real HTTP includes the servlet normalizer, security proxy, path-sensitive filters, and MVC. */
class TrailingSlashLiveChainTest : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val origin = "http://localhost:$testHttpPort"
    private val studyBase = "/chronicle/v3/study"

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun authenticatedAndPermitAllRoutesAcceptBothPaths() {
        for (path in listOf(studyBase, "$studyBase/")) {
            val response = send(HttpRequest.newBuilder(URI("$origin$path"))
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").GET())
            assertEquals(response.body(), 200, response.statusCode())
            assertEquals(401, send(HttpRequest.newBuilder(URI("$origin$path")).GET()).statusCode())
        }
        for (path in listOf("/chronicle/internal/health/live", "/chronicle/internal/health/live/")) {
            val response = send(HttpRequest.newBuilder(URI("$origin$path")).GET())
            assertEquals(response.body(), 204, response.statusCode())
        }
    }

    @Test
    fun researcherWriteKeyReachesDataCollectionWithEitherPath() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "slash compatibility", contact = "test@openlattice.com"))
        val created = send(HttpRequest.newBuilder(URI("$origin$studyBase/$studyId/api-keys"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"slash","scope":"WRITE","expiresInDays":30}""")))
        assertEquals(created.body(), 200, created.statusCode())
        val mapper = ObjectMappers.getJsonMapper()
        val key = mapper.readTree(created.body()).get("rawKey").asText()
        val body = mapper.writeValueAsString(ChronicleDataCollectionSettings())
        val path = "$studyBase/$studyId${StudyApi.DATA_COLLECTION}"
        val responses = listOf(path, "$path/").map { uri ->
            send(HttpRequest.newBuilder(URI("$origin$uri"))
                .header("X-Api-Key", key).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)))
        }
        // New studies use Android settings; the legacy PUT reaches the controller and refuses replacement.
        assertEquals(responses[0].body(), 409, responses[0].statusCode())
        assertEquals(responses[1].body(), responses[0].statusCode(), responses[1].statusCode())
    }

    @Test
    fun registeredNormalizerPrecedesSecurityAndLeavesRootUntouched() {
        val rhizome = BaseRhizomeServer::class.java.getDeclaredField("rhizome")
            .apply { isAccessible = true }.get(testServer)
        val jetty = Rhizome::class.java.getDeclaredField("jetty")
            .apply { isAccessible = true }.get(rhizome) as JettyLoam
        val handler = jetty.server.getDescendants(ServletContextHandler::class.java).single()
        val filters = handler.servletHandler.filters
        val normalizer = filters.single { it.filter is UrlHandlerFilter }
        val proxy = filters.single { it.filter is DelegatingFilterProxy }
        val names = handler.servletHandler.filterMappings.map { it.filterName }
        assertEquals(0, names.indexOf(normalizer.name))
        assertTrue(names.indexOf(normalizer.name) < names.indexOf(proxy.name))
        val root = MockHttpServletRequest("GET", "/").apply { pathInfo = "/" }
        normalizer.filter.doFilter(root, MockHttpServletResponse()) { request, _ ->
            assertSame(root, request)
            assertEquals("/", (request as HttpServletRequest).requestURI)
            assertEquals("/", request.pathInfo)
        }
        assertNull(send(HttpRequest.newBuilder(URI("$origin/")).GET()).headers().firstValue("Location").orElse(null))
    }
}
