package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.JsonMappingException
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.util.SsrfException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException
import java.util.*

class ChronicleServerExceptionHandlerTest {

    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val config = ErrorSanitizationConfig(
        sanitizeErrors = true,
        includeErrorId = true,
        logFullErrors = false
    )
    private val devConfig = ErrorSanitizationConfig(
        sanitizeErrors = false,
        includeStackTrace = true,
        includeErrorId = true,
        logFullErrors = false
    )

    private lateinit var handler: ChronicleServerExceptionHandler

    @Before
    fun setUp() {
        handler = ChronicleServerExceptionHandler(auditingManager, config)
    }

    private fun mockRequest(uri: String = "/api/test"): MockHttpServletRequest {
        return MockHttpServletRequest("GET", uri)
    }

    // --- handleResponseStatusException ---
    //
    // Regression: with no handler for ResponseStatusException these fell through to
    // handleOtherExceptions, so a controller's deliberate 401/404/400 reached the client as
    // "500 Internal Server Error" while the log recorded the real status. Observed live on
    // POST /chronicle/v3/participant-access/exchange: reusing a spent participant access code
    // logged `401 UNAUTHORIZED "Access code is invalid, expired, revoked, or already used"`
    // and answered 500.

    @Test
    fun testHandleResponseStatusExceptionKeepsTheChosenStatus() {
        val e = ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access code is invalid, expired, revoked, or already used")
        val result = handler.handleResponseStatusException(mockRequest(), e)
        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals(401, result.body?.status)
    }

    @Test
    fun testHandleResponseStatusExceptionKeepsTheControllerReason() {
        val e = ResponseStatusException(HttpStatus.NOT_FOUND, "Participant is not registered")
        val result = handler.handleResponseStatusException(mockRequest(), e)
        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals("Participant is not registered", result.body?.message)
        assertEquals("Not Found", result.body?.error)
    }

    @Test
    fun testHandleResponseStatusExceptionFallsBackToTheStatusPhraseWithoutAReason() {
        val e = ResponseStatusException(HttpStatus.FORBIDDEN)
        val result = handler.handleResponseStatusException(mockRequest(), e)
        assertEquals(HttpStatus.FORBIDDEN, result.statusCode)
        assertEquals("Forbidden", result.body?.message)
    }

    @Test
    fun testHandleResponseStatusExceptionStillSanitizesServerErrors() {
        // A 5xx must not leak the reason, even though the controller supplied one.
        val e = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "postgres pool exhausted on host db-7")
        val result = handler.handleResponseStatusException(mockRequest(), e)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.statusCode)
        assertFalse(result.body?.message!!.contains("db-7"))
    }

    @Test
    fun testHandleResponseStatusExceptionIncludesErrorId() {
        val e = ResponseStatusException(HttpStatus.BAD_REQUEST, "resourceId is only valid for QUESTIONNAIRE access codes")
        val result = handler.handleResponseStatusException(mockRequest(), e)
        assertNotNull(result.body?.errorId)
        assertTrue(result.body?.errorId!!.startsWith("ERR-"))
    }

    // --- handleNullPointerException ---

    @Test
    fun testHandleNullPointerExceptionReturns500() {
        val e = NullPointerException("null ref")
        val result = handler.handleNullPointerException(mockRequest(), e)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
        assertEquals(500, result.body?.status)
    }

    @Test
    fun testHandleNullPointerExceptionIncludesErrorId() {
        val e = NullPointerException("null ref")
        val result = handler.handleNullPointerException(mockRequest(), e)
        assertNotNull(result.body?.errorId)
        assertTrue(result.body?.errorId!!.startsWith("ERR-"))
    }

    @Test
    fun testHandleNullPointerExceptionIncludesPath() {
        val e = NullPointerException("null ref")
        val result = handler.handleNullPointerException(mockRequest("/api/studies"), e)
        assertEquals("/api/studies", result.body?.path)
    }

    @Test
    fun testErrorPathRedactsRequestIdentifiers() {
        val studyId = "550e8400-e29b-41d4-a716-446655440000"
        val participantId = "u15-device-owner"
        val sourceDeviceId = "iphone-idfv"
        val e = NullPointerException("null ref")

        val result = handler.handleNullPointerException(
            mockRequest("/chronicle/v3/study/$studyId/participant/$participantId/ios/$sourceDeviceId/upload?token=secret"),
            e
        )

        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/ios/{sourceDeviceId}/upload",
            result.body?.path
        )
        assertFalse(result.body?.path?.contains(studyId) == true)
        assertFalse(result.body?.path?.contains(participantId) == true)
        assertFalse(result.body?.path?.contains(sourceDeviceId) == true)
        assertFalse(result.body?.path?.contains("token=secret") == true)
    }

    @Test
    fun testHandleNullPointerExceptionGenericMessage() {
        val e = NullPointerException("internal details")
        val result = handler.handleNullPointerException(mockRequest(), e)
        assertTrue(result.body?.message?.contains("unexpected error") == true)
        assertFalse(result.body?.message?.contains("internal details") == true)
    }

    // --- handleNotFoundException ---

    @Test
    fun testHandleStudyNotFoundReturns404() {
        val studyId = UUID.randomUUID()
        val e = StudyNotFoundException(studyId, "study not found")
        val result = handler.handleNotFoundException(mockRequest(), e)
        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals(404, result.body?.status)
    }

    @Test
    fun testHandleCandidateNotFoundReturns404() {
        val candidateId = UUID.randomUUID()
        val e = CandidateNotFoundException(candidateId)
        val result = handler.handleNotFoundException(mockRequest(), e)
        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
    }

    @Test
    fun testHandleOrganizationNotFoundReturns404() {
        val orgId = UUID.randomUUID()
        val e = OrganizationNotFoundException(orgId, "org not found")
        val result = handler.handleNotFoundException(mockRequest(), e)
        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
    }

    @Test
    fun testHandleNoSuchElementReturns404() {
        val e = NoSuchElementException("no element")
        val result = handler.handleNotFoundException(mockRequest(), e)
        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
    }

    @Test
    fun testHandleNotFoundGenericMessage() {
        val e = NoSuchElementException("secret details")
        val result = handler.handleNotFoundException(mockRequest(), e)
        assertEquals("The requested resource was not found", result.body?.message)
    }

    // --- handleIllegalArgumentException ---

    @Test
    fun testHandleIllegalArgumentReturns400() {
        val e = IllegalArgumentException("bad input")
        val result = handler.handleIllegalArgumentException(mockRequest(), e)
        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals(400, result.body?.status)
    }

    @Test
    fun testHandleIllegalArgumentSanitizesMessage() {
        val e = IllegalArgumentException("SELECT * FROM users WHERE id=1")
        val result = handler.handleIllegalArgumentException(mockRequest(), e)
        assertFalse(result.body?.message?.contains("SELECT") == true)
    }

    @Test
    fun testHandleIllegalArgumentIncludesErrorId() {
        val e = IllegalArgumentException("bad input")
        val result = handler.handleIllegalArgumentException(mockRequest(), e)
        assertNotNull(result.body?.errorId)
    }

    // --- handleIllegalStateException ---

    @Test
    fun testHandleIllegalStateReturns409() {
        val e = IllegalStateException("invalid state")
        val result = handler.handleIllegalStateException(mockRequest(), e)
        assertEquals(HttpStatus.CONFLICT, result.statusCode)
        assertEquals(409, result.body?.status)
    }

    @Test
    fun testHandleIllegalStateSanitizesMessage() {
        val e = IllegalStateException("com.openlattice.chronicle.internal.Class failed")
        val result = handler.handleIllegalStateException(mockRequest(), e)
        assertFalse(result.body?.message?.contains("com.openlattice") == true)
    }

    @Test
    fun testHandleDuplicateParticipantConflictDoesNotExposeDatabaseCause() {
        val databaseCause = SQLException("duplicate key value violates unique constraint study_participants_pkey", "23505")
        val e = IllegalStateException("Participant is already registered for this study", databaseCause)

        val result = handler.handleIllegalStateException(mockRequest(), e)

        assertEquals(HttpStatus.CONFLICT, result.statusCode)
        assertEquals("Participant is already registered for this study", result.body?.message)
        assertFalse(result.body?.message?.contains("study_participants") == true)
        assertFalse(result.body?.message?.contains("23505") == true)
    }

    // --- handleAuthenticationException ---

    @Test
    fun testHandleAuthenticationReturns401() {
        val e = BadCredentialsException("invalid token")
        val result = handler.handleAuthenticationException(mockRequest(), e)
        assertEquals(HttpStatus.UNAUTHORIZED, result.statusCode)
        assertEquals(401, result.body?.status)
    }

    @Test
    fun testHandleAuthenticationGenericMessage() {
        val e = BadCredentialsException("token expired at 2024-01-01")
        val result = handler.handleAuthenticationException(mockRequest(), e)
        assertEquals("Authentication required", result.body?.message)
    }

    // --- handleUnauthorizedExceptions (AccessDeniedException) ---

    @Test
    fun testHandleAccessDeniedReturns403() {
        val e = AccessDeniedException("access denied")
        val result = handler.handleUnauthorizedExceptions(mockRequest(), e)
        assertEquals(HttpStatus.FORBIDDEN, result.statusCode)
        assertEquals(403, result.body?.status)
    }

    @Test
    fun testHandleAccessDeniedGenericMessage() {
        val e = AccessDeniedException("user xyz cannot access resource abc")
        val result = handler.handleUnauthorizedExceptions(mockRequest(), e)
        assertEquals("Access denied", result.body?.message)
    }

    // --- handleSqlException ---

    @Test
    fun testHandleSqlExceptionReturns500() {
        val e = SQLException("ERROR: relation does not exist", "42P01")
        val result = handler.handleSqlException(mockRequest(), e)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
        assertEquals(500, result.body?.status)
    }

    @Test
    fun testHandleSqlExceptionNeverExposesSqlDetails() {
        val e = SQLException("ERROR: SELECT * FROM secret_table", "42P01", 1)
        val result = handler.handleSqlException(mockRequest(), e)
        assertFalse(result.body?.message?.contains("SELECT") == true)
        assertFalse(result.body?.message?.contains("secret_table") == true)
    }

    @Test
    fun testHandleSqlExceptionIncludesErrorId() {
        val e = SQLException("db error")
        val result = handler.handleSqlException(mockRequest(), e)
        assertNotNull(result.body?.errorId)
    }

    // --- handleJsonExceptions ---

    @Test
    fun testHandleJsonExceptionReturns400() {
        val e = Mockito.mock(JsonMappingException::class.java)
        Mockito.`when`(e.originalMessage).thenReturn("invalid json field")
        Mockito.`when`(e.message).thenReturn("invalid json field")

        val result = handler.handleJsonExceptions(mockRequest(), e)
        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("Invalid JSON format", result.body?.message)
    }

    // --- handleOtherExceptions ---

    @Test
    fun testHandleOtherExceptionsReturns500InProd() {
        val e = Exception("unexpected error")
        val result = handler.handleOtherExceptions(mockRequest(), e)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
    }

    @Test
    fun testHandleOtherExceptionsGenericMessageInProd() {
        val e = Exception("internal class details exposed")
        val result = handler.handleOtherExceptions(mockRequest(), e)
        assertTrue(result.body?.message?.contains("unexpected error") == true)
    }

    @Test
    fun testHandleOtherExceptionsInDevModeShowsDetails() {
        val devHandler = ChronicleServerExceptionHandler(auditingManager, devConfig)
        val e = Exception("detailed error message")
        val result = devHandler.handleOtherExceptions(mockRequest(), e)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
        assertEquals("detailed error message", result.body?.message)
    }

    @Test
    fun testHandleOtherExceptionsInDevModeDoesNotExposeStackTrace() {
        val devHandler = ChronicleServerExceptionHandler(auditingManager, devConfig)
        val e = Exception("detailed error")
        val result = devHandler.handleOtherExceptions(mockRequest(), e)
        assertNull(result.body?.trace)
    }

    @Test
    fun testHandleOtherExceptionsInProdExcludesStackTrace() {
        val e = Exception("detailed error")
        val result = handler.handleOtherExceptions(mockRequest(), e)
        assertNull(result.body?.trace)
    }

    // --- Error ID uniqueness ---

    @Test
    fun testUniqueErrorIds() {
        val e = RuntimeException("test")
        val req = mockRequest()
        val result1 = handler.handleOtherExceptions(req, e)
        val result2 = handler.handleOtherExceptions(req, e)
        assertNotEquals(result1.body?.errorId, result2.body?.errorId)
    }

    // --- No error ID config ---

    @Test
    fun testNoErrorIdConfig() {
        val noIdHandler = ChronicleServerExceptionHandler(auditingManager, noErrorIdConfig)
        val e = NullPointerException("test")
        val result = noIdHandler.handleNullPointerException(mockRequest(), e)
        assertNull(result.body?.errorId)
    }

    private val noErrorIdConfig = ErrorSanitizationConfig(
        includeErrorId = false
    )

    // --- handleSsrfException ---

    @Test
    fun testHandleSsrfExceptionReturns403() {
        val e = SsrfException(
            com.openlattice.chronicle.util.SsrfViolationType.PRIVATE_IP,
            "http://internal:8080",
            "Blocked private IP"
        )
        val result = handler.handleSsrfException(mockRequest(), e)
        assertEquals(HttpStatus.FORBIDDEN, result.statusCode)
        assertEquals("Access denied", result.body?.message)
    }

    // --- handleMissingServletRequestParameterException ---

    @Test
    fun testHandleMissingParamReturns400() {
        val e = org.springframework.web.bind.MissingServletRequestParameterException("studyId", "UUID")
        val result = handler.handleMissingServletRequestParameterException(mockRequest(), e)
        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("Missing required parameter", result.body?.message)
        assertNotNull(result.body?.details)
    }

    @Test
    fun testHandleMissingParamIncludesParamName() {
        val e = org.springframework.web.bind.MissingServletRequestParameterException("studyId", "UUID")
        val result = handler.handleMissingServletRequestParameterException(mockRequest(), e)
        assertTrue(result.body?.details?.any { it.contains("studyId") } == true)
    }

    @Test
    fun testHandleMissingHeaderReturns400WithoutLeakingValues() {
        val methodParameter = org.springframework.core.MethodParameter(
            String::class.java.getDeclaredMethod("substring", Int::class.javaPrimitiveType),
            0
        )
        val e = org.springframework.web.bind.MissingRequestHeaderException(
            "X-Chronicle-Device-Id",
            methodParameter
        )

        val result = handler.handleMissingRequestHeaderException(mockRequest(), e)

        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
        assertEquals("Missing required header", result.body?.message)
        assertEquals(
            listOf("X-Chronicle-Device-Id: Required header is missing"),
            result.body?.details
        )
    }
}
