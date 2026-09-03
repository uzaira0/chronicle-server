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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.openlattice.chronicle.i18n.Messages
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Standardized API error response with sanitization support.
 *
 * This class provides a consistent error response format across all Chronicle API endpoints
 * with support for error ID correlation and optional stack trace inclusion for debugging.
 *
 * Security features:
 * - Error ID for server-side log correlation without exposing internal details
 * - Sanitized messages that scrub sensitive patterns (paths, SQL, class names)
 * - Optional stack trace inclusion controlled by configuration
 * - Field-level validation errors without exposing internal implementation
 *
 * @property status HTTP status code (e.g., 400, 401, 403, 404, 500)
 * @property error HTTP status reason phrase (e.g., "Bad Request", "Internal Server Error")
 * @property message User-friendly error message (sanitized in production)
 * @property errorId Unique identifier for correlating with server-side logs
 * @property timestamp When the error occurred
 * @property path Route-shaped request path that caused the error
 * @property details Additional details (e.g., field validation errors)
 * @property trace Stack trace (only included in development mode)
 *
 * @author uzaira0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public data class ApiError(
    @param:JsonProperty("status")
    val status: Int,

    @param:JsonProperty("error")
    val error: String,

    @param:JsonProperty("message")
    val message: String,

    @param:JsonProperty("errorId")
    val errorId: String? = null,

    @param:JsonProperty("timestamp")
    val timestamp: OffsetDateTime = OffsetDateTime.now(),

    @param:JsonProperty("path")
    val path: String? = null,

    @param:JsonProperty("details")
    val details: List<String>? = null,

    @param:JsonProperty("trace")
    val trace: String? = null
) {
    internal companion object {
        /**
         * Generates a unique error ID for log correlation.
         * Format: ERR-{UUID} for easy searching in logs.
         */
        public fun generateErrorId(): String = "ERR-${UUID.randomUUID()}"

        /**
         * Creates a generic 500 Internal Server Error response.
         * Used when the actual error must be hidden from clients.
         */
        public fun internalServerError(
            errorId: String? = generateErrorId(),
            path: String? = null
        ): ApiError = ApiError(
            status = 500,
            error = "Internal Server Error",
            message = Messages.format("error.internal", errorId ?: Messages.get("error.internal.unknownId")),
            errorId = errorId,
            path = path
        )

        /**
         * Creates a 400 Bad Request response for validation errors.
         */
        public fun badRequest(
            message: String,
            details: List<String>? = null,
            path: String? = null,
            errorId: String? = null
        ): ApiError = ApiError(
            status = 400,
            error = "Bad Request",
            message = message,
            errorId = errorId,
            path = path,
            details = details
        )

        /**
         * Creates a 401 Unauthorized response.
         * Always uses a generic message to prevent information disclosure.
         */
        public fun unauthorized(
            path: String? = null,
            errorId: String? = null
        ): ApiError = ApiError(
            status = 401,
            error = "Unauthorized",
            message = Messages.get("error.unauthorized"),
            errorId = errorId,
            path = path
        )

        /**
         * Creates a 403 Forbidden response.
         * Always uses a generic message to prevent information disclosure.
         */
        public fun forbidden(
            path: String? = null,
            errorId: String? = null
        ): ApiError = ApiError(
            status = 403,
            error = "Forbidden",
            message = Messages.get("error.forbidden"),
            errorId = errorId,
            path = path
        )

        /**
         * Creates a 404 Not Found response.
         * Always uses a generic message to prevent information disclosure.
         */
        public fun notFound(
            path: String? = null,
            errorId: String? = null
        ): ApiError = ApiError(
            status = 404,
            error = "Not Found",
            message = Messages.get("error.notFound"),
            errorId = errorId,
            path = path
        )

        /**
         * Creates a 429 Too Many Requests response.
         */
        public fun tooManyRequests(
            retryAfterSeconds: Long? = null,
            path: String? = null,
            errorId: String? = null
        ): ApiError = ApiError(
            status = 429,
            error = "Too Many Requests",
            message = if (retryAfterSeconds != null) {
                Messages.format("error.rateLimited.retryAfter", retryAfterSeconds.toString())
            } else {
                Messages.get("error.rateLimited")
            },
            errorId = errorId,
            path = path
        )
    }
}
