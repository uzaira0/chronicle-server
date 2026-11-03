package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.services.studies.EnrollmentDisclosurePendingException

import com.fasterxml.jackson.databind.JsonMappingException
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.util.SsrfException
import com.openlattice.chronicle.util.SsrfViolationType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.NoHandlerFoundException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Guards the PHI-in-logs redaction in [ChronicleServerExceptionHandler]. Mobile collection-upload
 * bodies can carry participant data, so the handler must log only structural metadata — the JSON
 * field PATH and the body SIZE — never the offending VALUE or the raw body.
 *
 * This attaches an in-memory Log4j2 appender to the handler's logger, drives the two redaction
 * branches with payloads containing a sentinel "PHI" string, and asserts the captured log lines
 * carry the structural hint but never the sentinel. It would fail if a future change reverted to
 * logging `e.originalMessage` / the raw request body. Complements
 * [ChronicleServerExceptionHandlerTest], which covers the client-facing response shape.
 */
class ChronicleServerExceptionHandlerLogRedactionTest {

    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val config = ErrorSanitizationConfig(sanitizeErrors = true, includeErrorId = true, logFullErrors = false)
    private val handler = ChronicleServerExceptionHandler(auditingManager, config)

    private lateinit var capture: ScopedLogCapture
    private val captured get() = capture.messages

    @Before
    fun attachAppender() {
        capture = ScopedLogCapture(ChronicleServerExceptionHandler::class.java, pattern = "%m")
    }

    @After
    fun detachAppender() {
        capture.close()
    }

    private fun mockRequest() = MockHttpServletRequest("POST", "/chronicle/v4/study/x/participant/p/android/sleep")

    @Test
    fun pendingEnrollmentIdsStayInConflictBodyAndOutOfLogs() {
        val ids = listOf("participant-private-sentinel-one", "participant-private-sentinel-two")
        for (fullErrors in listOf(false, true)) {
            val configuredHandler = ChronicleServerExceptionHandler(
                auditingManager, config.copy(logFullErrors = fullErrors),
            )
            val response = configuredHandler.handleResponseStatusException(
                MockHttpServletRequest("PATCH", "/chronicle/v3/study/x/settings"),
                EnrollmentDisclosurePendingException(ids),
            )
            assertEquals(409, response.statusCode.value())
            assertEquals(ids, response.body?.blockingParticipantIds)
            ids.forEach { assertTrue(response.body?.message?.contains(it) == true) }
        }
        assertTrue(captured.isNotEmpty())
        ids.forEach { assertFalse(captured.joinToString("\n").contains(it)) }
    }

    @Test
    fun postgresDiagnosticsNeverLogPayloadIncludingWrappedCauses() {
        val sentinel = "uploadedDiaryPayloadUniqueSentinel"
        val error = org.postgresql.util.PSQLException(
            org.postgresql.util.ServerErrorMessage("SERROR\u0000C22021\u0000M$sentinel\u0000D$sentinel\u0000W$sentinel\u0000\u0000"),
        )
        handler.handleSqlException(mockRequest(), error)
        handler.handleOtherExceptions(mockRequest(), IllegalStateException(sentinel, error))
        assertTrue(captured.isNotEmpty())
        assertFalse(captured.joinToString("\n").contains(sentinel))
    }

    @Test
    fun jsonMappingErrorLogsFieldPathNeverValue() {
        val phi = "PATIENT-SSN-987654321"
        val e = Mockito.mock(JsonMappingException::class.java)
        Mockito.`when`(e.path).thenReturn(listOf(JsonMappingException.Reference("body", "timestamp")))
        // originalMessage embeds the offending VALUE — exactly what the redaction must NOT log.
        Mockito.`when`(e.originalMessage).thenReturn("Cannot deserialize value '$phi'")
        Mockito.`when`(e.message).thenReturn("Cannot deserialize value '$phi'")

        val response = handler.handleJsonExceptions(mockRequest(), e)

        val log = captured.joinToString("\n")
        assertTrue("expected the field path 'timestamp' in the log, got: $log", log.contains("timestamp"))
        assertFalse("PHI value leaked into logs: $log", log.contains(phi))
        // And the client-facing response is generic, never the value.
        assertFalse(response.body?.message?.contains(phi) == true)
    }

    @Test
    fun unreadableBodyLogsLengthNeverContent() {
        val phi = "free-text-diary-entry-PATIENT-NAME-Jane-Doe"
        val bodyBytes = phi.toByteArray(Charsets.UTF_8)
        val inputMessage = object : HttpInputMessage {
            override fun getBody(): InputStream = ByteArrayInputStream(bodyBytes)
            override fun getHeaders(): HttpHeaders = HttpHeaders()
        }
        // The exception MESSAGE is generic; the PHI lives only in the body bytes, which the handler
        // must summarize by size only.
        val e = HttpMessageNotReadableException("JSON parse error", inputMessage)

        val response = handler.handleIllegalArgumentException(mockRequest(), e)

        val log = captured.joinToString("\n")
        assertTrue("expected the byte length in the log, got: $log", log.contains("length=${bodyBytes.size}"))
        assertFalse("raw body content leaked into logs: $log", log.contains("Jane-Doe"))
        assertFalse(response.body?.message?.contains("Jane-Doe") == true)
    }

    @Test
    fun ssrfViolationLogsStableTargetRefNeverRawTargetOrIp() {
        val secretTarget = "https://metadata.google.internal/latest/meta-data?token=SUPER-SECRET"
        val request = MockHttpServletRequest("POST", "/chronicle/v3/study/x/webhooks").apply {
            remoteAddr = "203.0.113.99"
        }
        val e = SsrfException(
            violationType = SsrfViolationType.METADATA_ENDPOINT,
            targetUrl = secretTarget,
            message = "SSRF blocked"
        )

        val response = handler.handleSsrfException(request, e)

        val log = captured.joinToString("\n")
        assertTrue("expected stable SSRF target reference in log, got: $log", log.contains("targetRef: ssrf:"))
        assertTrue("expected stable IP reference in log, got: $log", log.contains("ipRef: ip:"))
        assertFalse("raw SSRF target leaked into logs: $log", log.contains("metadata.google.internal"))
        assertFalse("query secret leaked into logs: $log", log.contains("SUPER-SECRET"))
        assertFalse("raw remote IP leaked into logs: $log", log.contains("203.0.113.99"))
        assertFalse(response.body?.message?.contains("metadata.google.internal") == true)
    }

    @Test
    fun missingRouteOmitsFrameworkMessageWithParticipantPath() {
        val studyId = "00000000-0000-0000-8000-0000000005f1"
        val participantId = "pixel-all-20260713-v49"
        val path = "/chronicle/v4/study/$studyId/participant/$participantId/reminders"
        val request = MockHttpServletRequest("POST", path)
        val exception = NoHandlerFoundException("POST", path, HttpHeaders())

        handler.handleNotFoundException(request, exception)

        val log = captured.joinToString("\n")
        assertFalse("study identifier leaked into logs: $log", log.contains(studyId))
        assertFalse("participant identifier leaked into logs: $log", log.contains(participantId))
        assertTrue(log.contains("URI: unmapped-route"))
        assertTrue(log.contains("routeRef: route:"))
        assertTrue(log.contains("request-derived message omitted"))
    }

    @Test
    fun missingArbitraryRouteOmitsUnknownPathContent() {
        val patientMarker = "patient-jane-doe"
        val path = "/chronicle/no-such-route/$patientMarker"
        val request = MockHttpServletRequest("POST", path)

        handler.handleNotFoundException(request, NoHandlerFoundException("POST", path, HttpHeaders()))

        val log = captured.joinToString("\n")
        assertFalse("arbitrary path content leaked into logs: $log", log.contains(patientMarker))
        assertTrue(log.contains("URI: unmapped-route"))
        assertTrue(log.contains("routeRef: route:"))
    }
}
