package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.util.validateParticipantId
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditLogEntryBuilder
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.survey.AppUsage
import com.openlattice.chronicle.survey.AppUsageFrequencyResponse
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.survey.SurveyApi.Companion.APP_USAGE_FREQUENCY_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.APP_USAGE_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.CONTROLLER
import com.openlattice.chronicle.survey.SurveyApi.Companion.DATA_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.DEVICE_USAGE_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.END_DATE
import com.openlattice.chronicle.survey.SurveyApi.Companion.FILE_NAME
import com.openlattice.chronicle.survey.SurveyApi.Companion.FILTERED_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.survey.SurveyApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.QUESTIONNAIRE_ID
import com.openlattice.chronicle.survey.SurveyApi.Companion.QUESTIONNAIRE_ID_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.QUESTIONNAIRE_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.START_DATE
import com.openlattice.chronicle.survey.SurveyApi.Companion.STUDY_ID
import com.openlattice.chronicle.survey.SurveyApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.THRESHOLD
import com.openlattice.chronicle.survey.SurveyApi.Companion.TYPE
import com.openlattice.chronicle.util.ChronicleServerUtil
import com.openlattice.chronicle.util.LogSanitizer
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletResponse

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */

@RestController
@RequestMapping(CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class SurveyController @Inject constructor(
    public val surveysService: SurveysService,
    public val studyService: StudyService,
    public val downloadService: DataDownloadService,
    public val idGenerationService: HazelcastIdGenerationService,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    public val auditService: AuditService,
    private val enrollmentManager: EnrollmentManager,
    private val participantFormSubmissionReceiptService: ParticipantFormSubmissionReceiptService,
) : SurveyApi, AuthorizingComponent {

    private fun requireKnownParticipant(studyId: UUID, participantId: String) {
        validateParticipantId(participantId)
        if (!enrollmentManager.isKnownParticipant(studyId, participantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.enrollment.participantNotRegistered"))
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + FILTERED_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppsFilteredForStudyAppUsageSurvey(@PathVariable(STUDY_ID) studyId: UUID): Collection<String> {
        ensureReadAccess(AclKey(studyId))
        val result = surveysService.getAppsFilteredForStudyAppUsageSurvey(studyId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("SurveyFilter")
            studyId(studyId)
            success(true)
        }
        return result
    }

    @Timed
    @PutMapping(
        path = [STUDY_ID_PATH + FILTERED_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun setAppsFilteredForStudyAppUsageSurvey(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody appPackages: Set<String>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditAction.SETTINGS_CHANGE, "SurveyFilter", studyId,
            failureMessage = "Set filtered apps failed",
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size)) },
        ) {
            surveysService.setAppsFilteredForStudyAppUsageSurvey(studyId, appPackages)
            ok
        }
    }

    @Timed
    @PatchMapping(
        path = [STUDY_ID_PATH + FILTERED_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun filterAppForStudyAppUsageSurvey(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody appPackages: Set<String>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditAction.SETTINGS_CHANGE, "SurveyFilter", studyId,
            failureMessage = "Filter app failed",
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size, "operation" to "filter")) },
        ) {
            surveysService.filterAppForStudyAppUsageSurvey(studyId, appPackages)
            ok
        }
    }

    @Timed
    @DeleteMapping(
        path = [STUDY_ID_PATH + FILTERED_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun allowAppForStudyAppUsageSurvey(studyId: UUID, @Valid @RequestBody appPackages: Set<String>): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditAction.SETTINGS_CHANGE, "SurveyFilter", studyId,
            failureMessage = "Allow app failed",
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size, "operation" to "allow")) },
        ) {
            surveysService.allowAppForStudyAppUsageSurvey(studyId, appPackages)
            ok
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + DEVICE_USAGE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getDeviceUsageSurveyData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(value = START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(value = END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
        @RequestParam(THRESHOLD, required = false) thresholdInSeconds: Int?,
    ): DeviceUsage {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        requireKnownParticipant(realStudyId, participantId)
        SynchronousExportLimits.validate(setOf(participantId), startDateTime, endDateTime)
        // Participant format validation prevents injection; data query is scoped to (studyId, participantId)
        val deviceUsageData = surveysService.getDeviceUsageData(realStudyId, participantId, startDateTime, endDateTime)

        val threshold = thresholdInSeconds ?: (studyService
            .getStudySettings(realStudyId)
            .getOrDefault(StudySettingType.Survey, SurveySettings()) as SurveySettings).appUsageThresholdInSeconds
        val packagesToKeep = deviceUsageData.usageByPackage.filterValues { it <= threshold }.keys
        val usageByPackage = deviceUsageData.usageByPackage - packagesToKeep
        auditService.logWithContext {
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
            resourceType("UsageData")
            studyId(realStudyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "deviceUsage"))
            additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
        }
        return DeviceUsage(
            usageByPackage.values.sum(),
            usageByPackage,
            deviceUsageData.categoryByPackage - packagesToKeep
        )
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + APP_USAGE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppUsageSurveyData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(value = START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(value = END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
        @RequestParam(THRESHOLD, required = false) thresholdInSeconds: Int?,
    ): List<AppUsage> {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        requireKnownParticipant(realStudyId, participantId)
        SynchronousExportLimits.validate(setOf(participantId), startDateTime, endDateTime)
        // Participant format validation prevents injection; data query is scoped to (studyId, participantId)
        val appUsageData = surveysService.getAndroidAppUsageData(realStudyId, participantId, startDateTime, endDateTime)
        val aggregate = surveysService.computeAggregateUsage(startDateTime, appUsageData)

        val threshold = thresholdInSeconds ?: (studyService
            .getStudySettings(realStudyId)
            .getOrDefault(StudySettingType.Survey, SurveySettings()) as SurveySettings).appUsageThresholdInSeconds

        //Only keep packages that exceed threshold usage time for query.
        val packagesToKeep = aggregate.filterValues { it > threshold }.keys
        auditService.logWithContext {
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
            resourceType("AppUsageSurvey")
            studyId(realStudyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "appUsage"))
            additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
        }
        return appUsageData.filter { packagesToKeep.contains(it.appPackageName) }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + APP_USAGE_FREQUENCY_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppUsageFrequency(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): AppUsageFrequencyResponse {
        // Participant-readable: study-scoped, RLS-enforced via getStudyId, no ACL (mirrors
        // getAppUsageSurveyData). Returns only the DAILY/HOURLY flag the web survey needs to pick
        // its variant. The DataCollection setting is polymorphic — only ChronicleDataCollectionSettings
        // carries the frequency (the modular AndroidDataCollectionSetting does not) — so anything
        // else falls back to DAILY, matching upstream's study-level, default-DAILY read.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val frequency = (studyService.getStudySettings(realStudyId)[StudySettingType.DataCollection]
            as? ChronicleDataCollectionSettings)?.appUsageFrequency ?: AppUsageFrequency.DAILY
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("AppUsageFrequency")
            studyId(realStudyId)
            success(true)
        }
        return AppUsageFrequencyResponse(frequency)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + APP_USAGE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun submitAppUsageSurvey(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @Valid @RequestBody @Size(max = 10_000) surveyResponses: List<AppUsage>,
    ) {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        requireKnownParticipant(realStudyId, participantId)
        audited(
            AuditAction.DATA_SUBMISSION, "AppUsageSurvey", realStudyId,
            failureMessage = "App usage survey submission failed",
            onSuccess = {
                accessedPHI(true)
                phiFields(listOf("participantId", "surveyResponses"))
                additionalData(
                    mapOf(
                        "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                        "responseCount" to surveyResponses.size,
                    )
                )
            },
            onFailure = {
                additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
            },
        ) {
            val scope = ParticipantFormAccessFilter.currentScope()
            if (scope == null) {
                surveysService.submitAppUsageSurvey(realStudyId, participantId, surveyResponses)
            } else {
                participantFormSubmissionReceiptService.executeWithoutResult(
                    scope = scope,
                    formKind = ParticipantFormKind.APP_USAGE,
                    resourceKey = "app-usage:${scope.logicalDate ?: "unspecified"}",
                    idempotencyKey = requireNotNull(ParticipantFormAccessFilter.currentIdempotencyKey()) {
                        "Participant idempotency key was not established by the access filter"
                    },
                    payload = surveyResponses,
                ) { connection ->
                    surveysService.submitAppUsageSurvey(connection, realStudyId, participantId, surveyResponses)
                }
                Unit
            }
        }
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun createQuestionnaire(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody questionnaire: Questionnaire,
    ): UUID {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditAction.CREATE, "Questionnaire", studyId,
            failureMessage = "Questionnaire creation failed",
            onSuccess = { resourceId(it) },
        ) {
            surveysService.createQuestionnaire(studyId, questionnaire)
        }
    }

    @Timed
    @DeleteMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH]
    )
    override fun deleteQuestionnaire(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
    ): OK {
        ensureOwnerAccess(AclKey(studyId))
        return audited(
            AuditAction.DELETE, "Questionnaire", studyId,
            resourceId = questionnaireId,
            failureMessage = "Questionnaire deletion failed",
        ) {
            surveysService.deleteQuestionnaire(studyId, questionnaireId)
            OK("Successfully deleted questionnaire $questionnaireId")
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getQuestionnaire(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
    ): Questionnaire {
        val questionnaire = surveysService.getQuestionnaire(studyId, questionnaireId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("Questionnaire")
            resourceId(questionnaireId)
            studyId(studyId)
            success(true)
        }
        return questionnaire
    }

    @Timed
    @PatchMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun updateQuestionnaire(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
        @Valid @RequestBody update: QuestionnaireUpdate,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditAction.UPDATE, "Questionnaire", studyId,
            resourceId = questionnaireId,
            failureMessage = "Questionnaire update failed",
        ) {
            surveysService.updateQuestionnaire(studyId, questionnaireId, update)
            OK("Successfully updated questionnaire $questionnaireId")
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyQuestionnaires(@PathVariable(STUDY_ID) studyId: UUID): List<Questionnaire> {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val questionnaires = surveysService.getStudyQuestionnaires(realStudyId)
        auditService.logWithContext {
            action(AuditAction.LIST)
            resourceType("Questionnaire")
            studyId(realStudyId)
            success(true)
            additionalData(mapOf("count" to questionnaires.size))
        }
        return questionnaires
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH],
        produces = []
    )
    override fun submitQuestionnaireResponses(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
        @Valid @RequestBody @Size(max = 10_000) responses: List<QuestionnaireResponse>,
    ): OK {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        requireKnownParticipant(realStudyId, participantId)
        return audited(
            AuditAction.DATA_SUBMISSION, "Questionnaire", realStudyId,
            resourceId = questionnaireId,
            failureMessage = "Questionnaire response submission failed",
            onSuccess = {
                accessedPHI(true)
                phiFields(listOf("participantId", "questionnaireResponses"))
                additionalData(
                    mapOf(
                        "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                        "responseCount" to responses.size,
                    )
                )
            },
            onFailure = {
                additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
            },
        ) {
            val scope = ParticipantFormAccessFilter.currentScope()
            if (scope == null) {
                surveysService.submitQuestionnaireResponses(realStudyId, participantId, questionnaireId, responses)
            } else {
                participantFormSubmissionReceiptService.executeWithSubmissionId(
                    scope = scope,
                    formKind = ParticipantFormKind.QUESTIONNAIRE,
                    resourceKey = "questionnaire:$questionnaireId",
                    idempotencyKey = requireNotNull(ParticipantFormAccessFilter.currentIdempotencyKey()) {
                        "Participant idempotency key was not established by the access filter"
                    },
                    payload = responses,
                ) { connection ->
                    surveysService.submitQuestionnaireResponses(
                        connection,
                        realStudyId,
                        participantId,
                        questionnaireId,
                        responses,
                    )
                }
            }
            OK()
        }
    }

    override fun getQuestionnaireResponses(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
        @PathVariable(TYPE) fileType: FileType,
    ): Iterable<Map<String, Any>> {
        ensureReadAccess(AclKey(studyId))
        val data = downloadService.getQuestionnaireResponses(studyId, questionnaireId)
        auditService.logWithContext {
            action(AuditAction.DOWNLOAD)
            resourceType("Questionnaire")
            resourceId(questionnaireId)
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "questionnaireResponses"))
        }
        return data
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun getQuestionnaireResponses(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
        @RequestParam(value = TYPE) fileType: FileType,
        @RequestParam(value = FILE_NAME) fileName: String? = "Questionnaire_${questionnaireId}_${
            LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        }",
        httpServletResponse: HttpServletResponse,
    ): Iterable<Map<String, Any>> {

        val data = getQuestionnaireResponses(studyId, questionnaireId, fileType)

        ChronicleServerUtil.setDownloadContentType(httpServletResponse, fileType)
        ChronicleServerUtil.setContentDisposition(httpServletResponse, fileName, fileType)

        return data
    }

    /**
     * Executes [block] inside a try/catch that logs a success or failure audit entry
     * with the common fields pre-populated.  Optional [resourceId], [onSuccess], and
     * [onFailure] lambdas allow call-sites to attach extra audit fields without
     * duplicating the boilerplate.
     */
    // reason: generic audit-wrapper helper — each param is a distinct audit hook; the catch must log any
    // failure type then rethrow unchanged (boundary catch). Bundling params would alter every call site.
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private inline fun <T> audited(
        auditAction: AuditAction,
        resource: String,
        studyId: UUID,
        resourceId: UUID? = null,
        failureMessage: String = "Operation failed",
        noinline onSuccess: (AuditLogEntryBuilder.(T) -> Unit)? = null,
        noinline onFailure: (AuditLogEntryBuilder.() -> Unit)? = null,
        block: () -> T,
    ): T {
        return try {
            val result = block()
            auditService.logWithContext {
                action(auditAction)
                resourceType(resource)
                studyId(studyId)
                resourceId?.let { resourceId(it) }
                success(true)
                onSuccess?.invoke(this, result)
            }
            result
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(auditAction)
                resourceType(resource)
                studyId(studyId)
                resourceId?.let { resourceId(it) }
                failed(ex.message ?: failureMessage)
                onFailure?.invoke(this)
            }
            throw ex
        }
    }
}
