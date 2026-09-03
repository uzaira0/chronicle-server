/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */
package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.JsonMappingException
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SsrfException
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException
import java.sql.SQLException
import java.util.*
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletRequest

/**
 * Central exception handler for Chronicle API with error response sanitization.
 *
 * This handler ensures that sensitive information is not leaked through error responses
 * while maintaining debugging capability through error ID correlation.
 *
 * Security features:
 * - Error ID generation for server-side log correlation
 * - Message sanitization to remove paths, SQL, class names
 * - Generic responses for 500 errors in production
 * - Full error logging server-side with error IDs
 *
 * @author uzaira0
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public open class ChronicleServerExceptionHandler @Inject constructor(
    override val auditingManager: AuditingManager,
    private val errorSanitizationConfig: ErrorSanitizationConfig
) : AuditingComponent {

    /**
     * Handles NullPointerException as 500 Internal Server Error.
     * NPEs indicate programming errors, not missing resources.
     */
    @ExceptionHandler(NullPointerException::class)
    public fun handleNullPointerException(req: HttpServletRequest, e: NullPointerException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        return ResponseEntity(
            ApiError.internalServerError(
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                path = safeErrorPath(req)
            ),
            HttpStatus.INTERNAL_SERVER_ERROR
        )
    }

    /**
     * Handles 404 Not Found exceptions.
     * Returns generic "resource not found" message to prevent information disclosure.
     */
    @ExceptionHandler(
        StudyRegistrationNotFoundException::class,
        StudyNotFoundException::class,
        CandidateNotFoundException::class,
        OrganizationNotFoundException::class,
        NoHandlerFoundException::class,
        NoSuchElementException::class
    )
    // reason: boundary catch — auditing failure must not break the generic 404 response path
    @Suppress("TooGenericExceptionCaught")
    public fun handleNotFoundException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        // Audit study not found events
        try {
            val principal = Principals.getCurrentSecurablePrincipal()
            val principals = Principals.getCurrentPrincipals()
            val event = when (e) {
                is StudyNotFoundException -> {
                    AuditableEvent(
                        AclKey(e.studyId),
                        principal.id,
                        principal.principal,
                        AuditEventType.STUDY_NOT_FOUND,
                        "Unable to find study ${e.studyId} [ErrorId: $errorId]",
                        e.studyId,
                        data = mapOf("principals" to principals, "errorId" to errorId)
                    )
                }
                else -> {
                    AuditableEvent(
                        AclKey(IdConstants.CHRONICLE.id),
                        principal.id,
                        principal.principal,
                        AuditEventType.STUDY_NOT_FOUND,
                        "Resource not found [ErrorId: $errorId]",
                        IdConstants.UNINITIALIZED.id,
                        data = mapOf("principals" to principals, "errorId" to errorId)
                    )
                }
            }
            recordEvent(event)
        } catch (auditEx: Exception) {
            logger.warn("Failed to audit not found event: {}", auditEx.message)
        }

        // Always return generic 404 response to prevent information disclosure
        return ResponseEntity(
            ApiError.notFound(
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.NOT_FOUND
        )
    }

    /**
     * Handles 400 Bad Request for illegal arguments and malformed requests.
     */
    // reason: boundary catch — measuring body length must not throw past the bad-request handler
    @Suppress("TooGenericExceptionCaught")
    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    public fun handleIllegalArgumentException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()

        when (e) {
            is HttpMessageNotReadableException -> {
                // Never log the raw request body. Mobile collection upload bodies can carry
                // participant data (PHI risk), and the existing sanitizers only scrub
                // log-injection / paths / SQL / credentials — NOT participant content. Log the
                // body SIZE only; the parse location/type travels with the exception elsewhere.
                val bodyLength = try {
                    e.httpInputMessage.body.readBytes().size
                } catch (ioEx: Exception) {
                    // Body unreadable; fall back to unknown length. Log the cause WITHOUT the body content.
                    logger.debug("Could not measure unreadable request body length [ErrorId: {}]", errorId, ioEx)
                    -1
                }
                logger.error(
                    "Unreadable request body [ErrorId: {}], length={} bytes (content not logged)",
                    errorId,
                    bodyLength
                )
            }
            else -> logger.debug("Body is not available.")
        }

        // A HttpMessageNotReadableException's message/stack embeds the offending body VALUE
        // (Jackson populates it), which can be participant content — so suppress it in the log.
        logExceptionWithId(errorId, req, e, logMessage = e !is HttpMessageNotReadableException)

        val message = when {
            // Body-parse failure: never echo the value back to the client either — the sanitizers
            // do not scrub participant content. Return a fixed generic message.
            e is HttpMessageNotReadableException -> Messages.get("error.request.invalidBody")
            errorSanitizationConfig.sanitizeErrors -> errorSanitizationConfig.sanitizeMessage(e.message)
            else -> e.message ?: Messages.get("error.request.invalid")
        }

        return ResponseEntity(
            ApiError.badRequest(
                message = message,
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles validation errors from @Valid annotations on @RequestBody parameters.
     * Returns field-specific validation errors without exposing internal details.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    public fun handleMethodArgumentNotValidException(
        req: HttpServletRequest,
        e: MethodArgumentNotValidException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logger.warn(
            "Validation failed for request {} {} [ErrorId: {}]",
            sanitizeLogValue(req.method),
            safeErrorPath(req),
            errorId
        )

        // Extract field errors - only expose field names and validation messages
        val errors = e.bindingResult.fieldErrors.map { fieldError ->
            "${fieldError.field}: ${fieldError.defaultMessage}"
        }

        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.validationFailed"),
                details = errors.ifEmpty { null },
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles constraint violations from @Validated annotations on method parameters.
     * This covers @PathVariable and @RequestParam validation.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    public fun handleConstraintViolationException(
        req: HttpServletRequest,
        e: ConstraintViolationException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logger.warn(
            "Constraint violation for request {} {} [ErrorId: {}]",
            sanitizeLogValue(req.method),
            safeErrorPath(req),
            errorId
        )

        val errors = e.constraintViolations.map { violation ->
            val propertyPath = violation.propertyPath.toString()
            val paramName = propertyPath.substringAfterLast('.')
            "$paramName: ${violation.message}"
        }

        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.invalidParameters"),
                details = errors.ifEmpty { null },
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles missing request parameters.
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    public fun handleMissingServletRequestParameterException(
        req: HttpServletRequest,
        e: MissingServletRequestParameterException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.missingParameter"),
                details = listOf(Messages.format("error.request.missingParameter.detail", e.parameterName)),
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles omitted required request headers without turning a malformed request into a 500.
     */
    @ExceptionHandler(MissingRequestHeaderException::class)
    public fun handleMissingRequestHeaderException(
        req: HttpServletRequest,
        e: MissingRequestHeaderException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.missingHeader"),
                details = listOf(Messages.format("error.request.missingHeader.detail", e.headerName)),
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles type mismatch errors (e.g., UUID parsing failures).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    public fun handleMethodArgumentTypeMismatchException(
        req: HttpServletRequest,
        e: MethodArgumentTypeMismatchException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        val expectedType = e.requiredType?.simpleName ?: "unknown"
        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.invalidParameterType"),
                details = listOf(Messages.format("error.request.invalidParameterType.detail", e.name, expectedType)),
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Handles 409 Conflict for illegal state exceptions.
     */
    @ExceptionHandler(IllegalStateException::class)
    public fun handleIllegalStateException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        val message = if (errorSanitizationConfig.sanitizeErrors) {
            errorSanitizationConfig.sanitizeMessage(e.message)
        } else {
            e.message ?: Messages.get("error.state.invalid")
        }

        return ResponseEntity(
            ApiError(
                status = HttpStatus.CONFLICT.value(),
                error = "Conflict",
                message = message,
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                path = safeErrorPath(req)
            ),
            HttpStatus.CONFLICT
        )
    }

    /**
     * Honours the status a controller deliberately chose with `ResponseStatusException`.
     *
     * Without this handler these fall through to [handleOtherExceptions] and every one of them
     * becomes a 500 — so `throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access code is
     * invalid, expired, revoked, or already used")` reached the client as "Internal Server Error"
     * even though the log correctly recorded `401 UNAUTHORIZED`. Twenty-two throws across the
     * controllers are written this way, covering 400/401/403/404/409/413/429 and more.
     *
     * The reason string is the controller's own fixed text, not request-derived, so it is safe to
     * return; sanitization still applies to 5xx, which keeps internal failures opaque.
     */
    @ExceptionHandler(ResponseStatusException::class)
    public fun handleResponseStatusException(
        req: HttpServletRequest,
        e: ResponseStatusException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) {
            return ResponseEntity(
                ApiError.internalServerError(
                    errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                    path = safeErrorPath(req)
                ),
                status
            )
        }

        return ResponseEntity(
            ApiError(
                status = status.value(),
                error = status.reasonPhrase,
                message = e.reason ?: status.reasonPhrase,
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                path = safeErrorPath(req)
            ),
            status
        )
    }

    /**
     * Handles 401 Unauthorized for authentication failures.
     * Always returns generic message to prevent information disclosure.
     */
    @ExceptionHandler(
        AuthenticationException::class,
        AuthenticationCredentialsNotFoundException::class,
        BadCredentialsException::class
    )
    public fun handleAuthenticationException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        // Always return generic unauthorized response
        return ResponseEntity(
            ApiError.unauthorized(
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.UNAUTHORIZED
        )
    }

    /**
     * Handles 403 Forbidden for access denied exceptions.
     * Always returns generic message to prevent information disclosure.
     */
    @ExceptionHandler(AccessDeniedException::class, com.geekbeast.controllers.exceptions.ForbiddenException::class)
    public fun handleUnauthorizedExceptions(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        // Always return generic forbidden response
        return ResponseEntity(
            ApiError.forbidden(
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.FORBIDDEN
        )
    }

    /**
     * Handles SSRF security exceptions.
     * Returns 403 Forbidden to prevent information disclosure about internal network.
     * Does not expose detailed error messages to prevent attackers from probing the allowlist.
     */
    @ExceptionHandler(SsrfException::class)
    public fun handleSsrfException(req: HttpServletRequest, e: SsrfException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()

        // Log a stable target fingerprint for security monitoring. Do not log the
        // raw URL/host: webhook URLs and callback targets often contain secrets in
        // query strings or hostnames that reveal internal infrastructure.
        logger.warn(
            "SSRF violation detected [ErrorId: {}] - Type: {}, targetRef: {}, ipRef: {}, URI: {}",
            errorId,
            e.violationType,
            LogSanitizer.stableFingerprint(e.targetUrl, prefix = "ssrf"),
            LogSanitizer.stableFingerprint(req.remoteAddr ?: "", prefix = "ip"),
            safeErrorPath(req)
        )

        // Return generic error to prevent information disclosure
        return ResponseEntity(
            ApiError.forbidden(
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.FORBIDDEN
        )
    }

    /**
     * Handles database/SQL exceptions.
     * ALWAYS returns generic error to prevent SQL/schema disclosure.
     */
    @ExceptionHandler(SQLException::class)
    public fun handleSqlException(req: HttpServletRequest, e: SQLException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()

        // Log SQL diagnostics without raw SQL/error text. Error messages can include
        // query fragments, table names, connection strings, or values.
        logger.error(
            "Database error [ErrorId: {}] - SQLState: {}, ErrorCode: {}, Message: {}",
            errorId,
            e.sqlState,
            e.errorCode,
            errorSanitizationConfig.sanitizeMessage(e.message)
        )

        // ALWAYS return generic error for SQL exceptions - never expose SQL details
        return ResponseEntity(
            ApiError.internalServerError(
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                path = safeErrorPath(req)
            ),
            HttpStatus.INTERNAL_SERVER_ERROR
        )
    }

    /**
     * Handles JSON mapping/parsing exceptions.
     */
    @ExceptionHandler(JsonMappingException::class)
    public fun handleJsonExceptions(req: HttpServletRequest, e: JsonMappingException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        // Log only the field PATH (e.g. "rows[3].timestamp"), never e.originalMessage — the
        // original message embeds the offending field VALUE, which can be participant content/PHI.
        val fieldPath = e.path.joinToString(".") { ref -> ref.fieldName ?: "[${ref.index}]" }
        logger.error("JSON mapping error [ErrorId: {}] at path: {} (value not logged)", errorId, fieldPath)
        // e.message / stack embed the offending field VALUE (Jackson populates it) — suppress it.
        logExceptionWithId(errorId, req, e, logMessage = false)

        return ResponseEntity(
            ApiError.badRequest(
                message = Messages.get("error.request.invalidJson"),
                path = safeErrorPath(req),
                errorId = if (errorSanitizationConfig.includeErrorId) errorId else null
            ),
            HttpStatus.BAD_REQUEST
        )
    }

    /**
     * Catch-all handler for unhandled exceptions.
     * Always returns generic 500 error in production to prevent information disclosure.
     */
    @ExceptionHandler(Exception::class)
    public fun handleOtherExceptions(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logExceptionWithId(errorId, req, e)

        // Check if this exception type should always be sanitized
        val shouldAlwaysSanitize = errorSanitizationConfig.shouldAlwaysSanitize(e.javaClass.name)

        return if (errorSanitizationConfig.sanitizeErrors || shouldAlwaysSanitize) {
            // Production mode: return generic error with error ID for correlation
            ResponseEntity(
                ApiError.internalServerError(
                    errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                    path = safeErrorPath(req)
                ),
                HttpStatus.INTERNAL_SERVER_ERROR
            )
        } else {
            // Development mode: include more details
            ResponseEntity(
                ApiError(
                    status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    error = "Internal Server Error",
                    message = e.message ?: Messages.get("error.unexpected"),
                    errorId = if (errorSanitizationConfig.includeErrorId) errorId else null,
                    path = safeErrorPath(req),
                    trace = null
                ),
                HttpStatus.INTERNAL_SERVER_ERROR
            )
        }
    }

    /**
     * Logs an exception with the error ID for correlation.
     */
    private fun logExceptionWithId(
        errorId: String,
        req: HttpServletRequest,
        e: Exception,
        // For body-parse exceptions (HttpMessageNotReadableException / JsonMappingException) the
        // exception MESSAGE and stack embed the offending request VALUE — which can be participant
        // content (PHI). Those call sites pass logMessage=false so only the error id + exception
        // CLASS are logged here; the field path / body length is already logged (without the value)
        // by the caller.
        logMessage: Boolean = true,
    ) {
        // Spring builds NoHandlerFoundException messages from the raw request URI. That URI can
        // contain arbitrary participant content, so never pass this framework-generated message,
        // stack, or URI to an application logger. A one-way route reference retains correlation
        // without assuming an unknown path follows any known route schema.
        if (e is NoHandlerFoundException) {
            logger.error(
                "Error ID: {} | Method: {} | URI: unmapped-route | routeRef: {} | Exception: {} | Message: request-derived message omitted",
                errorId,
                sanitizeLogValue(req.method),
                LogSanitizer.stableFingerprint(req.requestURI, prefix = "route"),
                e.javaClass.name,
            )
            return
        }
        if (!logMessage) {
            logger.error(
                "Error ID: {} - {} (message/stack omitted: may contain request content)",
                errorId,
                e.javaClass.simpleName,
            )
            return
        }
        if (errorSanitizationConfig.logFullErrors) {
            logger.error(
                "Error ID: {} | Method: {} | URI: {} | IP: {} | Exception: {} | Message: {}",
                errorId,
                sanitizeLogValue(req.method),
                safeErrorPath(req),
                LogSanitizer.stableFingerprint(req.remoteAddr ?: "", prefix = "ip"),
                e.javaClass.name,
                errorSanitizationConfig.sanitizeMessage(e.message)
            )
        } else {
            logger.error(
                "Error ID: {} - {} - {}",
                errorId,
                e.javaClass.simpleName,
                errorSanitizationConfig.sanitizeMessage(e.message)
            )
        }
    }

    /**
     * Sanitizes a value for safe logging (prevents log injection).
     */
    private fun sanitizeLogValue(value: String?): String {
        if (value == null) return "null"
        return value
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .take(500)
    }

    private fun safeErrorPath(req: HttpServletRequest): String = LogSanitizer.sanitizeRequestPath(req.requestURI)

    internal companion object {
        private val logger = LoggerFactory.getLogger(ChronicleServerExceptionHandler::class.java)
    }
}

public open class StudyRegistrationNotFoundException : RuntimeException {
    internal constructor(message: String) : super(message)
    internal constructor(message: String, cause: Throwable) : super(message, cause)
}

public open class CandidateNotFoundException(candidateId: UUID, message: String? = "$candidateId") : RuntimeException(message)
public open class StudyNotFoundException(public val studyId: UUID, message: String) : RuntimeException(message)
public open class OrganizationNotFoundException(public val organization: UUID, message: String) : RuntimeException(message)
public open class TimeUseDiaryDownloadException(public val studyId: UUID, message: String) : RuntimeException(message)
