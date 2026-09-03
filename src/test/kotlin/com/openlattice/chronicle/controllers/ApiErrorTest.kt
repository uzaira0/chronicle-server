package com.openlattice.chronicle.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {

    // --- generateErrorId ---

    @Test
    fun testGenerateErrorIdNotNull() {
        val errorId = ApiError.generateErrorId()
        assertNotNull(errorId)
    }

    @Test
    fun testGenerateErrorIdStartsWithERR() {
        val errorId = ApiError.generateErrorId()
        assertTrue(errorId.startsWith("ERR-"))
    }

    @Test
    fun testGenerateErrorIdIsUnique() {
        val id1 = ApiError.generateErrorId()
        val id2 = ApiError.generateErrorId()
        assertNotEquals(id1, id2)
    }

    // --- factory methods ---

    @Test
    fun testInternalServerError() {
        val error = ApiError.internalServerError()
        assertEquals(500, error.status)
        assertEquals("Internal Server Error", error.error)
        assertTrue(error.message.contains("unexpected error"))
        assertNotNull(error.errorId)
    }

    @Test
    fun testInternalServerErrorWithPath() {
        val error = ApiError.internalServerError(path = "/api/test")
        assertEquals("/api/test", error.path)
    }

    @Test
    fun testInternalServerErrorWithCustomErrorId() {
        val error = ApiError.internalServerError(errorId = "ERR-custom")
        assertEquals("ERR-custom", error.errorId)
    }

    @Test
    fun testInternalServerErrorWithNullErrorId() {
        val error = ApiError.internalServerError(errorId = null)
        assertNull(error.errorId)
    }

    @Test
    fun testBadRequest() {
        val error = ApiError.badRequest(message = "invalid input")
        assertEquals(400, error.status)
        assertEquals("Bad Request", error.error)
        assertEquals("invalid input", error.message)
    }

    @Test
    fun testBadRequestWithDetails() {
        val details = listOf("field1: required", "field2: too long")
        val error = ApiError.badRequest(message = "validation failed", details = details)
        assertNotNull(error.details)
        assertEquals(2, error.details?.size)
    }

    @Test
    fun testBadRequestWithPath() {
        val error = ApiError.badRequest(message = "bad", path = "/api/studies")
        assertEquals("/api/studies", error.path)
    }

    @Test
    fun testBadRequestWithErrorId() {
        val error = ApiError.badRequest(message = "bad", errorId = "ERR-123")
        assertEquals("ERR-123", error.errorId)
    }

    @Test
    fun testUnauthorized() {
        val error = ApiError.unauthorized()
        assertEquals(401, error.status)
        assertEquals("Unauthorized", error.error)
        assertEquals("Authentication required", error.message)
    }

    @Test
    fun testUnauthorizedWithPath() {
        val error = ApiError.unauthorized(path = "/api/secure")
        assertEquals("/api/secure", error.path)
    }

    @Test
    fun testForbidden() {
        val error = ApiError.forbidden()
        assertEquals(403, error.status)
        assertEquals("Forbidden", error.error)
        assertEquals("Access denied", error.message)
    }

    @Test
    fun testForbiddenWithPath() {
        val error = ApiError.forbidden(path = "/api/admin")
        assertEquals("/api/admin", error.path)
    }

    @Test
    fun testNotFound() {
        val error = ApiError.notFound()
        assertEquals(404, error.status)
        assertEquals("Not Found", error.error)
        assertEquals("The requested resource was not found", error.message)
    }

    @Test
    fun testNotFoundWithPath() {
        val error = ApiError.notFound(path = "/api/missing")
        assertEquals("/api/missing", error.path)
    }

    @Test
    fun testTooManyRequests() {
        val error = ApiError.tooManyRequests()
        assertEquals(429, error.status)
        assertEquals("Too Many Requests", error.error)
        assertTrue(error.message.contains("Rate limit exceeded"))
    }

    @Test
    fun testTooManyRequestsWithRetryAfter() {
        val error = ApiError.tooManyRequests(retryAfterSeconds = 30)
        assertTrue(error.message.contains("30"))
    }

    @Test
    fun testTooManyRequestsWithoutRetryAfter() {
        val error = ApiError.tooManyRequests()
        assertTrue(error.message.contains("try again later"))
    }

    // --- data class behavior ---

    @Test
    fun testApiErrorTimestampNotNull() {
        val error = ApiError(status = 200, error = "OK", message = "success")
        assertNotNull(error.timestamp)
    }

    @Test
    fun testApiErrorDefaults() {
        val error = ApiError(status = 200, error = "OK", message = "success")
        assertNull(error.errorId)
        assertNull(error.path)
        assertNull(error.details)
        assertNull(error.trace)
    }

    @Test
    fun testApiErrorWithAllFields() {
        val error = ApiError(
            status = 400,
            error = "Bad Request",
            message = "bad",
            errorId = "ERR-1",
            path = "/api",
            details = listOf("detail1"),
            trace = "stack trace"
        )
        assertEquals(400, error.status)
        assertEquals("Bad Request", error.error)
        assertEquals("bad", error.message)
        assertEquals("ERR-1", error.errorId)
        assertEquals("/api", error.path)
        assertEquals(1, error.details?.size)
        assertEquals("stack trace", error.trace)
    }

    @Test
    fun testApiErrorEquality() {
        val timestamp = java.time.OffsetDateTime.now()
        val error1 = ApiError(status = 200, error = "OK", message = "ok", timestamp = timestamp)
        val error2 = ApiError(status = 200, error = "OK", message = "ok", timestamp = timestamp)
        assertEquals(error1, error2)
    }

    @Test
    fun testApiErrorInequality() {
        val timestamp = java.time.OffsetDateTime.now()
        val error1 = ApiError(status = 200, error = "OK", message = "ok", timestamp = timestamp)
        val error2 = ApiError(status = 400, error = "Bad", message = "bad", timestamp = timestamp)
        assertNotEquals(error1, error2)
    }
}
