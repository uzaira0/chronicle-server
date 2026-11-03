package com.openlattice.chronicle.controllers


import com.codahale.metrics.annotation.Timed
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.util.validateParticipantId
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditEntryBuilder
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormWindow
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.participantaccess.SubmissionReceiptScope
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.requireStudyId
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.services.surveys.AppUsageDayRules
import com.openlattice.chronicle.services.surveys.appUsageDayRules
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.survey.AppUsageFrequencyResponse
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireResponse
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.EditRevisionApi
import org.springframework.web.bind.annotation.RequestHeader
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.survey.SurveyApi.Companion.APP_USAGE_FREQUENCY_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.APP_USAGE_DAYS_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.APP_USAGE_PATH
import com.openlattice.chronicle.survey.SurveyApi.Companion.DATE
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
        val snapshot = surveysService.getFilteredAppsSnapshot(studyId)
        val request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            as? org.springframework.web.context.request.ServletRequestAttributes
        request?.response?.setHeader("ETag", "\"${snapshot.revision}\"")
        val result = snapshot.appPackages
        auditService.logWithContext {
            action(AuditEventType.VIEW)
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
        @org.springframework.web.bind.annotation.RequestHeader(value = "If-Match", required = false) ifMatch: String?,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditEventType.SETTINGS_CHANGE, "SurveyFilter", studyId,
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size)) },
        ) {
            val request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                as? org.springframework.web.context.request.ServletRequestAttributes
            val expected = parseSettingsRevisionPrecondition(ifMatch)
            val revision = surveysService.setAppsFilteredForStudyAppUsageSurvey(studyId, appPackages, expected)
            studyService.refreshStudyCache(setOf(studyId))
            request?.response?.setHeader("ETag", "\"$revision\"")
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
            AuditEventType.SETTINGS_CHANGE, "SurveyFilter", studyId,
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size, "operation" to "filter")) },
        ) {
            surveysService.filterAppForStudyAppUsageSurvey(studyId, appPackages)
            studyService.refreshStudyCache(setOf(studyId))
            ok
        }
    }

    @Timed
    @DeleteMapping(
        path = [STUDY_ID_PATH + FILTERED_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun allowAppForStudyAppUsageSurvey(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody appPackages: Set<String>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditEventType.SETTINGS_CHANGE, "SurveyFilter", studyId,
            onSuccess = { additionalData(mapOf("appCount" to appPackages.size, "operation" to "allow")) },
        ) {
            surveysService.allowAppForStudyAppUsageSurvey(studyId, appPackages)
            studyService.refreshStudyCache(setOf(studyId))
            ok
        }
    }

    private fun resolveUsageSurveyStudy(
        studyId: UUID, participantId: String, startDateTime: OffsetDateTime, endDateTime: OffsetDateTime,
    ): UUID {
        val realStudyId = studyService.requireStudyId(studyId)
        val scope = ParticipantFormAccessFilter.currentScope()
        if (scope == null) ensureReadAccess(AclKey(realStudyId))
        else if (!scope.permits(
                ParticipantFormKind.APP_USAGE, realStudyId, participantId, null,
                window = ParticipantFormWindow(startDateTime, endDateTime),
            )) {
            throw ForbiddenException(Messages.get(ParticipantFormAccessFilter.INVALID_SESSION_MESSAGE_KEY))
        }
        requireKnownParticipant(realStudyId, participantId)
        SynchronousExportLimits.validate(setOf(participantId), startDateTime, endDateTime)
        return realStudyId
    }

    private fun usageThreshold(studyId: UUID, thresholdInSeconds: Int?, setting: (SurveySettings) -> Int): Int =
        thresholdInSeconds ?: setting(studyService.getStudySettings(studyId)
            .getOrDefault(StudySettingType.Survey, SurveySettings()) as SurveySettings)

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
        val realStudyId = resolveUsageSurveyStudy(studyId, participantId, startDateTime, endDateTime)
        // Participant format validation prevents injection; data query is scoped to (studyId, participantId)
        val deviceUsageData = surveysService.getDeviceUsageData(realStudyId, participantId, startDateTime, endDateTime)

        val threshold = usageThreshold(realStudyId, thresholdInSeconds, SurveySettings::deviceUsageThresholdInSeconds)
        val packagesToKeep = deviceUsageData.usageByPackage.filterValues { it <= threshold }.keys
        val usageByPackage = deviceUsageData.usageByPackage - packagesToKeep
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
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

    /** A participant session reaches every ended day of its own participant; the day rule is the server's. */
    private fun resolveAppUsageSurvey(studyId: UUID, participantId: String): Pair<UUID, AppUsageDayRules> {
        val realStudyId = studyService.requireStudyId(studyId)
        val scope = ParticipantFormAccessFilter.currentScope()
        if (scope == null) ensureReadAccess(AclKey(realStudyId))
        else if (!scope.permits(ParticipantFormKind.APP_USAGE, realStudyId, participantId, null)) {
            throw ForbiddenException(Messages.get(ParticipantFormAccessFilter.INVALID_SESSION_MESSAGE_KEY))
        }
        requireKnownParticipant(realStudyId, participantId)
        return realStudyId to appUsageDayRules(StudySettings(studyService.getStudySettings(realStudyId)))
    }

    private fun auditAppUsageRead(studyId: UUID, participantId: String) = auditService.logWithContext {
        action(AuditEventType.PARTICIPANT_DATA_ACCESS)
        resourceType("AppUsageSurvey")
        studyId(studyId)
        success(true)
        accessedPHI(true)
        phiFields(listOf("participantId", "appUsage"))
        additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + APP_USAGE_DAYS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppUsageSurveyDays(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
    ): AppUsageSurveyDays {
        val (realStudyId, rules) = resolveAppUsageSurvey(studyId, participantId)
        val days = surveysService.getAppUsageDays(realStudyId, participantId, rules)
            ?: throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
        auditAppUsageRead(realStudyId, participantId)
        return days
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + APP_USAGE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppUsageSurveyData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): AppUsageDaySurvey {
        val (realStudyId, rules) = resolveAppUsageSurvey(studyId, participantId)
        val day = surveysService.getAppUsageDay(realStudyId, participantId, date, rules)
        auditAppUsageRead(realStudyId, participantId)
        return day
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + APP_USAGE_FREQUENCY_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAppUsageFrequency(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): AppUsageFrequencyResponse {
        ensureParticipantInstrumentRead(studyId, ParticipantFormKind.APP_USAGE)
        val realStudyId = studyService.requireStudyId(studyId)
        val frequency = StudySettings(studyService.getStudySettings(realStudyId)).appUsageSurvey().appUsageFrequency
        auditService.logWithContext {
            action(AuditEventType.VIEW)
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
        @RequestParam(DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @Valid @RequestBody @Size(max = 10_000) answers: List<AppUsageAnswer>,
    ) {
        val realStudyId = studyService.requireStudyId(studyId)
        requireKnownParticipant(realStudyId, participantId)
        audited(
            AuditEventType.DATA_SUBMISSION, "AppUsageSurvey", realStudyId,
            onSuccess = {
                accessedPHI(true)
                phiFields(listOf("participantId", "surveyResponses"))
                additionalData(
                    mapOf(
                        "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                        "responseCount" to answers.size,
                    )
                )
            },
            onFailure = {
                additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
            },
        ) {
            val rules = appUsageDayRules(StudySettings(studyService.getStudySettings(realStudyId)))
            val scope = ParticipantFormAccessFilter.currentScope()
            if (scope == null) {
                // No participant capability was presented, so this is an authenticated caller.
                // Authentication alone is not authorization to write another study's PHI.
                ensureWriteAccess(AclKey(realStudyId))
                surveysService.submitAppUsageDay(realStudyId, participantId, date, answers, rules)
            } else {
                if (!scope.permits(ParticipantFormKind.APP_USAGE, realStudyId, participantId, null)) {
                    throw ForbiddenException(Messages.get(ParticipantFormAccessFilter.INVALID_SESSION_MESSAGE_KEY))
                }
                val idempotencyKey = ParticipantFormAccessFilter.requireIdempotencyKey()
                participantFormSubmissionReceiptService.executeWithoutResult(
                    receiptScope = SubmissionReceiptScope(
                        participant = scope,
                        formKind = ParticipantFormKind.APP_USAGE,
                        // One receipt per attempt: a later attempt answers the blocks the day still lists.
                        resourceKey = "app-usage:$date:$idempotencyKey",
                        idempotencyKey = idempotencyKey,
                    ),
                    payload = answers,
                ) { connection ->
                    surveysService.submitAppUsageDay(connection, realStudyId, participantId, date, answers, rules)
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
            AuditEventType.CREATE, "Questionnaire", studyId,
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
            AuditEventType.DELETE, "Questionnaire", studyId,
            resourceId = questionnaireId,
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
        ensureParticipantInstrumentRead(studyId, ParticipantFormKind.QUESTIONNAIRE, questionnaireId)
        val questionnaire = surveysService.getQuestionnaire(studyId, questionnaireId)
        auditService.logWithContext {
            action(AuditEventType.VIEW)
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
        @RequestHeader(EditRevisionApi.IF_MATCH) revision: String,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return audited(
            AuditEventType.UPDATE, "Questionnaire", studyId,
            resourceId = questionnaireId,
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
        ensureReadAccess(AclKey(studyId))
        val realStudyId = studyService.requireStudyId(studyId)
        val questionnaires = surveysService.getStudyQuestionnaires(realStudyId)
        auditService.logWithContext {
            action(AuditEventType.LIST)
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
        val realStudyId = studyService.requireStudyId(studyId)
        requireKnownParticipant(realStudyId, participantId)
        return audited(
            AuditEventType.DATA_SUBMISSION, "Questionnaire", realStudyId,
            resourceId = questionnaireId,
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
                // Authenticated caller rather than a participant capability: require study write.
                ensureWriteAccess(AclKey(realStudyId))
                surveysService.submitQuestionnaireResponses(realStudyId, participantId, questionnaireId, responses)
            } else {
                participantFormSubmissionReceiptService.executeWithSubmissionId(
                    receiptScope = SubmissionReceiptScope(
                        participant = scope.copy(logicalDate = ParticipantFormAccessFilter.requireSubmissionDate()),
                        formKind = ParticipantFormKind.QUESTIONNAIRE,
                        resourceKey = "questionnaire:$questionnaireId",
                        idempotencyKey = ParticipantFormAccessFilter.requireIdempotencyKey(),
                    ),
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
            action(AuditEventType.DOWNLOAD)
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
        @RequestParam(value = FILE_NAME) fileName: String?,
        httpServletResponse: HttpServletResponse,
    ): Iterable<Map<String, Any>> {

        val data = getQuestionnaireResponses(studyId, questionnaireId, fileType)

        ChronicleServerUtil.setDownloadContentType(httpServletResponse, fileType)
        ChronicleServerUtil.setContentDisposition(
            httpServletResponse,
            fileName ?: "Questionnaire_${questionnaireId}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}",
            fileType,
        )

        return data
    }

    /** Browser form POST of [getQuestionnaireResponses]; the token resolver checks its CSRF form field. */
    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + QUESTIONNAIRE_PATH + QUESTIONNAIRE_ID_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun postQuestionnaireResponses(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(QUESTIONNAIRE_ID) questionnaireId: UUID,
        @RequestParam(value = TYPE) fileType: FileType,
        @RequestParam(value = FILE_NAME) fileName: String?,
        httpServletResponse: HttpServletResponse,
    ): Iterable<Map<String, Any>> =
        getQuestionnaireResponses(studyId, questionnaireId, fileType, fileName, httpServletResponse)

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
        auditAction: AuditEventType,
        resource: String,
        studyId: UUID,
        resourceId: UUID? = null,
        noinline onSuccess: (AuditEntryBuilder.(T) -> Unit)? = null,
        noinline onFailure: (AuditEntryBuilder.() -> Unit)? = null,
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
                success(false)
                onFailure?.invoke(this)
            }
            throw ex
        }
    }
}
