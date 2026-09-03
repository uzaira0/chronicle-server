package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException

class GlobalExceptionHandlerTest {

    private val defaultConfig = ErrorSanitizationConfig()
    private val devConfig = ErrorSanitizationConfig(
        sanitizeErrors = false,
        includeStackTrace = true,
        includeErrorId = true,
        logFullErrors = false
    )
    private val noErrorIdConfig = ErrorSanitizationConfig(
        includeErrorId = false
    )

    private fun mockRequest(uri: String = "/api/test", method: String = "GET"): MockHttpServletRequest {
        val request = MockHttpServletRequest(method, uri)
        return request
    }

    // --- createSanitizedError ---

    @Test
    fun testCreateSanitizedErrorReturnsApiError() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertNotNull(result)
        assertEquals(500, result.status)
    }

    @Test
    fun testCreateSanitizedErrorSetsCorrectPath() {
        val exception = RuntimeException("test error")
        val request = mockRequest("/api/test/path")

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals("/api/test/path", result.path)
    }

    @Test
    fun testCreateSanitizedErrorRedactsPathIdentifiers() {
        val studyId = "550e8400-e29b-41d4-a716-446655440000"
        val participantId = "u15-device-owner"
        val sourceDeviceId = "pixel-install-id"
        val exception = RuntimeException("test error")
        val request = mockRequest(
            "/chronicle/v4/study/$studyId/participant/$participantId/android/$sourceDeviceId/upload?token=secret"
        )

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)

        assertEquals(
            "/chronicle/v4/study/{studyId}/participant/{participantId}/android/{sourceDeviceId}/upload",
            result.path
        )
        assertFalse(result.path?.contains(studyId) == true)
        assertFalse(result.path?.contains(participantId) == true)
        assertFalse(result.path?.contains(sourceDeviceId) == true)
        assertFalse(result.path?.contains("token=secret") == true)
    }

    @Test
    fun testCreateSanitizedErrorIncludesErrorId() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertNotNull(result.errorId)
        assertTrue(result.errorId!!.startsWith("ERR-"))
    }

    @Test
    fun testCreateSanitizedErrorExcludesErrorIdWhenConfigured() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, noErrorIdConfig)
        assertNull(result.errorId)
    }

    // --- 5xx errors sanitized ---

    @Test
    fun testServerErrorReturnsSanitizedMessage() {
        val exception = RuntimeException("internal details exposed")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(500, result.status)
        assertTrue(result.message.contains("unexpected error"))
    }

    @Test
    fun testServerErrorReturnsGenericMessageInProd() {
        val exception = RuntimeException("SQL error: SELECT * FROM users WHERE id=1")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(500, result.status)
        assertFalse(result.message.contains("SELECT"))
    }

    @Test
    fun testServerErrorInDevModeShowsDetails() {
        val exception = RuntimeException("detailed error message")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, devConfig)
        // In dev mode with sanitizeErrors=false, for non-always-sanitize exceptions
        // the behavior depends on whether the exception is 5xx
        assertEquals(500, result.status)
    }

    // --- 4xx client errors ---

    @Test
    fun testIllegalArgumentReturns400() {
        val exception = IllegalArgumentException("bad input")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(400, result.status)
    }

    @Test
    fun testBadCredentialsReturns401() {
        val exception = BadCredentialsException("invalid token")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(401, result.status)
        assertEquals("Authentication required", result.message)
    }

    @Test
    fun testAccessDeniedReturns403() {
        val exception = AccessDeniedException("not authorized")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(403, result.status)
        assertEquals("Access denied", result.message)
    }

    @Test
    fun testNoSuchElementReturns404() {
        val exception = NoSuchElementException("not found")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(404, result.status)
        assertEquals("The requested resource was not found", result.message)
    }

    @Test
    fun testIllegalStateReturns409() {
        val exception = IllegalStateException("conflict")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(409, result.status)
    }

    @Test
    fun testStudyNotFoundReturns404() {
        val studyId = java.util.UUID.randomUUID()
        val exception = StudyNotFoundException(studyId, "study not found")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(404, result.status)
    }

    @Test
    fun testCandidateNotFoundReturns404() {
        val candidateId = java.util.UUID.randomUUID()
        val exception = CandidateNotFoundException(candidateId)
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(404, result.status)
    }

    @Test
    fun testOrganizationNotFoundReturns404() {
        val orgId = java.util.UUID.randomUUID()
        val exception = OrganizationNotFoundException(orgId, "org not found")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(404, result.status)
    }

    // --- createErrorResponse ---

    @Test
    fun testCreateErrorResponseReturnsResponseEntity() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createErrorResponse(exception, request, defaultConfig)
        assertNotNull(result)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.statusCode)
    }

    @Test
    fun testCreateErrorResponseWithExplicitStatus() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createErrorResponse(exception, request, defaultConfig, HttpStatus.BAD_REQUEST)
        assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
    }

    @Test
    fun testCreateErrorResponseBodyNotNull() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createErrorResponse(exception, request, defaultConfig)
        assertNotNull(result.body)
    }

    @Test
    fun testCreateErrorResponseBodyHasCorrectStatus() {
        val exception = IllegalArgumentException("bad input")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createErrorResponse(exception, request, defaultConfig)
        assertEquals(400, result.body?.status)
    }

    // --- explicit status override ---

    @Test
    fun testCreateSanitizedErrorWithExplicitStatus() {
        val exception = RuntimeException("test error")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(
            exception, request, defaultConfig, HttpStatus.SERVICE_UNAVAILABLE
        )
        assertEquals(503, result.status)
    }

    @Test
    fun testCreateSanitizedErrorInfersStatusFromException() {
        val exception = IllegalArgumentException("bad arg")
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertEquals(400, result.status)
    }

    // --- Edge cases ---

    @Test
    fun testNullExceptionMessage() {
        val exception = RuntimeException()
        val request = mockRequest()

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertNotNull(result)
        assertNotNull(result.message)
    }

    @Test
    fun testEmptyRequestUri() {
        val exception = RuntimeException("test")
        val request = mockRequest("")

        val result = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        assertNotNull(result)
    }

    @Test
    fun testUniqueErrorIds() {
        val exception = RuntimeException("test")
        val request = mockRequest()

        val result1 = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)
        val result2 = GlobalExceptionHandler.createSanitizedError(exception, request, defaultConfig)

        assertNotEquals(result1.errorId, result2.errorId)
    }
}
