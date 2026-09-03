/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import jakarta.servlet.http.HttpServletRequest

/**
 * Centralized error handling utilities with sanitization support.
 *
 * This object provides utility methods for handling exceptions in a secure manner,
 * sanitizing error responses to prevent information disclosure while maintaining
 * debugging capability through error ID correlation.
 *
 * Security features:
 * - Generates unique error IDs for log correlation
 * - Sanitizes messages to remove sensitive patterns (paths, SQL, class names)
 * - Returns generic messages for 500 errors in production
 * - Logs full error details server-side for debugging
 *
 * @author uzaira0
 */
public object GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Creates a sanitized error response for any exception.
     *
     * @param exception The exception that occurred
     * @param request The HTTP request that caused the error
     * @param config The error sanitization configuration
     * @param status The HTTP status to return (optional, will be inferred from exception type if not provided)
     * @return A sanitized ApiError response
     */
    public fun createSanitizedError(
        exception: Exception,
        request: HttpServletRequest,
        config: ErrorSanitizationConfig,
        status: HttpStatus? = null
    ): ApiError {
        val errorId = ApiError.generateErrorId()
        val httpStatus = status ?: inferHttpStatus(exception)
        val path = LogSanitizer.sanitizeRequestPath(request.requestURI)

        // Always log the full error server-side with the error ID
        if (config.logFullErrors) {
            logFullError(errorId, exception, request, config)
        } else {
            logger.error(
                "Error ID: {} - {} - {}",
                errorId,
                exception.javaClass.simpleName,
                config.sanitizeMessage(exception.message)
            )
        }

        // Determine if we should sanitize this error
        val shouldSanitize = config.sanitizeErrors ||
                config.shouldAlwaysSanitize(exception.javaClass.name)

        return when {
            // Always return generic messages for server errors
            httpStatus.is5xxServerError && shouldSanitize -> {
                val responseErrorId = if (config.includeErrorId) errorId else null
                ApiError(
                    status = httpStatus.value(),
                    error = httpStatus.reasonPhrase,
                    message = Messages.format(
                        "error.internal",
                        responseErrorId ?: Messages.get("error.internal.unknownId"),
                    ),
                    errorId = responseErrorId,
                    path = path,
                    trace = null
                )
            }
            // Include sanitized details for client errors
            httpStatus.is4xxClientError -> {
                createClientErrorResponse(exception, httpStatus, errorId, path, config)
            }
            // For other cases, create a basic sanitized response
            else -> {
                ApiError(
                    status = httpStatus.value(),
                    error = httpStatus.reasonPhrase,
                    message = if (shouldSanitize) {
                        config.sanitizeMessage(exception.message)
                    } else {
                        exception.message ?: Messages.get("error.generic")
                    },
                    errorId = if (config.includeErrorId) errorId else null,
                    path = path,
                    trace = null
                )
            }
        }
    }

    /**
     * Creates a ResponseEntity with the appropriate status code and sanitized error body.
     */
    public fun createErrorResponse(
        exception: Exception,
        request: HttpServletRequest,
        config: ErrorSanitizationConfig,
        status: HttpStatus? = null
    ): ResponseEntity<ApiError> {
        val error = createSanitizedError(exception, request, config, status)
        return ResponseEntity.status(error.status).body(error)
    }

    /**
     * Infers the appropriate HTTP status code from the exception type.
     */
    private fun inferHttpStatus(exception: Exception): HttpStatus {
        return when (exception) {
            // 400 Bad Request
            is IllegalArgumentException,
            is MethodArgumentNotValidException,
            is MethodArgumentTypeMismatchException,
            is MissingServletRequestParameterException,
            is jakarta.validation.ConstraintViolationException -> HttpStatus.BAD_REQUEST

            // 401 Unauthorized
            is AuthenticationException,
            is AuthenticationCredentialsNotFoundException,
            is BadCredentialsException -> HttpStatus.UNAUTHORIZED

            // 403 Forbidden
            is AccessDeniedException,
            is SecurityException -> HttpStatus.FORBIDDEN

            // 404 Not Found
            is NoHandlerFoundException,
            is NoSuchElementException,
            is StudyNotFoundException,
            is CandidateNotFoundException,
            is OrganizationNotFoundException,
            is StudyRegistrationNotFoundException -> HttpStatus.NOT_FOUND

            // 409 Conflict
            is IllegalStateException -> HttpStatus.CONFLICT

            // 500 Internal Server Error (default for unhandled exceptions)
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
    }

    /**
     * Creates an error response for 4xx client errors with appropriate detail level.
     */
    private fun createClientErrorResponse(
        exception: Exception,
        status: HttpStatus,
        errorId: String,
        path: String,
        config: ErrorSanitizationConfig
    ): ApiError {
        return when (status) {
            HttpStatus.UNAUTHORIZED -> ApiError.unauthorized(
                path = path,
                errorId = if (config.includeErrorId) errorId else null
            )

            HttpStatus.FORBIDDEN -> ApiError.forbidden(
                path = path,
                errorId = if (config.includeErrorId) errorId else null
            )

            HttpStatus.NOT_FOUND -> ApiError.notFound(
                path = path,
                errorId = if (config.includeErrorId) errorId else null
            )

            HttpStatus.BAD_REQUEST -> {
                val (message, details) = extractValidationDetails(exception, config)
                ApiError.badRequest(
                    message = message,
                    details = details,
                    path = path,
                    errorId = if (config.includeErrorId) errorId else null
                )
            }

            else -> ApiError(
                status = status.value(),
                error = status.reasonPhrase,
                message = if (config.sanitizeErrors) {
                    config.sanitizeMessage(exception.message)
                } else {
                    exception.message ?: Messages.get("error.generic")
                },
                errorId = if (config.includeErrorId) errorId else null,
                path = path,
                trace = null
            )
        }
    }

    /**
     * Extracts validation error details from validation exceptions.
     * Returns a pair of (message, details list).
     */
    private fun extractValidationDetails(
        exception: Exception,
        config: ErrorSanitizationConfig
    ): Pair<String, List<String>?> {
        return when (exception) {
            is MethodArgumentNotValidException -> {
                val errors = exception.bindingResult.fieldErrors.map { fieldError ->
                    // Only expose field name and validation message, not internal values
                    "${fieldError.field}: ${fieldError.defaultMessage}"
                }
                Pair(Messages.get("error.request.validationFailed"), errors.ifEmpty { null })
            }

            is jakarta.validation.ConstraintViolationException -> {
                val errors = exception.constraintViolations.map { violation ->
                    val propertyPath = violation.propertyPath.toString()
                    val paramName = propertyPath.substringAfterLast('.')
                    "$paramName: ${violation.message}"
                }
                Pair(Messages.get("error.request.validationFailed"), errors.ifEmpty { null })
            }

            is MethodArgumentTypeMismatchException -> {
                val paramName = exception.name
                val expectedType = exception.requiredType?.simpleName ?: "unknown"
                Pair(
                    Messages.get("error.request.invalidParameters"),
                    listOf(Messages.format("error.request.invalidParameterType.detail", paramName, expectedType))
                )
            }

            is MissingServletRequestParameterException -> {
                Pair(
                    Messages.get("error.request.missingParameter"),
                    listOf(Messages.format("error.request.missingParameter.detail", exception.parameterName))
                )
            }

            is IllegalArgumentException -> {
                val sanitizedMessage = if (config.sanitizeErrors) {
                    config.sanitizeMessage(exception.message)
                } else {
                    exception.message ?: Messages.get("error.request.invalidArgument")
                }
                Pair(sanitizedMessage, null)
            }

            else -> {
                val sanitizedMessage = if (config.sanitizeErrors) {
                    config.sanitizeMessage(exception.message)
                } else {
                    exception.message ?: Messages.get("error.request.invalid")
                }
                Pair(sanitizedMessage, null)
            }
        }
    }

    /**
     * Logs the full error details server-side with the error ID for correlation.
     */
    private fun logFullError(
        errorId: String,
        exception: Exception,
        request: HttpServletRequest,
        config: ErrorSanitizationConfig
    ) {
        val ipRef = LogSanitizer.stableFingerprint(request.remoteAddr ?: "", prefix = "ip")
        val sanitizedUri = LogSanitizer.sanitizeRequestPath(request.requestURI)
        val sanitizedMethod = sanitizeLogValue(request.method)

        logger.error(
            "Error ID: {} | Method: {} | URI: {} | IP: {} | Exception: {} | Message: {}",
            errorId,
            sanitizedMethod,
            sanitizedUri,
            ipRef,
            exception.javaClass.name,
            config.sanitizeMessage(exception.message)
        )
    }

    /**
     * Sanitizes a value for safe logging (prevents log injection).
     */
    private fun sanitizeLogValue(value: String?): String {
        if (value == null) return "null"
        // Remove newlines and control characters to prevent log injection
        return value
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(500) // Limit length
    }
}
