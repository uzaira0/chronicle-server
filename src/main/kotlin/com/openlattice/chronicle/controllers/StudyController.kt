package com.openlattice.chronicle.controllers


import com.codahale.metrics.annotation.Timed
import com.geekbeast.mappers.mappers.ObjectMappers
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.ChronicleUsageEventType
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.constants.CustomMediaType
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.OrganizationNotFoundException
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.apikeys.InvalidEnrollmentAttemptException
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.ParticipantQuarantineDecision
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentAttemptEndedException
import com.openlattice.chronicle.services.enrollment.EnrollmentAuthorization
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.studies.CollectionHaltedException
import com.openlattice.chronicle.services.studies.CollectionUploadRequest
import com.openlattice.chronicle.services.studies.LockedStudyUpdate
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.requireStudyId
import com.openlattice.chronicle.services.studies.StudySettingsAuditService
import com.openlattice.chronicle.services.studies.StudySettingsNotificationService
import com.openlattice.chronicle.services.studies.checkLockedStudyRevision
import com.openlattice.chronicle.services.studies.collectionSettingsForFeatures
import com.openlattice.chronicle.services.studies.ensureParticipantPolicyMutable
import com.openlattice.chronicle.services.studies.mergeAndStampDataCollectionSettingsVersionLocked
import com.openlattice.chronicle.services.studies.mergeStudySetting
import com.openlattice.chronicle.services.studies.stampInitialDataCollectionSettings
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsQueryService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.upload.UploadRowRejectedException
import com.openlattice.chronicle.services.upload.requireUploadRow
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.storage.AndroidSensorAvailabilityRepository
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.AndroidDiagnosticsPage
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.study.IosUploadStatus
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyApi.Companion.ANDROID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.AVAILABILITY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.CONTROLLER
import com.openlattice.chronicle.study.StudyApi.Companion.DATA_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.DEVICES_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.DIAGNOSTICS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.IOS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANTS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.study.StudyApi.Companion.RETRIEVE
import com.openlattice.chronicle.study.StudyApi.Companion.SENSORS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SETTINGS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SETTING_TYPE
import com.openlattice.chronicle.study.StudyApi.Companion.SETTING_TYPE_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STATS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STATUS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.UPLOAD_STATUS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.VERIFY_PATH
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingsAuditEntry
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.util.ChronicleServerUtil
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.PaginationDefaults
import com.openlattice.chronicle.util.normalizeEntityTag
import com.openlattice.chronicle.util.validateParticipantId
import com.openlattice.chronicle.services.upload.MAX_UPLOAD_ROWS
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*



private val SETTINGS_REVISION_ETAG = Regex("""\d+""")

/**
 * Settings writes require a revision read by the caller; an unconditional wildcard cannot protect a draft.
 */
internal fun parseSettingsRevisionPrecondition(header: String?): Long =
    parseRevisionPrecondition(header, "error.study.invalidSettingsRevision")

/** A numeric If-Match revision the caller read; missing or wildcard is 428, anything else non-numeric is 400. */
internal fun parseRevisionPrecondition(header: String?, invalidMessageKey: String): Long {
    val value = header?.trim()?.takeUnless { it.isEmpty() || it == "*" }
        ?: throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, Messages.get("error.study.settingsRevisionRequired"))
    val revision = SETTINGS_REVISION_ETAG.matchEntire(normalizeEntityTag(value))?.value?.toLongOrNull()
    return revision ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get(invalidMessageKey))
}

/** Public enrollment always presents one complete, replay-safe invitation credential. */
internal fun requireV4EnrollmentCredentials(
    authentication: Authentication?,
    enrollmentCode: String?,
    manifestDigest: String?,
    enrollmentAttemptId: String?,
    proposedApiKey: String?,
) {
    if (authentication !is MobileEnrollmentAuthenticationToken ||
        listOf(enrollmentCode, manifestDigest, enrollmentAttemptId, proposedApiKey).any { it.isNullOrBlank() }) {
        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.enrollment.credentialOrDigestInvalid"))
    }
}


/**
 * @author Solomon Tang <solomon@openlattice.com>
 */

// reason: Spring routing controller — DI constructor + handler set is the public surface; every catch(Exception) is an
// intentional request-handler boundary that records an audit-failure event and rethrows; restructuring risks auth/routing behavior
@Suppress("LargeClass", "TooManyFunctions", "LongParameterList", "TooGenericExceptionCaught")
@RestController
@RequestMapping(CONTROLLER)
public open class StudyController @Inject constructor(
    hazelcastInstance: HazelcastInstance,
    public val storageResolver: StorageResolver,
    public val enrollmentService: EnrollmentService,
    public val studyService: StudyService,
    public val screenTimeUsageUploadService: ScreenTimeUsageUploadService,
    public val androidSensorDataUploadService: AndroidSensorDataUploadService,
    public val batteryTelemetryUploadService: BatteryTelemetryUploadService,
    public val interactionEventsUploadService: InteractionEventsUploadService,
    public val appAudioActivityUploadService: AppAudioActivityUploadService,
    public val appAudioContentUploadService: AppAudioContentUploadService,
    public val notificationActivityUploadService: NotificationActivityUploadService,
    public val sleepEventsUploadService: SleepEventsUploadService,
    public val activityRecognitionEventsUploadService: ActivityRecognitionEventsUploadService,
    public val healthMetricsUploadService: HealthMetricsUploadService,
    public val connectivityStateEventsUploadService: ConnectivityStateEventsUploadService,
    public val appNetworkUsageUploadService: AppNetworkUsageUploadService,
    public val deviceSettingsUploadService: DeviceSettingsUploadService,
    public val uploadDiagnosticsUploadService: UploadDiagnosticsUploadService,
    public val appDataUploadService: AppDataUploadService,
    public val downloadService: DataDownloadService,
    public val enrollmentManager: EnrollmentManager,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    public val auditService: AuditService,
    public val studySettingsAuditService: StudySettingsAuditService,
    public val participantCollectionAcknowledgmentService: ParticipantCollectionAcknowledgmentService,
    public val studySettingsNotificationService: StudySettingsNotificationService,
    public val apiKeyService: ApiKeyService,
    public val dataDeletionOrchestrator: DataDeletionOrchestrator,
    public val studyLifecycleService: StudyLifecycleService,
    public val webhookService: WebhookService,
    private val enrollmentManifestService: EnrollmentManifestService,
//    private val managementApi: ManagementAPI,
) : StudyApi, AuthorizingComponent {

    private val studies = HazelcastMap.STUDIES.getMap(hazelcastInstance)
    private val uploadDiagnosticsQueryService by lazy { UploadDiagnosticsQueryService(storageResolver) }

    private fun mobileUploadEnrollmentGate(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        operation: String,
        allowCollectionHaltResolution: Boolean = false,
    ) {
        if (SecurityContextHolder.getContext().authentication is ApiKeyAuthenticationToken) {
            requireAcknowledgmentApiKey(studyId, participantId, randomDeviceId)
        } else {
            ensureWriteAccess(AclKey(studyId))
        }
        // A deleted participant (row quarantined or erased) gets notEnrolled, not a 409.
        val participationStatus = enrollmentManager.getDeviceParticipationStatus(studyId, participantId)
        var responseReasonKey = "error.enrollment.notEnrolled"
        val (rejectionLog, logAsError) = when {
            participationStatus !in setOf(ParticipationStatus.ENROLLED, ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED) ->
                "participant is not actively enrolled (status=$participationStatus)" to false
            !enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId) ->
                "data source not found" to true
            participationStatus == ParticipationStatus.ENROLLED && !allowCollectionHaltResolution &&
                participantCollectionAcknowledgmentService.isCollectionHalted(
                studyId,
                participantId,
                randomDeviceId,
            ) -> {
                responseReasonKey = "error.enrollment.collectionHalted"
                "required collection consent is unresolved" to false
            }
            else -> return
        }
        rejectMobileUpload(studyId, participantId, randomDeviceId, operation, responseReasonKey, rejectionLog, logAsError)
    }

    /**
     * Runs a device-data write atomically with the collection-halt predicate, judged by the batch's settings version
     * and decision sequence headers. [mobileUploadEnrollmentGate] stays as the cheap fast-fail; the write runs inside
     * the recheck's transaction and is rolled back with the same 403 the gate returns when the recheck refuses it.
     */
    private fun <T> guardedDeviceWrite(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        operation: String,
        uploadModules: Set<CollectionModuleId> = emptySet(),
        allowQueuedCaptures: Boolean = true,
        write: (Set<CollectionModuleId>) -> T,
    ): T = collectionWriteOrReject(studyId, participantId, randomDeviceId, operation) {
        participantCollectionAcknowledgmentService.withCollectionUploadRecheck(
            uploadRequest(studyId, participantId, randomDeviceId), uploadModules, allowPartial = false, allowQueuedCaptures = allowQueuedCaptures,
        ) { authorization -> write(authorization.acceptedModules) }
    }

    private fun <T> collectionWriteOrReject(
        studyId: UUID, participantId: String, randomDeviceId: UUID, operation: String, write: () -> T,
    ): T = try {
        write()
    } catch (halted: CollectionHaltedException) {
        logger.warn("Collection halt landed between the upload gate and the write, rejecting {}", operation, halted)
        rejectMobileUpload(
            studyId,
            participantId,
            randomDeviceId,
            operation,
            "error.enrollment.collectionHalted",
            "required collection consent became unresolved before the write committed",
            logAsError = false,
        )
    }

    private fun rejectMobileUpload(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        operation: String,
        responseReasonKey: String,
        rejectionLog: String,
        logAsError: Boolean,
    ): Nothing {
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        val dataSourceRef = LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device")
        if (logAsError) {
            logger.error(
                "{}, rejecting {} - studyId = {}, participantRef = {}, dataSourceRef = {}",
                rejectionLog,
                operation,
                studyId,
                participantRef,
                dataSourceRef,
            )
        } else {
            logger.warn(
                "{}, rejecting {} - studyId = {}, participantRef = {}, dataSourceRef = {}",
                rejectionLog,
                operation,
                studyId,
                participantRef,
                dataSourceRef,
            )
        }
        auditService.logWithContext {
            action(AuditEventType.DATA_SUBMISSION)
            resourceType("MobileUpload")
            studyId(studyId)
            success(false)
            additionalData(
                mapOf(
                    "participantId" to participantId,
                    "randomDeviceId" to randomDeviceId.toString(),
                    "operation" to operation,
                    "reason" to responseReasonKey,
                )
            )
        }
        throw ResponseStatusException(
            HttpStatus.FORBIDDEN,
            Messages.get(responseReasonKey),
        )
    }

    /** The upload's identity and the settings version and decision sequence its rows were captured under (headers). */
    private fun uploadRequest(
        studyId: UUID, participantId: String, randomDeviceId: UUID, settingsVersion: Int? = null, decisionSeq: Long? = null,
    ) = CollectionUploadRequest(studyId, participantId, randomDeviceId, uploadSettingsVersion(settingsVersion), uploadDecisionSeq(decisionSeq))

    private fun uploadSettingsVersion(version: Int? = null): Int? =
        (version?.toString() ?: requestHeader(CollectionUploadEvidence.SETTINGS_VERSION_HEADER))?.let { raw ->
        raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.study.invalidCollectionSettingsVersion"))
    }

    private fun uploadDecisionSeq(seq: Long? = null): Long? =
        (seq?.toString() ?: requestHeader(CollectionUploadEvidence.DECISION_SEQ_HEADER))?.let { raw ->
            raw.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.study.invalidCollectionDecisionSeq"))
        }

    private fun requestHeader(name: String): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader(name)

    /** Publishes the study's settings revision as a strong `ETag` for `If-Match` preconditions. */
    private fun setSettingsRevisionEtag(revision: Long) {
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.response
            ?.setHeader(HttpHeaders.ETAG, "\"$revision\"")
    }

    private fun getStudyWithEtag(studyId: UUID): Study = studyService.getStudy(studyId).also {
        setSettingsRevisionEtag(it.settingsRevision)
    }

    private fun requireAcknowledgmentApiKey(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
    ): ApiKeyAuthenticationToken {
        val mobileKey = SecurityContextHolder.getContext().authentication as? ApiKeyAuthenticationToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.enrollment.apiKeyRequired"))
        if (mobileKey.studyId != studyId || mobileKey.participantId != participantId || mobileKey.randomDeviceId != randomDeviceId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.apiKeyMismatch"))
        }
        return mobileKey
    }

    private fun usageEventModule(event: ChronicleUsageEvent): CollectionModuleId =
        if (event.appPackageName == "android" && fromInteractionType(event.interactionType) in lifecycleEventTypes) {
            CollectionModuleId.DEVICE_LIFECYCLE
        } else {
            CollectionModuleId.USAGE_EVENTS
        }

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyController::class.java)!!
        private val lifecycleEventTypes = setOf(
            ChronicleUsageEventType.BATTERY_LOW,
            ChronicleUsageEventType.BATTERY_OKAY,
            ChronicleUsageEventType.BATTERY_CHARGING,
            ChronicleUsageEventType.BATTERY_DISCHARGING,
            ChronicleUsageEventType.POWER_SAVE_MODE_ON,
            ChronicleUsageEventType.POWER_SAVE_MODE_OFF,
            ChronicleUsageEventType.NETWORK_CONNECTED,
            ChronicleUsageEventType.NETWORK_DISCONNECTED,
            ChronicleUsageEventType.LOW_MEMORY,
        ).mapTo(hashSetOf()) { it.value }

        private val GET_STUDY_DEVICES_SQL = """
            SELECT participant_id, random_device_id, device_type, source_device
            FROM devices
            WHERE study_id = ?
            ORDER BY participant_id, random_device_id
            LIMIT ? OFFSET ?
        """.trimIndent()

        private val GET_IOS_UPLOAD_STATUS_SQL = """
            SELECT ${PostgresColumns.PARTICIPANT_ID.name},
                   count(*)::bigint AS committed_rows,
                   max(${PostgresColumns.UPLOADED_AT.name}) AS last_committed_at,
                   max(${PostgresColumns.SCREEN_TIME_OBSERVATION_END.name}) AS last_observation_end_at
            FROM ${ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.name}
            WHERE ${PostgresColumns.STUDY_ID.name} = ?
            GROUP BY ${PostgresColumns.PARTICIPANT_ID.name}
            ORDER BY ${PostgresColumns.PARTICIPANT_ID.name}
            LIMIT ? OFFSET ?
        """.trimIndent()

    }

    private fun enrollDevice(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        sourceDevice: SourceDevice,
        proposedApiKey: String,
        enrollmentAttemptId: UUID,
    ): EnrollmentResponse {
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)

        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        return try {
            val id = enrollmentService.registerDevice(realStudyId, participantId, randomDeviceId, sourceDevice)

            // Audit every device enrollment.
            auditService.logWithContext {
                action(AuditEventType.DEVICE_ENROLLMENT)
                resourceType("Device")
                resourceId(id)
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "deviceInfo"))
                additionalData(mapOf(
                    "participantId" to participantId,
                    "randomDeviceId" to id.toString(),
                    "deviceType" to (sourceDevice.javaClass.simpleName)
                ))
            }

            // Issue a per-device API key bound to (studyId, participantId, chronicleId).
            // The Android client persists this key and sends it as X-Api-Key for all
            // subsequent uploads to this server. ApiKeyAuthenticationFilter enforces
            // that the path's studyId/participantId match the key's binding.
            //
            // Failure here propagates: this server REQUIRES API-key auth on v4 mobile
            // uploads, so silently returning apiKey=null would leave the client persisting
            // an unworkable enrollment that 403s on every upload. Better to fail enrollment
            // visibly and let the client retry.
            val apiKey = apiKeyService.installMobileApiKey(
                studyId = realStudyId,
                participantId = participantId,
                randomDeviceId = id,
                enrollmentAttemptId = enrollmentAttemptId,
                proposedApiKey = proposedApiKey,
            ).rawKey

            EnrollmentResponse(chronicleId = id, apiKey = apiKey,
                hardStopGeneration = enrollmentService.getDeviceCollectionStatus(realStudyId, participantId).hardStop?.generation ?: 0)
        } catch (ex: Exception) {
            // Log failed enrollment attempts for security monitoring
            auditService.logWithContext {
                action(AuditEventType.DEVICE_ENROLLMENT)
                resourceType("Device")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf(
                    "participantId" to participantId,
                    "randomDeviceId" to randomDeviceId.toString()
                ))
            }
            throw ex
        }
    }

    override fun getEnrollmentPreviewV4(
        studyId: UUID,
        participantId: String,
        enrollmentCode: String,
    ): EnrollmentPreviewResponse = enrollmentManifestService.getPreview(studyId, participantId, enrollmentCode)

    @Suppress("DEPRECATION")
    override fun enrollV4(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        datasource: SourceDevice,
        enrollmentCode: String?,
        manifestDigest: String?,
        enrollmentAttemptId: String?,
        proposedApiKey: String?,
    ): EnrollmentResponse {
        requireV4EnrollmentCredentials(
            SecurityContextHolder.getContext().authentication,
            enrollmentCode,
            manifestDigest,
            enrollmentAttemptId,
            proposedApiKey,
        )
        checkNotNull(enrollmentCode)
        checkNotNull(manifestDigest)
        checkNotNull(enrollmentAttemptId)
        checkNotNull(proposedApiKey)
        when (enrollmentManifestService.authorizeEnrollmentAttempt(
            studyId,
            participantId,
            enrollmentCode,
            manifestDigest,
            enrollmentAttemptId,
            sourceRandomDeviceId,
            datasource,
            proposedApiKey,
        )) {
            EnrollmentAuthorization.AUTHORIZED -> Unit
            EnrollmentAuthorization.STUDY_CHANGED -> throw EnrollmentAttemptEndedException(HttpStatus.CONFLICT,
                Messages.get("error.enrollment.studyChanged"), EnrollmentAttemptContracts.ENROLLMENT_STUDY_CHANGED_CODE)
            EnrollmentAuthorization.CANCELLED -> throw enrollmentCancelled()
            EnrollmentAuthorization.REFUSED -> throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                Messages.get("error.enrollment.credentialOrDigestInvalid"),
            )
        }
        return try {
            enrollDevice(
                studyId,
                participantId,
                sourceRandomDeviceId,
                datasource,
                proposedApiKey,
                UUID.fromString(enrollmentAttemptId),
            )
        } catch (_: InvalidEnrollmentAttemptException) {
            // A cancel that won the device lock after the bind committed ends this attempt as cancelled.
            if (apiKeyService.isCancelledEnrollmentKey(proposedApiKey)) throw enrollmentCancelled()
            throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                Messages.get("error.enrollment.credentialOrDigestInvalid"),
            )
        }
    }

    private fun enrollmentCancelled() = EnrollmentAttemptEndedException(HttpStatus.GONE,
        Messages.get("error.enrollment.cancelled"), EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE)

    @Timed
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun createStudy(@Valid @RequestBody study: Study): UUID {
        ensureAuthenticated()
        val stampedStudy = stampInitialDataCollectionSettings(study)
        stampedStudy.organizationIds.forEach { organizationId -> ensureOwnerAccess(AclKey(organizationId)) }
        logger.info("Creating study associated with organizations ${stampedStudy.organizationIds}")
        return try {
            val id = studyService.createStudy(stampedStudy) { connection, createdId ->
                studySettingsAuditService.recordSettingsDiff(createdId, emptyMap(), stampedStudy.settings, connection)
            }
            auditService.logWithContext {
                action(AuditEventType.STUDY_CREATE)
                resourceType("Study")
                resourceId(id)
                success(true)
                additionalData(mapOf("organizationIds" to stampedStudy.organizationIds.toString()))
            }
            id
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.STUDY_CREATE)
                resourceType("Study")
                success(false)
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getStudy(@PathVariable(STUDY_ID) studyId: UUID): Study {
        ensureReadAccess(AclKey(studyId))
        logger.info("Retrieving study with id $studyId")

        return try {
            val study = getStudyWithEtag(studyId)
            recordReadEvent(
                AuditableEvent(
                    AclKey(studyId),
                    eventType = AuditEventType.GET_STUDY,
                    description = "",
                    study = studyId,
                    organization = IdConstants.UNINITIALIZED.id,
                    data = mapOf()
                )
            )
            auditService.logWithContext {
                action(AuditEventType.VIEW)
                resourceType("Study")
                resourceId(studyId)
                studyId(studyId)
                success(true)
            }
            study
        } catch (ex: NoSuchElementException) {
            auditService.logWithContext {
                action(AuditEventType.VIEW)
                resourceType("Study")
                studyId(studyId)
                success(false)
            }
            throw StudyNotFoundException(studyId, "No study with id $studyId found.", ex)
        }

    }

    @Timed
    @GetMapping(
        path = [ORGANIZATION_PATH + ORGANIZATION_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getOrgStudies(
        @PathVariable(ORGANIZATION_ID) organizationId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<Study> {
        val safeLimit = PaginationDefaults.clampLimit(limit)
        val safeOffset = PaginationDefaults.clampOffset(offset)

        ensureReadAccess(AclKey(organizationId))
        val currentUser = Principals.getCurrentSecurablePrincipal()
        logger.info("Retrieving studies with organization id $organizationId on behalf of ${currentUser.principal.id}")

        return try {
            val studies = studyService.getOrgStudies(organizationId, safeLimit, safeOffset)
            recordReadEvents(studies.map { study ->
                    AuditableEvent(
                        AclKey(study.id),
                        currentUser.id,
                        currentUser.principal,
                        eventType = AuditEventType.GET_STUDY,
                        study = study.id,
                        organization = organizationId,
                    )
            })
            auditService.logWithContext {
                action(AuditEventType.LIST)
                resourceType("Study")
                organizationId(organizationId)
                success(true)
                additionalData(mapOf("studyCount" to studies.size))
            }

            studies
        } catch (ex: NoSuchElementException) {
            auditService.logWithContext {
                action(AuditEventType.LIST)
                resourceType("Study")
                organizationId(organizationId)
                success(false)
            }
            throw OrganizationNotFoundException(organizationId, "No organization with id $organizationId found.", ex)
        }

    }

    @Timed
    @PatchMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH + SETTING_TYPE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateStudySettings(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(SETTING_TYPE) settingType: StudySettingType,
        @Valid @RequestBody settings: StudySetting,
    ): OK {
        when (settingType) {
            else -> ensureWriteAccess(AclKey(studyId))
        }

        val expectedRevision = parseSettingsRevisionPrecondition(requestHeader(HttpHeaders.IF_MATCH))

        return try {
            val persistedUpdate = persistLockedStudySettingsMutation(studyId, expectedRevision) { priorSettings ->
                mergeStudySetting(priorSettings, settingType, settings)
            }
            setSettingsRevisionEtag(persistedUpdate.settingsRevision)
            studyService.refreshStudyCache(setOf(studyId))

            auditService.logWithContext {
                action(AuditEventType.SETTINGS_CHANGE)
                resourceType("StudySettings")
                resourceId(studyId)
                studyId(studyId)
                success(true)
                additionalData(mapOf("settingType" to settingType.name))
            }

            studySettingsNotificationService.notifySettingsChanged(studyId)

            ok
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.SETTINGS_CHANGE)
                resourceType("StudySettings")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH + StudyApi.AUDIT_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudySettingsAudit(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): List<StudySettingsAuditEntry> {
        ensureReadAccess(AclKey(studyId))
        return studySettingsAuditService.getAuditHistory(studyId, limit.coerceIn(1, 200), offset.coerceAtLeast(0))
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH + StudyApi.ACKNOWLEDGMENTS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyCollectionAcknowledgments(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): List<CollectionAcknowledgmentEntry> {
        ensureReadAccess(AclKey(studyId))
        val acknowledgments = participantCollectionAcknowledgmentService.getAcknowledgments(
            studyId, limit.coerceIn(1, 200), offset.coerceAtLeast(0)
        )
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("CollectionAcknowledgments")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "sourceRandomDeviceId", "collectionDecisions"))
            additionalData(mapOf("participantCount" to acknowledgments.map { it.participantId }.distinct().size))
        }
        return acknowledgments
    }

    /**
     * Records a settings-audit entry for every setting type that actually changed
     * between [before] and [after]. This is the single source of settings-audit
     * coverage: the study-create, general-update, and legacy data-collection write
     * paths all funnel through it (and the per-type PATCH path inherits it via the
     * general update it delegates to), so no settings write escapes the trail.
     *
     * Equality is decided on the serialized JSON so a type whose value object lacks
     * structural equality is not spuriously reported. Removals (a type present in
     * [before] but absent in [after]) are skipped — the audit entry requires a
     * non-null after-value and settings are replaced/added, never dropped, in
     * practice. Audit failures roll back the settings transaction.
     */
    // reason: the two continues (skip-unchanged, skip-absent-after) are the clearest expression of the diff scan; restructuring loop control is risky
    



    @Timed
    @PatchMapping(
        path = [STUDY_ID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateStudy(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody study: StudyUpdate,
        @RequestParam(value = RETRIEVE, required = false, defaultValue = "false") retrieve: Boolean,
    ): Study? {
        val expectedRevision = parseSettingsRevisionPrecondition(requestHeader(HttpHeaders.IF_MATCH))
        val studyAclKey = AclKey(studyId)
        ensureOwnerAccess(studyAclKey)
        val currentUser = Principals.getCurrentSecurablePrincipal()
        logger.info("Updating study with id $studyId on behalf of ${currentUser.principal.id}")
        return try {
            val persistedUpdate = persistStudyUpdate(studyId, study, studyAclKey, currentUser, expectedRevision)
            setSettingsRevisionEtag(persistedUpdate.settingsRevision)
            studyService.refreshStudyCache(setOf(studyId))
            auditService.logWithContext {
                action(AuditEventType.STUDY_UPDATE)
                resourceType("Study")
                resourceId(studyId)
                studyId(studyId)
                success(true)
            }
            if (retrieve) getStudyWithEtag(studyId) else null
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.STUDY_UPDATE)
                resourceType("Study")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    private fun persistStudyUpdate(
        studyId: UUID,
        requestedStudy: StudyUpdate,
        studyAclKey: AclKey,
        currentUser: SecurablePrincipal,
        expectedRevision: Long?,
    ): LockedStudyUpdate =
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<LockedStudyUpdate>(connection, auditingManager)
                .transaction { transaction ->
                    checkLockedStudyRevision(transaction, studyId, expectedRevision)
                    val lockedUpdate = mergeAndStampDataCollectionSettingsVersionLocked(
                        transaction,
                        studyId,
                        requestedStudy,
                    )
                    lockedUpdate.stampedStudy.settings?.let { requestedSettings ->
                        ensureParticipantPolicyMutable(
                            transaction,
                            studyId,
                            lockedUpdate.priorSettings,
                            requestedSettings,
                        )
                    }
                    studyService.updateStudy(transaction, studyId, lockedUpdate.stampedStudy)
                    lockedUpdate.stampedStudy.settings?.let { settings ->
                        studySettingsAuditService.recordSettingsDiff(studyId, lockedUpdate.priorSettings ?: emptyMap(), settings, transaction)
                    }
                    lockedUpdate.copy(settingsRevision = studyService.getStudySettingsRevision(transaction, studyId))
                }
                .audit { _ ->
                    listOf(
                        AuditableEvent(
                            studyAclKey,
                            currentUser.id,
                            currentUser.principal,
                            AuditEventType.UPDATE_STUDY,
                            study = studyId,
                            data = mapOf(),
                        ),
                    )
                }
                .buildAndRun()
        }

    @Timed
    @DeleteMapping(
        path = [STUDY_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun destroyStudy(@PathVariable studyId: UUID): Iterable<UUID> {
        ensureOwnerAccess(AclKey(studyId))
        val currentUser = Principals.getCurrentSecurablePrincipal()
        logger.info("Quarantining study {} for verified deletion", studyId)
        return try {
            val operationId = studyLifecycleService.requestStudyDeletion(
                studyId = studyId,
                userId = currentUser.id.toString(),
            )
            recordCommittedEvent(
                AuditableEvent(
                    aclKey = AclKey(operationId),
                    securablePrincipalId = currentUser.id,
                    principal = currentUser.principal,
                    eventType = AuditEventType.DELETE_STUDY,
                    description = "Study deletion requested; data is blocked until an owner runs the erasure",
                    study = studyId,
                    data = mapOf("operationId" to operationId.toString()),
                )
            )
            auditService.logWithContext {
                action(AuditEventType.STUDY_DELETE)
                resourceType("Study")
                resourceId(studyId)
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantData", "studyData"))
            }
            listOf(operationId)
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.STUDY_DELETE)
                resourceType("Study")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    @Timed
    @DeleteMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun deleteStudyParticipants(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody participantIds: Set<String>,
    ): Iterable<UUID> {
        require(participantIds.size <= PaginationDefaults.MAX_BULK_IDS) {
            "Cannot delete more than ${PaginationDefaults.MAX_BULK_IDS} participants in a single request"
        }
        ensureValidStudy(studyId)
        ensureOwnerAccess(AclKey(studyId))

        return try {
            val operationIds = participantIds.map { participantId ->
                val transaction = dataDeletionOrchestrator.quarantineParticipantAtomically(
                    studyId = studyId,
                    participantId = participantId,
                    mode = DataDeletionMode.WITHDRAW_AND_ERASE,
                    requestedBy = Principals.getCurrentUser().id,
                    idempotencyKey = UUID.randomUUID(),
                ) { connection ->
                    ParticipantQuarantineDecision(
                        Unit,
                        createOperation = true,
                        auditEvent = studyService.updateParticipationStatus(
                            connection, studyId, participantId, ParticipationStatus.NOT_ENROLLED,
                        ),
                    )
                }
                checkNotNull(transaction.operationId)
            }
            recordCommittedEvent(
                AuditableEvent(
                    AclKey(studyId),
                    eventType = AuditEventType.DELETE_PARTICIPANTS,
                    description = "Participant refs ${LogSanitizer.stableFingerprints(participantIds, "participant")} " +
                        "are blocked pending an owner-run erasure",
                    study = studyId,
                    data = mapOf("operationIds" to operationIds.map(UUID::toString)),
                )
            )
            auditService.logWithContext {
                action(AuditEventType.DATA_DELETION)
                resourceType("Participant")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "participantData"))
                additionalData(mapOf("participantCount" to participantIds.size))
            }
            operationIds
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_DELETION)
                resourceType("Participant")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun registerParticipant(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody participant: Participant,
    ): UUID {
        ensureValidStudy(studyId)
        ensureWriteAccess(AclKey(studyId))

        return try {
            val id = studyService.registerParticipant(studyId, participant)
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_ENROLL)
                resourceType("Participant")
                resourceId(id)
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId"))
            }
            id
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_ENROLL)
                resourceType("Participant")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    @Timed
    public fun uploadScreenTimeData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody envelope: ScreenTimeUsageEnvelope,
    ): Int {
        validateUploadEnvelopeIdentity(
            requestStudyId = studyId,
            requestParticipantId = participantId,
            requestSourceRandomDeviceId = sourceRandomDeviceId,
            envelopeStudyId = envelope.studyId,
            envelopeParticipantId = envelope.participantId,
            envelopeRandomDeviceId = envelope.randomDeviceId,
        )
        logParticipantAction("iOS Screen Time upload received", studyId, participantId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, randomDeviceId, "iOS Screen Time upload")

        return try {
            val count = guardedDeviceWrite(
                studyId, participantId, randomDeviceId, "iOS Screen Time upload", setOf(CollectionModuleId.USAGE_EVENTS)) {
                webhookService.withDataSubmission(studyId, participantId, ParticipantDataType.IOSScreenTime) {
                    screenTimeUsageUploadService.upload(studyId, participantId, randomDeviceId, envelope)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.SCREEN_TIME_UPLOAD)
                resourceType("ScreenTimeData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "screenTimeData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "ios"))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.SCREEN_TIME_UPLOAD)
                resourceType("ScreenTimeData")
                studyId(studyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    override fun uploadAndroidSensorDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidSensorSample>,
    ): Int {
        logParticipantAction("Android sensor data upload received", studyId, participantId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, randomDeviceId, "android sensor data upload")
        return try {
            val count = guardedDeviceWrite(
                studyId, participantId, randomDeviceId, "android sensor data upload",
                data.mapTo(linkedSetOf()) { SensorCollectionModules.moduleFor(it.sensor) },
            ) { consentedModules ->
                webhookService.withDataSubmission(studyId, participantId, ParticipantDataType.AndroidSensor) {
                    if (data.any { SensorCollectionModules.moduleFor(it.sensor) !in consentedModules }) {
                        throw CollectionHaltedException(studyId, participantId, randomDeviceId)
                    }
                    androidSensorDataUploadService.upload(studyId, participantId, randomDeviceId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "android"))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    override fun uploadBatteryTelemetryV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<BatterySample>,
        settingsVersion: Int?,
        decisionSeq: Long?,
    ): UploadReceipt {
        logParticipantAction("Battery telemetry upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "battery telemetry upload")

        val ids = data.mapTo(linkedSetOf()) { sample ->
            runCatching { UUID.fromString(sample.id) }.getOrNull()
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, Messages.get("error.request.invalidParameters"))
        }
        return try {
            // A batch the participant's decisions refuse is reported discarded, never re-sent.
            val (count, receipt) = collectionWriteOrReject(realStudyId, participantId, randomDeviceId, "battery telemetry upload") {
                participantCollectionAcknowledgmentService.withCollectionUploadRecheck(
                    uploadRequest(realStudyId, participantId, randomDeviceId, settingsVersion, decisionSeq),
                    setOf(CollectionModuleId.BATTERY_TELEMETRY), allowPartial = true,
                ) { authorization ->
                    if (CollectionModuleId.BATTERY_TELEMETRY !in authorization.acceptedModules) {
                        0 to UploadReceipt(emptySet(), ids, authorization.settingsVersion)
                    } else webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.BatteryTelemetry) {
                        batteryTelemetryUploadService.upload(realStudyId, participantId, randomDeviceId, data)
                    } to UploadReceipt(ids, emptySet(), authorization.settingsVersion)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                success(true)
                // Battery telemetry is DEVICE_STATE_METADATA-class data — no participant
                // content (see BatterySample doc) — so accessedPHI is intentionally false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android"
                    )
                )
            }
            receipt
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [IosBatterySample]s (`battery_telemetry`, iOS realization).
     * The logic method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring
     * the collection-ack split), so this method carries no Spring `@PostMapping`.
     * Same enrollment + datasource gating and audit shape as [uploadBatteryTelemetry].
     */
    public fun uploadIosBatteryTelemetry(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<IosBatterySample>,
    ): Int {
        logParticipantAction("iOS battery telemetry upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "iOS battery telemetry upload")

        return try {
            val count = guardedDeviceWrite(
                realStudyId, participantId, randomDeviceId, "iOS battery telemetry upload") {
                batteryTelemetryUploadService.uploadIos(realStudyId, participantId, randomDeviceId, data)
            }
            auditService.logWithContext {
                action(AuditEventType.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                success(true)
                // Battery telemetry is DEVICE_STATE_METADATA-class data — no participant
                // content (see IosBatterySample doc) — so accessedPHI is intentionally false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "ios"
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    override fun uploadInteractionEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidInteractionEvent>,
    ): Int {
        logParticipantAction("Interaction events upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "interaction events upload")

        return try {
            val count = guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "interaction events upload", setOf(
                CollectionModuleId.INTERACTION_EVENTS)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.InteractionEvents) {
                    interactionEventsUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.INTERACTION_EVENTS_UPLOAD)
                resourceType("InteractionEvents")
                studyId(realStudyId)
                success(true)
                // Interaction events are INTERACTION_METADATA-class data — content-free by
                // construction (see AndroidInteractionEvent doc) — so accessedPHI stays false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android"
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.INTERACTION_EVENTS_UPLOAD)
                resourceType("InteractionEvents")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    /**
     * Persists app-audio-activity samples uploaded by the Android `audio_activity` module.
     * The logic method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring the
     * collection-ack / interaction split), so this method carries no Spring `@PostMapping`.
     * Gated on enrollment + known datasource like the other android writes.
     */
    public fun uploadAudioActivity(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidAudioActivityEvent>,
    ): Int {
        logParticipantAction("Audio activity upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "audio activity upload")

        return try {
            val count = guardedDeviceWrite(
                realStudyId, participantId, randomDeviceId, "audio activity upload", setOf(CollectionModuleId.AUDIO_ACTIVITY)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.AudioActivity) {
                    appAudioActivityUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AudioActivity")
                studyId(realStudyId)
                success(true)
                // Audio activity is BEHAVIORAL_METADATA-class data — mic-free by construction
                // (see AndroidAudioActivityEvent doc) — so accessedPHI stays false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android",
                        "module" to CollectionModuleId.AUDIO_ACTIVITY.id
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AudioActivity")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.AUDIO_ACTIVITY.id))
            }
            throw ex
        }
    }

    /**
     * Persists media-metadata samples uploaded by the Android `audio_content` module.
     * The logic method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring the
     * collection-ack / interaction split), so this method carries no Spring `@PostMapping`.
     * Gated on enrollment + known datasource like the other android writes.
     */
    public fun uploadAudioContent(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidAudioContentEvent>,
    ): Int {
        logParticipantAction("Audio content upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "audio content upload")

        return try {
            val count = guardedDeviceWrite(
                realStudyId, participantId, randomDeviceId, "audio content upload", setOf(CollectionModuleId.AUDIO_CONTENT)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.AudioContent) {
                    appAudioContentUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AudioContent")
                studyId(realStudyId)
                success(true)
                // Audio content is MEDIA_CONTENT-class data — it can carry a track/episode
                // title (see AndroidAudioContentEvent doc), so accessedPHI is set true.
                accessedPHI(true)
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android",
                        "module" to CollectionModuleId.AUDIO_CONTENT.id
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AudioContent")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.AUDIO_CONTENT.id))
            }
            throw ex
        }
    }

    /**
     * Persists notification-activity samples uploaded by the Android `notification_activity`
     * module. The logic method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring
     * the collection-ack / interaction split), so this method carries no Spring `@PostMapping`.
     * Gated on enrollment + known datasource like the other android writes.
     */
    public fun uploadNotificationActivity(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidNotificationActivityEvent>,
    ): Int {
        logParticipantAction("Notification activity upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "notification activity upload")

        return try {
            val count = guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "notification activity upload", setOf(
                CollectionModuleId.NOTIFICATION_ACTIVITY)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.NotificationActivity) {
                    notificationActivityUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("NotificationActivity")
                studyId(realStudyId)
                success(true)
                // Notification activity is BEHAVIORAL_METADATA-class data — content-free by
                // construction (see AndroidNotificationActivityEvent doc) — so accessedPHI stays false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android",
                        "module" to CollectionModuleId.NOTIFICATION_ACTIVITY.id
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("NotificationActivity")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.NOTIFICATION_ACTIVITY.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidSleepEvent] sleep samples (the `sleep` collection module).
     * The v4 HTTP path is mapped by [StudyV4Controller]; this is the logic method. Gated on
     * enrollment + known datasource like the other android writes, idempotent on the server
     * via the sleep_events PK. HEALTH_METRICS-class but content-free/mic-free by construction.
     */
    public fun uploadSleepEvents(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidSleepEvent>,
    ): Int {
        logParticipantAction("Sleep events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "sleep events upload")

        return try {
            val count = guardedDeviceWrite(
                realStudyId, participantId, randomDeviceId, "sleep events upload", setOf(CollectionModuleId.SLEEP)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.SleepEvents) {
                    sleepEventsUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("SleepEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "android",
                    "module" to CollectionModuleId.SLEEP.id))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("SleepEvents")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.SLEEP.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidActivityRecognitionEvent] samples (`activity_recognition`).
     * BEHAVIORAL_METADATA-class, content-free (label + confidence only).
     */
    public fun uploadActivityRecognitionEvents(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidActivityRecognitionEvent>,
    ): Int {
        logParticipantAction("Activity recognition events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "activity recognition events upload")

        return try {
            val count = guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "activity recognition events upload", setOf(
                CollectionModuleId.ACTIVITY_RECOGNITION)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.ActivityRecognition) {
                    activityRecognitionEventsUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("ActivityRecognitionEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to CollectionModuleId.ACTIVITY_RECOGNITION.id
                ))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("ActivityRecognitionEvents")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.ACTIVITY_RECOGNITION.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidHealthMetricEvent] records (`health_connect`). HEALTH_METRICS-class.
     */
    public fun uploadHealthMetrics(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidHealthMetricEvent>,
        platform: String = "android",
    ): Int {
        logParticipantAction("Health metrics upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "health metrics upload")

        return try {
            val count = collectionWriteOrReject(realStudyId, participantId, randomDeviceId, "health metrics upload") {
                participantCollectionAcknowledgmentService.withHealthUploadRecheck(CollectionUploadRequest(
                    studyId = realStudyId, participantId = participantId, randomDeviceId = randomDeviceId,
                    settingsVersion = uploadSettingsVersion(), decisionSeq = uploadDecisionSeq(),
                )) { scope ->
                    webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.HealthMetrics) {
                        healthMetricsUploadService.upload(scope, data)
                    }
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("HealthMetrics")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to platform,
                    "module" to CollectionModuleId.HEALTH_CONNECT.id))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("HealthMetrics")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.HEALTH_CONNECT.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidConnectivityStateEvent] samples (`connectivity_state`).
     * DEVICE_STATE_METADATA-class; transport + flags only, no SSID/BSSID/IP/cell id.
     */
    public fun uploadConnectivityStateEvents(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidConnectivityStateEvent>,
        platform: String = "android",
    ): Int {
        logParticipantAction("Connectivity state events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "connectivity state events upload")

        return try {
            val count = guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "connectivity state events upload", setOf(
                CollectionModuleId.CONNECTIVITY_STATE)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.ConnectivityState) {
                    connectivityStateEventsUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("ConnectivityStateEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to platform, "module" to CollectionModuleId.CONNECTIVITY_STATE.id
                ))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("ConnectivityStateEvents")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.CONNECTIVITY_STATE.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidAppNetworkUsageEvent] samples (`app_network_usage`).
     * BEHAVIORAL_METADATA-class; per-app byte counts only, no payload/destination visibility.
     */
    public fun uploadAppNetworkUsage(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidAppNetworkUsageEvent>,
    ): Int {
        logParticipantAction("App network usage upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "app network usage upload")

        return try {
            val count = guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "app network usage upload", setOf(
                CollectionModuleId.APP_NETWORK_USAGE)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.AppNetworkUsage) {
                    appNetworkUsageUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AppNetworkUsage")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to CollectionModuleId.APP_NETWORK_USAGE.id
                ))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("AppNetworkUsage")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.APP_NETWORK_USAGE.id))
            }
            throw ex
        }
    }

    /**
     * Ingests a batch of [AndroidDeviceSettingsEvent] snapshots (`device_settings`).
     * DEVICE_STATE_METADATA-class; content-free/identity-free device toggle snapshot.
     */
    public fun uploadDeviceSettings(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidDeviceSettingsEvent>,
    ): Int {
        logParticipantAction("Device settings upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "device settings upload")

        return try {
            val count = guardedDeviceWrite(
                realStudyId, participantId, randomDeviceId, "device settings upload", setOf(CollectionModuleId.DEVICE_SETTINGS)) {
                webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.DeviceSettings) {
                    deviceSettingsUploadService.upload(realStudyId, participantId, data)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("DeviceSettings")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to CollectionModuleId.DEVICE_SETTINGS.id
                ))
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("DeviceSettings")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId, "module" to CollectionModuleId.DEVICE_SETTINGS.id))
            }
            throw ex
        }
    }

    /** Stores bounded, redacted diagnostic occurrences; the receipt names exactly the stored and the refused events. */
    public fun uploadDiagnostics(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        data: List<AndroidUploadDiagnosticEvent>,
    ): UploadReceipt {
        logParticipantAction("Upload diagnostics received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)
        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "upload diagnostics")

        return guardedDeviceWrite(realStudyId, participantId, randomDeviceId, "upload diagnostics") {
            uploadDiagnosticsUploadService.upload(realStudyId, participantId, randomDeviceId, data)
        }
    }

    /**
     * Records a participant's on-device acknowledgment of one or more newly-enabled
     * collection modules. This is the logic
     * method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring the
     * enroll / enrollV4 split), so this method carries no Spring `@PostMapping`.
     *
     * Gated on enrollment + known datasource like the other android writes; the
     * `X-Api-Key` device auth is already enforced by the mobile API filter. The
     * acknowledgment trail is append-only and the server stamps its own
     * authoritative receipt time — the body's `acknowledgedAt` is advisory.
     */
    override fun reportCollectionAcknowledgmentV4(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        acknowledgment: CollectionAcknowledgment,
    ): OK {
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)

        mobileUploadEnrollmentGate(
            realStudyId,
            participantId,
            randomDeviceId,
            "collection acknowledgment",
            allowCollectionHaltResolution = true,
        )
        val mobileKey = requireAcknowledgmentApiKey(realStudyId, participantId, randomDeviceId)

        return try {
            val entry = participantCollectionAcknowledgmentService.recordAcknowledgment(
                studyId = realStudyId,
                participantId = participantId,
                sourceRandomDeviceId = sourceRandomDeviceId,
                apiKeyId = mobileKey.keyId,
                acknowledgment = acknowledgment,
            )
            auditService.logWithContext {
                action(AuditEventType.COLLECTION_ACKNOWLEDGMENT)
                resourceType("CollectionAcknowledgment")
                studyId(realStudyId)
                success(true)
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "acknowledgedModules" to acknowledgment.acknowledgedModules.map { it.id },
                        "declinedModules" to acknowledgment.declinedModules.map { it.id },
                        "unavailableModules" to acknowledgment.unavailableModules.map { it.id },
                        "trigger" to acknowledgment.trigger.name,
                        "settingsVersion" to (acknowledgment.settingsVersion ?: "legacy"),
                        "disclosureVersion" to (acknowledgment.disclosureVersion ?: "legacy"),
                        "manifestDigest" to (acknowledgment.manifestDigest ?: "legacy"),
                        "randomDeviceId" to randomDeviceId.toString(),
                    )
                )
            }
            OK("Recorded collection acknowledgment ${entry.id}")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.COLLECTION_ACKNOWLEDGMENT)
                resourceType("CollectionAcknowledgment")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    public fun reportAndroidSensorAvailability(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody availability: AndroidDeviceSensorAvailability,
    ): Int {
        logParticipantAction("Android sensor availability report received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.requireStudyId(studyId)

        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)
        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "sensor availability report")

        return try {
            val result = guardedDeviceWrite(
                realStudyId,
                participantId,
                randomDeviceId,
                "sensor availability report",
                setOf(CollectionModuleId.SENSOR_AVAILABILITY),
                allowQueuedCaptures = false,
            ) {
                storageResolver.getPlatformStorage().connection.use { conn ->
                    AndroidSensorAvailabilityRepository(conn).upsert(realStudyId, participantId, randomDeviceId, availability).also {
                        ParticipantStatsMapstore(storageResolver.getPlatformStorage())
                            .recordAndroidUploadUsing(conn, realStudyId, participantId)
                    }
                }
            }
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("SensorData")
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorAvailability"))
                additionalData(mapOf("participantId" to participantId, "randomDeviceId" to randomDeviceId.toString()))
            }
            result
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.DATA_SUBMISSION)
                resourceType("SensorData")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + DEVICES_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyDevices(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Map<String, List<Map<String, Any>>> {
        ensureReadAccess(AclKey(studyId))
        return try {
            val result = loadStudyDevices(studyId, PaginationDefaults.clampLimit(limit), PaginationDefaults.clampOffset(offset))
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_DATA_ACCESS)
                resourceType("Device")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "deviceInfo"))
                additionalData(mapOf("participantCount" to result.size))
            }
            result
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_DATA_ACCESS)
                resourceType("Device")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    private fun loadStudyDevices(studyId: UUID, limit: Int, offset: Int): Map<String, List<Map<String, Any>>> {
        return storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(GET_STUDY_DEVICES_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit + 1)
                ps.setInt(3, offset)
                ps.executeQuery().use { readStudyDeviceRows(it, limit, offset) }
            }
        }
    }

    private fun readStudyDeviceRows(rs: java.sql.ResultSet, limit: Int, offset: Int): Map<String, List<Map<String, Any>>> {
        val result = mutableMapOf<String, MutableList<Map<String, Any>>>()
        var rowsRead = 0
        while (rs.next()) {
            if (rowsRead++ == limit) {
                val request = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
                request?.response?.setHeader("X-Next-Offset", (offset.toLong() + limit).toString())
                break
            }
            val participantId = rs.getString("participant_id")
            val deviceInfo = mapOf<String, Any>(
                "randomDeviceId" to rs.getObject("random_device_id", UUID::class.java),
                "deviceType" to (rs.getString("device_type") ?: ""),
                "sourceDevice" to ObjectMappers.getJsonMapper().readTree(rs.getString("source_device") ?: "{}")
            )
            result.getOrPut(participantId) { mutableListOf() }.add(deviceInfo)
        }
        return result
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + ANDROID_PATH + SENSORS_PATH + AVAILABILITY_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudySensorAvailability(@PathVariable(STUDY_ID) studyId: UUID): List<AndroidDeviceSensorAvailability> {
        ensureReadAccess(AclKey(studyId))
        return try {
            val result = loadStudySensorAvailability(studyId)
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_DATA_ACCESS)
                resourceType("SensorData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorAvailability"))
                additionalData(mapOf("resultCount" to result.size))
            }
            result
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.PARTICIPANT_DATA_ACCESS)
                resourceType("SensorData")
                studyId(studyId)
                success(false)
            }
            throw ex
        }
    }

    private fun loadStudySensorAvailability(studyId: UUID): List<AndroidDeviceSensorAvailability> =
        storageResolver.getPlatformStorage().connection.use { AndroidSensorAvailabilityRepository(it).listForStudy(studyId) }

    /**
     * Applies a settings mutation under the study row lock.
     *
     * The mutation only ever receives the authoritative row-locked map, so a per-type write can
     * never carry a client-supplied merge of the other types. When [expectedRevision] is supplied
     * (`If-Match`) it is compared against the row-locked revision inside the same transaction, so
     * a dashboard that read a stale map is rejected with 412 rather than clobbering a concurrent
     * change to a different setting type. A missing precondition keeps the legacy behaviour.
     */
    

    private fun persistLockedStudySettingsMutation(
        studyId: UUID,
        expectedRevision: Long?,
        mutation: (StudySettings) -> StudySettings,
    ): LockedStudyUpdate = studyService.persistLockedStudySettingsMutation(
        studyId, expectedRevision, studySettingsAuditService, mutation,
    )

    override fun uploadAndroidSensorDataReceiptV4(
        studyId: UUID, participantId: String, sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidSensorSample>,
        settingsVersion: Int?,
        decisionSeq: Long?,
    ): UploadReceipt {
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, randomDeviceId, "sensor receipt upload", allowCollectionHaltResolution = true)
        requireUploadRow(data.map { it.id }.toSet().size == data.size) { "Duplicate sensor sample IDs" }
        val receipt = collectionWriteOrReject(studyId, participantId, randomDeviceId, "sensor receipt upload") {
            participantCollectionAcknowledgmentService.withCollectionUploadRecheck(
                uploadRequest(studyId, participantId, randomDeviceId, settingsVersion, decisionSeq),
                data.mapTo(linkedSetOf()) { SensorCollectionModules.moduleFor(it.sensor) }, allowPartial = true,
            ) { authorization ->
                val retained = storageResolver.getPlatformStorage().connection.use { connection ->
                    ParticipantPurgeCutoff.retainPermitted(
                        connection, studyId, participantId, data, { it.timestamp })
                }
                val accepted = retained.filter { SensorCollectionModules.moduleFor(it.sensor) in authorization.acceptedModules }
                webhookService.withDataSubmission(studyId, participantId, ParticipantDataType.AndroidSensor) {
                    androidSensorDataUploadService.upload(studyId, participantId, randomDeviceId, accepted)
                }
                val acceptedIds = accepted.mapTo(linkedSetOf()) { it.id }
                UploadReceipt(acceptedIds, data.mapTo(linkedSetOf()) { it.id } - acceptedIds, authorization.settingsVersion)
            }
        }
        auditService.logWithContext {
            action(AuditEventType.SENSOR_DATA_UPLOAD)
            resourceType("SensorData")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            additionalData(mapOf("recordCount" to receipt.acceptedIds.size, "discardedCount" to receipt.discardedIds.size))
        }
        return receipt
    }

    /** Every module a usage batch touches, including the optional fields its events carry. */
    private fun usageUploadModules(events: List<ChronicleUsageEvent>): Set<CollectionModuleId> =
        events.flatMapTo(linkedSetOf(), ::usageUploadModules)

    private fun usageUploadModules(event: ChronicleUsageEvent): Set<CollectionModuleId> = buildSet {
        add(usageEventModule(event))
        if (usageEventModule(event) == CollectionModuleId.USAGE_EVENTS && event.activityClass != null) {
            add(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        }
        if (event.user.isNotEmpty()) add(CollectionModuleId.USER_IDENTIFICATION)
    }

    /** Drops the optional fields whose module the participant has not consented to. */
    private fun consentedFields(event: ChronicleUsageEvent, consented: Set<CollectionModuleId>) = event.copy(
        activityClass = event.activityClass.takeIf {
            usageEventModule(event) == CollectionModuleId.DEVICE_LIFECYCLE || CollectionModuleId.IN_APP_ACTIVITY_CLASS in consented
        },
        user = event.user.takeIf { CollectionModuleId.USER_IDENTIFICATION in consented }.orEmpty(),
    )

    override fun uploadAndroidUsageEventDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        sourceRandomDeviceId: String,
        @Valid @RequestBody data: ChronicleData,
        settingsVersion: Int?,
        decisionSeq: Long?,
    ): UploadReceipt {
        logParticipantAction("Android usage event upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.requireStudyId(studyId)
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(realStudyId, participantId, sourceRandomDeviceId)
        // The upload recheck refuses a device that has not acknowledged consent.
        mobileUploadEnrollmentGate(realStudyId, participantId, randomDeviceId, "android usage event upload")
        val events = data.filterIsInstance<ChronicleUsageEvent>()
        val ids = events.map { it.clientEventId ?: throw UploadRowRejectedException("Usage event has no client event ID") }
        requireUploadRow(ids.toSet().size == ids.size) { "Duplicate usage client event IDs" }
        return try {
            val receipt = collectionWriteOrReject(realStudyId, participantId, randomDeviceId, "android usage event upload") {
                participantCollectionAcknowledgmentService.withCollectionUploadRecheck(
                    uploadRequest(realStudyId, participantId, randomDeviceId, settingsVersion, decisionSeq),
                    uploadModules = usageUploadModules(events), allowPartial = true,
                ) { authorization ->
                    // An event is stored when its module is accepted; an optional field it was not authorized
                    // for is stripped, never held. A refused event is answered discarded, never re-sent.
                    val accepted = events.filter { usageEventModule(it) in authorization.acceptedModules }
                    val stored = if (accepted.isEmpty()) emptySet() else appDataUploadService.uploadAndroidUsageEvents(
                        realStudyId, participantId, randomDeviceId, accepted.map { consentedFields(it, authorization.acceptedModules) },
                    )
                    webhookService.withDataSubmission(realStudyId, participantId, ParticipantDataType.UsageEvents) { stored.size }
                    UploadReceipt(stored, ids.toSet() - stored, authorization.settingsVersion)
                }
            }
            auditService.logWithContext {
                action(AuditEventType.USAGE_DATA_UPLOAD)
                resourceType("UsageData")
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "usageEvents"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to receipt.acceptedIds.size,
                    "discardedCount" to receipt.discardedIds.size))
            }
            receipt
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.USAGE_DATA_UPLOAD)
                resourceType("UsageData")
                studyId(realStudyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudySettings(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): Map<StudySettingType, StudySetting> {
        // The full settings endpoint requires authentication.
        // Mobile clients should use getAndroidSensorSettings() or getStudySensors() instead,
        // which return only the minimal sensor config needed for enrollment.
        ensureAuthenticated()
        ensureReadAccess(AclKey(studyId))
        val realStudyId = studyService.requireStudyId(studyId)
        val study = getStudyWithEtag(realStudyId)
        val settings = collectionSettingsForFeatures(study)
        auditService.logWithContext {
            action(AuditEventType.VIEW)
            resourceType("StudySettings")
            studyId(realStudyId)
            success(true)
        }
        return settings
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH + SETTING_TYPE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudySetting(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(SETTING_TYPE) settingsKey: StudySettingType,
    ): StudySetting {
        if (settingsKey == StudySettingType.DataCollection) {
            // Mobile clients read only this public policy projection, without acquiring full study access.
            val snapshot = participantCollectionAcknowledgmentService.collectionSettingsSnapshotForDevice(studyId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.study.notFound"))
            setSettingsRevisionEtag(snapshot.revision)
            logStudySettingView(studyId, settingsKey)
            return snapshot.setting
        }
        ensureReadAccess(AclKey(studyId))
        val settings = getStudyWithEtag(studyId).settings
        val setting = when (settingsKey) {
            StudySettingType.AppUsageSurvey -> settings.appUsageSurvey()
            else -> settings.getValue(settingsKey)
        }
        logStudySettingView(studyId, settingsKey)
        return setting
    }

    private fun logStudySettingView(studyId: UUID, settingsKey: StudySettingType) {
        auditService.logWithContext {
            action(AuditEventType.VIEW)
            resourceType("StudySettings")
            studyId(studyId)
            success(true)
            additionalData(mapOf("settingType" to settingsKey.name))
        }
    }



    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyParticipants(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Iterable<Participant> {
        val safeLimit = PaginationDefaults.clampLimit(limit)
        val safeOffset = PaginationDefaults.clampOffset(offset)

        ensureAuthenticated()
        ensureReadAccess(AclKey(studyId))
        // Read once: the service's iterable runs its query again on every iteration.
        val listed = studyService.getStudyParticipants(studyId, safeLimit, safeOffset).toList()
        val consent = participantCollectionAcknowledgmentService.consentStates(studyId, listed.map { it.participantId })
        val participants = listed.map { participant -> participant.copy(consentState = consent[participant.participantId]) }
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("Participant")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantData"))
            additionalData(mapOf("participantCount" to participants.size))
        }
        return participants
    }

    @Timed
    @GetMapping(
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAllStudies(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Iterable<Study> {
        val safeLimit = PaginationDefaults.clampLimit(limit)
        val safeOffset = PaginationDefaults.clampOffset(offset)

        ensureAuthenticated()
        val studyAclKeys = authorizationManager.listAuthorizedObjectsOfType(
            Principals.getCurrentPrincipals(),
            SecurableObjectType.Study,
            EnumSet.of(Permission.READ)
        )
        // The list pages in memory over one Hazelcast getAll(), so it refuses an ID set it would have to cut short.
        val studyIds = studyAclKeys.mapTo(mutableSetOf()) { it.first() }
        require(studyIds.size <= PaginationDefaults.MAX_BULK_IDS) {
            "More than ${PaginationDefaults.MAX_BULK_IDS} studies are readable; list them per organization"
        }
        val studies = studyService.getStudies(studyIds)

        recordReadEvents(studies.map {
            AuditableEvent(
                aclKey = AclKey(it.id),
                eventType = AuditEventType.GET_ALL_STUDIES,
                study = it.id,
                description = "Loaded all accessible studies."
            )
        })

        auditService.logWithContext {
            action(AuditEventType.LIST)
            resourceType("Study")
            success(true)
            additionalData(mapOf("studyCount" to studies.count()))
        }

        // Pagination applied in-memory because getAllStudies fetches from Hazelcast cache
        // (studies.getAll), which does not support SQL LIMIT/OFFSET.
        // Stable order, so consecutive offsets never repeat or skip a study.
        return studies.sortedBy { it.id }.drop(safeOffset).take(safeLimit)
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + STATS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getParticipantStats(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Map<String, ParticipantStats> {
        val safeLimit = PaginationDefaults.clampLimit(limit)
        val safeOffset = PaginationDefaults.clampOffset(offset)

        ensureReadAccess(AclKey(studyId))
        val stats = studyService.getStudyParticipantStats(studyId, safeLimit, safeOffset)
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("Participant")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantStats"))
            additionalData(mapOf("participantCount" to stats.size))
        }
        return stats
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + IOS_PATH + UPLOAD_STATUS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getIosUploadStatus(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Map<String, IosUploadStatus> {
        ensureReadAccess(AclKey(studyId))
        val status = loadIosUploadStatus(studyId, PaginationDefaults.clampLimit(limit), PaginationDefaults.clampOffset(offset))
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("ScreenTimeData")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "iosUploadStatus"))
            additionalData(mapOf("participantCount" to status.size))
        }
        return status
    }

    private fun loadIosUploadStatus(studyId: UUID, limit: Int, offset: Int): Map<String, IosUploadStatus> {
        val result = linkedMapOf<String, IosUploadStatus>()
        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(GET_IOS_UPLOAD_STATUS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs -> readIosUploadStatusRows(rs, result) }
            }
        }
        return result
    }


    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + ANDROID_PATH + DIAGNOSTICS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getAndroidDiagnostics(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(value = "participantId", required = false) participantId: String?,
        @RequestParam(value = "randomDeviceId", required = false) randomDeviceId: UUID?,
        @RequestParam(value = "from", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDay: LocalDate?,
        @RequestParam(value = "to", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDay: LocalDate?,
        @RequestParam(value = "moduleFamily", required = false) moduleFamily: String?,
        @RequestParam(value = "issueCode", required = false) issueCode: String?,
        @RequestParam(value = "cursor", required = false) cursor: String?,
        @RequestParam(value = "limit", defaultValue = "50") limit: Int,
    ): AndroidDiagnosticsPage {
        ensureReadAccess(AclKey(studyId))
        val page = uploadDiagnosticsQueryService.getPage(
            studyId = studyId,
            participantId = participantId,
            randomDeviceId = randomDeviceId,
            fromDay = fromDay,
            toDay = toDay,
            moduleFamily = moduleFamily,
            issueCode = issueCode,
            cursor = cursor,
            limit = limit,
        )
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("UploadDiagnostics")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "randomDeviceId", "uploadDiagnostics"))
            additionalData(mapOf("rowCount" to page.items.size))
        }
        return page
    }

    private fun readIosUploadStatusRows(
        rs: java.sql.ResultSet,
        result: MutableMap<String, IosUploadStatus>
    ) {
        while (rs.next()) {
            val participantId = rs.getString("participant_id")
            result[participantId] = IosUploadStatus(
                participantId = participantId,
                committedRows = rs.getLong("committed_rows"),
                lastCommittedAt = rs.getObject("last_committed_at", OffsetDateTime::class.java),
                lastObservationEndAt = rs.getObject("last_observation_end_at", OffsetDateTime::class.java),
            )
        }
    }

    private fun logParticipantAction(message: String, studyId: UUID, participantId: String) {
        logger.info(
            "{} for studyId={}, participantRef={}",
            message,
            studyId,
            LogSanitizer.stableFingerprint(participantId, "participant")
        )
    }



    override fun getParticipantsData(
        studyId: UUID,
        dataType: ParticipantDataType,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime?,
        endDateTime: OffsetDateTime?,
    ): Iterable<Map<String, Any>> = participantsData(
        studyId, dataType, participantIds, SynchronousExportLimits.validate(participantIds, startDateTime, endDateTime),
    )

    private fun participantsData(
        studyId: UUID,
        dataType: ParticipantDataType,
        participantIds: Set<String>,
        window: SynchronousExportWindow,
    ): Iterable<Map<String, Any>> {
        val startDateTime = window.start
        val endDateTime = window.end
        ensureReadAccess(AclKey(studyId))
        return when (dataType) {
            ParticipantDataType.AppUsageSurvey -> downloadService.getParticipantsAppUsageSurveyData(
                studyId,
                participantIds,
                startDateTime,
                endDateTime,
                StudySettings(studyService.getStudySettings(studyId)).timeZone(),
            )

            ParticipantDataType.UsageEvents -> {
                downloadService.getParticipantsUsageEventsData(studyId, participantIds, startDateTime, endDateTime)
            }

            ParticipantDataType.AndroidSensor -> {
                downloadService.getParticipantsAndroidSensorData(studyId, participantIds, startDateTime, endDateTime)
            }

            ParticipantDataType.SensorAvailability,
            ParticipantDataType.BatteryTelemetry,
            ParticipantDataType.InteractionEvents,
            ParticipantDataType.AudioActivity,
            ParticipantDataType.AudioContent,
            ParticipantDataType.NotificationActivity,
            ParticipantDataType.SleepEvents,
            ParticipantDataType.ActivityRecognition,
            ParticipantDataType.HealthMetrics,
            ParticipantDataType.ConnectivityState,
            ParticipantDataType.AppNetworkUsage,
            ParticipantDataType.DeviceSettings,
            ParticipantDataType.IOSScreenTime -> downloadService.getParticipantsCollectionData(
                studyId,
                participantIds,
                dataType,
                startDateTime,
                endDateTime,
            )

            ParticipantDataType.UploadDiagnostics -> downloadService.getParticipantsUploadDiagnosticsData(
                studyId,
                participantIds,
                startDateTime,
                endDateTime,
            )
        }
    }

    private fun getParticipantsDataWithSensorFilter(
        studyId: UUID, request: SynchronousExportRequest, window: SynchronousExportWindow,
    ): Iterable<Map<String, Any>> {
        if (request.dataType == ParticipantDataType.AndroidSensor && !request.sensorTypes.isNullOrEmpty()) {
            ensureReadAccess(AclKey(studyId))
            return downloadService.getParticipantsAndroidSensorData(
                studyId, request.participantId, window.start, window.end, request.sensorTypes,
            )
        }
        return participantsData(studyId, request.dataType, request.participantId, window)
    }

    @PatchMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + STATUS_PATH]
    )
    override fun updateParticipationStatus(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(PARTICIPATION_STATUS) participationStatus: ParticipationStatus,
        @RequestHeader(EditRevisionApi.IF_MATCH, required = false) revision: String?,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        val expectedRevision = parseRevisionPrecondition(revision, "error.participant.invalidStatusRevision")
        return try {
            // The service owns "already in force" and the audit row of a change; an unchanged status writes neither.
            studyService.updateParticipationStatus(studyId, participantId, participationStatus, expectedRevision)
            OK("Successfully updated participation status")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(if (participationStatus == ParticipationStatus.NOT_ENROLLED) AuditEventType.PARTICIPANT_WITHDRAW
                    else AuditEventType.UPDATE_PARTICIPATION_STATUS)
                resourceType("Participant")
                studyId(studyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    /**
     * ConcurrentEditFilter locks the row and sets the ETag; this reads the snapshot on its pinned connection.
     * The explicit charset keeps the Content-Type the filter served before this handler existed.
     */
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + StudyApi.ANNOTATIONS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8"]
    )
    public fun getParticipantAnnotations(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
    ): String {
        ensureReadAccess(AclKey(studyId))
        val json = studyService.getParticipantAnnotations(studyId, participantId)
            ?: throw NoSuchElementException("Resource not found")
        auditService.logWithContext {
            action(AuditEventType.PARTICIPANT_DATA_ACCESS)
            resourceType("ParticipantAnnotations")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantNotes", "participantTags"))
            additionalData(mapOf("participantId" to participantId))
        }
        return json
    }

    @PatchMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + StudyApi.ANNOTATIONS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateParticipantAnnotations(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @Valid @RequestBody annotations: ParticipantAnnotationsUpdate,
        @RequestHeader(EditRevisionApi.IF_MATCH) revision: String,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return try {
            studyService.updateParticipantAnnotations(studyId, participantId, annotations)
            auditService.logWithContext {
                action(AuditEventType.UPDATE)
                resourceType("Participant")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "annotations"))
                additionalData(mapOf("participantId" to participantId))
            }
            OK("Successfully updated participant annotations")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.UPDATE)
                resourceType("Participant")
                studyId(studyId)
                success(false)
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE, CustomMediaType.TEXT_CSV_VALUE]
    )
    public fun getParticipantsData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @ModelAttribute request: SynchronousExportRequest,
        response: HttpServletResponse,
    ): Iterable<Map<String, Any>> {
        check(request.responseType == FileType.csv || request.responseType == FileType.json) {
            "Requested file type must be json or CSV."
        }
        val window = SynchronousExportLimits.validate(request.participantId, request.startDate, request.endDate)
        val data = getParticipantsDataWithSensorFilter(
            studyId, request, window,
        )
        ChronicleServerUtil.setDownloadContentType(response, request.responseType)
        ChronicleServerUtil.setContentDisposition(response,
            request.fileName ?: "${request.dataType}_${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}",
            request.responseType)
        auditParticipantDownload(studyId, request.dataType, request.participantId.size, request.responseType)
        // CSV and JSON both stream from the cursor (PostgresDownload*HttpMessageConverter); no size is refused.
        return data
    }

    /** Browser form POST of [getParticipantsData]; the token resolver checks its CSRF form field. */
    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANTS_PATH + DATA_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE, CustomMediaType.TEXT_CSV_VALUE]
    )
    public fun postParticipantsData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @ModelAttribute request: SynchronousExportRequest,
        response: HttpServletResponse,
    ): Iterable<Map<String, Any>> = getParticipantsData(studyId, request, response)

    private fun auditParticipantDownload(
        studyId: UUID, dataType: ParticipantDataType, participantCount: Int, fileType: FileType,
    ) {
        recordReadEvent(
            AuditableEvent(
                aclKey = AclKey(studyId),
                securablePrincipalId = Principals.getCurrentSecurablePrincipal().id,
                principal = Principals.getCurrentUser(),
                eventType = AuditEventType.DOWNLOAD_PARTICIPANTS_DATA,
                description = dataType.toString(),
                study = studyId
            )
        )

        auditService.logWithContext {
            action(AuditEventType.DOWNLOAD)
            resourceType("ParticipantData")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantData"))
            additionalData(mapOf(
                "dataType" to dataType.name,
                "participantCount" to participantCount,
                "fileType" to fileType.name
            ))
        }

    }


    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + VERIFY_PATH]
    )
    override fun isKnownParticipant(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
    ): Boolean {
        ensureReadAccess(AclKey(studyId))
        val result = enrollmentService.isKnownParticipant(studyId, participantId)
        auditService.logWithContext {
            action(AuditEventType.VIEW)
            resourceType("Participant")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId"))
            additionalData(mapOf("participantId" to participantId, "isKnown" to result))
        }
        return result
    }

    /**
     * Ensures that study id provided is for a valid study.
     *
     */
    private fun ensureValidStudy(studyId: UUID): Boolean {
        return studyService.isValidStudy(studyId)
    }

    private fun validateUploadEnvelopeIdentity(
        requestStudyId: UUID,
        requestParticipantId: String,
        requestSourceRandomDeviceId: String,
        envelopeStudyId: String,
        envelopeParticipantId: String,
        envelopeRandomDeviceId: String,
    ) {
        val parsedEnvelopeStudyId = runCatching { UUID.fromString(envelopeStudyId) }.getOrNull()
        if (
            parsedEnvelopeStudyId != requestStudyId ||
            envelopeParticipantId != requestParticipantId ||
            envelopeRandomDeviceId != requestSourceRandomDeviceId
        ) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                Messages.get("error.enrollment.uploadIdentityMismatch"),
            )
        }
    }

}
