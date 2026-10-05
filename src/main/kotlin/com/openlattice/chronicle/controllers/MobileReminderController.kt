package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeCommand
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.util.validateParticipantId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Device-bound reminder manifest. Android displays native notifications, but every
 * destination is a backend-hosted participant form protected by a one-time access code.
 * Time Use Diary is intentionally absent: it has no Android reminder path.
 */
@RestController
@Timed
public open class MobileReminderController(
    private val enrollmentManager: EnrollmentManager,
    private val studyService: StudyService,
    private val surveysManager: SurveysManager,
    private val participantFormAccessService: ParticipantFormAccessService,
) {
    // The DispatcherServlet is mounted at /chronicle/* and strips that prefix before Spring
    // matches controller paths. Keep the full path as well for direct servlet access.
    @PostMapping(
        path = [
            "/v4/study/{studyId}/participant/{participantId}/reminders",
            "/chronicle/v4/study/{studyId}/participant/{participantId}/reminders",
        ]
    )
    public fun getReminderConfiguration(
        @PathVariable studyId: UUID,
        @PathVariable participantId: String,
        authentication: Authentication,
        response: HttpServletResponse,
    ): MobileReminderConfiguration {
        val device = boundDevice(studyId, participantId, authentication)
        val status = enrollmentManager.getDeviceParticipationStatus(studyId, participantId)
        val forms = if (status == ParticipationStatus.ENROLLED) {
            buildForms(studyId, participantId, device, mintAccessCodes = true)
        } else {
            emptyList()
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return MobileReminderConfiguration(status, forms)
    }

    /** Returns reminder metadata for local scheduling without issuing participant access codes. */
    @GetMapping(
        path = [
            "/v4/study/{studyId}/participant/{participantId}/reminders",
            "/chronicle/v4/study/{studyId}/participant/{participantId}/reminders",
        ]
    )
    public fun getReminderSchedule(
        @PathVariable studyId: UUID,
        @PathVariable participantId: String,
        authentication: Authentication,
        response: HttpServletResponse,
    ): MobileReminderConfiguration {
        val device = boundDevice(studyId, participantId, authentication)
        val status = enrollmentManager.getDeviceParticipationStatus(studyId, participantId)
        val forms = if (status == ParticipationStatus.ENROLLED) {
            buildForms(studyId, participantId, device, mintAccessCodes = false)
        } else {
            emptyList()
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return MobileReminderConfiguration(status, forms)
    }

    /**
     * Side-effect-free status read for the device's enrollment monitor. The reminder manifest
     * rotates form access codes, so polling it every 15 minutes would churn live links.
     * A deleted participant reads NOT_ENROLLED. A key deleted by erasure is admitted here through its
     * tombstone (see ApiKeyAuthenticationFilter), so a phone offline through the quarantine still learns it.
     */
    @GetMapping(
        path = [
            "/v4/study/{studyId}/participant/{participantId}/status",
            "/chronicle/v4/study/{studyId}/participant/{participantId}/status",
        ]
    )
    public fun getParticipationStatus(
        @PathVariable studyId: UUID,
        @PathVariable participantId: String,
        authentication: Authentication,
        response: HttpServletResponse,
    ): ParticipationStatus {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        // An erased key carries no binding to check; it answers NOT_ENROLLED whatever the path names.
        if ((authentication as? ApiKeyAuthenticationToken)?.erased == true) {
            validateParticipantId(participantId)
            return ParticipationStatus.NOT_ENROLLED
        }
        boundDevice(studyId, participantId, authentication)
        return enrollmentManager.getDeviceParticipationStatus(studyId, participantId)
    }

    private fun boundDevice(studyId: UUID, participantId: String, authentication: Authentication): ApiKeyAuthenticationToken {
        validateParticipantId(participantId)
        val device = authentication as? ApiKeyAuthenticationToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.enrollment.apiKeyRequired"))
        if (device.studyId != studyId || device.participantId != participantId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.deviceNotBound"))
        }
        return device
    }

    private fun buildForms(
        studyId: UUID,
        participantId: String,
        device: ApiKeyAuthenticationToken,
        mintAccessCodes: Boolean,
    ): List<MobileReminderForm> {
        val templates = buildList {
            if (studyService.isNotificationsEnabled(studyId)) {
                add(
                    ReminderTemplate(
                        kind = ParticipantFormKind.APP_USAGE,
                        resourceId = null,
                        title = Messages.get("reminder.appUsage.title"),
                        recurrenceRule = "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0",
                    )
                )
            }
            surveysManager.getStudyQuestionnaires(studyId)
                .asSequence()
                .filter { questionnaire -> questionnaire.active }
                .mapNotNull { questionnaire ->
                    val id = questionnaire.id ?: return@mapNotNull null
                    val recurrence = questionnaire.recurrenceRule?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ReminderTemplate(
                        kind = ParticipantFormKind.QUESTIONNAIRE,
                        resourceId = id,
                        title = questionnaire.title,
                        recurrenceRule = recurrence,
                    )
                }
                .forEach(::add)
        }
        if (!mintAccessCodes) {
            val noCodeExpiresAt = OffsetDateTime.now()
            return templates.map { template ->
                MobileReminderForm(
                    formKind = template.kind,
                    resourceId = template.resourceId,
                    title = template.title,
                    recurrenceRule = template.recurrenceRule,
                    accessCode = "",
                    accessCodeExpiresAt = noCodeExpiresAt,
                )
            }
        }
        val accessCodes = participantFormAccessService.createAccessCodes(
            templates.map { template ->
                ParticipantAccessCodeCommand(
                    studyId = studyId,
                    participantId = participantId,
                    formKind = template.kind,
                    resourceId = template.resourceId,
                    logicalDate = null,
                    requestedExpiresAt = null,
                    issuerType = ParticipantAccessCodeIssuerType.DEVICE,
                    issuedBy = "device:${device.keyId}",
                )
            },
        )
        return templates.zip(accessCodes) { template, access ->
            MobileReminderForm(
                formKind = template.kind,
                resourceId = template.resourceId,
                title = template.title,
                recurrenceRule = template.recurrenceRule,
                accessCode = access.accessCode,
                accessCodeExpiresAt = access.expiresAt,
            )
        }
    }

    private data class ReminderTemplate(
        val kind: ParticipantFormKind,
        val resourceId: UUID?,
        val title: String,
        val recurrenceRule: String,
    )
}
