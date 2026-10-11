package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.configuration.SecurityHardeningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockFilterChain
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.filter.CharacterEncodingFilter
import org.springframework.web.filter.CorsFilter

class HardeningLiveChainTest : ChronicleServerTests() {
    @Test fun registeredChainEnforcesSizeValidationHeadersBrowserAuthenticationAndCors() {
        val chain = testServer.context.getBean("securityFilterChain") as SecurityFilterChain
        assertTrue(chain.filters.any { it is CorsFilter })
        val encodingIndex = chain.filters.indexOfFirst { it is CharacterEncodingFilter }
        val config = testServer.context.getBean(SecurityHardeningConfig::class.java)
        assertTrue(chain.filters.indexOf(config.requestValidationFilter()) > encodingIndex)
        fun send(request: MockHttpServletRequest): MockHttpServletResponse {
            val response = MockHttpServletResponse()
            FilterChainProxy(chain).doFilter(request, response, MockFilterChain())
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
            return response
        }
        val oversized = MockHttpServletRequest("POST", "/chronicle/v3/observability/browser-errors").apply {
            setContent(ByteArray(SecurityHardeningConfig.MAX_REQUEST_SIZE_BYTES.toInt() + 1))
        }
        assertEquals(413, send(oversized).status)
        assertEquals(400, send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply { addParameter("name", "bad\u0000text") }).status)
        assertEquals(400, send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply { addParameter("name", "one", "two") }).status)
        assertEquals(401, send(MockHttpServletRequest("POST", "/chronicle/v3/observability/browser-errors")).status)
        // Spring's own DPoP path would answer with a DPoP challenge; the guard refuses the scheme before it runs.
        val dpop = send(MockHttpServletRequest("GET", "/chronicle/v3/studies").apply {
            addHeader("Authorization", "dpop eyJhbGciOiJSUzI1NiJ9.e30.sig")
            addHeader("DPoP", "eyJ0eXAiOiJkcG9wK2p3dCJ9.e30.sig")
        })
        assertEquals(401, dpop.status)
        assertEquals("Bearer", dpop.getHeader("WWW-Authenticate"))
        for (method in listOf("OPTIONS", "GET")) {
            val response = send(MockHttpServletRequest(method, "/chronicle/v3/studies").apply {
                addHeader(HttpHeaders.ORIGIN, "https://foreign.example")
                if (method == "OPTIONS") addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            })
            assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        }
    }
}
