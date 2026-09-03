package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.google.common.base.MoreObjects
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.participantaccess.IdempotentSubmissionResult
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.CONTROLLER
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.DATA_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.DATA_TYPE
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.END_DATE
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.FILE_NAME
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.IDS_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.PARTICIPANTS_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.START_DATE
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.SETTINGS_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.STUDY_ID
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.timeusediary.TimeUseDiaryDownloadDataType
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettingsResponse
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.ChronicleServerUtil
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.validateParticipantId
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import jakarta.servlet.http.HttpServletResponse

/**
 * @author Andrew Carter andrew@openlattice.com
 */

@RestController
@RequestMapping(CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class TimeUseDiaryController(
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    public val storageResolver: StorageResolver,
    public val idGenerationService: HazelcastIdGenerationService,
    public val timeUseDiaryService: TimeUseDiaryService,
    public val studyService: StudyService,
    public val auditService: AuditService,
    private val enrollmentManager: EnrollmentManager,
    private val participantFormSubmissionReceiptService: ParticipantFormSubmissionReceiptService,
) : TimeUseDiaryApi, AuthorizingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(TimeUseDiaryController::class.java)!!
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    // reason: boundary catch — must record an audit failure event for any error type and rethrow on the submission path
    @Suppress("TooGenericExceptionCaught")
    override fun submitTimeUseDiary(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @Valid @RequestBody @Size(max = 10_000) responses: List<TimeUseDiaryResponse>
    ): UUID {
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        validateParticipantId(participantId)
        if (!enrollmentManager.isKnownParticipant(realStudyId, participantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.enrollment.participantNotRegistered"))
        }
        return try {
            val participantScope = ParticipantFormAccessFilter.currentScope()
            val submissionResult = if (participantScope == null) {
                IdempotentSubmissionResult(
                    storageResolver.getPlatformStorage().connection.use { conn ->
                        AuditedTransactionBuilder<UUID>(conn, auditingManager)
                            .transaction { connection ->
                                timeUseDiaryService.submitTimeUseDiary(
                                    connection,
                                    realStudyId,
                                    participantId,
                                    responses
                                )
                            }
                            .audit {
                                listOf(tudSubmissionEvent(realStudyId, participantId, it))
                            }
                            .buildAndRun()
                    },
                    replayed = false,
                )
            } else {
                participantFormSubmissionReceiptService.executeWithSubmissionId(
                    scope = participantScope,
                    formKind = ParticipantFormKind.TIME_USE_DIARY,
                    resourceKey = "time-use-diary:${participantScope.logicalDate ?: "unspecified"}",
                    idempotencyKey = requireNotNull(ParticipantFormAccessFilter.currentIdempotencyKey()) {
                        "Participant idempotency key was not established by the access filter"
                    },
                    payload = responses,
                ) { connection ->
                    timeUseDiaryService.submitTimeUseDiary(connection, realStudyId, participantId, responses)
                }
            }
            val submissionId = submissionResult.value
            if (participantScope != null && !submissionResult.replayed) {
                recordEvent(tudSubmissionEvent(realStudyId, participantId, submissionId))
            }
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("TimeUseDiary")
                resourceId(submissionId)
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "timeUseDiaryResponses"))
                additionalData(
                    mapOf(
                        "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                        "responseCount" to responses.size,
                    )
                )
            }
            submissionId
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("TimeUseDiary")
                studyId(realStudyId)
                failed(ex.message ?: "TUD submission failed")
                additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
            }
            throw ex
        }
    }

    private fun tudSubmissionEvent(studyId: UUID, participantId: String, submissionId: UUID): AuditableEvent =
        AuditableEvent(
            aclKey = AclKey(studyId),
            securablePrincipalId = Principals.getAnonymousSecurablePrincipal().id,
            principal = Principals.getAnonymousUser(),
            eventType = AuditEventType.SUBMIT_TIME_USE_DIARY,
            description = "participantRef=${LogSanitizer.stableFingerprint(participantId, "participant")}, " +
                "submissionId=$submissionId",
            study = studyId,
        )

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getTimeUseDiarySettings(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): TimeUseDiarySettingsResponse {
        // Participant-readable: the `/chronicle/v3/time-use-diary/**` GET tree is permitAll, so a
        // participant (no authenticated principal) can read the study's diary variant settings.
        // Study-scoped and RLS-enforced via getStudyId; returns only the non-sensitive variant
        // flags (OSU/Sherbrooke/clock/locale) the web form needs to honor the configured
        // instrument rather than relying on URL params. Mirrors submitTimeUseDiary's auth pattern.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        // Study.kt seeds a default TimeUseDiarySettings() for every study, so the cast normally
        // holds; the fallback keeps a missing/legacy setting from throwing on an anonymous read.
        val settings = studyService.getStudySettings(realStudyId)[StudySettingType.TimeUseDiary]
            as? TimeUseDiarySettings ?: TimeUseDiarySettings()
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("TimeUseDiarySettings")
            studyId(realStudyId)
            success(true)
        }
        return TimeUseDiarySettingsResponse.from(settings)
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getParticipantTUDSubmissionIdsByDate(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
    ): Map<OffsetDateTime, Set<UUID>> {
        val participantScope = ParticipantFormAccessFilter.currentScope()
        val participantAccess = participantScope?.permits(
            ParticipantFormKind.TIME_USE_DIARY,
            studyId,
            participantId,
            null,
        ) == true
        if (!participantAccess) {
            accessCheck(AclKey(studyId), EnumSet.of(Permission.READ))
        }
        logger.info(
            "Retrieving TimeUseDiary ids from study {} for {}",
            studyId,
            LogSanitizer.stableFingerprint(participantId, "participant"),
        )
        val submissionsIdsByDate = timeUseDiaryService.getParticipantTUDSubmissionsByDate(
            studyId,
            participantId,
            startDateTime,
            endDateTime
        )
        recordEvent(
            AuditableEvent(
                AclKey(studyId),
                if (participantAccess) Principals.getAnonymousSecurablePrincipal().id
                else Principals.getCurrentSecurablePrincipal().id,
                if (participantAccess) Principals.getAnonymousUser() else Principals.getCurrentUser(),
                AuditEventType.GET_TIME_USE_DIARY_SUBMISSION,
                "participantRef=${LogSanitizer.stableFingerprint(participantId, "participant")}: " +
                    "$startDateTime - $endDateTime",
                studyId,
            )
        )
        auditService.logWithContext {
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
            resourceType("TimeUseDiary")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "submissionIds"))
            additionalData(mapOf("participantRef" to LogSanitizer.stableFingerprint(participantId, "participant")))
        }
        return submissionsIdsByDate
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + IDS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyTUDSubmissionIdsByDate(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
    ): Map<LocalDate, Set<UUID>> {
        ensureReadAccess(AclKey(studyId))
        logger.info("Retrieving TimeUseDiary ids from study $studyId")
        val submissionsIdsByDate = timeUseDiaryService.getStudyTUDSubmissionIdsByDate(
            studyId,
            startDateTime,
            endDateTime
        )
        recordEvent(
            AuditableEvent(
                AclKey(studyId),
                eventType = AuditEventType.GET_TIME_USE_DIARY_SUBMISSION,
                description = "$startDateTime - $endDateTime",
                study = studyId,
            )
        )
        auditService.logWithContext {
            action(AuditAction.LIST)
            resourceType("TimeUseDiary")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("submissionIds"))
        }
        return submissionsIdsByDate
    }

    @Override
    override fun getStudyTUDSubmissions(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(DATA_TYPE) dataType: TimeUseDiaryDownloadDataType,
        @RequestParam(START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
    ): Iterable<List<Map<String, Any>>> {
        ensureReadAccess(AclKey(studyId))
        val data = timeUseDiaryService.getStudyTUDSubmissions(
            studyId,
            participantIds = null,
            dataType,
            startDateTime,
            endDateTime
        )
        auditService.logWithContext {
            action(AuditAction.DOWNLOAD)
            resourceType("TimeUseDiary")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "timeUseDiaryData"))
            additionalData(mapOf("dataType" to dataType.name))
        }
        return data
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun getStudyTUDSubmissions(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(DATA_TYPE) dataType: TimeUseDiaryDownloadDataType,
        @RequestParam(START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime,
        @RequestParam(END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime,
        response: HttpServletResponse
    ): Iterable<List<Map<String, Any>>> {
        val data = getStudyTUDSubmissions(
            studyId,
            dataType,
            startDateTime,
            endDateTime
        )

        val filename = "TimeUseDiary_${dataType}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.csv"

        ChronicleServerUtil.setDownloadContentType(response, FileType.csv)
        ChronicleServerUtil.setContentDisposition(response, filename, FileType.csv)

        recordEvent(
            AuditableEvent(
                aclKey = AclKey(studyId),
                securablePrincipalId = Principals.getCurrentSecurablePrincipal().id,
                principal = Principals.getCurrentUser(),
                eventType = AuditEventType.DOWNLOAD_TIME_USE_DIARY_DATA,
                description = dataType.toString(),
                study = studyId
            )
        )

        return data
    }

    override fun getParticipantsTudSubmissions(
        studyId: UUID,
        participantIds: Set<String>,
        dataType: TimeUseDiaryDownloadDataType,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<List<Map<String, Any>>> {
        ensureReadAccess(AclKey(studyId))
        val data = timeUseDiaryService.getStudyTUDSubmissions(
            studyId,
            participantIds,
            dataType,
            startDateTime,
            endDateTime
        )
        auditService.logWithContext {
            action(AuditAction.DOWNLOAD)
            resourceType("TimeUseDiary")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "timeUseDiaryData"))
            additionalData(mapOf("dataType" to dataType.name, "participantCount" to participantIds.size))
        }
        return data
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @RateLimit(type = RateLimitType.SENSITIVE, keyStrategy = RateLimitKeyStrategy.STUDY)
    public fun getParticipantsTudSubmissions(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(PARTICIPANT_ID) @Size(max = SynchronousExportLimits.MAX_PARTICIPANTS) participantIds: Set<String>,
        @RequestParam(DATA_TYPE) dataType: TimeUseDiaryDownloadDataType,
        @RequestParam(START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime?,
        @RequestParam(END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime?,
        @RequestParam(FILE_NAME) @Size(max = 64) fileName: String?,
        response: HttpServletResponse
    ): Iterable<List<Map<String, Any>>> {
        val (boundedStart, boundedEnd) = SynchronousExportLimits.validate(
            participantIds,
            startDateTime,
            endDateTime,
        )
        val data = getParticipantsTudSubmissions(
            studyId,
            participantIds,
            dataType,
            boundedStart,
            boundedEnd,
        )

        ChronicleServerUtil.setDownloadContentType(response, FileType.csv)
        ChronicleServerUtil.setContentDisposition(
            response,
            MoreObjects.firstNonNull(fileName, "TimeUseDiary_${dataType}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}"), FileType.csv)

        recordEvent(
            AuditableEvent(
                aclKey = AclKey(studyId),
                securablePrincipalId = Principals.getCurrentSecurablePrincipal().id,
                principal = Principals.getCurrentUser(),
                eventType = AuditEventType.DOWNLOAD_PARTICIPANTS_TIME_USE_DIARY_DATA,
                description = "type = $dataType, participantCount = ${participantIds.size}",
                study = studyId
            )
        )

        return data
    }
}
