package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.surveys.appUsageDayRules
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.util.validateParticipantId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Device-bound reminder schedule. Android displays native notifications, but every
 * destination is a backend-hosted participant form; the phone mints its one-time code on tap.
 * Time Use Diary is intentionally absent: it has no Android reminder path.
 */
@RestController
@Timed
public open class MobileReminderController(
    private val enrollmentManager: EnrollmentManager,
    private val studyService: StudyService,
    private val surveysManager: SurveysManager,
    private val participantCollectionAcknowledgmentService: ParticipantCollectionAcknowledgmentService,
) {
    // The DispatcherServlet is mounted at /chronicle/* and strips that prefix before Spring
    // matches controller paths. Keep the full path as well for direct servlet access.
    /** Reminder metadata for local scheduling; the phone mints a form code only when the participant taps. */
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
        val forms = if (remindersActive(studyId, participantId, device, status)) {
            buildForms(studyId, participantId)
        } else {
            emptyList()
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return MobileReminderConfiguration(status, forms)
    }

    /**
     * Side-effect-free status read for the device's enrollment monitor. A key deleted by erasure
     * is admitted through its tombstone (see ApiKeyAuthenticationFilter), so an offline phone learns it.
     * It carries the participant's closed capture windows and the server's clock: the phone converts the windows to its
     * own clock with that measurement and decides on the phone which rows fall inside them.
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
    ): MobileParticipationStatus {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if ((authentication as? ApiKeyAuthenticationToken)?.erased == true) {
            validateParticipantId(participantId)
            return MobileParticipationStatus(ParticipationStatus.ERASED, OffsetDateTime.now(ZoneOffset.UTC))
        }
        boundDevice(studyId, participantId, authentication)
        return enrollmentManager.getDeviceCollectionStatus(studyId, participantId)
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

    /** A required-module decline stops reminders like a pause, while the real participation status is still reported. */
    private fun remindersActive(
        studyId: UUID,
        participantId: String,
        device: ApiKeyAuthenticationToken,
        status: ParticipationStatus,
    ): Boolean = status == ParticipationStatus.ENROLLED && device.randomDeviceId?.let {
        !participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, it)
    } == true

    private fun buildForms(studyId: UUID, participantId: String): List<MobileReminderForm> = buildList {
        val rules = appUsageDayRules(StudySettings(studyService.getStudySettings(studyId)))
        // The survey admission decides whether the study asks about app usage at all (shared devices only).
        surveysManager.getAppUsageDays(studyId, participantId, rules)?.let { days ->
            add(
                MobileReminderForm(
                    formKind = ParticipantFormKind.APP_USAGE,
                    title = Messages.get("reminder.appUsage.title"),
                    // Noon and 19:00 in the study zone, until every ended day is answered.
                    recurrenceRule = "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0",
                    timeZone = days.timeZone,
                    unansweredDays = days.days.any { it.state != AppUsageDayState.ANSWERED },
                )
            )
        }
        surveysManager.getStudyQuestionnaires(studyId)
            .asSequence()
            .filter { questionnaire -> questionnaire.active }
            .mapNotNull { questionnaire ->
                val id = questionnaire.id ?: return@mapNotNull null
                val recurrence = questionnaire.recurrenceRule?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MobileReminderForm(formKind = ParticipantFormKind.QUESTIONNAIRE, resourceId = id, title = questionnaire.title,
                    recurrenceRule = recurrence)
            }
            .forEach(::add)
    }
}
