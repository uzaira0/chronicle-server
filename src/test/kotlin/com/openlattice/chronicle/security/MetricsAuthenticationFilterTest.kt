package com.openlattice.chronicle.security

import jakarta.servlet.FilterChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

class MetricsAuthenticationFilterTest {

    private companion object {
        const val STRONG_PASSWORD = "metrics-test-password-is-32-characters"
    }

    @Test
    fun missingServerCredentialPreventsApplicationStartup() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricsAuthenticationFilter("chronicle-metrics", "")
        }
    }

    @Test
    fun weakServerCredentialPreventsApplicationStartup() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricsAuthenticationFilter("chronicle-metrics", "too-short")
        }
    }

    @Test
    fun wrongCredentialIsUnauthorized() {
        val filter = MetricsAuthenticationFilter("chronicle-metrics", STRONG_PASSWORD)
        val request = MockHttpServletRequest("GET", "/prometheus/")
        request.addHeader("Authorization", basic("chronicle-metrics", "wrong-password"))
        val response = MockHttpServletResponse()
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertEquals("Basic realm=\"Chronicle metrics\"", response.getHeader("WWW-Authenticate"))
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun correctDedicatedCredentialReachesMetricsServlet() {
        val filter = MetricsAuthenticationFilter("chronicle-metrics", STRONG_PASSWORD)
        val request = MockHttpServletRequest("GET", "/prometheus/")
        request.addHeader("Authorization", basic("chronicle-metrics", STRONG_PASSWORD))
        val response = MockHttpServletResponse()
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun nonMetricsRequestDoesNotRequireScraperCredential() {
        val filter = MetricsAuthenticationFilter("chronicle-metrics", STRONG_PASSWORD)
        val request = MockHttpServletRequest("GET", "/chronicle/v3/study")
        val response = MockHttpServletResponse()
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun metricsLikePrefixDoesNotExpandProtectedServletBoundary() {
        val filter = MetricsAuthenticationFilter("chronicle-metrics", STRONG_PASSWORD)
        val request = MockHttpServletRequest("GET", "/prometheus-not-a-servlet")
        val response = MockHttpServletResponse()
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun servletPathAndPathParametersCannotBypassMetricsAuthentication() {
        val filter = MetricsAuthenticationFilter("chronicle-metrics", STRONG_PASSWORD)
        val servletMappedRequest = MockHttpServletRequest("GET", "/untrusted-original-path")
        servletMappedRequest.servletPath = "/prometheus"
        val servletMappedResponse = MockHttpServletResponse()
        val servletMappedChain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(servletMappedRequest, servletMappedResponse, servletMappedChain)

        assertEquals(401, servletMappedResponse.status)
        verify(servletMappedChain, never()).doFilter(servletMappedRequest, servletMappedResponse)

        val pathParameterRequest = MockHttpServletRequest("GET", "/prometheus;jsessionid=synthetic")
        val pathParameterResponse = MockHttpServletResponse()
        val pathParameterChain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(pathParameterRequest, pathParameterResponse, pathParameterChain)

        assertEquals(401, pathParameterResponse.status)
        verify(pathParameterChain, never()).doFilter(pathParameterRequest, pathParameterResponse)
    }

    @Test
    fun fileBackedCredentialReloadsWithoutRestart() {
        val passwordFile = Files.createTempFile("chronicle-metrics-", ".secret")
        try {
            Files.writeString(passwordFile, "$STRONG_PASSWORD\n", StandardCharsets.UTF_8)
            val filter = MetricsAuthenticationFilter(
                "chronicle-metrics",
                "",
                listOf(passwordFile),
            )

            assertRequestStatus(filter, STRONG_PASSWORD, null)

            val rotatedPassword = "rotated-metrics-password-is-32-characters"
            Files.writeString(passwordFile, rotatedPassword, StandardCharsets.UTF_8)

            assertRequestStatus(filter, STRONG_PASSWORD, 401)
            assertRequestStatus(filter, rotatedPassword, null)
        } finally {
            Files.deleteIfExists(passwordFile)
        }
    }

    @Test
    fun overlappingFileBackedCredentialsAreBothAccepted() {
        val currentFile = Files.createTempFile("chronicle-metrics-current-", ".secret")
        val previousFile = Files.createTempFile("chronicle-metrics-previous-", ".secret")
        val previousPassword = "previous-metrics-password-is-32-characters"
        try {
            Files.writeString(currentFile, STRONG_PASSWORD, StandardCharsets.UTF_8)
            Files.writeString(previousFile, previousPassword, StandardCharsets.UTF_8)
            val filter = MetricsAuthenticationFilter(
                "chronicle-metrics",
                "",
                listOf(currentFile, previousFile),
            )

            assertRequestStatus(filter, STRONG_PASSWORD, null)
            assertRequestStatus(filter, previousPassword, null)
        } finally {
            Files.deleteIfExists(currentFile)
            Files.deleteIfExists(previousFile)
        }
    }

    @Test
    fun unreadableRuntimeCredentialFailsClosedAsUnavailable() {
        val passwordFile = Files.createTempFile("chronicle-metrics-", ".secret")
        try {
            Files.writeString(passwordFile, STRONG_PASSWORD, StandardCharsets.UTF_8)
            val filter = MetricsAuthenticationFilter(
                "chronicle-metrics",
                "",
                listOf(passwordFile),
            )
            Files.delete(passwordFile)

            assertRequestStatus(filter, STRONG_PASSWORD, 503)
        } finally {
            Files.deleteIfExists(passwordFile)
        }
    }

    @Test
    fun invalidFileBackedCredentialPreventsApplicationStartup() {
        val passwordFile = Files.createTempFile("chronicle-metrics-", ".secret")
        try {
            Files.writeString(passwordFile, "too-short", StandardCharsets.UTF_8)

            assertThrows(IllegalArgumentException::class.java) {
                MetricsAuthenticationFilter(
                    "chronicle-metrics",
                    "",
                    listOf(passwordFile),
                )
            }
        } finally {
            Files.deleteIfExists(passwordFile)
        }
    }

    @Test
    fun passwordFileConfigurationRejectsMissingRotationSlots() {
        assertEquals(
            listOf("/run/current", "/run/previous", "/run/next"),
            MetricsAuthenticationFilter
                .parsePasswordFiles(" /run/current, /run/previous, /run/next ")
                .map { it.toString() },
        )
        assertThrows(IllegalArgumentException::class.java) {
            MetricsAuthenticationFilter.parsePasswordFiles("/run/current,,/run/next")
        }
    }

    private fun assertRequestStatus(
        filter: MetricsAuthenticationFilter,
        password: String,
        expectedStatus: Int?,
    ) {
        val request = MockHttpServletRequest("GET", "/prometheus/")
        request.addHeader("Authorization", basic("chronicle-metrics", password))
        val response = MockHttpServletResponse()
        val chain = Mockito.mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        if (expectedStatus == null) {
            verify(chain).doFilter(request, response)
        } else {
            assertEquals(expectedStatus, response.status)
            verify(chain, never()).doFilter(request, response)
        }
    }

    private fun basic(username: String, password: String): String {
        val encoded = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8)
        )
        return "Basic $encoded"
    }
}
