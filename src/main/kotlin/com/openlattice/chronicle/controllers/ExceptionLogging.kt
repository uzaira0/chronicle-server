package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.util.sqlCause

import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.util.ClientIpRecord
import com.openlattice.chronicle.util.LogSanitizer
import jakarta.servlet.http.HttpServletRequest
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.servlet.NoHandlerFoundException

internal class ExceptionLogging(private val errorSanitizationConfig: ErrorSanitizationConfig) {
    /**
     * Logs an exception with the error ID for correlation.
     */
    fun logExceptionWithId(
        errorId: String,
        req: HttpServletRequest,
        e: Exception,
        // For body-parse exceptions (HttpMessageNotReadableException / JsonMappingException) the
        // exception MESSAGE and stack embed the offending request VALUE — which can be participant
        // content (PHI). Those call sites pass logMessage=false so only the error id + exception
        // CLASS are logged here; the field path / body length is already logged (without the value)
        // by the caller.
        logMessage: Boolean = true,
        // A 4xx answer is the client's mistake, not a server failure: WARN keeps it out of the
        // ERROR stream that operators watch for real faults.
        clientError: Boolean = false,
    ) {
        val log = logger.atLevel(if (clientError) Level.WARN else Level.ERROR)
        val databaseFailure = e.sqlCause()
        if (databaseFailure != null) {
            logDatabaseException(errorId, databaseFailure)
            return
        }
        // Spring builds NoHandlerFoundException messages from the raw request URI. That URI can
        // contain arbitrary participant content, so never pass this framework-generated message,
        // stack, or URI to an application logger. A one-way route reference retains correlation
        // without assuming an unknown path follows any known route schema.
        if (e is NoHandlerFoundException) {
            log.log(
                "Error ID: {} | Method: {} | URI: unmapped-route | routeRef: {} | Exception: {} | Message: request-derived message omitted",
                errorId,
                sanitizeLogValue(req.method),
                LogSanitizer.stableFingerprint(req.requestURI, prefix = "route"),
                e.javaClass.name,
            )
            return
        }
        if (!logMessage) {
            log.log(
                "Error ID: {} - {} (message/stack omitted: may contain request content)",
                errorId,
                e.javaClass.simpleName,
            )
            return
        }
        if (errorSanitizationConfig.logFullErrors) {
            log.log(
                "Error ID: {} | Method: {} | URI: {} | IP: {} | Exception: {} | Message: {}",
                errorId,
                sanitizeLogValue(req.method),
                safeErrorPath(req),
                ClientIpRecord.logReference(req),
                e.javaClass.name,
                errorSanitizationConfig.sanitizeMessage(e.message)
            )
        } else {
            log.log(
                "Error ID: {} - {} - {}",
                errorId,
                e.javaClass.simpleName,
                errorSanitizationConfig.sanitizeMessage(e.message)
            )
        }
    }

    fun logDatabaseException(errorId: String, failure: SQLException) {
        logger.error("Database error [ErrorId: {}] - SQLState: {}, ErrorCode: {}",
            errorId, failure.sqlState, failure.errorCode)
    }

    fun logUnreadableBody(errorId: String, e: HttpMessageNotReadableException) {
        val bodyLength = try {
            e.httpInputMessage.body.readBytes().size
        } catch (_: Exception) {
            logger.debug("Could not measure unreadable request body length [ErrorId: {}]", errorId)
            -1
        }
        logger.warn("Unreadable request body [ErrorId: {}], length={} bytes (content not logged)", errorId, bodyLength)
    }

    companion object {
        /**
         * Sanitizes a value for safe logging (prevents log injection).
         */
        fun sanitizeLogValue(value: String?): String {
            if (value == null) return "null"
            return value
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .take(500)
        }

        fun safeErrorPath(req: HttpServletRequest): String = LogSanitizer.sanitizeRequestPath(req.requestURI)

        private val logger = LoggerFactory.getLogger(ChronicleServerExceptionHandler::class.java)
    }
}
