package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.ExchangeParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.validateParticipantId
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

@RestController
@RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class ParticipantFormAccessController(
    private val participantFormAccessService: ParticipantFormAccessService,
    private val enrollmentManager: EnrollmentManager,
    private val auditService: AuditService,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
) : AuthorizingComponent {
    @PostMapping(
        path = [
            "/v3/study/{studyId}/participant/{participantId}/form-access-codes",
            "/chronicle/v3/study/{studyId}/participant/{participantId}/form-access-codes",
            "/v4/study/{studyId}/participant/{participantId}/form-access-codes",
            "/chronicle/v4/study/{studyId}/participant/{participantId}/form-access-codes",
        ]
    )
    public fun createAccessCode(
        @PathVariable studyId: UUID,
        @PathVariable participantId: String,
        @Valid @RequestBody request: CreateParticipantFormAccessCodeRequest,
        authentication: Authentication,
        response: HttpServletResponse,
    ): ParticipantFormAccessCodeResponse {
        validateParticipantId(participantId)
        if (!enrollmentManager.isKnownParticipant(studyId, participantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.enrollment.participantNotRegistered"))
        }

        val (issuerType, issuedBy) = if (authentication is ApiKeyAuthenticationToken) {
            if (authentication.studyId != studyId || authentication.participantId != participantId) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.deviceNotBound"))
            }
            if (request.formKind !in setOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.form.kindNotIssuableByReminder"))
            }
            ParticipantAccessCodeIssuerType.DEVICE to "device:${authentication.keyId}"
        } else {
            ensureWriteAccess(AclKey(studyId))
            ParticipantAccessCodeIssuerType.RESEARCHER to authentication.name
        }

        val accessCodeResponse = try {
            participantFormAccessService.createAccessCode(
                studyId = studyId,
                participantId = participantId,
                formKind = request.formKind,
                resourceId = request.resourceId,
                logicalDate = request.logicalDate,
                requestedExpiresAt = request.expiresAt,
                issuerType = issuerType,
                issuedBy = issuedBy,
            )
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
        }
        auditService.logWithContext {
            action(AuditAction.CREATE)
            resourceType("ParticipantFormAccessCode")
            studyId(studyId)
            success(true)
            additionalData(
                mapOf(
                    "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                    "formKind" to request.formKind.name,
                    "issuerType" to issuerType.name,
                )
            )
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return accessCodeResponse
    }

    @PostMapping(
        path = [
            "/chronicle/v3/participant-access/exchange",
            "/v3/participant-access/exchange",
        ]
    )
    public fun exchangeAccessCode(
        @Valid @RequestBody request: ExchangeParticipantFormAccessCodeRequest,
        response: HttpServletResponse,
    ): ParticipantFormSessionResponse {
        val exchanged = participantFormAccessService.exchangeAccessCode(request.accessCode)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.form.accessCodeInvalid"))

        val cookie = ResponseCookie.from(
            ParticipantFormAccessFilter.SESSION_COOKIE,
            exchanged.rawSessionToken,
        )
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(Duration.between(java.time.OffsetDateTime.now(), exchanged.response.expiresAt).coerceAtLeast(Duration.ZERO))
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        auditService.logWithContext {
            action(AuditAction.CREATE)
            resourceType("ParticipantFormSession")
            studyId(exchanged.response.studyId)
            success(true)
            additionalData(mapOf("formKind" to exchanged.response.formKind.name))
        }
        return exchanged.response
    }
}
