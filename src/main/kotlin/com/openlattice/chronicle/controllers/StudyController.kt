package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.google.common.base.MoreObjects
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.constants.CustomMediaType
import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.deletion.DeleteStudyTableData
import com.openlattice.chronicle.deletion.StudyDeletionStorage
import com.openlattice.chronicle.deletion.StudyDeletionTable
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.collection.AmbientAudioClassificationEvent
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorSetting
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.AmbientAudioUploadService
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.upload.EncryptedPayloadUploadService
import com.openlattice.chronicle.services.delete.ParticipantDeletionPlan
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.services.upload.UserIdentificationEnvelope
import com.openlattice.chronicle.services.upload.UserIdentificationUploadService
import com.openlattice.chronicle.services.crypto.StudyEncryptionKeyService
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.apikeys.InvalidEnrollmentAttemptException
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudySettingsAuditService
import com.openlattice.chronicle.services.studies.StudySettingsNotificationService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.IosUploadStatus
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyPermissions
import com.openlattice.chronicle.study.StudyPermissionsUpdate
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingsAuditEntry
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.study.StudyApi.Companion.ANDROID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.BATTERY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.AVAILABILITY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.CONTROLLER
import com.openlattice.chronicle.study.StudyApi.Companion.DATA_COLLECTION
import com.openlattice.chronicle.study.StudyApi.Companion.DATA_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.DEVICES_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.DATA_TYPE
import com.openlattice.chronicle.study.StudyApi.Companion.END_DATE
import com.openlattice.chronicle.study.StudyApi.Companion.ENROLL_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.FILE_NAME
import com.openlattice.chronicle.study.StudyApi.Companion.IOS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ORGANIZATION_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANTS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPATION_STATUS
import com.openlattice.chronicle.study.StudyApi.Companion.PERMISSIONS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.RESPONSE_TYPE
import com.openlattice.chronicle.study.StudyApi.Companion.RETRIEVE
import com.openlattice.chronicle.study.StudyApi.Companion.SENSORS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SETTINGS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SETTING_TYPE
import com.openlattice.chronicle.study.StudyApi.Companion.SETTING_TYPE_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SOURCE_DEVICE_ID
import com.openlattice.chronicle.study.StudyApi.Companion.SOURCE_DEVICE_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.START_DATE
import com.openlattice.chronicle.util.DeviceIdUtils
import com.openlattice.chronicle.study.StudyApi.Companion.STATS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STATUS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.UPLOAD_STATUS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.VERIFY_PATH
import com.openlattice.chronicle.util.ChronicleServerUtil
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.PaginationDefaults
import com.openlattice.chronicle.util.validateParticipantId
import com.openlattice.chronicle.webhooks.WebhookEventType
import org.slf4j.LoggerFactory
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
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

/** Assigns the authoritative revision for DataCollection settings, ignoring client-supplied revisions. */
internal fun stampDataCollectionSettingsVersion(
    priorSettings: StudySettings?,
    requestedUpdate: StudyUpdate,
): StudyUpdate {
    val requestedSettings = requestedUpdate.settings ?: return requestedUpdate
    requireExportableStudyEncryption(requestedSettings)
    val requested = requestedSettings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting
        ?: return requestedUpdate
    validatePlayCollectionPolicy(requested)
    val prior = priorSettings?.get(StudySettingType.DataCollection) as? AndroidDataCollectionSetting
    val stampedVersion = when {
        prior == null -> AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION
        requested.copy(settingsVersion = prior.settingsVersion) == prior -> prior.settingsVersion
        else -> prior.settingsVersion + 1
    }
    return requestedUpdate.copy(
        settings = StudySettings(
            requestedSettings.mapValues { (type, setting) ->
                if (type == StudySettingType.DataCollection) {
                    requested.copy(settingsVersion = stampedVersion)
                } else {
                    setting
                }
            },
        ),
    )
}

/** Ignores caller-supplied collection revisions when a study is first created. */
internal fun stampInitialDataCollectionSettings(study: Study): Study {
    val initialSettings = if (study.settings.containsKey(StudySettingType.DataCollection)) {
        study.settings
    } else {
        StudySettings(
            study.settings + (StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting()),
        )
    }
    val stampedSettings = checkNotNull(
        stampDataCollectionSettingsVersion(
            priorSettings = null,
            requestedUpdate = StudyUpdate(settings = initialSettings),
        ).settings,
    )
    if (stampedSettings == study.settings) return study
    return Study(
        studyId = study.id,
        title = study.title,
        description = study.description,
        createdAt = study.createdAt,
        updatedAt = study.updatedAt,
        startedAt = study.startedAt,
        endedAt = study.endedAt,
        lat = study.lat,
        lon = study.lon,
        group = study.group,
        version = study.version,
        contact = study.contact,
        organizationIds = study.organizationIds,
        notificationsEnabled = study.notificationsEnabled,
        storage = study.storage,
        settings = stampedSettings,
        modules = study.modules,
        phoneNumber = study.phoneNumber,
    )
}

internal data class LockedStudyUpdate(
    val priorSettings: StudySettings?,
    val stampedStudy: StudyUpdate,
)

/** Reads and stamps settings only after serializing on the authoritative study row. */
internal fun stampDataCollectionSettingsVersionLocked(
    connection: java.sql.Connection,
    studyId: UUID,
    requestedUpdate: StudyUpdate,
): LockedStudyUpdate {
    if (requestedUpdate.settings == null) return LockedStudyUpdate(null, requestedUpdate)
    val priorSettings = loadLockedStudySettings(connection, studyId)
    return LockedStudyUpdate(
        priorSettings,
        stampDataCollectionSettingsVersion(priorSettings, requestedUpdate),
    )
}

internal fun loadLockedStudySettings(
    connection: java.sql.Connection,
    studyId: UUID,
): StudySettings = connection.prepareStatement(
        "SELECT settings FROM studies WHERE study_id = ? FOR UPDATE",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.study.notFound"))
            }
            resultSet.getString("settings")
                ?.let { ObjectMappers.getJsonMapper().readValue(it, StudySettings::class.java) }
                ?: StudySettings(emptyMap())
        }
    }

internal fun mergeLegacyDataCollectionSettings(
    priorSettings: StudySettings,
    legacySetting: ChronicleDataCollectionSettings,
): StudySettings {
    if (priorSettings[StudySettingType.DataCollection] is AndroidDataCollectionSetting) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.legacyCollectionSettings"),
        )
    }
    return StudySettings(priorSettings + (StudySettingType.DataCollection to legacySetting))
}

/** Applies a single-setting endpoint delta to the authoritative row-locked settings map. */
internal fun mergeStudySetting(
    priorSettings: StudySettings,
    settingType: StudySettingType,
    setting: StudySetting,
): StudySettings = StudySettings(priorSettings + (settingType to setting)).also(::requireExportableStudyEncryption)

/** Encrypted payload export is not yet implemented; never allow a study to select a data sink it cannot export. */
internal fun requireExportableStudyEncryption(settings: StudySettings) {
    val encryption = settings[StudySettingType.Encryption] as? StudyEncryptionSetting
    require(encryption?.enabled != true) {
        "Study payload encryption cannot be enabled until encrypted participant export is supported"
    }
}

private val PLAY_CUSTOM_COLLECTION_CADENCE_MODULES = setOf(
    CollectionModuleId.CONNECTIVITY_STATE,
    CollectionModuleId.DEVICE_SETTINGS,
    CollectionModuleId.APP_NETWORK_USAGE,
    CollectionModuleId.HEALTH_CONNECT,
    CollectionModuleId.BATTERY_TELEMETRY,
)

/** Keeps the signed manifest within policy controls the Play client actually enforces. */
internal fun validatePlayCollectionPolicy(setting: AndroidDataCollectionSetting) {
    setting.modules.forEach { (moduleId, moduleSetting) ->
        if (moduleId in PLAY_CUSTOM_COLLECTION_CADENCE_MODULES) {
            require(moduleSetting.collectionCadence.jitterSeconds == 0L) {
                "${moduleId.id}.collectionCadence.jitterSeconds is not supported by the Play build"
            }
        } else {
            require(
                moduleSetting.collectionCadence ==
                    com.openlattice.chronicle.collection.CollectionCadence.DEFAULT_COLLECTION,
            ) {
                "${moduleId.id}.collectionCadence is not supported by the Play build"
            }
        }
        require(moduleSetting.uploadCadence == com.openlattice.chronicle.collection.CollectionCadence.DEFAULT_UPLOAD) {
            "${moduleId.id}.uploadCadence is not supported by the Play build"
        }
        require(moduleSetting.batteryPolicy == com.openlattice.chronicle.collection.BatteryPolicy.DEFAULT) {
            "${moduleId.id}.batteryPolicy is not supported by the Play build"
        }
        require(moduleSetting.networkPolicy == com.openlattice.chronicle.collection.NetworkPolicy.DEFAULT) {
            "${moduleId.id}.networkPolicy is not supported by the Play build"
        }
        require(
            moduleId == CollectionModuleId.HEALTH_CONNECT || moduleSetting.healthConnectRecordTypes.isEmpty(),
        ) {
            "healthConnectRecordTypes may only be configured for the health_connect module"
        }
    }

    val healthConnect = setting.modules[CollectionModuleId.HEALTH_CONNECT]
    require(healthConnect == null || !healthConnect.enabled || healthConnect.healthConnectRecordTypes.isNotEmpty()) {
        "An enabled health_connect module requires at least one healthConnectRecordType"
    }
}

/** Prevents changing the legal disclosure after a participant or device has entered the study. */
internal fun ensureParticipantPolicyMutable(
    connection: java.sql.Connection,
    studyId: UUID,
    priorSettings: StudySettings?,
    requestedSettings: StudySettings,
) {
    val priorPolicy = priorSettings?.get(StudySettingType.ParticipantPolicy)
    val requestedPolicy = requestedSettings[StudySettingType.ParticipantPolicy]
    rejectDataCollectionRemoval(priorSettings, requestedSettings)
    val policyChanged = priorPolicy != requestedPolicy
    val collectionChanged = priorSettings?.get(StudySettingType.DataCollection) !=
        requestedSettings[StudySettingType.DataCollection]
    val requestedCollection = requestedSettings[StudySettingType.DataCollection]
    val unversionedCollectionChanged = collectionChanged && requestedCollection !is AndroidDataCollectionSetting
    val legacyAndroidSensorChanged = priorSettings?.get(StudySettingType.AndroidSensor) !=
        requestedSettings[StudySettingType.AndroidSensor]
    if (!policyChanged && !collectionChanged && !legacyAndroidSensorChanged) return

    // Enrollment digest validation takes a shared lock on this same row. Taking the
    // exclusive lock before the activity check prevents a policy write from crossing
    // an in-flight manifest validation.
    connection.prepareStatement("SELECT 1 FROM studies WHERE study_id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.study.notFound"))
            }
        }
    }

    val (hasActiveEnrollment, hasPendingEnrollment) = loadEnrollmentDisclosureActivity(connection, studyId)
    if (hasPendingEnrollment) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.enrollmentDisclosurePending"),
        )
    }
    rejectImmutableActiveDisclosureChanges(
        policyChanged,
        legacyAndroidSensorChanged,
        unversionedCollectionChanged,
        hasActiveEnrollment,
    )
}

private fun rejectDataCollectionRemoval(
    priorSettings: StudySettings?,
    requestedSettings: StudySettings,
) {
    val hadVersionedSettings = priorSettings?.get(StudySettingType.DataCollection) is AndroidDataCollectionSetting
    val retainsVersionedSettings = requestedSettings[StudySettingType.DataCollection] is AndroidDataCollectionSetting
    if (hadVersionedSettings && !retainsVersionedSettings) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.versionedSettingsRemoval"),
        )
    }
}

private fun rejectImmutableActiveDisclosureChanges(
    policyChanged: Boolean,
    legacyAndroidSensorChanged: Boolean,
    unversionedCollectionChanged: Boolean,
    hasActiveEnrollment: Boolean,
) {
    if (policyChanged && hasActiveEnrollment) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.participantPolicyLocked"),
        )
    }
    if (legacyAndroidSensorChanged && hasActiveEnrollment) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.androidSensorSettingsLocked"),
        )
    }
    if (unversionedCollectionChanged && hasActiveEnrollment) {
        throw ResponseStatusException(
            HttpStatus.CONFLICT,
            Messages.get("error.study.unversionedSettingsLocked"),
        )
    }
}

private fun loadEnrollmentDisclosureActivity(
    connection: java.sql.Connection,
    studyId: UUID,
): Pair<Boolean, Boolean> = connection.prepareStatement(
        """
        SELECT
            (
                EXISTS (
                    SELECT 1
                    FROM study_participants
                    WHERE study_id = ? AND participation_status = 'ENROLLED'
                ) OR EXISTS (
                    SELECT 1
                    FROM devices
                    WHERE study_id = ?
                )
            ) AS active_enrollment,
            EXISTS (
                SELECT 1
                FROM participant_form_access_codes
                WHERE study_id = ? AND form_kind = 'ENROLLMENT'
                  AND enrollment_attempt_id IS NOT NULL
                  AND enrollment_replay_expires_at > now()
                  AND revoked_at IS NULL
            ) AS pending_enrollment
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.setObject(2, studyId)
        statement.setObject(3, studyId)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Participant-policy activity query returned no row" }
            resultSet.getBoolean(1) to resultSet.getBoolean(2)
        }
    }

internal enum class V4EnrollmentCredentialMode {
    ONE_TIME_CODE,
    LEGACY_SIGNED_REQUEST,
}

/** Distinguishes public invitation enrollment from the controlled legacy HMAC compatibility path. */
internal fun requireV4EnrollmentCredentialMode(
    authentication: Authentication?,
    enrollmentCode: String?,
    manifestDigest: String?,
    enrollmentAttemptId: String?,
    proposedApiKey: String?,
): V4EnrollmentCredentialMode {
    val publicHeaders = listOf(enrollmentCode, manifestDigest, enrollmentAttemptId, proposedApiKey)
    val hasCompletePublicCredential = publicHeaders.all { !it.isNullOrBlank() }
    val hasNoPublicCredential = publicHeaders.all { it.isNullOrBlank() }
    return when {
        hasCompletePublicCredential && authentication is MobileEnrollmentAuthenticationToken ->
            V4EnrollmentCredentialMode.ONE_TIME_CODE
        hasNoPublicCredential && authentication is MobileApiHmacAuthenticationToken ->
            V4EnrollmentCredentialMode.LEGACY_SIGNED_REQUEST
        else -> throw ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            Messages.get("error.enrollment.credentialOrDigestInvalid"),
        )
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
@Validated
@RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class StudyController @Inject constructor(
    hazelcastInstance: HazelcastInstance,
    public val storageResolver: StorageResolver,
    public val idGenerationService: HazelcastIdGenerationService,
    public val enrollmentService: EnrollmentService,
    public val studyService: StudyService,
    public val sensorDataUploadService: SensorDataUploadService,
    public val androidSensorDataUploadService: AndroidSensorDataUploadService,
    public val batteryTelemetryUploadService: BatteryTelemetryUploadService,
    public val interactionEventsUploadService: InteractionEventsUploadService,
    public val appAudioActivityUploadService: AppAudioActivityUploadService,
    public val ambientAudioUploadService: AmbientAudioUploadService,
    public val appAudioContentUploadService: AppAudioContentUploadService,
    public val notificationActivityUploadService: NotificationActivityUploadService,
    public val sleepEventsUploadService: SleepEventsUploadService,
    public val activityRecognitionEventsUploadService: ActivityRecognitionEventsUploadService,
    public val healthMetricsUploadService: HealthMetricsUploadService,
    public val connectivityStateEventsUploadService: ConnectivityStateEventsUploadService,
    public val appNetworkUsageUploadService: AppNetworkUsageUploadService,
    public val deviceSettingsUploadService: DeviceSettingsUploadService,
    public val uploadDiagnosticsUploadService: UploadDiagnosticsUploadService,
    public val encryptedPayloadUploadService: EncryptedPayloadUploadService,
    public val studyEncryptionKeyService: StudyEncryptionKeyService,
    public val appDataUploadService: AppDataUploadService,
    public val downloadService: DataDownloadService,
    public val enrollmentManager: EnrollmentManager,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    public val chronicleJobService: JobService,
    public val auditService: AuditService,
    public val studySettingsAuditService: StudySettingsAuditService,
    public val participantCollectionAcknowledgmentService: ParticipantCollectionAcknowledgmentService,
    public val studySettingsNotificationService: StudySettingsNotificationService,
    public val apiKeyService: com.openlattice.chronicle.services.apikeys.ApiKeyService,
    public val dataDeletionOrchestrator: DataDeletionOrchestrator,
    public val studyLifecycleService: StudyLifecycleService,
    public val webhookService: WebhookService,
//    private val managementApi: ManagementAPI,
) : StudyApi, AuthorizingComponent {

    private val studies = HazelcastMap.STUDIES.getMap(hazelcastInstance)

    @Inject
    private lateinit var enrollmentManifestService: EnrollmentManifestService

    private fun fireDataSubmitted(
        studyId: UUID,
        participantId: String,
        dataType: String,
        recordCount: Int,
    ) {
        if (recordCount <= 0) return
        webhookService.fireEvent(
            studyId,
            WebhookEventType.DATA_SUBMITTED,
            mapOf(
                "participantId" to participantId,
                "dataType" to dataType,
                "recordCount" to recordCount,
            ),
        )
    }

    private fun mobileUploadEnrollmentGate(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        operation: String,
        allowCollectionHaltResolution: Boolean = false,
    ) {
        val participationStatus = enrollmentManager.getParticipationStatus(studyId, participantId)
        var responseReasonKey = "error.enrollment.notEnrolled"
        val (rejectionLog, logAsError) = when {
            participationStatus != ParticipationStatus.ENROLLED ->
                "participant is not actively enrolled (status=$participationStatus)" to false
            !enrollmentManager.isKnownDatasource(studyId, participantId, deviceId) ->
                "data source not found" to true
            !allowCollectionHaltResolution && participantCollectionAcknowledgmentService.isCollectionHalted(
                studyId,
                participantId,
                deviceId,
            ) -> {
                responseReasonKey = "error.enrollment.collectionHalted"
                "required collection consent is unresolved" to false
            }
            else -> return
        }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        val dataSourceRef = LogSanitizer.stableFingerprint(deviceId.toString(), "device")
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
            action(AuditAction.DATA_SUBMISSION)
            resourceType("MobileUpload")
            studyId(studyId)
            failed(Messages.en(responseReasonKey))
            additionalData(
                mapOf(
                    "participantId" to participantId,
                    "deviceId" to deviceId.toString(),
                    "operation" to operation,
                )
            )
        }
        throw ResponseStatusException(
            HttpStatus.FORBIDDEN,
            Messages.get(responseReasonKey),
        )
    }

    private fun requireAcknowledgmentApiKey(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
    ): ApiKeyAuthenticationToken {
        val mobileKey = SecurityContextHolder.getContext().authentication as? ApiKeyAuthenticationToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.enrollment.apiKeyRequired"))
        if (mobileKey.studyId != studyId || mobileKey.participantId != participantId || mobileKey.deviceId != deviceId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.apiKeyMismatch"))
        }
        return mobileKey
    }

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyController::class.java)!!
        private val settingsAuditMapper = ObjectMappers.getJsonMapper()

        private val UPSERT_SENSOR_AVAILABILITY_SQL = """
            INSERT INTO android_device_sensor_availability
                (study_id, participant_id, device_id, available_sensors, unavailable_sensors,
                 screen_width_pixels, screen_height_pixels, screen_density_dpi, display_rotation,
                 interaction_pointer_capture_capability, reported_at)
            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, device_id)
            DO UPDATE SET
                available_sensors = EXCLUDED.available_sensors,
                unavailable_sensors = EXCLUDED.unavailable_sensors,
                screen_width_pixels = EXCLUDED.screen_width_pixels,
                screen_height_pixels = EXCLUDED.screen_height_pixels,
                screen_density_dpi = EXCLUDED.screen_density_dpi,
                display_rotation = EXCLUDED.display_rotation,
                interaction_pointer_capture_capability = EXCLUDED.interaction_pointer_capture_capability,
                reported_at = EXCLUDED.reported_at
        """.trimIndent()

        private val GET_STUDY_DEVICES_SQL = """
            SELECT participant_id, device_id, device_type, source_device
            FROM devices
            WHERE study_id = ?
        """.trimIndent()

        private val GET_STUDY_SENSOR_AVAILABILITY_SQL = """
            SELECT participant_id, device_id, available_sensors, unavailable_sensors,
                   screen_width_pixels, screen_height_pixels, screen_density_dpi, display_rotation,
                   interaction_pointer_capture_capability, reported_at
            FROM android_device_sensor_availability
            WHERE study_id = ?
        """.trimIndent()

        private val GET_IOS_UPLOAD_STATUS_SQL = """
            WITH committed AS (
                SELECT participant_id,
                       count(*)::bigint AS committed_rows,
                       max(exact_recordeddate) AS last_committed_at,
                       max(datetimeend) AS last_observation_end_at
                FROM sensor_data
                WHERE study_id = ?
                GROUP BY participant_id
            ),
            buffered AS (
                SELECT participant_id,
                       count(*)::bigint AS buffered_batches,
                       coalesce(sum(jsonb_array_length(data)), 0)::bigint AS buffered_records,
                       max(uploaded_at) AS last_buffered_upload_at
                FROM upload_buffer
                WHERE study_id = ?
                  AND upload_type = 'Ios'
                GROUP BY participant_id
            )
            SELECT coalesce(committed.participant_id, buffered.participant_id) AS participant_id,
                   coalesce(committed.committed_rows, 0) AS committed_rows,
                   committed.last_committed_at,
                   committed.last_observation_end_at,
                   coalesce(buffered.buffered_batches, 0) AS buffered_batches,
                   coalesce(buffered.buffered_records, 0) AS buffered_records,
                   buffered.last_buffered_upload_at
            FROM committed
            FULL OUTER JOIN buffered USING (participant_id)
            ORDER BY participant_id
        """.trimIndent()

        /** Binds a nullable Int to an INTEGER parameter, setting SQL NULL when the value is null. */
        private fun setNullableInt(ps: java.sql.PreparedStatement, index: Int, value: Int?) {
            if (value != null) ps.setInt(index, value) else ps.setNull(index, java.sql.Types.INTEGER)
        }
    }

    internal fun createStudyDeletionJobs(studyId: UUID, contact: String): List<ChronicleJob> {
        val eventDataSourceName = storageResolver.resolveDataSourceName(studyId)
        return StudyDeletionStorage.values().mapNotNull { storage ->
            val tables = StudyDeletionTable.values()
                .filter { it.storage == storage }
                .toSet()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            ChronicleJob(
                id = idGenerationService.getNextId(),
                contact = contact,
                definition = DeleteStudyTableData(
                    studyId = studyId,
                    tables = tables,
                    eventDataSourceName = eventDataSourceName.takeIf { storage == StudyDeletionStorage.EVENT },
                ),
            )
        }
    }

    /**
     * This call needs to be efficient as it is invoked at enrollment and everytime a phone attempts to upload data.
     *
     * Note: getStudyId could be sped up using an in-memory cache of legacy studies instead of postgres lookup
     * every time. See CHRONICLE-2.
     */
    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + SOURCE_DEVICE_ID_PATH + ENROLL_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Deprecated("Use v4 enrollV4 instead, which accepts device ID via header")
    override fun enroll(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody sourceDevice: SourceDevice,
    ): EnrollmentResponse {
        warnParticipantAction(
            "Deprecated v3 enroll called with sourceDeviceId in path",
            studyId,
            participantId
        )
        return enrollDevice(
            studyId,
            participantId,
            sourceDeviceId,
            sourceDevice,
            proposedApiKey = null,
            enrollmentAttemptId = null,
        )
    }

    private fun enrollDevice(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        sourceDevice: SourceDevice,
        proposedApiKey: String?,
        enrollmentAttemptId: UUID?,
    ): EnrollmentResponse {
        check((proposedApiKey == null) == (enrollmentAttemptId == null)) {
            "Enrollment attempt and proposed key must be supplied together"
        }
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }

        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        return try {
            val id = enrollmentService.registerDevice(realStudyId, participantId, deviceId, sourceDevice)
            studyService.updateLastDevicePing(realStudyId, participantId, sourceDevice)

            // Comprehensive audit logging for device enrollment (HIPAA: PHI access tracking)
            auditService.logWithContext {
                action(AuditAction.DEVICE_ENROLLMENT)
                resourceType("Device")
                resourceId(id)
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "deviceInfo"))
                additionalData(mapOf(
                    "participantId" to participantId,
                    "deviceId" to id.toString(),
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
            val apiKey = if (proposedApiKey == null) {
                apiKeyService.createMobileApiKey(
                    studyId = realStudyId,
                    participantId = participantId,
                    deviceId = id,
                )
            } else {
                apiKeyService.installMobileApiKey(
                    studyId = realStudyId,
                    participantId = participantId,
                    deviceId = id,
                    enrollmentAttemptId = checkNotNull(enrollmentAttemptId),
                    proposedApiKey = proposedApiKey,
                )
            }.rawKey

            EnrollmentResponse(chronicleId = id, apiKey = apiKey)
        } catch (ex: Exception) {
            // Log failed enrollment attempts for security monitoring
            auditService.logWithContext {
                action(AuditAction.DEVICE_ENROLLMENT)
                resourceType("Device")
                studyId(realStudyId)
                failed(ex.message ?: "Enrollment failed")
                additionalData(mapOf(
                    "participantId" to participantId,
                    "deviceId" to deviceId.toString()
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
        sourceDeviceId: String,
        datasource: SourceDevice,
        enrollmentCode: String?,
        manifestDigest: String?,
        enrollmentAttemptId: String?,
        proposedApiKey: String?,
    ): EnrollmentResponse {
        val mode = requireV4EnrollmentCredentialMode(
            SecurityContextHolder.getContext().authentication,
            enrollmentCode,
            manifestDigest,
            enrollmentAttemptId,
            proposedApiKey,
        )
        if (mode == V4EnrollmentCredentialMode.ONE_TIME_CODE) {
            checkNotNull(enrollmentCode)
            checkNotNull(manifestDigest)
            checkNotNull(enrollmentAttemptId)
            checkNotNull(proposedApiKey)
            if (!enrollmentManifestService.authorizeEnrollmentAttempt(
                    studyId,
                    participantId,
                    enrollmentCode,
                    manifestDigest,
                    enrollmentAttemptId,
                    sourceDeviceId,
                    datasource,
                    proposedApiKey,
                )
            ) {
                throw ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    Messages.get("error.enrollment.credentialOrDigestInvalid"),
                )
            }
            return try {
                enrollDevice(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    datasource,
                    proposedApiKey,
                    UUID.fromString(enrollmentAttemptId),
                )
            } catch (_: InvalidEnrollmentAttemptException) {
                throw ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    Messages.get("error.enrollment.credentialOrDigestInvalid"),
                )
            }
        }
        return enrollDevice(
            studyId,
            participantId,
            sourceDeviceId,
            datasource,
            proposedApiKey = null,
            enrollmentAttemptId = null,
        )
    }

    @Timed
    @PostMapping(
        path = ["", "/"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun createStudy(@Valid @RequestBody study: Study): UUID {
        ensureAuthenticated()
        val stampedStudy = stampInitialDataCollectionSettings(study)
        if (stampedStudy.settings.containsKey(StudySettingType.Sensor) && !isAdmin()) {
            throw ForbiddenException("Only admins can modify sensor types.")
        }
        stampedStudy.organizationIds.forEach { organizationId -> ensureOwnerAccess(AclKey(organizationId)) }
        logger.info("Creating study associated with organizations ${stampedStudy.organizationIds}")
        return try {
            val id = studyService.createStudy(stampedStudy)
            auditService.logWithContext {
                action(AuditAction.STUDY_CREATE)
                resourceType("Study")
                resourceId(id)
                success(true)
                additionalData(mapOf("organizationIds" to stampedStudy.organizationIds.toString()))
            }
            // Settings-audit coverage for any settings supplied at creation time
            // (before-state is empty — each provided type is a first-time config).
            recordSettingsAuditDiff(id, emptyMap(), stampedStudy.settings)
            id
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.STUDY_CREATE)
                resourceType("Study")
                failed(ex.message ?: "Study creation failed")
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
            val study = studyService.getStudy(studyId)
            recordEvent(
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
                action(AuditAction.VIEW)
                resourceType("Study")
                resourceId(studyId)
                studyId(studyId)
                success(true)
            }
            study
        } catch (ex: NoSuchElementException) {
            auditService.logWithContext {
                action(AuditAction.VIEW)
                resourceType("Study")
                studyId(studyId)
                failed(ex.message ?: "Study not found")
            }
            throw StudyNotFoundException(studyId, "No study with id $studyId found.")
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
            studies.forEach { study ->
                recordEvent(
                    AuditableEvent(
                        AclKey(study.id),
                        currentUser.id,
                        currentUser.principal,
                        eventType = AuditEventType.GET_STUDY,
                        study = study.id,
                        organization = organizationId,
                    )
                )
            }
            auditService.logWithContext {
                action(AuditAction.LIST)
                resourceType("Study")
                organizationId(organizationId)
                success(true)
                additionalData(mapOf("studyCount" to studies.size))
            }

            studies
        } catch (ex: NoSuchElementException) {
            auditService.logWithContext {
                action(AuditAction.LIST)
                resourceType("Study")
                organizationId(organizationId)
                failed(ex.message ?: "Organization not found")
            }
            throw OrganizationNotFoundException(organizationId, "No organization with id $organizationId found.")
        }

    }

    @Timed
    @RateLimit(type = RateLimitType.SENSITIVE)
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
            StudySettingType.Sensor -> ensureAdminAccess()
            else -> ensureWriteAccess(AclKey(studyId))
        }

        return try {
            val persistedUpdate = persistLockedStudySettingsMutation(studyId) { priorSettings ->
                mergeStudySetting(priorSettings, settingType, settings)
            }
            studyService.refreshStudyCache(setOf(studyId))
            recordSettingsAuditDiff(
                studyId,
                checkNotNull(persistedUpdate.priorSettings),
                checkNotNull(persistedUpdate.stampedStudy.settings),
            )

            auditService.logWithContext {
                action(AuditAction.SETTINGS_CHANGE)
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
                action(AuditAction.SETTINGS_CHANGE)
                resourceType("StudySettings")
                studyId(studyId)
                failed(ex.message ?: "Settings update failed")
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
        return participantCollectionAcknowledgmentService.getAcknowledgments(
            studyId, limit.coerceIn(1, 200), offset.coerceAtLeast(0)
        )
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
     * practice. Best-effort: an audit failure must never block the settings write.
     */
    // reason: the two continues (skip-unchanged, skip-absent-after) are the clearest expression of the diff scan; restructuring loop control is risky
    @Suppress("LoopWithTooManyJumpStatements")
    private fun recordSettingsAuditDiff(
        studyId: UUID,
        before: Map<StudySettingType, StudySetting>,
        after: Map<StudySettingType, StudySetting>,
    ) {
        try {
            val changedBy = Principals.getCurrentUser().id
            val sourceIp = try {
                com.openlattice.chronicle.audit.AuditRequestContext.getClientIpAddress()
            } catch (_: Exception) {
                null
            }
            for (settingType in (before.keys + after.keys)) {
                val beforeValue = before[settingType]
                val afterValue = after[settingType] ?: continue
                if (beforeValue != null &&
                    settingsAuditMapper.writeValueAsString(beforeValue) ==
                    settingsAuditMapper.writeValueAsString(afterValue)
                ) {
                    continue
                }
                val changeSummary = studySettingsAuditService.generateChangeSummary(
                    settingType, beforeValue, afterValue
                )
                studySettingsAuditService.recordSettingsChange(
                    studyId = studyId,
                    changedBy = changedBy,
                    sourceIp = sourceIp,
                    settingKey = settingType,
                    beforeValue = beforeValue,
                    afterValue = afterValue,
                    changeSummary = changeSummary,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to record settings audit for study {}", studyId, e)
        }
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PERMISSIONS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyPermissions(@PathVariable(STUDY_ID) studyId: UUID): StudyPermissions {
        val studyAclKey = AclKey(studyId)
        ensureOwnerAccess(studyAclKey)
        return authorizationManager.getAllSecurableObjectPermissions(studyAclKey).aces.fold(StudyPermissions()) { studyPermissions, ace ->
            if (ace.expirationDate.isAfter(OffsetDateTime.now())) {
                if (ace.permissions.containsAll(
                        EnumSet.of(
                            Permission.READ,
                            Permission.WRITE,
                            Permission.OWNER
                        )
                    )
                ) {
                    studyPermissions.owners.add(ace.principal)
                } else if (ace.permissions.containsAll(
                        EnumSet.of(
                            Permission.READ,
                            Permission.WRITE
                        )
                    )
                ) {
                    studyPermissions.managers.add(ace.principal)
                } else if (ace.permissions.containsAll(
                        EnumSet.of(
                            Permission.READ,
                        )
                    )
                ) {
                    studyPermissions.viewers.add(ace.principal)
                }
            }
            studyPermissions
        }
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PERMISSIONS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateStudyPermissions(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestBody @Valid permissionsUpdate: StudyPermissionsUpdate
    ): StudyPermissions {
        val studyAclKey = AclKey(studyId)
        ensureOwnerAccess(studyAclKey)

        val allPermissions = EnumSet.allOf(Permission::class.java)

        permissionsUpdate.revokeViewStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.removePermission(studyAclKey, p, EnumSet.of(Permission.READ, Permission.WRITE, Permission.OWNER))
        }

        permissionsUpdate.revokeManageStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.removePermission(studyAclKey, p, EnumSet.of(Permission.OWNER, Permission.WRITE))
        }

        permissionsUpdate.revokeOwnerStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.removePermission(studyAclKey, p, EnumSet.of(Permission.OWNER))
        }

        permissionsUpdate.grantViewStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.addPermission(studyAclKey, p, EnumSet.of(Permission.READ))
        }

        permissionsUpdate.grantManageStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.addPermission(studyAclKey, p, EnumSet.of(Permission.WRITE,Permission.READ))
        }

        permissionsUpdate.grantOwnerStudy.forEach {
            val p = Principal(PrincipalType.USER, it)
            authorizationManager.addPermission(studyAclKey, p, allPermissions)
        }

        return getStudyPermissions(studyId)
    }

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
        val studyAclKey = AclKey(studyId)
        ensureOwnerAccess(studyAclKey)
        if (study.settings?.containsKey(StudySettingType.Sensor) == true && !isAdmin()) {
            throw ForbiddenException("Only admins can modify sensor types.")
        }
        val currentUser = Principals.getCurrentSecurablePrincipal()
        logger.info("Updating study with id $studyId on behalf of ${currentUser.principal.id}")
        return try {
            val persistedUpdate = persistStudyUpdate(studyId, study, studyAclKey, currentUser)
            studyService.refreshStudyCache(setOf(studyId))
            persistedUpdate.stampedStudy.settings?.let { newSettings ->
                recordSettingsAuditDiff(studyId, persistedUpdate.priorSettings ?: emptyMap(), newSettings)
            }
            auditService.logWithContext {
                action(AuditAction.STUDY_UPDATE)
                resourceType("Study")
                resourceId(studyId)
                studyId(studyId)
                success(true)
            }
            if (retrieve) studyService.getStudy(studyId) else null
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.STUDY_UPDATE)
                resourceType("Study")
                studyId(studyId)
                failed(ex.message ?: "Study update failed")
            }
            throw ex
        }
    }

    private fun persistStudyUpdate(
        studyId: UUID,
        requestedStudy: StudyUpdate,
        studyAclKey: AclKey,
        currentUser: com.openlattice.chronicle.authorization.SecurablePrincipal,
    ): LockedStudyUpdate =
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<LockedStudyUpdate>(connection, auditingManager)
                .transaction { transaction ->
                    val lockedUpdate = stampDataCollectionSettingsVersionLocked(
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
                    lockedUpdate
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
            val operationId = studyLifecycleService.scheduleImmediateStudyDeletion(
                studyId = studyId,
                userId = currentUser.id.toString(),
            )
            recordEvent(
                AuditableEvent(
                    aclKey = AclKey(operationId),
                    securablePrincipalId = currentUser.id,
                    principal = currentUser.principal,
                    eventType = AuditEventType.DELETE_STUDY,
                    description = "Study entered inaccessible seven-day deletion quarantine",
                    study = studyId,
                    data = mapOf("operationId" to operationId.toString()),
                )
            )
            auditService.logWithContext {
                action(AuditAction.STUDY_DELETE)
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
                action(AuditAction.STUDY_DELETE)
                resourceType("Study")
                studyId(studyId)
                failed(ex.message ?: "Study deletion failed")
            }
            throw ex
        }
    }

    private fun buildStudyDeletionAuditEvents(
        studyId: UUID,
        currentUser: com.openlattice.chronicle.authorization.SecurablePrincipal,
        jobIds: Iterable<UUID>,
    ): List<AuditableEvent> {
        return listOf(
            AuditableEvent(
                AclKey(studyId),
                currentUser.id,
                currentUser.principal,
                AuditEventType.DELETE_STUDY,
                "",
                studyId,
                UUID(0, 0),
                mapOf()
            )
        ) + jobIds.map {
            AuditableEvent(
                AclKey(it),
                currentUser.id,
                currentUser.principal,
                AuditEventType.CREATE_JOB,
                "",
                studyId
            )
        }
    }

    private fun buildParticipantDeletionJobs(studyId: UUID, participantIds: Set<String>): List<ChronicleJob> {
        return ParticipantDeletionPlan.jobs(
            studyId = studyId,
            participantIds = participantIds,
            contact = Principals.getCurrentUser().id,
            nextJobId = idGenerationService::getNextId,
        )
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
        ensureWriteAccess(AclKey(studyId))

        return try {
            val operationIds = participantIds.map { participantId ->
                studyService.updateParticipationStatus(studyId, participantId, ParticipationStatus.NOT_ENROLLED)
                dataDeletionOrchestrator.quarantineParticipant(
                    studyId = studyId,
                    participantId = participantId,
                    mode = DataDeletionMode.WITHDRAW_AND_ERASE,
                    requestedBy = Principals.getCurrentUser().id,
                    idempotencyKey = UUID.randomUUID(),
                )
            }
            recordEvent(
                AuditableEvent(
                    AclKey(studyId),
                    eventType = AuditEventType.DELETE_PARTICIPANTS,
                    description = "Participant refs ${LogSanitizer.stableFingerprints(participantIds, "participant")} " +
                        "entered inaccessible seven-day deletion quarantine",
                    study = studyId,
                    data = mapOf("operationIds" to operationIds.map(UUID::toString)),
                )
            )
            auditService.logWithContext {
                action(AuditAction.DATA_DELETION)
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
                action(AuditAction.DATA_DELETION)
                resourceType("Participant")
                studyId(studyId)
                failed(ex.message ?: "Participant deletion failed")
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
                action(AuditAction.PARTICIPANT_ENROLL)
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
                action(AuditAction.PARTICIPANT_ENROLL)
                resourceType("Participant")
                studyId(studyId)
                failed(ex.message ?: "Participant registration failed")
            }
            throw ex
        }
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + SOURCE_DEVICE_ID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @Deprecated("Use v4 uploadSensorDataV4 instead")
    override fun uploadSensorData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<SensorDataSample>,
    ): Int {
        warnParticipantAction(
            "Deprecated v3 uploadSensorData called with sourceDeviceId in path",
            studyId,
            participantId
        )
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, deviceId, "sensor data upload")
        return try {
            val count = sensorDataUploadService.upload(studyId, participantId, deviceId, data)
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count))
            }
            fireDataSubmitted(studyId, participantId, "ios_sensor", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                failed(ex.message ?: "Sensor data upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + SOURCE_DEVICE_ID_PATH + "/screen-time"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun uploadScreenTimeData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody envelope: ScreenTimeUsageEnvelope,
    ): Int {
        validateUploadEnvelopeIdentity(
            requestStudyId = studyId,
            requestParticipantId = participantId,
            requestSourceDeviceId = sourceDeviceId,
            envelopeStudyId = envelope.studyId,
            envelopeParticipantId = envelope.participantId,
            envelopeDeviceId = envelope.deviceId,
        )
        logParticipantAction("iOS Screen Time upload received", studyId, participantId)
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, deviceId, "iOS Screen Time upload")

        return try {
            val samples = ScreenTimeUsageUploadService.toSensorDataSamples(envelope, sourceDeviceId)
            if (samples.isEmpty()) {
                return 0
            }
            val count = sensorDataUploadService.upload(studyId, participantId, deviceId, samples)
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("ScreenTimeData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "screenTimeData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "ios"))
            }
            fireDataSubmitted(studyId, participantId, "ios_screen_time", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("ScreenTimeData")
                studyId(studyId)
                failed(ex.message ?: "iOS Screen Time upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + SOURCE_DEVICE_ID_PATH + "/user-identification"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun uploadUserIdentificationData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody envelope: UserIdentificationEnvelope,
    ): Int {
        validateUploadEnvelopeIdentity(
            requestStudyId = studyId,
            requestParticipantId = participantId,
            requestSourceDeviceId = sourceDeviceId,
            envelopeStudyId = envelope.studyId,
            envelopeParticipantId = envelope.participantId,
            envelopeDeviceId = envelope.deviceId,
        )
        logParticipantAction("iOS user identification upload received", studyId, participantId)
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, deviceId, "iOS user identification upload")

        return try {
            val samples = UserIdentificationUploadService.toSensorDataSamples(envelope, sourceDeviceId)
            if (samples.isEmpty()) {
                return 0
            }
            val count = sensorDataUploadService.upload(studyId, participantId, deviceId, samples)
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("UserIdentificationData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "userIdentificationData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "ios"))
            }
            fireDataSubmitted(studyId, participantId, "ios_user_identification", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("UserIdentificationData")
                studyId(studyId)
                failed(ex.message ?: "iOS user identification upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SOURCE_DEVICE_ID_PATH + SENSORS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @Deprecated("Use v4 uploadAndroidSensorDataV4 instead")
    override fun uploadAndroidSensorData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidSensorSample>,
    ): Int {
        logParticipantAction("Android sensor data upload received", studyId, participantId)
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(studyId, participantId, deviceId, "android sensor data upload")
        return try {
            val count = androidSensorDataUploadService.upload(studyId, participantId, deviceId, data)
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorData"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "android"))
            }
            fireDataSubmitted(studyId, participantId, "android_sensor", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("SensorData")
                studyId(studyId)
                failed(ex.message ?: "Android sensor data upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SOURCE_DEVICE_ID_PATH + BATTERY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @Deprecated("Use v4 uploadBatteryTelemetryV4 instead")
    override fun uploadBatteryTelemetry(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<BatterySample>,
    ): Int {
        logParticipantAction("Battery telemetry upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "battery telemetry upload")

        return try {
            val count = batteryTelemetryUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.BATTERY_TELEMETRY_UPLOAD)
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
            fireDataSubmitted(realStudyId, participantId, "battery_telemetry", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                failed(ex.message ?: "Battery telemetry upload failed")
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
        sourceDeviceId: String,
        data: List<IosBatterySample>,
    ): Int {
        logParticipantAction("iOS battery telemetry upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "iOS battery telemetry upload")

        return try {
            val count = batteryTelemetryUploadService.uploadIos(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.BATTERY_TELEMETRY_UPLOAD)
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
                action(AuditAction.BATTERY_TELEMETRY_UPLOAD)
                resourceType("BatteryTelemetry")
                studyId(realStudyId)
                failed(ex.message ?: "iOS battery telemetry upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @Deprecated("Use v4 uploadInteractionEventsV4 instead")
    override fun uploadInteractionEvents(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidInteractionEvent>,
    ): Int {
        logParticipantAction("Interaction events upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "interaction events upload")

        return try {
            val count = interactionEventsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.INTERACTION_EVENTS_UPLOAD)
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
            fireDataSubmitted(realStudyId, participantId, "interaction_events", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.INTERACTION_EVENTS_UPLOAD)
                resourceType("InteractionEvents")
                studyId(realStudyId)
                failed(ex.message ?: "Interaction events upload failed")
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
        sourceDeviceId: String,
        data: List<AndroidAudioActivityEvent>,
    ): Int {
        logParticipantAction("Audio activity upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "audio activity upload")

        return try {
            val count = appAudioActivityUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
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
                        "module" to "audio_activity"
                    )
                )
            }
            fireDataSubmitted(realStudyId, participantId, "audio_activity", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AudioActivity")
                studyId(realStudyId)
                failed(ex.message ?: "Audio activity upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "audio_activity"))
            }
            throw ex
        }
    }

    /**
     * Persists on-device sound-classification labels uploaded by the `ambient_audio` module
     * (currently iOS SoundAnalysis). The logic method; the v4 HTTP path is mapped by
     * [StudyV4Controller] (mirroring the collection-ack / interaction split), so this method
     * carries no Spring `@PostMapping`. Gated on enrollment + known datasource like the other
     * participant writes.
     */
    public fun uploadAmbientAudio(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        data: List<AmbientAudioClassificationEvent>,
        platform: String = "ios",
    ): Int {
        logParticipantAction("Ambient audio upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "ambient audio upload")

        return try {
            val count = ambientAudioUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AmbientAudio")
                studyId(realStudyId)
                success(true)
                // Ambient audio is AMBIENT_AUDIO_CONTEXT-class data — labels-only by
                // construction (sound classified on device, audio discarded at the classifier
                // boundary; see AmbientAudioClassificationEvent doc) — so accessedPHI stays false.
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to platform,
                        "module" to "ambient_audio"
                    )
                )
            }
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AmbientAudio")
                studyId(realStudyId)
                failed(ex.message ?: "Ambient audio upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "ambient_audio"))
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
        sourceDeviceId: String,
        data: List<AndroidAudioContentEvent>,
    ): Int {
        logParticipantAction("Audio content upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "audio content upload")

        return try {
            val count = appAudioContentUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
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
                        "module" to "audio_content"
                    )
                )
            }
            fireDataSubmitted(realStudyId, participantId, "audio_content", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AudioContent")
                studyId(realStudyId)
                failed(ex.message ?: "Audio content upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "audio_content"))
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
        sourceDeviceId: String,
        data: List<AndroidNotificationActivityEvent>,
    ): Int {
        logParticipantAction("Notification activity upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "notification activity upload")

        return try {
            val count = notificationActivityUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
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
                        "module" to "notification_activity"
                    )
                )
            }
            fireDataSubmitted(realStudyId, participantId, "notification_activity", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("NotificationActivity")
                studyId(realStudyId)
                failed(ex.message ?: "Notification activity upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "notification_activity"))
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
        sourceDeviceId: String,
        data: List<AndroidSleepEvent>,
    ): Int {
        logParticipantAction("Sleep events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "sleep events upload")

        return try {
            val count = sleepEventsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("SleepEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to "android", "module" to "sleep"))
            }
            fireDataSubmitted(realStudyId, participantId, "sleep_events", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("SleepEvents")
                studyId(realStudyId)
                failed(ex.message ?: "Sleep events upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "sleep"))
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
        sourceDeviceId: String,
        data: List<AndroidActivityRecognitionEvent>,
    ): Int {
        logParticipantAction("Activity recognition events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "activity recognition events upload")

        return try {
            val count = activityRecognitionEventsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("ActivityRecognitionEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to "activity_recognition"
                ))
            }
            fireDataSubmitted(realStudyId, participantId, "activity_recognition", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("ActivityRecognitionEvents")
                studyId(realStudyId)
                failed(ex.message ?: "Activity recognition events upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "activity_recognition"))
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
        sourceDeviceId: String,
        data: List<AndroidHealthMetricEvent>,
        platform: String = "android",
    ): Int {
        logParticipantAction("Health metrics upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "health metrics upload")

        return try {
            val count = healthMetricsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("HealthMetrics")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf("participantId" to participantId, "recordCount" to count, "platform" to platform, "module" to "health_connect"))
            }
            fireDataSubmitted(realStudyId, participantId, "health_metrics", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("HealthMetrics")
                studyId(realStudyId)
                failed(ex.message ?: "Health metrics upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "health_connect"))
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
        sourceDeviceId: String,
        data: List<AndroidConnectivityStateEvent>,
        platform: String = "android",
    ): Int {
        logParticipantAction("Connectivity state events upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "connectivity state events upload")

        return try {
            val count = connectivityStateEventsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("ConnectivityStateEvents")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to platform, "module" to "connectivity_state"
                ))
            }
            fireDataSubmitted(realStudyId, participantId, "connectivity_state", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("ConnectivityStateEvents")
                studyId(realStudyId)
                failed(ex.message ?: "Connectivity state events upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "connectivity_state"))
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
        sourceDeviceId: String,
        data: List<AndroidAppNetworkUsageEvent>,
    ): Int {
        logParticipantAction("App network usage upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "app network usage upload")

        return try {
            val count = appNetworkUsageUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AppNetworkUsage")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to "app_network_usage"
                ))
            }
            fireDataSubmitted(realStudyId, participantId, "app_network_usage", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("AppNetworkUsage")
                studyId(realStudyId)
                failed(ex.message ?: "App network usage upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "app_network_usage"))
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
        sourceDeviceId: String,
        data: List<AndroidDeviceSettingsEvent>,
    ): Int {
        logParticipantAction("Device settings upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "device settings upload")

        return try {
            val count = deviceSettingsUploadService.upload(realStudyId, participantId, data)
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("DeviceSettings")
                studyId(realStudyId)
                success(true)
                additionalData(mapOf(
                    "participantId" to participantId, "recordCount" to count,
                    "platform" to "android", "module" to "device_settings"
                ))
            }
            fireDataSubmitted(realStudyId, participantId, "device_settings", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("DeviceSettings")
                studyId(realStudyId)
                failed(ex.message ?: "Device settings upload failed")
                additionalData(mapOf("participantId" to participantId, "module" to "device_settings"))
            }
            throw ex
        }
    }

    /** Stores bounded, redacted upload failures after the exact enrolled server recovers. */
    public fun uploadDiagnostics(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        data: List<AndroidUploadDiagnosticEvent>,
    ): List<String> {
        logParticipantAction("Upload diagnostics received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "upload diagnostics")

        return uploadDiagnosticsUploadService.upload(realStudyId, participantId, deviceId, data)
    }

    /**
     * Records a participant's on-device acknowledgment of one or more newly-enabled
     * collection modules (collection loop closure design §5.2). This is the logic
     * method; the v4 HTTP path is mapped by [StudyV4Controller] (mirroring the
     * enroll / enrollV4 split), so this method carries no Spring `@PostMapping`.
     *
     * Gated on enrollment + known datasource like the other android writes; the
     * `X-Api-Key` device auth is already enforced by the mobile API filter. The
     * acknowledgment trail is append-only (V26) and the server stamps its own
     * authoritative receipt time — the body's `acknowledgedAt` is advisory.
     */
    override fun reportCollectionAcknowledgmentV4(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        acknowledgment: CollectionAcknowledgment,
    ): OK {
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(
            realStudyId,
            participantId,
            deviceId,
            "collection acknowledgment",
            allowCollectionHaltResolution = true,
        )
        val mobileKey = requireAcknowledgmentApiKey(realStudyId, participantId, deviceId)

        return try {
            val entry = participantCollectionAcknowledgmentService.recordAcknowledgment(
                studyId = realStudyId,
                participantId = participantId,
                sourceDeviceId = sourceDeviceId,
                apiKeyId = mobileKey.keyId,
                acknowledgment = acknowledgment,
            )
            auditService.logWithContext {
                action(AuditAction.COLLECTION_ACKNOWLEDGMENT)
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
                        "deviceId" to deviceId.toString(),
                    )
                )
            }
            OK("Recorded collection acknowledgment ${entry.id}")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.COLLECTION_ACKNOWLEDGMENT)
                resourceType("CollectionAcknowledgment")
                studyId(realStudyId)
                failed(ex.message ?: "Collection acknowledgment failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    /**
     * Stores a batch of envelope-encrypted payloads (HIPAA-2028 W2). The logic method;
     * the v4 HTTP path is mapped by [StudyV4Controller] (mirroring the collection-ack
     * split), so this method carries no Spring `@PostMapping`. Gated on enrollment +
     * known datasource like the other android writes; the server stores ciphertext blind.
     */
    override fun uploadAndroidEncryptedDataV4(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
        data: List<EncryptedEnvelope>,
    ): Int {
        throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            Messages.get("error.encryption.uploadDisabled"),
        )
        @Suppress("UNREACHABLE_CODE")
        logParticipantAction("Encrypted payload upload received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)

        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "encrypted payload upload")

        return try {
            val count = encryptedPayloadUploadService.upload(realStudyId, participantId, deviceId, data)
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("EncryptedPayload")
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "encryptedPayload"))
                additionalData(
                    mapOf(
                        "participantId" to participantId,
                        "recordCount" to count,
                        "platform" to "android",
                        "encryption" to "envelope",
                    )
                )
            }
            fireDataSubmitted(realStudyId, participantId, "encrypted_payload", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SENSOR_DATA_UPLOAD)
                resourceType("EncryptedPayload")
                studyId(realStudyId)
                failed(ex.message ?: "Encrypted payload upload failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    /**
     * Admin op: provision (or rotate) the study's envelope-encryption keypair. Generates
     * an RSA-4096 key, stores the private key in the [studyEncryptionKeyService]'s key
     * store (Vault in production), and persists the public-only [StudyEncryptionSetting]
     * into the study settings so devices fetch it via the settings-by-type endpoint.
     * Returns the public setting; the private key is never returned. Backend-only
     * (operator curl) — not a frontend surface.
     */
    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + "/encryption/provision"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun provisionStudyEncryption(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): StudyEncryptionSetting {
        ensureAuthenticated()
        ensureOwnerAccess(AclKey(studyId))

        throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            Messages.get("error.encryption.studySettingDisabled"),
        )

        @Suppress("UNREACHABLE_CODE")
        val setting = studyEncryptionKeyService.provision(studyId)

        val persistedUpdate = persistLockedStudySettingsMutation(studyId) { priorSettings ->
            StudySettings(priorSettings + (StudySettingType.Encryption to setting))
        }
        studyService.refreshStudyCache(setOf(studyId))
        recordSettingsAuditDiff(
            studyId,
            checkNotNull(persistedUpdate.priorSettings),
            checkNotNull(persistedUpdate.stampedStudy.settings),
        )
        return setting
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH
            + ANDROID_PATH + SOURCE_DEVICE_ID_PATH + SENSORS_PATH + AVAILABILITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun reportAndroidSensorAvailability(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) sourceDeviceId: String,
        @Valid @RequestBody availability: AndroidDeviceSensorAvailability,
    ): Int {
        logParticipantAction("Android sensor availability report received", studyId, participantId)
        validateParticipantId(participantId)
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }

        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, sourceDeviceId)
        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "sensor availability report")

        return try {
            val result = storageResolver.getPlatformStorage().connection.use { conn ->
                conn.prepareStatement(UPSERT_SENSOR_AVAILABILITY_SQL).use { ps ->
                    var index = 0
                    ps.setObject(++index, realStudyId)
                    ps.setString(++index, participantId)
                    ps.setString(++index, deviceId.toString())
                    ps.setArray(++index, conn.createArrayOf("text",
                        availability.availableSensors.map { it.name }.toTypedArray()))
                    ps.setArray(++index, conn.createArrayOf("text",
                        availability.unavailableSensors.map { it.name }.toTypedArray()))
                    setNullableInt(ps, ++index, availability.screenWidthPixels)
                    setNullableInt(ps, ++index, availability.screenHeightPixels)
                    setNullableInt(ps, ++index, availability.screenDensityDpi)
                    setNullableInt(ps, ++index, availability.displayRotation)
                    ps.setString(++index, availability.interactionPointerCaptureCapability?.name)
                    ps.executeUpdate()
                }
            }
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("SensorData")
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "sensorAvailability"))
                additionalData(mapOf("participantId" to participantId, "deviceId" to deviceId.toString()))
            }
            result
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.DATA_SUBMISSION)
                resourceType("SensorData")
                studyId(realStudyId)
                failed(ex.message ?: "Sensor availability report failed")
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
    override fun getStudyDevices(@PathVariable(STUDY_ID) studyId: UUID): Map<String, List<Map<String, Any>>> {
        ensureReadAccess(AclKey(studyId))
        return try {
            val result = loadStudyDevices(studyId)
            auditService.logWithContext {
                action(AuditAction.PARTICIPANT_DATA_ACCESS)
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
                action(AuditAction.PARTICIPANT_DATA_ACCESS)
                resourceType("Device")
                studyId(studyId)
                failed(ex.message ?: "Get study devices failed")
            }
            throw ex
        }
    }

    private fun loadStudyDevices(studyId: UUID): Map<String, List<Map<String, Any>>> {
        val result = mutableMapOf<String, MutableList<Map<String, Any>>>()
        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(GET_STUDY_DEVICES_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val participantId = rs.getString("participant_id")
                    val deviceInfo = mapOf<String, Any>(
                        "deviceId" to rs.getObject("device_id", UUID::class.java),
                        "deviceType" to (rs.getString("device_type") ?: ""),
                        "sourceDevice" to (rs.getString("source_device") ?: "{}")
                    )
                    result.getOrPut(participantId) { mutableListOf() }.add(deviceInfo)
                }
            }
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
                action(AuditAction.PARTICIPANT_DATA_ACCESS)
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
                action(AuditAction.PARTICIPANT_DATA_ACCESS)
                resourceType("SensorData")
                studyId(studyId)
                failed(ex.message ?: "Get sensor availability failed")
            }
            throw ex
        }
    }

    private fun loadStudySensorAvailability(studyId: UUID): List<AndroidDeviceSensorAvailability> {
        val result = mutableListOf<AndroidDeviceSensorAvailability>()
        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(GET_STUDY_SENSOR_AVAILABILITY_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    result.add(readSensorAvailabilityRow(rs))
                }
            }
        }
        return result
    }

    private fun readSensorAvailabilityRow(rs: java.sql.ResultSet): AndroidDeviceSensorAvailability {
        val availableArray = rs.getArray("available_sensors")?.array as? Array<*> ?: emptyArray<String>()
        val unavailableArray = rs.getArray("unavailable_sensors")?.array as? Array<*> ?: emptyArray<String>()
        return AndroidDeviceSensorAvailability(
            participantId = rs.getString("participant_id"),
            deviceId = rs.getString("device_id"),
            availableSensors = availableArray.mapNotNull { name ->
                try { AndroidSensorType.valueOf(name.toString()) } catch (_: Exception) { null }
            }.toSet(),
            unavailableSensors = unavailableArray.mapNotNull { name ->
                try { AndroidSensorType.valueOf(name.toString()) } catch (_: Exception) { null }
            }.toSet(),
            screenWidthPixels = rs.getObject("screen_width_pixels") as? Int,
            screenHeightPixels = rs.getObject("screen_height_pixels") as? Int,
            screenDensityDpi = rs.getObject("screen_density_dpi") as? Int,
            displayRotation = rs.getObject("display_rotation") as? Int,
            interactionPointerCaptureCapability = rs.getString("interaction_pointer_capture_capability")
                ?.let { value -> runCatching { InteractionPointerCaptureCapability.valueOf(value) }.getOrNull() },
            reportedAt = rs.getObject("reported_at", java.time.OffsetDateTime::class.java)
        )
    }

    override fun getAndroidSensorSettings(
        @PathVariable(STUDY_ID) studyId: UUID,
    ): AndroidSensorSetting {
        // Fetch sensor settings directly without going through getStudySettings(),
        // which now requires authentication. This endpoint remains public for mobile clients.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val settings = studyService.getStudySettings(realStudyId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("StudySettings")
            studyId(realStudyId)
            success(true)
            additionalData(mapOf("endpoint" to "getAndroidSensorSettings"))
        }
        return settings[StudySettingType.AndroidSensor] as? AndroidSensorSetting
            ?: AndroidSensorSetting.NO_SENSORS
    }

    @Timed
    @PutMapping(
        path = [STUDY_ID_PATH + DATA_COLLECTION],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun setChronicleDataCollectionSettings(
        @PathVariable(STUDY_ID) studyId: UUID,
        @Valid @RequestBody dataCollectionSettings: ChronicleDataCollectionSettings,
    ): OK {
        ensureValidStudy(studyId)
        ensureWriteAccess(AclKey(studyId))

        return try {
            val persistedUpdate = persistLockedStudySettingsMutation(studyId) { priorSettings ->
                mergeLegacyDataCollectionSettings(priorSettings, dataCollectionSettings)
            }
            studyService.refreshStudyCache(setOf(studyId))
            recordSettingsAuditDiff(
                studyId,
                checkNotNull(persistedUpdate.priorSettings),
                checkNotNull(persistedUpdate.stampedStudy.settings),
            )
            auditService.logWithContext {
                action(AuditAction.SETTINGS_CHANGE)
                resourceType("StudySettings")
                resourceId(studyId)
                studyId(studyId)
                success(true)
                additionalData(mapOf("settingType" to "DataCollection"))
            }
            OK()
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.SETTINGS_CHANGE)
                resourceType("StudySettings")
                studyId(studyId)
                failed(ex.message ?: "Data collection settings update failed")
            }
            throw ex
        }
    }

    private fun persistLockedStudySettingsMutation(
        studyId: UUID,
        mutation: (StudySettings) -> StudySettings,
    ): LockedStudyUpdate = storageResolver.getPlatformStorage().connection.use { connection ->
        AuditedTransactionBuilder<LockedStudyUpdate>(connection, auditingManager)
            .transaction { transaction ->
                val priorSettings = loadLockedStudySettings(transaction, studyId)
                val requestedStudy = StudyUpdate(settings = mutation(priorSettings))
                val lockedUpdate = LockedStudyUpdate(
                    priorSettings,
                    stampDataCollectionSettingsVersion(priorSettings, requestedStudy),
                )
                ensureParticipantPolicyMutable(
                    transaction,
                    studyId,
                    priorSettings,
                    checkNotNull(lockedUpdate.stampedStudy.settings),
                )
                studyService.updateStudy(transaction, studyId, lockedUpdate.stampedStudy)
                lockedUpdate
            }
            .audit { _ ->
                listOf(
                    AuditableEvent(
                        aclKey = AclKey(studyId),
                        eventType = AuditEventType.UPDATE_STUDY_SETTINGS,
                    ),
                )
            }
            .buildAndRun()
    }

    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SOURCE_DEVICE_ID_PATH]
    )
    @Deprecated("Use v4 uploadAndroidUsageEventDataV4 instead")
    override fun uploadAndroidUsageEventData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @PathVariable(SOURCE_DEVICE_ID) datasourceId: String,
        @Valid @RequestBody data: ChronicleData,
    ): Int {
        logParticipantAction("Android usage event upload received", studyId, participantId)
        validateParticipantId(participantId)
        // Legacy study ID resolution is needed as long as there are enrolled participants in legacy studies.
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, datasourceId)
        mobileUploadEnrollmentGate(realStudyId, participantId, deviceId, "android usage event upload")
        return try {
            val count = data.groupBy { it.javaClass }.map { (clazz, dataByClass) ->
                when (clazz) {
                    ChronicleUsageEvent::class.java -> appDataUploadService.uploadAndroidUsageEvents(
                        realStudyId,
                        participantId,
                        deviceId,
                        dataByClass.map { it as ChronicleUsageEvent })

                    else -> 0
                }
            }.sum()
            auditService.logWithContext {
                action(AuditAction.USAGE_DATA_UPLOAD)
                resourceType("UsageData")
                studyId(realStudyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "usageEvents"))
                additionalData(mapOf("participantId" to participantId, "recordCount" to count))
            }
            fireDataSubmitted(realStudyId, participantId, "android_usage_events", count)
            count
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.USAGE_DATA_UPLOAD)
                resourceType("UsageData")
                studyId(realStudyId)
                failed(ex.message ?: "Usage event data upload failed")
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
        // T-27: Full settings endpoint now requires authentication.
        // Mobile clients should use getAndroidSensorSettings() or getStudySensors() instead,
        // which return only the minimal sensor config needed for enrollment.
        ensureAuthenticated()
        ensureReadAccess(AclKey(studyId))
        val realStudyId = studyService.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val settings = studyService.getStudySettings(realStudyId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
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
        when (settingsKey) {
            // T-27: Sensor and AndroidSensor are the only setting types that mobile
            // clients need without authentication. Everything else requires read access.
            //
            // Phase 9A: DataCollection joins the mobile-public set. The generalized
            // AndroidDataCollectionSetting (chronicle-models §1B.2) carries no apiKey,
            // signing secret, or participantId — it is pure, study-scoped collection
            // configuration, exactly like AndroidSensor — so a current/updated mobile
            // client may read it without authentication. The read stays study-scoped
            // and RLS-enforced via ensureValidStudy; this is an explicit decision, not
            // an accidental relaxation, and mirrors the existing AndroidSensor path.
            StudySettingType.Sensor,
            StudySettingType.AndroidSensor,
            StudySettingType.DataCollection,
            // W2: the study encryption setting carries ONLY the public key (no private key,
            // apiKey, signing secret, or participantId), and the device must fetch it
            // pre-enrollment to seal its uploads — so it joins the mobile-public set,
            // study-scoped and RLS-enforced via ensureValidStudy, exactly like DataCollection.
            StudySettingType.Encryption -> ensureValidStudy(studyId)
            else -> ensureReadAccess(AclKey(studyId))
        }
        val settings = studyService.getStudySettings(studyId)
        val setting = when (settingsKey) {
            StudySettingType.AndroidSensor -> settings[settingsKey] ?: AndroidSensorSetting.NO_SENSORS
            StudySettingType.Sensor -> settings[settingsKey] ?: SensorSetting.NO_SENSORS
            // Phase 9A: generalized DataCollection read with the legacy fallback chain
            // (design §1B.4): stored DataCollection setting → derived from legacy
            // AndroidSensor → safe coded defaults. A missing setting never throws and
            // never silently enables a privacy-sensitive module — fromLegacy() only
            // ever enables hardware_sensors, and only when AndroidSensor has sensors.
            StudySettingType.DataCollection -> settings[settingsKey] as? AndroidDataCollectionSetting
                ?: AndroidDataCollectionSetting.fromLegacy(
                    settings[StudySettingType.AndroidSensor] as? AndroidSensorSetting
                )
            // W2: an un-provisioned study returns a disabled default (device → plaintext),
            // never throws. The setting carries only the public key.
            StudySettingType.Encryption -> settings[settingsKey] as? StudyEncryptionSetting
                ?: StudyEncryptionSetting()
            else -> settings.getValue(settingsKey)
        }
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("StudySettings")
            studyId(studyId)
            success(true)
            additionalData(mapOf("settingType" to settingsKey.name))
        }
        return setting
    }

    @Deprecated("Prefer getStudySetting, this is left in for app compat.")
    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + SETTINGS_PATH + SENSORS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudySensors(@PathVariable(STUDY_ID) studyId: UUID): Set<SensorType> {
        val sensors = studyService.getStudySensors(studyId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("StudySettings")
            studyId(studyId)
            success(true)
            additionalData(mapOf("endpoint" to "getStudySensors"))
        }
        return sensors
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
        val participants = studyService.getStudyParticipants(studyId, safeLimit, safeOffset)
        auditService.logWithContext {
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
            resourceType("Participant")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantData"))
            additionalData(mapOf("participantCount" to participants.count()))
        }
        return participants
    }

    @Timed
    @GetMapping(
        path = ["", "/"],
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
        // Cap the number of study IDs to prevent unbounded Hazelcast getAll().
        // Users with access to thousands of studies would otherwise force full materialization.
        val studyIds = studyAclKeys.mapTo(mutableSetOf()) { it.first() }
        val cappedStudyIds = if (studyIds.size > PaginationDefaults.MAX_BULK_IDS) {
            studyIds.take(PaginationDefaults.MAX_BULK_IDS).toMutableSet()
        } else studyIds
        val studies = studyService.getStudies(cappedStudyIds)

        auditingManager.recordEvents(studies.map {
            AuditableEvent(
                aclKey = AclKey(it.id),
                eventType = AuditEventType.GET_ALL_STUDIES,
                study = it.id,
                description = "Loaded all accessible studies."
            )
        })

        auditService.logWithContext {
            action(AuditAction.LIST)
            resourceType("Study")
            success(true)
            additionalData(mapOf("studyCount" to studies.count()))
        }

        // Pagination applied in-memory because getAllStudies fetches from Hazelcast cache
        // (studies.getAll), which does not support SQL LIMIT/OFFSET.
        return studies.toList().drop(safeOffset).take(safeLimit)
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
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
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
    override fun getIosUploadStatus(@PathVariable(STUDY_ID) studyId: UUID): Map<String, IosUploadStatus> {
        ensureReadAccess(AclKey(studyId))
        val status = loadIosUploadStatus(studyId)
        auditService.logWithContext {
            action(AuditAction.PARTICIPANT_DATA_ACCESS)
            resourceType("SensorData")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "iosUploadStatus"))
            additionalData(mapOf("participantCount" to status.size))
        }
        return status
    }

    private fun loadIosUploadStatus(studyId: UUID): Map<String, IosUploadStatus> {
        val result = linkedMapOf<String, IosUploadStatus>()
        storageResolver.getPlatformStorage().connection.use { conn ->
            conn.prepareStatement(GET_IOS_UPLOAD_STATUS_SQL).use { ps ->
                ps.setObject(1, studyId.toString())
                ps.setObject(2, studyId)
                ps.executeQuery().use { rs -> readIosUploadStatusRows(rs, result) }
            }
        }
        return result
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
                bufferedBatches = rs.getLong("buffered_batches"),
                bufferedRecords = rs.getLong("buffered_records"),
                lastBufferedUploadAt = rs.getObject("last_buffered_upload_at", OffsetDateTime::class.java),
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

    private fun warnParticipantAction(message: String, studyId: UUID, participantId: String) {
        logger.warn(
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
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>> {
        ensureReadAccess(AclKey(studyId))
        return when (dataType) {
            ParticipantDataType.Preprocessed -> downloadService.getPreprocessedUsageEventsData(
                studyId,
                participantIds,
                startDateTime,
                endDateTime
            )

            ParticipantDataType.AppUsageSurvey -> downloadService.getParticipantsAppUsageSurveyData(
                studyId,
                participantIds,
                startDateTime,
                endDateTime
            )

            ParticipantDataType.IOSSensor -> {
                @Suppress("DEPRECATION") val sensors = getStudySensors(studyId)
                downloadService.getParticipantsSensorData(studyId, participantIds, sensors, startDateTime, endDateTime)
            }

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
            ParticipantDataType.DeviceSettings -> downloadService.getParticipantsCollectionData(
                studyId,
                participantIds,
                dataType,
                startDateTime,
                endDateTime,
            )
        }
    }

    private fun getParticipantsDataWithSensorFilter(
        studyId: UUID,
        dataType: ParticipantDataType,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        sensorTypes: Set<String>?
    ): Iterable<Map<String, Any>> {
        if (dataType == ParticipantDataType.AndroidSensor && !sensorTypes.isNullOrEmpty()) {
            ensureReadAccess(AclKey(studyId))
            return downloadService.getParticipantsAndroidSensorData(
                studyId, participantIds, startDateTime, endDateTime, sensorTypes
            )
        }
        return getParticipantsData(studyId, dataType, participantIds, startDateTime, endDateTime)
    }

    @PatchMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + STATUS_PATH]
    )
    override fun updateParticipationStatus(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestParam(PARTICIPATION_STATUS) participationStatus: ParticipationStatus,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return try {
            studyService.updateParticipationStatus(studyId, participantId, participationStatus)
            auditService.logWithContext {
                action(AuditAction.PARTICIPANT_WITHDRAW)
                resourceType("Participant")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "participationStatus"))
                additionalData(mapOf("participantId" to participantId, "newStatus" to participationStatus.name))
            }
            OK("Successfully updated participation status ${ChronicleServerUtil.STUDY_PARTICIPANT}")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.PARTICIPANT_WITHDRAW)
                resourceType("Participant")
                studyId(studyId)
                failed(ex.message ?: "Participation status update failed")
                additionalData(mapOf("participantId" to participantId))
            }
            throw ex
        }
    }

    @PatchMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + StudyApi.ANNOTATIONS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateParticipantAnnotations(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestBody annotations: Map<String, @JvmSuppressWildcards Any?>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        return try {
            studyService.updateParticipantAnnotations(studyId, participantId, annotations)
            auditService.logWithContext {
                action(AuditAction.UPDATE)
                resourceType("Participant")
                studyId(studyId)
                success(true)
                accessedPHI(true)
                phiFields(listOf("participantId", "annotations"))
                additionalData(mapOf("participantId" to participantId))
            }
            OK("Successfully updated participant annotations ${ChronicleServerUtil.STUDY_PARTICIPANT}")
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.UPDATE)
                resourceType("Participant")
                studyId(studyId)
                failed(ex.message ?: "Participant annotation update failed")
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
    @RateLimit(type = RateLimitType.SENSITIVE, keyStrategy = RateLimitKeyStrategy.STUDY)
    public fun getParticipantsData(
        @PathVariable(STUDY_ID) studyId: UUID,
        @RequestParam(value = DATA_TYPE) dataType: ParticipantDataType,
        @RequestParam(value = PARTICIPANT_ID) @Size(max = SynchronousExportLimits.MAX_PARTICIPANTS) participantIds: Set<String>,
        @RequestParam(value = START_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startDateTime: OffsetDateTime?,
        @RequestParam(value = END_DATE) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endDateTime: OffsetDateTime?,
        @RequestParam(value = RESPONSE_TYPE, defaultValue = "csv") fileType: FileType,
        @RequestParam(value = FILE_NAME) @Size(max = 64) fileName: String?,
        @RequestParam(value = "sensorTypes", required = false) sensorTypes: Set<String>?,
        response: HttpServletResponse,
    ): Iterable<Map<String, Any>> {
        check(fileType == FileType.csv || fileType == FileType.json) { "Requested file type must be json or CSV." }
        val (boundedStart, boundedEnd) = SynchronousExportLimits.validate(
            participantIds,
            startDateTime,
            endDateTime,
        )
        val data = getParticipantsDataWithSensorFilter(
            studyId,
            dataType,
            participantIds,
            boundedStart,
            boundedEnd,
            sensorTypes
        )

        ChronicleServerUtil.setDownloadContentType(response, fileType)
        ChronicleServerUtil.setContentDisposition(
            response,
            MoreObjects.firstNonNull(
                fileName,
                "${dataType}_${
                    LocalDate.now()
                        .format(DateTimeFormatter.BASIC_ISO_DATE)
                }"
            ),
            fileType
        )

        // Legacy audit event (existing system)
        recordEvent(
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
            action(AuditAction.DOWNLOAD)
            resourceType("ParticipantData")
            studyId(studyId)
            success(true)
            accessedPHI(true)
            phiFields(listOf("participantId", "participantData"))
            additionalData(mapOf(
                "dataType" to dataType.name,
                "participantCount" to participantIds.size,
                "fileType" to fileType.name
            ))
        }

        if (fileType != FileType.json) return data

        // The legacy JSON client contract requires an array. Bound materialization
        // so a large but authorized export cannot exhaust the server heap.
        val jsonRows = data.take(SynchronousExportLimits.MAX_JSON_ROWS + 1)
        if (jsonRows.size > SynchronousExportLimits.MAX_JSON_ROWS) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                Messages.format(
                    "error.export.jsonRowLimit",
                    SynchronousExportLimits.MAX_JSON_ROWS.toString(),
                ),
            )
        }
        return jsonRows
    }

    @Timed
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + VERIFY_PATH]
    )
    override fun isKnownParticipant(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PARTICIPANT_ID) participantId: String,
    ): Boolean {
        val result = enrollmentService.isKnownParticipant(studyId, participantId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
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
        requestSourceDeviceId: String,
        envelopeStudyId: String,
        envelopeParticipantId: String,
        envelopeDeviceId: String,
    ) {
        val parsedEnvelopeStudyId = runCatching { UUID.fromString(envelopeStudyId) }.getOrNull()
        if (
            parsedEnvelopeStudyId != requestStudyId ||
            envelopeParticipantId != requestParticipantId ||
            envelopeDeviceId != requestSourceDeviceId
        ) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                Messages.get("error.enrollment.uploadIdentityMismatch"),
            )
        }
    }

}
