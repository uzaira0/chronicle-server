package com.openlattice.chronicle.filters

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.ee10.servlet.FilterHolder
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Server
import org.junit.Assert.*
import org.junit.Test
import java.util.EnumSet

class ServletErrorHardeningTest {
    @Test
    fun filterErrorsAreGenericJsonForEveryAcceptHeader() {
        val server = Server()
        val connector = LocalConnector(server)
        server.addConnector(connector)
        val context = ServletContextHandler()
        server.handler = context
        context.addServlet(ServletHolder(object : HttpServlet() {
            override fun doGet(request: HttpServletRequest, response: HttpServletResponse) { response.status = 204 }
        }), "/*")
        context.addFilter(FilterHolder(Filter { request, response, _ ->
            if ((request as HttpServletRequest).requestURI.endsWith("send")) {
                (response as HttpServletResponse).sendError(401, "private-diagnostic-sentinel")
            } else throw IllegalStateException("private-diagnostic-sentinel")
        }), "/*", EnumSet.of(DispatcherType.REQUEST))
        ServletErrorHardeningInitializer().onStartup(context.servletContext)
        try {
            server.start()
            for (path in listOf("send", "throw")) for (accept in listOf("text/html", "application/json", "*/*")) {
                val response = connector.getResponse("GET /private-uri-sentinel/$path HTTP/1.1\r\nHost: localhost\r\nAccept: $accept\r\nConnection: close\r\n\r\n")
                assertTrue(response, response.contains("Content-Type: application/json", ignoreCase = true))
                assertTrue(response, response.contains("\"errorId\""))
                assertFalse(response, response.contains("private-diagnostic-sentinel"))
                assertFalse(response, response.contains("private-uri-sentinel"))
                assertFalse(response, response.contains("<html"))
            }
        } finally { server.stop() }
    }
}
