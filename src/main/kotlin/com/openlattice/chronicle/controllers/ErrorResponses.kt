package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

internal class ErrorResponses(private val config: ErrorSanitizationConfig) {
    fun badRequest(
        req: HttpServletRequest,
        errorId: String,
        message: String,
        details: List<String>? = null,
        code: String? = null,
    ): ResponseEntity<ApiError> = response(req, errorId, HttpStatus.BAD_REQUEST) {
        ApiError.badRequest(message = message, details = details, errorId = it).copy(code = code)
    }

    fun notFound(req: HttpServletRequest, errorId: String): ResponseEntity<ApiError> =
        response(req, errorId, HttpStatus.NOT_FOUND) { ApiError.notFound(errorId = it) }

    fun unauthorized(req: HttpServletRequest, errorId: String): ResponseEntity<ApiError> =
        response(req, errorId, HttpStatus.UNAUTHORIZED) { ApiError.unauthorized(errorId = it) }

    fun forbidden(req: HttpServletRequest, errorId: String): ResponseEntity<ApiError> =
        response(req, errorId, HttpStatus.FORBIDDEN) { ApiError.forbidden(errorId = it) }

    fun internalError(
        req: HttpServletRequest,
        errorId: String,
        status: HttpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
    ): ResponseEntity<ApiError> = response(req, errorId, status) { ApiError.internalServerError(errorId = it) }

    fun response(
        req: HttpServletRequest,
        errorId: String,
        error: ApiError,
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): ResponseEntity<ApiError> = response(req, errorId, HttpStatus.valueOf(error.status), headers) { error.copy(errorId = it) }

    private fun response(
        req: HttpServletRequest,
        errorId: String,
        status: HttpStatus,
        headers: HttpHeaders = HttpHeaders.EMPTY,
        body: (String?) -> ApiError,
    ): ResponseEntity<ApiError> = ResponseEntity(
        body(errorId.takeIf { config.includeErrorId }).copy(path = ExceptionLogging.safeErrorPath(req)),
        HttpHeaders().apply {
            addAll(headers)
            contentType = MediaType.APPLICATION_JSON
        },
        status,
    )
}
