package com.openlattice.chronicle.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ObservabilityFilterTest {

    @Test
    fun `MDC request metadata uses stable refs and redacted route path`() {
        val filter = ObservabilityFilter()
        val studyId = "550e8400-e29b-41d4-a716-446655440000"
        val participantId = "u15-device-owner"
        val sourceDeviceId = "iphone-identifier-for-vendor"
        val request = MockHttpServletRequest(
            "POST",
            "/chronicle/v3/study/$studyId/participant/$participantId/android/$sourceDeviceId/upload"
        ).apply {
            remoteAddr = "127.0.0.1"
        }
        val response = MockHttpServletResponse()
        val captured = mutableMapOf<String, String?>()
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            captured["studyId"] = MDC.get("studyId")
            captured["participantId"] = MDC.get("participantId")
            captured["httpPath"] = MDC.get("httpPath")
            captured["httpMethod"] = MDC.get("httpMethod")
        }

        filter.doFilter(request, response, chain)

        assertTrue(captured["studyId"]?.startsWith("study:") == true)
        assertFalse(captured["studyId"]!!.contains(studyId))
        assertTrue(captured["participantId"]?.startsWith("participant:") == true)
        assertFalse(captured["participantId"]!!.contains(participantId))
        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/android/{sourceDeviceId}/upload",
            captured["httpPath"]
        )
        assertEquals("POST", captured["httpMethod"])
        assertNotNull(response.getHeader("X-Request-ID"))
        assertNotNull(response.getHeader("X-Trace-ID"))
    }

    @Test
    fun `MDC is cleared after request finishes`() {
        val filter = ObservabilityFilter()
        val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session").apply {
            remoteAddr = "127.0.0.1"
        }

        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ ->
            assertNotNull(MDC.get("requestId"))
        })

        assertEquals(null, MDC.get("requestId"))
    }
}
