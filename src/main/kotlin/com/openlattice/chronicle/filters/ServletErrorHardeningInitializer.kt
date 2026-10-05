package com.openlattice.chronicle.filters

import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.ee10.servlet.ErrorHandler
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletContextRequest
import org.springframework.web.WebApplicationInitializer
import java.util.UUID

/** Covers sendError and exceptions raised before DispatcherServlet's exception advice. */
public class ServletErrorHardeningInitializer : WebApplicationInitializer {
    public override fun onStartup(servletContext: ServletContext) {
        val context = (servletContext as ServletContextHandler.ServletContextApi).contextHandler as ServletContextHandler
        context.errorHandler = SanitizedErrorHandler()
    }

    internal class SanitizedErrorHandler : ErrorHandler() {
        override fun generateAcceptableResponse(
            base: ServletContextRequest, request: HttpServletRequest, response: HttpServletResponse,
            code: Int, message: String?,
        ) {
            response.status = code
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            response.setHeader("Cache-Control", "no-store")
            response.writer.write("""{"status":$code,"message":"Request failed","errorId":"${UUID.randomUUID()}"}""")
        }
    }
}
