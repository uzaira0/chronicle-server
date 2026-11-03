package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.rhizome.core.RhizomeSecurity
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.API_KEY_HEADER
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.SpringServletContainerInitializer
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.filter.DelegatingFilterProxy
import org.springframework.web.filter.UrlHandlerFilter

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
            .header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, java.util.UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"slash","scope":"WRITE","expiresInDays":30}""")))
        assertEquals(created.body(), 200, created.statusCode())
        val mapper = ObjectMappers.getJsonMapper()
        val key = mapper.readTree(created.body()).get("rawKey").asText()
        val body = mapper.writeValueAsString(CollectionDefaults.androidDataCollectionSetting())
        val path = "$studyBase/$studyId/settings/type/DataCollection"
        val responses = listOf(path, "$path/").map { uri ->
            val current = send(HttpRequest.newBuilder(URI("$origin$studyBase/$studyId/settings"))
                .header(API_KEY_HEADER, key).GET())
            assertEquals(current.body(), 200, current.statusCode())
            send(HttpRequest.newBuilder(URI("$origin$uri"))
                .header(API_KEY_HEADER, key).header("Content-Type", "application/json")
                .header(EditRevisionApi.IF_MATCH, current.headers().firstValue("ETag").orElseThrow())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)))
        }
        // Both canonical paths reach the current per-type settings handler.
        assertEquals(responses[0].body(), 200, responses[0].statusCode())
        assertEquals(responses[1].body(), responses[0].statusCode(), responses[1].statusCode())
    }

    @Test
    fun registeredNormalizerPrecedesSecurityAndLeavesRootUntouched() {
        val handler = ServletContextHandler(ServletContextHandler.SESSIONS)
        GenericWebApplicationContext(handler.servletContext).use { context ->
            context.beanFactory.registerSingleton("springSecurityFilterChain",
                Filter { request, response, chain -> chain.doFilter(request, response) })
            context.refresh()
            handler.servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context)
            // Use the real SCI during Jetty STARTING; a fixture subclass would be auto-discovered too.
            handler.addServletContainerInitializer(SpringServletContainerInitializer(), RhizomeSecurity::class.java)
            val server = Server().apply { this.handler = handler }
            try {
                server.start()
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
            } finally {
                server.stop()
            }
        }
        assertNull(send(HttpRequest.newBuilder(URI("$origin/")).GET()).headers().firstValue("Location").orElse(null))
    }
}
