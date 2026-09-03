package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditLogEntryBuilder
import com.openlattice.chronicle.audit.AuditRequestContext
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.StudyLifecycleStatus
import jakarta.annotation.PostConstruct
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Operator-pinned reviewer identity; callers cannot supply or override either value. */
public data class ReviewerEnrollmentScope(
    val studyId: UUID,
    val participantId: String,
)

/** Fresh one-time invitation plus the authoritative disclosure it is bound to. */
public data class ReviewerEnrollmentBootstrapResponse(
    val enrollmentCode: String,
    val preview: EnrollmentPreviewResponse,
)

/**
 * Converts the reusable Play Console reviewer credential into a short-lived, one-time
 * enrollment capability. The reusable credential itself is handled only by the exact-route
 * authentication filter and never reaches persistence.
 */
public open class ReviewerEnrollmentService(
    private val scope: ReviewerEnrollmentScope?,
    private val participantFormAccessService: ParticipantFormAccessService,
    private val enrollmentManifestService: EnrollmentManifestService,
    private val enrollmentManager: EnrollmentManager,
    private val studyService: StudyService,
    private val studyLifecycleService: StudyLifecycleService,
    private val auditService: AuditService,
    private val clock: Clock = Clock.systemUTC(),
) {
    internal companion object {
        public const val ISSUED_BY: String = "play-reviewer-bootstrap"
        private const val INVITATION_LIFETIME_MINUTES = 15L
    }

    public fun isConfiguredStudy(studyId: UUID): Boolean = scope?.studyId == studyId

    /** Fail startup closed when an enabled public reviewer route points at an unusable subject. */
    @PostConstruct
    public fun validateConfiguredScope() {
        scope?.let { configuredScope ->
            val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            requireAvailableStudy(configuredScope, now)
            requireAvailableParticipant(configuredScope)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    public open fun mint(): ReviewerEnrollmentBootstrapResponse {
        val configuredScope = requireConfiguredScope()
        return try {
            val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            requireAvailableStudy(configuredScope, now)
            requireAvailableParticipant(configuredScope)

            val invitation = participantFormAccessService.createReplacingAccessCode(
                studyId = configuredScope.studyId,
                participantId = configuredScope.participantId,
                formKind = ParticipantFormKind.ENROLLMENT,
                resourceId = null,
                logicalDate = null,
                requestedExpiresAt = now.plusMinutes(INVITATION_LIFETIME_MINUTES),
                issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
                issuedBy = ISSUED_BY,
            )
            val preview = enrollmentManifestService.getPreview(
                configuredScope.studyId,
                configuredScope.participantId,
                invitation.accessCode,
            )
            audit(configuredScope.studyId, success = true, status = HttpStatus.OK, outcome = "invitation_minted")
            ReviewerEnrollmentBootstrapResponse(invitation.accessCode, preview)
        } catch (exception: ResponseStatusException) {
            audit(
                configuredScope.studyId,
                success = false,
                status = HttpStatus.valueOf(exception.statusCode.value()),
                outcome = "scope_unavailable",
            )
            throw exception
        } catch (exception: Exception) {
            audit(
                configuredScope.studyId,
                success = false,
                status = HttpStatus.INTERNAL_SERVER_ERROR,
                outcome = "mint_failed",
            )
            throw exception
        }
    }

    private fun requireConfiguredScope(): ReviewerEnrollmentScope =
        scope ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.notFound"))

    private fun requireAvailableStudy(configuredScope: ReviewerEnrollmentScope, now: OffsetDateTime) {
        val study = try {
            studyService.getStudy(configuredScope.studyId)
        } catch (_: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.reviewer.unavailable"))
        }
        if (
            studyLifecycleService.getLifecycleStatus(configuredScope.studyId) != StudyLifecycleStatus.ACTIVE ||
            now.isBefore(study.startedAt) ||
            !now.isBefore(study.endedAt)
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.reviewer.unavailable"))
        }
    }

    private fun requireAvailableParticipant(configuredScope: ReviewerEnrollmentScope) {
        if (!enrollmentManager.isKnownParticipant(configuredScope.studyId, configuredScope.participantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.reviewer.unavailable"))
        }
        val status = enrollmentManager.getParticipationStatus(configuredScope.studyId, configuredScope.participantId)
        if (status != ParticipationStatus.ENROLLED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.reviewer.unavailable"))
        }
    }

    private fun audit(studyId: UUID, success: Boolean, status: HttpStatus, outcome: String) {
        auditService.log(
            AuditLogEntryBuilder()
                .ipAddress(AuditRequestContext.getClientIpAddress())
                .userAgent(AuditRequestContext.getUserAgent())
                .requestPath(AuditRequestContext.getRequestPath())
                .requestMethod(AuditRequestContext.getRequestMethod())
                .action(if (success) AuditAction.CREATE else AuditAction.INVALID_REQUEST)
                .resourceType("PlayReviewerEnrollmentInvitation")
                .studyId(studyId)
                .success(success)
                .responseCode(status.value())
                .additionalData(mapOf("outcome" to outcome))
                .build(),
        )
    }
}
