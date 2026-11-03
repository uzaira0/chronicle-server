package com.openlattice.chronicle.configuration

import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ResearcherWriteCorsTest {
    @Test fun browserCanSendCreateAndEditPreconditionsAndReadSnapshotRevision() {
        val request = MockHttpServletRequest("POST", "/chronicle/v3/study").apply {
            addHeader("Idempotency-Key", "create-fixture")
            addHeader("If-Match", "revision-fixture")
        }
        val response = MockHttpServletResponse()
        var reachedApplication = false
        CorsValidationFilter(CorsConfiguration()).doFilter(request, response) { forwarded, returned ->
            assertEquals("create-fixture", (forwarded as jakarta.servlet.http.HttpServletRequest).getHeader("Idempotency-Key"))
            assertEquals("revision-fixture", forwarded.getHeader("If-Match"))
            (returned as jakarta.servlet.http.HttpServletResponse).setHeader("ETag", "next-revision")
            reachedApplication = true
        }
        assertEquals(true, reachedApplication)
        assertEquals("next-revision", response.getHeader("ETag"))
    }

    @Test fun shippedConfigurationContainsOnlyOptionsTheOriginFilterConsumes() {
        val mapper = ObjectMappers.getYamlMapper().copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val text = checkNotNull(javaClass.getResource("/cors.yaml")).readText()
        assertEquals(setOf("enabled", "allowed-origins", "development-mode", "development-ports"),
            mapper.readTree(text).fieldNames().asSequence().toSet())
        assertEquals(CorsConfiguration(), mapper.readValue(text, CorsConfiguration::class.java))
    }
}
