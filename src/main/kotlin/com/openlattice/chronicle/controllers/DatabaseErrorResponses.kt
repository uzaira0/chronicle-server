package com.openlattice.chronicle.controllers

import jakarta.servlet.http.HttpServletRequest
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

internal class DatabaseErrorResponses(
    private val errors: ErrorResponses,
    private val logging: ExceptionLogging,
) {
    fun handleSqlException(req: HttpServletRequest, e: SQLException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()

        logging.logDatabaseException(errorId, e)

        val failures = generateSequence(e) { it.nextException }.take(32).toList()
        val status = failures.mapNotNull { failure ->
            when {
                failure is SQLTransientConnectionException -> HttpStatus.SERVICE_UNAVAILABLE
                failure.sqlState?.startsWith("22") == true -> HttpStatus.BAD_REQUEST
                failure.sqlState == "42501" -> HttpStatus.FORBIDDEN
                failure.sqlState?.take(2) in setOf("08", "40", "53") || failure.sqlState == "57P01" ->
                    HttpStatus.SERVICE_UNAVAILABLE
                else -> null
            }
        }.firstOrNull() ?: HttpStatus.INTERNAL_SERVER_ERROR
        val headers = HttpHeaders()
        if (status == HttpStatus.SERVICE_UNAVAILABLE) headers.set(HttpHeaders.RETRY_AFTER, "5")
        return errors.response(req, errorId, ApiError(status.value(), status.reasonPhrase, status.reasonPhrase), headers)
    }
}
