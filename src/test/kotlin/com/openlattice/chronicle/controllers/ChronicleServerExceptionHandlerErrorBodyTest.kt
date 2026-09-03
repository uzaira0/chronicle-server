package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.NoHandlerFoundException
import java.util.UUID

/**
 * Locks down what a browser client is allowed to see in a Chronicle error body.
 *
 * This exists because the Compliance-tab 404 was misdiagnosed off the error body: the
 * `{studyId}` segment in `path` was read as an unsubstituted Spring mapping template
 * leaking out of the router. It is not. [com.openlattice.chronicle.util.LogSanitizer]
 * REDACTS the real study UUID and puts `{studyId}` in its place, and these tests assert
 * exactly that — the real identifier must be absent, and the placeholder must be present.
 *
 * The handler is shared: `ChronicleServerExceptionHandler` is the single
 * `@RestControllerAdvice` registered by `ChronicleServerMvcPod`, so this body shape is
 * what EVERY route in the application returns on error.
 */
class ChronicleServerExceptionHandlerErrorBodyTest {

    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val handler = ChronicleServerExceptionHandler(auditingManager, ErrorSanitizationConfig())
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    private val studyId = UUID.randomUUID()
    private val requestUri = "/chronicle/v3/compliance/study/$studyId"

    private fun notFoundBody(): ObjectNode {
        val request = MockHttpServletRequest("GET", requestUri)
        request.requestURI = requestUri
        val response = handler.handleNotFoundException(
            request,
            NoHandlerFoundException("GET", requestUri, HttpHeaders())
        )
        val body = requireNotNull(response.body) { "404 handler returned no body" }
        return mapper.valueToTree(body)
    }

    @Test
    fun testNotFoundBodyRedactsTheRealStudyIdentifier() {
        val json = mapper.writeValueAsString(notFoundBody())

        assertFalse(
            "Error body leaked the real study UUID: $json",
            json.contains(studyId.toString())
        )
        assertEquals(
            "/chronicle/v3/compliance/study/{studyId}",
            notFoundBody().get("path").asText()
        )
    }

    @Test
    fun testNotFoundBodyCarriesOnlyAnOpaqueCorrelationId() {
        val errorId = notFoundBody().get("errorId").asText()

        assertNotNull(errorId)
        // ERR- plus a random UUID and nothing else: no class name, no host, no SQL state,
        // no request content. It is only useful when paired with the server-side log.
        assertTrue(
            "errorId is not an opaque correlation id: $errorId",
            Regex("^ERR-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(errorId)
        )
        // Two requests must not be correlatable to each other by the id.
        assertFalse(errorId == notFoundBody().get("errorId").asText())
    }

    @Test
    fun testNotFoundBodyExposesNoStackTraceOrInternalDetail() {
        val json = notFoundBody()

        assertFalse("Stack trace returned to client", json.has("trace"))
        assertFalse("Validation details returned on a 404", json.has("details"))
        assertEquals("The requested resource was not found", json.get("message").asText())
        assertEquals("Not Found", json.get("error").asText())
        assertEquals(404, json.get("status").asInt())

        val fieldNames = json.fieldNames().asSequence().toSet()
        assertEquals(
            "Unexpected fields in the 404 body: $fieldNames",
            setOf("status", "error", "message", "errorId", "timestamp", "path"),
            fieldNames
        )

        val rendered = mapper.writeValueAsString(json)
        listOf("NoHandlerFoundException", "com.openlattice", "org.springframework", "java.lang")
            .forEach { internal ->
                assertFalse("Error body leaked internal symbol $internal: $rendered", rendered.contains(internal))
            }
    }

    @Test
    fun testErrorIdCanBeSuppressedEntirelyByConfiguration() {
        val strictHandler = ChronicleServerExceptionHandler(
            auditingManager,
            ErrorSanitizationConfig(includeErrorId = false)
        )
        val request = MockHttpServletRequest("GET", requestUri)
        request.requestURI = requestUri
        val body = requireNotNull(
            strictHandler.handleNotFoundException(
                request,
                NoHandlerFoundException("GET", requestUri, HttpHeaders())
            ).body
        )

        assertFalse(mapper.valueToTree<ObjectNode>(body).has("errorId"))
    }
}
