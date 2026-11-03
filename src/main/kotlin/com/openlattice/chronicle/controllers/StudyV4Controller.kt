package com.openlattice.chronicle.controllers


import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
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
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.RANDOM_DEVICE_ID_HEADER
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.HEADER_ENROLLMENT_CODE
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.HEADER_MANIFEST_DIGEST
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.HEADER_ENROLLMENT_ATTEMPT_ID
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.HEADER_PROPOSED_API_KEY
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.StudyApi.Companion.AUDIO_ACTIVITY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.AUDIO_CONTENT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.NOTIFICATION_ACTIVITY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SLEEP_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ACTIVITY_RECOGNITION_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.HEALTH_CONNECT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.CONNECTIVITY_STATE_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.APP_NETWORK_USAGE_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.DEVICE_SETTINGS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.UPLOAD_DIAGNOSTICS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SCREEN_TIME_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ANDROID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ENROLL_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.ENROLLMENT_PREVIEW_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.IOS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.AVAILABILITY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.BATTERY_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.INTERACTION_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.COLLECTION_ACK_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SENSORS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.RECEIPT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.V4_CONTROLLER
import com.openlattice.chronicle.util.ValidParticipantId
import com.openlattice.chronicle.services.upload.MAX_UPLOAD_ROWS
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

// This class intentionally maps the complete versioned REST surface one endpoint per function.
@Suppress("DEPRECATION", "TooManyFunctions")
@RestController
@RequestMapping(V4_CONTROLLER)
public open class StudyV4Controller @Inject constructor() {


    @Inject
    private lateinit var studyController: StudyController

    @Timed
    @RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @GetMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLLMENT_PREVIEW_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun getEnrollmentPreviewV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(HEADER_ENROLLMENT_CODE) enrollmentCode: String,
    ): EnrollmentPreviewResponse =
        studyController.getEnrollmentPreviewV4(studyId, participantId, enrollmentCode)

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ENROLL_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    // Parameter shape is fixed by the public StudyApi enrollment wire contract.
    @Suppress("LongParameterList")
    public fun enrollV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody sourceDevice: SourceDevice,
        @RequestHeader(HEADER_ENROLLMENT_CODE, required = false) enrollmentCode: String?,
        @RequestHeader(HEADER_MANIFEST_DIGEST, required = false) manifestDigest: String?,
        @RequestHeader(HEADER_ENROLLMENT_ATTEMPT_ID, required = false) enrollmentAttemptId: String?,
        @RequestHeader(HEADER_PROPOSED_API_KEY, required = false) proposedApiKey: String?,
    ): EnrollmentResponse {
        return studyController.enrollV4(
            studyId,
            participantId,
            sourceRandomDeviceId,
            sourceDevice,
            enrollmentCode,
            manifestDigest,
            enrollmentAttemptId,
            proposedApiKey,
        )
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidUsageEventDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody data: ChronicleData,
        @RequestHeader(CollectionUploadEvidence.SETTINGS_VERSION_HEADER, required = false) settingsVersion: Int?,
        @RequestHeader(CollectionUploadEvidence.DECISION_SEQ_HEADER, required = false) decisionSeq: Long?,
    ): UploadReceipt =
        studyController.uploadAndroidUsageEventDataV4(studyId, participantId, sourceRandomDeviceId, data, settingsVersion, decisionSeq)

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SENSORS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidSensorDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidSensorSample>,
    ): Int {
        return studyController.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SENSORS_PATH + RECEIPT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidSensorDataReceiptV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidSensorSample>,
        @RequestHeader(CollectionUploadEvidence.SETTINGS_VERSION_HEADER, required = false) settingsVersion: Int?,
        @RequestHeader(CollectionUploadEvidence.DECISION_SEQ_HEADER, required = false) decisionSeq: Long?,
    ): UploadReceipt =
        studyController.uploadAndroidSensorDataReceiptV4(studyId, participantId, sourceRandomDeviceId, data, settingsVersion, decisionSeq)

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + BATTERY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadBatteryTelemetryV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<BatterySample>,
        @RequestHeader(CollectionUploadEvidence.SETTINGS_VERSION_HEADER, required = false) settingsVersion: Int?,
        @RequestHeader(CollectionUploadEvidence.DECISION_SEQ_HEADER, required = false) decisionSeq: Long?,
    ): UploadReceipt =
        studyController.uploadBatteryTelemetryV4(studyId, participantId, sourceRandomDeviceId, data, settingsVersion, decisionSeq)

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + INTERACTION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadInteractionEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidInteractionEvent>,
    ): Int {
        return studyController.uploadInteractionEventsV4(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + AUDIO_ACTIVITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAudioActivityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidAudioActivityEvent>,
    ): Int {
        return studyController.uploadAudioActivity(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + AUDIO_CONTENT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAudioContentV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidAudioContentEvent>,
    ): Int {
        return studyController.uploadAudioContent(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + NOTIFICATION_ACTIVITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadNotificationActivityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidNotificationActivityEvent>,
    ): Int {
        return studyController.uploadNotificationActivity(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SLEEP_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadSleepEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidSleepEvent>,
    ): Int {
        return studyController.uploadSleepEvents(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + ACTIVITY_RECOGNITION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadActivityRecognitionEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidActivityRecognitionEvent>,
    ): Int {
        return studyController.uploadActivityRecognitionEvents(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + HEALTH_CONNECT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadHealthMetricsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidHealthMetricEvent>,
    ): Int {
        return studyController.uploadHealthMetrics(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + CONNECTIVITY_STATE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadConnectivityStateEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidConnectivityStateEvent>,
    ): Int {
        return studyController.uploadConnectivityStateEvents(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + APP_NETWORK_USAGE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAppNetworkUsageV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidAppNetworkUsageEvent>,
    ): Int {
        return studyController.uploadAppNetworkUsage(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + DEVICE_SETTINGS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadDeviceSettingsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidDeviceSettingsEvent>,
    ): Int {
        return studyController.uploadDeviceSettings(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + UPLOAD_DIAGNOSTICS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadDiagnosticsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = UploadDiagnosticsUploadService.MAX_EVENTS) data: List<AndroidUploadDiagnosticEvent>,
    ): UploadReceipt = studyController.uploadDiagnostics(studyId, participantId, sourceRandomDeviceId, data)

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SENSORS_PATH + AVAILABILITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun reportAndroidSensorAvailabilityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody availability: AndroidDeviceSensorAvailability,
    ): Int {
        return studyController.reportAndroidSensorAvailability(studyId, participantId, sourceRandomDeviceId, availability)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + SCREEN_TIME_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadScreenTimeDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody envelope: ScreenTimeUsageEnvelope,
    ): Int = studyController.uploadScreenTimeData(studyId, participantId, sourceRandomDeviceId, envelope)

    @Timed
    @PostMapping(
        path = [
            STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + COLLECTION_ACK_PATH,
            STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + COLLECTION_ACK_PATH,
        ],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun reportCollectionAcknowledgmentV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody acknowledgment: CollectionAcknowledgment,
    ): OK {
        return studyController.reportCollectionAcknowledgmentV4(studyId, participantId, sourceRandomDeviceId, acknowledgment)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + BATTERY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosBatteryTelemetry(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<IosBatterySample>,
    ): Int {
        return studyController.uploadIosBatteryTelemetry(studyId, participantId, sourceRandomDeviceId, data)
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + CONNECTIVITY_STATE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosConnectivityStateEvents(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidConnectivityStateEvent>,
    ): Int {
        // The sample schema is platform-neutral despite its historical Android name.
        return studyController.uploadConnectivityStateEvents(studyId, participantId, sourceRandomDeviceId, data, platform = "ios")
    }

    @Timed
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + HEALTH_CONNECT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosHealthMetrics(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader(RANDOM_DEVICE_ID_HEADER) sourceRandomDeviceId: String,
        @Valid @RequestBody @Size(max = MAX_UPLOAD_ROWS) data: List<AndroidHealthMetricEvent>,
    ): Int {
        // HealthKit realization of the health_connect module; the record schema is
        // platform-neutral despite its historical Android name.
        return studyController.uploadHealthMetrics(studyId, participantId, sourceRandomDeviceId, data, platform = "ios")
    }

}
