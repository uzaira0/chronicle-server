package com.openlattice.chronicle.filters

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.ee11.servlet.FilterHolder
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.ee11.servlet.ServletHolder
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpStatus
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import java.util.UUID

class ServletErrorHardeningTest {
    @Test
    fun filterErrorsAreGenericJsonForEveryAcceptHeader() = withServer { server, connector ->
        server.start()
        for (code in listOf(HttpStatus.UNAUTHORIZED_401, HttpStatus.NOT_FOUND_404, HttpStatus.INTERNAL_SERVER_ERROR_500)) {
            for (accept in listOf("text/html", "application/json", "*/*", "application/pdf")) {
                assertSanitizedResponse(connector.getResponse(errorRequest(code, accept)), code)
            }
        }
    }

    private fun assertSanitizedResponse(response: String, code: Int) {
        val parsed = HttpTester.parseResponse(response)
        assertTrue(response, parsed.isComplete)
        assertFalse(response, parsed.isEarlyEOF)
        assertEquals(code, parsed.status)
        assertTrue(response, parsed.get(HttpHeader.CONTENT_TYPE).startsWith("application/json"))
        assertEquals("no-store", parsed.get(HttpHeader.CACHE_CONTROL))
        val body = parsed.content
        val match = Regex("""\{"status":$code,"message":"Request failed","errorId":"([0-9a-f-]+)"}""").matchEntire(body)
        assertTrue(response, match != null)
        UUID.fromString(checkNotNull(match).groupValues[1])
        assertFalse(response, body.contains(DIAGNOSTIC))
        assertFalse(response, body.contains(CAUSE))
        assertFalse(response, body.contains(PRIVATE_URI))
    }

    private fun errorRequest(code: Int, accept: String = "application/json") =
        "GET $PRIVATE_URI/$code HTTP/1.1\r\nHost: localhost\r\nAccept: $accept\r\nConnection: close\r\n\r\n"

    private fun withServer(test: (Server, LocalConnector) -> Unit) {
        val server = Server()
        val connector = LocalConnector(server)
        server.addConnector(connector)
        val context = ServletContextHandler()
        server.handler = context
        context.addServlet(ServletHolder(object : HttpServlet() {
            override fun doGet(request: HttpServletRequest, response: HttpServletResponse) { response.status = 204 }
        }), "/*")
        context.addFilter(FilterHolder(Filter { request, response, _ ->
            val code = (request as HttpServletRequest).requestURI.substringAfterLast('/').toInt()
            if (code == HttpStatus.INTERNAL_SERVER_ERROR_500) throw IllegalStateException(DIAGNOSTIC, IllegalArgumentException(CAUSE))
            (response as HttpServletResponse).sendError(code, DIAGNOSTIC)
        }), "/*", EnumSet.of(DispatcherType.REQUEST))
        ServletErrorHardeningInitializer().onStartup(context.servletContext)
        try { test(server, connector) } finally { server.stop() }
    }

    private companion object {
        const val DIAGNOSTIC = "private-diagnostic-sentinel-sk_test_not_a_real_secret"
        const val CAUSE = "private-cause-sentinel-sk_test_not_a_real_secret"
        const val PRIVATE_URI = "/private-uri-sentinel"
    }
}
