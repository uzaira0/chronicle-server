package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.ScopedLogCapture
import jakarta.servlet.FilterChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.OAuth2AccessToken

class RequestValidationFilterTest {
    private val filter = SecurityHardeningConfig().requestValidationFilter()

    @Test fun rejectsInvalidInputsWithoutLoggingRequestData() {
        val sentinel = "private-request-sentinel"
        val cases = mapOf(
            MockHttpServletRequest("GET", "/$sentinel\u0000") to "Invalid request URI",
            MockHttpServletRequest("GET", "/").apply { queryString = "$sentinel=\u0000" } to "Invalid query string",
            MockHttpServletRequest("GET", "/").apply { addParameter(sentinel, "\u0000") } to "Invalid parameter value",
            MockHttpServletRequest("GET", "/").apply {
                addParameter(sentinel, "x".repeat(SecurityHardeningConfig.MAX_PARAMETER_LENGTH + 1))
            } to "Parameter value too long",
            object : MockHttpServletRequest("GET", "/") {
                override fun getParameterMap(): Map<String, Array<String>> = throw IllegalArgumentException(sentinel)
            } to "Invalid request parameters",
        )
        ScopedLogCapture(SecurityHardeningConfig::class.java).use { logs ->
            for ((request, reason) in cases) {
                val response = MockHttpServletResponse()
                var passed = false
                filter.doFilter(request, response, FilterChain { _, _ -> passed = true })
                assertEquals(HttpStatus.BAD_REQUEST.value(), response.status)
                assertEquals(reason, response.errorMessage)
                assertFalse(passed)
            }
            assertTrue(logs.messages.isNotEmpty())
            assertFalse(logs.messages.joinToString().contains(sentinel))
        }
    }

    @Test fun validParametersPassIncludingTheMaximumLength() {
        val request = MockHttpServletRequest("GET", "/").apply {
            addParameter("value", "x".repeat(SecurityHardeningConfig.MAX_PARAMETER_LENGTH))
        }
        var passed = false
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> passed = true })
        assertTrue(passed)
    }

    @Test fun dpopIsRejectedBeforeParameterParsing() {
        val request = object : MockHttpServletRequest("GET", "/") {
            override fun getParameterMap(): Map<String, Array<String>> = error("must not parse parameters")
        }.apply { addHeader(HttpHeaders.AUTHORIZATION, OAuth2AccessToken.TokenType.DPOP.value.lowercase()) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> error("must not pass DPoP") })
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.status)
        assertEquals(OAuth2AccessToken.TokenType.BEARER.value, response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
    }
}
