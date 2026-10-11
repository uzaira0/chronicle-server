package com.openlattice.chronicle.filters

import jakarta.servlet.ServletContext
import org.eclipse.jetty.ee11.servlet.ErrorHandler
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.springframework.web.WebApplicationInitializer
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Covers sendError and exceptions raised before DispatcherServlet's exception advice. */
public class ServletErrorHardeningInitializer : WebApplicationInitializer {
    public override fun onStartup(servletContext: ServletContext) {
        val context = (servletContext as ServletContextHandler.ServletContextApi).contextHandler as ServletContextHandler
        context.errorHandler = SanitizedErrorHandler()
    }

    internal class SanitizedErrorHandler : ErrorHandler() {
        init {
            setShowStacks(false)
            setShowMessageInTitle(false)
        }

        override fun generateResponse(
            request: Request, response: Response, code: Int, message: String?, cause: Throwable?, callback: Callback,
        ) {
            response.status = code
            response.headers.put(HttpHeader.CACHE_CONTROL, "no-store")
            // Jetty error response path: https://github.com/jetty/jetty.project/blob/jetty-12.1.14/jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/handler/ErrorHandler.java https://github.com/jetty/jetty.project/blob/jetty-12.1.14/jetty-ee11/jetty-ee11-servlet/src/main/java/org/eclipse/jetty/ee11/servlet/ErrorHandler.java https://github.com/jetty/jetty.project/blob/jetty-12.1.14/jetty-ee11/jetty-ee11-servlet/src/main/java/org/eclipse/jetty/ee11/servlet/ServletChannel.java
            generateAcceptableResponse(request, response, callback, "application/json", listOf(StandardCharsets.UTF_8), code, message, cause)
        }

        override fun writeErrorJson(request: Request, writer: PrintWriter, code: Int, message: String?, cause: Throwable?) {
            writer.write("""{"status":$code,"message":"Request failed","errorId":"${UUID.randomUUID()}"}""")
        }
    }
}
