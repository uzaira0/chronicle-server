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
        val request = MockHttpServletRequest(
            "POST",
            "/chronicle/v4/study/$studyId/participant/$participantId/android"
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
            "/chronicle/v4/study/{studyId}/participant/{participantId}/android",
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

    /**
     * A support ticket quotes the X-Request-ID / X-Trace-ID the dashboard showed. A well-formed id from the proxy
     * or a W3C traceparent is carried through; a malformed or log-injecting one is replaced, never echoed.
     */
    @Test
    fun forwardedCorrelationIdsAreKeptOnlyWhenWellFormed() {
        fun send(requestId: String?, traceparent: String?): MockHttpServletResponse {
            val request = MockHttpServletRequest("GET", "/chronicle/v3/auth/session").apply {
                requestId?.let { addHeader("X-Request-ID", it) }
                traceparent?.let { addHeader("traceparent", it) }
            }
            return MockHttpServletResponse().also { ObservabilityFilter().doFilter(request, it, FilterChain { _, _ -> }) }
        }
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val kept = send("edge-req_42", "00-$traceId-00f067aa0ba902b7-01")
        assertEquals("edge-req_42", kept.getHeader("X-Request-ID"))
        assertEquals(traceId, kept.getHeader("X-Trace-ID"))

        for ((requestId, traceparent) in listOf(
            "bad\r\nInjected: yes" to "00-${traceId.uppercase()}-00f067aa0ba902b7-01",
            "a".repeat(65) to "garbage",
        )) {
            val replaced = send(requestId, traceparent)
            assertTrue(Regex("[0-9a-f-]{36}").matches(replaced.getHeader("X-Request-ID")!!))
            val freshTrace = replaced.getHeader("X-Trace-ID")!!
            assertTrue(freshTrace, Regex("[0-9a-f]{32}").matches(freshTrace))
            assertFalse(freshTrace.equals(traceId, ignoreCase = true))
        }
    }
}
