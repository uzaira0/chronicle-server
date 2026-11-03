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

import com.openlattice.chronicle.services.studies.EnrollmentDisclosurePendingException

import com.openlattice.chronicle.services.studies.StudySettingsRevisionMismatchException

import com.openlattice.chronicle.util.sqlCause

import com.fasterxml.jackson.databind.JsonMappingException
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.configuration.RequestBodyTooLargeException
import com.openlattice.chronicle.constants.CustomMediaType.FILE_TYPE_PARAMETER
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.controllers.ExceptionLogging.Companion.safeErrorPath
import com.openlattice.chronicle.controllers.ExceptionLogging.Companion.sanitizeLogValue
import com.openlattice.chronicle.exceptions.CandidateNotFoundException
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.exceptions.OrganizationNotFoundException
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.exceptions.StudyRegistrationNotFoundException
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.study.StudySettingsPreconditionFailure
import com.openlattice.chronicle.util.ClientIpRecord
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SsrfException
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletRequest
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import com.openlattice.chronicle.services.surveys.AppUsageDayConflictException
import com.openlattice.chronicle.services.enrollment.EnrollmentAttemptEndedException
import com.openlattice.chronicle.storage.ParticipantProcessingBlockedException
import com.openlattice.chronicle.generated.ParticipantProcessingContracts
import org.springframework.web.servlet.NoHandlerFoundException

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
    private val logging = ExceptionLogging(errorSanitizationConfig)
    private val requestErrors = RequestErrorResponses(errorSanitizationConfig)
    private val errors = ErrorResponses(errorSanitizationConfig)
    private val databaseErrors = DatabaseErrorResponses(errors, logging)

    /**
     * Handles NullPointerException as 500 Internal Server Error.
     * NPEs indicate programming errors, not missing resources.
     */
    @ExceptionHandler(NullPointerException::class)
    public fun handleNullPointerException(req: HttpServletRequest, e: NullPointerException): ResponseEntity<ApiError> {
        e.sqlCause()
            ?.let { return handleSqlException(req, it) }
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e)

        return errors.internalError(req, errorId)
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
    public fun handleNotFoundException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        // Audit study not found events
        recordEventBestEffort {
            val principal = Principals.getCurrentSecurablePrincipal()
            val principals = Principals.getCurrentPrincipals()
            val studyId = (e as? StudyNotFoundException)?.studyId
            AuditableEvent(
                AclKey(studyId ?: IdConstants.CHRONICLE.id), principal.id, principal.principal,
                AuditEventType.STUDY_NOT_FOUND,
                if (studyId != null) "Unable to find study $studyId [ErrorId: $errorId]"
                    else "Resource not found [ErrorId: $errorId]",
                studyId ?: IdConstants.UNINITIALIZED.id,
                data = mapOf("principals" to principals, "errorId" to errorId),
            )
        }
        return errors.notFound(req, errorId)
    }

    /**
     * Handles 400 Bad Request for illegal arguments and malformed requests.
     */
    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class, RequestBodyTooLargeException::class)
    public fun handleIllegalArgumentException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is RequestBodyTooLargeException }) {
            return handleResponseStatusException(req, ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE))
        }
        e.sqlCause()?.let { return databaseErrors.handleSqlException(req, it) }
        val errorId = ApiError.generateErrorId()
        val message = requestErrors.invalidArgumentMessage(e)
        if (e is HttpMessageNotReadableException) logging.logUnreadableBody(errorId, e)
        logging.logExceptionWithId(errorId, req, e, logMessage = e !is HttpMessageNotReadableException, clientError = true)
        return errors.badRequest(req, errorId, message, code = requestErrors.bodyErrorCode(e))
    }

    /**
     * Handles bean validation of request bodies (@Valid) and of method parameters, including the rows of a list body
     * and constraints on path variables. Returns field-specific validation errors without exposing internal details.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class, HandlerMethodValidationException::class)
    public fun handleValidationException(
        req: HttpServletRequest,
        e: Exception
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logger.warn(
            "Validation failed for request {} {} [ErrorId: {}]",
            sanitizeLogValue(req.method),
            safeErrorPath(req),
            errorId
        )

        return errors.badRequest(req = req, errorId = errorId,
            message = Messages.get("error.request.validationFailed"),
            details = ValidationDetails.of(e),
            code = requestErrors.validationErrorCode(),
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
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        return errors.badRequest(req = req, errorId = errorId,
            message = Messages.get("error.request.missingParameter"),
            details = listOf(Messages.format("error.request.missingParameter.detail", e.parameterName)),
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
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        return errors.badRequest(req = req, errorId = errorId,
            message = Messages.get("error.request.missingHeader"),
            details = listOf(Messages.format("error.request.missingHeader.detail", e.headerName)),
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
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        val expectedType = e.requiredType?.simpleName ?: "unknown"
        return errors.badRequest(req = req, errorId = errorId,
            message = Messages.get("error.request.invalidParameterType"),
            details = listOf(Messages.format("error.request.invalidParameterType.detail", e.name, expectedType)),
        )
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    public fun handleNotAcceptableException(
        req: HttpServletRequest,
        e: HttpMediaTypeNotAcceptableException,
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e, clientError = true)
        val fileType = req.getParameter(FILE_TYPE_PARAMETER)
        return if (fileType != null && FileType.values().none { it.name == fileType }) {
            errors.badRequest(req, errorId, Messages.get("error.request.invalidParameterType"))
        } else {
            val status = HttpStatus.NOT_ACCEPTABLE
            errors.response(req, errorId, ApiError(status.value(), status.reasonPhrase, status.reasonPhrase))
        }
    }

    /**
     * Handles 409 Conflict for illegal state exceptions.
     */
    @ExceptionHandler(IllegalStateException::class)
    public fun handleIllegalStateException(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        if (e.message == "Participant is already registered for this study" && (e.cause as? SQLException)?.sqlState == "23505") {
            logging.logExceptionWithId(ApiError.generateErrorId(), req, e, clientError = true)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(409, "Conflict",
                "Participant is already registered for this study", path = safeErrorPath(req)))
        }
        e.sqlCause()
            ?.let { return handleSqlException(req, it) }
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e)

        val message = if (errorSanitizationConfig.sanitizeErrors) {
            errorSanitizationConfig.sanitizeMessage(e.message)
        } else {
            e.message ?: Messages.get("error.state.invalid")
        }

        return errors.response(req, errorId, ApiError(
            status = HttpStatus.CONFLICT.value(), error = "Conflict", message = message,
        ))
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
    /**
     * 412 for a stale study-settings `If-Match` precondition.
     *
     * The body carries the current revision and the current settings so the dashboard can
     * re-render from this response instead of issuing another GET and racing again. No error id or
     * path sanitization applies: the payload is the caller's own authorized study state.
     */
    @ExceptionHandler(StudySettingsRevisionMismatchException::class)
    public fun handleStudySettingsRevisionMismatch(
        e: StudySettingsRevisionMismatchException,
    ): ResponseEntity<StudySettingsPreconditionFailure> {
        logger.info("Study settings precondition failed at revision {}", e.currentRevision)
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
            .eTag("\"${e.currentRevision}\"")
            .body(
                StudySettingsPreconditionFailure(
                    settingsRevision = e.currentRevision,
                    settings = e.currentSettings,
                ),
            )
    }

    @ExceptionHandler(ResponseStatusException::class)
    public fun handleResponseStatusException(
        req: HttpServletRequest,
        e: ResponseStatusException
    ): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        logging.logExceptionWithId(errorId, req, e, logMessage = e !is EnrollmentDisclosurePendingException,
            clientError = status.is4xxClientError)
        if (status.is5xxServerError) {
            return errors.internalError(req, errorId, status)
        }

        // Preserve the existing 413 error text after Spring 7 renamed this status.
        val reasonPhrase = if (status == HttpStatus.CONTENT_TOO_LARGE) PAYLOAD_TOO_LARGE_REASON else status.reasonPhrase
        return errors.response(req, errorId, ApiError(
            status = status.value(), error = reasonPhrase, message = e.reason ?: reasonPhrase,
            blockingParticipantIds = (e as? EnrollmentDisclosurePendingException)?.blockingParticipantIds,
            code = (e as? ParticipantProcessingBlockedException)?.let { ParticipantProcessingContracts.PARTICIPANT_PROCESSING_BLOCKED_CODE }
                ?: (e as? AppUsageDayConflictException)?.conflict?.name
                ?: (e as? EnrollmentAttemptEndedException)?.code,
        ))
    }

    @ExceptionHandler(CollectionUnavailableException::class)
    internal fun handleCollectionUnavailable(req: HttpServletRequest, failure: CollectionUnavailableException): ResponseEntity<ApiError> =
        handleResponseStatusException(req, ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get(when (failure.scope) {
            CollectionUnavailableException.Scope.STUDY -> "error.study.registrationUnavailable"
            CollectionUnavailableException.Scope.PARTICIPANT -> "error.enrollment.notEnrolled"
        })))

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
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        // Always return generic unauthorized response
        return errors.unauthorized(req, errorId)
    }

    /**
     * Handles 403 Forbidden for access denied exceptions.
     * Always returns generic message to prevent information disclosure.
     */
    @ExceptionHandler(AccessDeniedException::class, ForbiddenException::class)
    public fun handleUnauthorizedExceptions(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e, clientError = true)

        // Always return generic forbidden response
        return errors.forbidden(req, errorId)
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
            ClientIpRecord.logReference(req),
            safeErrorPath(req)
        )

        // Return generic error to prevent information disclosure
        return errors.forbidden(req, errorId)
    }

    /**
     * Handles database/SQL exceptions.
     * ALWAYS returns generic error to prevent SQL/schema disclosure.
     */
    @ExceptionHandler(SQLException::class)
    public fun handleSqlException(req: HttpServletRequest, e: SQLException): ResponseEntity<ApiError> =
        databaseErrors.handleSqlException(req, e)

    /**
     * Handles JSON mapping/parsing exceptions.
     */
    @ExceptionHandler(JsonMappingException::class)
    public fun handleJsonExceptions(req: HttpServletRequest, e: JsonMappingException): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        // Log only the field PATH (e.g. "rows[3].timestamp"), never e.originalMessage — the
        // original message embeds the offending field VALUE, which can be participant content/PHI.
        val fieldPath = e.path.joinToString(".") { ref -> ref.fieldName ?: "[${ref.index}]" }
        logger.warn("JSON mapping error [ErrorId: {}] at path: {} (value not logged)", errorId, fieldPath)
        // e.message / stack embed the offending field VALUE (Jackson populates it) — suppress it.
        logging.logExceptionWithId(errorId, req, e, logMessage = false, clientError = true)

        return errors.badRequest(req = req, errorId = errorId,
            message = Messages.get("error.request.invalidJson"),
        )
    }

    /**
     * 405 for a mapped path called with the wrong method, 415 for an unsupported body type.
     *
     * Without these the catch-all answered 500, so a client probing a newer method on an older
     * server (GET /reminders against a POST-only release) could not tell "not supported" from a
     * server fault and never fell back.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class, HttpMediaTypeNotSupportedException::class)
    public fun handleUnsupportedRequest(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e, clientError = true)
        val status = if (e is HttpRequestMethodNotSupportedException) {
            HttpStatus.METHOD_NOT_ALLOWED
        } else {
            HttpStatus.UNSUPPORTED_MEDIA_TYPE
        }
        val headers = HttpHeaders()
        val allowed = (e as? HttpRequestMethodNotSupportedException)?.supportedHttpMethods
        if (!allowed.isNullOrEmpty()) headers.allow = allowed
        return errors.response(req, errorId, ApiError(status.value(), status.reasonPhrase, status.reasonPhrase), headers)
    }

    /**
     * Catch-all handler for unhandled exceptions.
     * Always returns generic 500 error in production to prevent information disclosure.
     */
    @ExceptionHandler(Exception::class)
    public fun handleOtherExceptions(req: HttpServletRequest, e: Exception): ResponseEntity<ApiError> {
        e.sqlCause()
            ?.let { return handleSqlException(req, it) }
        val errorId = ApiError.generateErrorId()
        logging.logExceptionWithId(errorId, req, e)

        // Check if this exception type should always be sanitized
        val shouldAlwaysSanitize = errorSanitizationConfig.shouldAlwaysSanitize(e.javaClass.name)

        return if (errorSanitizationConfig.sanitizeErrors || shouldAlwaysSanitize) {
            errors.internalError(req, errorId)
        } else {
            errors.response(req, errorId, ApiError(
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(), error = "Internal Server Error",
                message = e.message ?: Messages.get("error.unexpected"),
            ))
        }
    }

    internal companion object {
        internal const val PAYLOAD_TOO_LARGE_REASON = "Payload Too Large"
        private val logger = LoggerFactory.getLogger(ChronicleServerExceptionHandler::class.java)
    }
}
