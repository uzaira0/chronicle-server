package com.openlattice.chronicle.filters

import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletResponse

internal fun assertFilterError(
    response: MockHttpServletResponse,
    status: Int,
    message: String,
    code: String? = null,
): JsonNode {
    val error = ObjectMappers.getJsonMapper().readTree(response.contentAsByteArray)
    assertEquals(status, response.status)
    assertEquals("application/json", response.contentType)
    assertEquals(status, error.path("status").asInt())
    assertEquals(HttpStatus.valueOf(status).reasonPhrase, error.path("error").asText())
    assertEquals(message, error.path("message").asText())
    assertEquals(code, error.get("code")?.asText())
    return error
}

class FilterErrorsTest {
    @Test
    fun rejectedFiltersUseTheApiErrorEnvelopeAndEscapeMessages() {
        val message = "Invalid \"field\"\nPlease retry — corrected"
        for (status in listOf(400, 401, 403, 409, 413, 429, 503)) {
            val response = MockHttpServletResponse()
            writeFilterError(response, status, message, "REQUEST_REJECTED")
            assertFilterError(response, status, message, "REQUEST_REJECTED")
        }
    }
}
