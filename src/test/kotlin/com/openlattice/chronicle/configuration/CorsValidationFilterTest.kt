package com.openlattice.chronicle.configuration

import com.geekbeast.mappers.mappers.ObjectMappers
import jakarta.servlet.Filter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The selfhost dashboard is same-origin, so the shipped cors.yaml lists no origins: a page on another site that
 * makes the researcher's browser send a credentialed request is refused before any controller runs.
 */
class CorsValidationFilterTest {
    private val shipped: CorsConfiguration = checkNotNull(javaClass.getResourceAsStream("/cors.yaml")).use {
        ObjectMappers.getYamlMapper().readValue(it, CorsConfiguration::class.java)
    }

    /** The filter bean exactly as the security chain gets it for [config]. */
    private fun filterFor(config: CorsConfiguration): Filter = AnnotationConfigApplicationContext().use { context ->
        context.registerBean(CorsConfiguration::class.java, java.util.function.Supplier { config })
        context.register(CorsValidationFilterConfig::class.java)
        context.refresh()
        context.getBean("corsValidationFilter", Filter::class.java)
    }

    /** Status and whether the request reached the application. */
    private fun send(filter: Filter, origin: String?, method: String = "POST"): Pair<Int, Boolean> {
        val request = MockHttpServletRequest(method, "/chronicle/v3/study/participants")
        origin?.let { request.addHeader("Origin", it) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response.status to (chain.request != null)
    }

    @Test
    fun shippedConfigLetsSameOriginAndPreflightThroughAndRefusesEveryOtherOrigin() {
        val filter = filterFor(shipped)
        assertEquals(200 to true, send(filter, null))
        assertEquals(200 to true, send(filter, ""))
        assertEquals(200 to true, send(filter, "https://evil.example", method = "OPTIONS"))
        for (origin in listOf("https://evil.example", "null", "http://localhost:5173")) {
            assertEquals(origin, 403 to false, send(filter, origin))
        }
        val request = MockHttpServletRequest("POST", "/chronicle/v3/study").apply { addHeader("Origin", "null") }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        val error = ObjectMappers.getJsonMapper().readTree(response.contentAsByteArray)
        assertEquals(403, error.path("status").asInt())
        assertEquals("Forbidden", error.path("error").asText())
        assertEquals("Cross-origin request from disallowed origin", error.path("message").asText())
    }

    @Test
    fun listedOriginsMatchExactlyAndDevelopmentModeAddsOnlyLoopbackOnItsPorts() {
        val listed = filterFor(shipped.copy(allowedOrigins = listOf("https://panel.example.cl")))
        assertEquals(200 to true, send(listed, "https://panel.example.cl"))
        for (near in listOf("https://panel.example.cl/", "http://panel.example.cl", "https://panel.example.cl:8443")) {
            assertEquals(near, 403 to false, send(listed, near))
        }

        val development = filterFor(shipped.copy(developmentMode = true))
        for (origin in listOf("http://localhost:5173", "http://127.0.0.1:3000", "http://localhost:8080")) {
            assertEquals(origin, 200 to true, send(development, origin))
        }
        for (origin in listOf("http://localhost:9999", "https://localhost:5173", "http://192.168.1.20:5173")) {
            assertEquals(origin, 403 to false, send(development, origin))
        }
    }

    @Test
    fun disablingCorsValidationPassesCrossOriginRequestsThrough() {
        assertEquals(200 to true, send(filterFor(shipped.copy(enabled = false)), "https://evil.example"))
    }
}
