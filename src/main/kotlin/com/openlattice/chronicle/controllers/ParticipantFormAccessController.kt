package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.configuration.CookiePolicy
import com.openlattice.chronicle.configuration.CookiePurpose
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.data.ParticipationStatus
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
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.validateParticipantId
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@RestController
public open class ParticipantFormAccessController(
    private val participantFormAccessService: ParticipantFormAccessService,
    private val enrollmentManager: EnrollmentManager,
    private val studyManager: StudyManager,
    private val participantCollectionAcknowledgmentService: ParticipantCollectionAcknowledgmentService,
    private val auditService: AuditService,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    private val cookiePolicy: CookiePolicy,
) : AuthorizingComponent {
    @GetMapping(path = [
        "/v3/study/{studyId}/pending-enrollments",
        "/chronicle/v3/study/{studyId}/pending-enrollments",
    ])
    public fun getPendingEnrollments(
        @PathVariable studyId: UUID,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") limit: Int = 100,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") offset: Int = 0,
    ): List<String> {
        ensureReadAccess(AclKey(studyId))
        return participantFormAccessService.getPendingEnrollments(studyId, limit, offset)
    }

    @GetMapping(path = ["/v3/study/{studyId}/settings/locks", "/chronicle/v3/study/{studyId}/settings/locks"])
    public fun getSettingLocks(@PathVariable studyId: UUID): List<StudySettingType> {
        ensureReadAccess(AclKey(studyId))
        return participantFormAccessService.getSettingLocks(studyId)
    }

    @PostMapping(path = [
        "/v3/study/{studyId}/participant/{participantId}/pending-enrollment/cancel",
        "/chronicle/v3/study/{studyId}/participant/{participantId}/pending-enrollment/cancel",
    ])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public fun cancelPendingEnrollment(@PathVariable studyId: UUID, @PathVariable participantId: String) {
        ensureWriteAccess(AclKey(studyId))
        validateParticipantId(participantId)
        participantFormAccessService.cancelPendingEnrollment(studyId, participantId)
    }

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

        val issuer = if (authentication is ApiKeyAuthenticationToken) {
            if (authentication.studyId != studyId || authentication.participantId != participantId) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.deviceNotBound"))
            }
            if (request.formKind !in setOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.form.kindNotIssuableByReminder"))
            }
            // Like the reminder manifest, a device mints codes only while enrolled; this also covers a
            // researcher deletion before its quarantine hides the row.
            if (enrollmentManager.getDeviceParticipationStatus(studyId, participantId) != ParticipationStatus.ENROLLED) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.notEnrolled"))
            }
            if (authentication.randomDeviceId?.let {
                    participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, it)
                } != false
            ) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.collectionHalted"))
            }
            FormCodeIssuer(ParticipantAccessCodeIssuerType.DEVICE, "device:${authentication.keyId}")
        } else {
            ensureWriteAccess(AclKey(studyId))
            FormCodeIssuer(ParticipantAccessCodeIssuerType.RESEARCHER, authentication.name)
        }

        val accessCodeResponse = try {
            participantFormAccessService.createAccessCode(
                studyId, participantId, request.formKind, request.resourceId, request.logicalDate,
                request.expiresAt, issuer.type, issuer.id,
            )
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
        }
        auditService.logWithContext {
            action(AuditEventType.CREATE)
            resourceType("ParticipantFormAccessCode")
            studyId(studyId)
            success(true)
            additionalData(
                mapOf(
                    "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                    "formKind" to request.formKind.name,
                    "issuerType" to issuer.type.name,
                )
            )
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return accessCodeResponse
    }

    /**
     * The enrollment link already sent to this participant, for the QR dialog to show again. 404 when there is
     * none; issuing a new one is the explicit POST to form-access-codes, which replaces the sent link.
     */
    @GetMapping(path = [
        "/v3/study/{studyId}/participant/{participantId}/enrollment-code",
        "/chronicle/v3/study/{studyId}/participant/{participantId}/enrollment-code",
    ])
    public fun getLiveEnrollmentCode(
        @PathVariable studyId: UUID,
        @PathVariable participantId: String,
        response: HttpServletResponse,
    ): ParticipantFormAccessCodeResponse {
        ensureWriteAccess(AclKey(studyId))
        validateParticipantId(participantId)
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        val live = participantFormAccessService.liveEnrollmentInvitation(studyId, participantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        auditService.logWithContext {
            action(AuditEventType.VIEW)
            resourceType("ParticipantFormAccessCode")
            studyId(studyId)
            success(true)
            additionalData(mapOf(
                "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                "formKind" to ParticipantFormKind.ENROLLMENT.name,
            ))
        }
        return live
    }

    @RateLimit(type = RateLimitType.WRITE)
    @GetMapping(path = ["/chronicle/v3/participant-access/session", "/v3/participant-access/session"])
    public fun recoverSession(
        @CookieValue(name = ParticipantFormAccessFilter.SESSION_COOKIE, required = false) token: String?,
        response: HttpServletResponse,
    ): ParticipantFormSessionResponse {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val context = token?.let(participantFormAccessService::recoverSession)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return withPolicyLinks(context)
    }

    @RateLimit(type = RateLimitType.WRITE)
    @PostMapping(
        path = [
            "/chronicle/v3/participant-access/exchange",
            "/v3/participant-access/exchange",
        ]
    )
    public fun exchangeAccessCode(
        @Valid @RequestBody request: ExchangeParticipantFormAccessCodeRequest,
        response: HttpServletResponse,
        servletRequest: HttpServletRequest? = null,
    ): ParticipantFormSessionResponse {
        val exchanged = participantFormAccessService.exchangeAccessCode(request.accessCode, request.attemptId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.form.accessCodeInvalid"))

        cookiePolicy.addCookie(
            response = response,
            request = servletRequest,
            purpose = CookiePurpose.PARTICIPANT_SESSION,
            name = ParticipantFormAccessFilter.SESSION_COOKIE,
            value = exchanged.rawSessionToken,
            maxAge = Duration.between(OffsetDateTime.now(), exchanged.response.expiresAt)
                .coerceAtLeast(Duration.ZERO),
        )
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        auditService.logWithContext {
            action(AuditEventType.CREATE)
            resourceType("ParticipantFormSession")
            studyId(exchanged.response.studyId)
            success(true)
            additionalData(mapOf("formKind" to exchanged.response.formKind.name))
        }
        return withPolicyLinks(exchanged.response)
    }

    private fun withPolicyLinks(context: ParticipantFormSessionResponse): ParticipantFormSessionResponse {
        val settings = try {
            studyManager.getStudySettings(context.studyId)
        } catch (_: NoSuchElementException) {
            emptyMap()
        }
        return context.withPolicyLinks(settings)
    }
}

/** Copies the study's participant-policy links onto a form session, so every form can link them. */
internal fun ParticipantFormSessionResponse.withPolicyLinks(
    settings: Map<StudySettingType, StudySetting>,
): ParticipantFormSessionResponse {
    val policy = settings[StudySettingType.ParticipantPolicy] as? StudyParticipantPolicy ?: return this
    return copy(privacyPolicyUrl = policy.privacyPolicyUrl, withdrawalUrl = policy.withdrawalUrl)
}

private data class FormCodeIssuer(val type: ParticipantAccessCodeIssuerType, val id: String)
