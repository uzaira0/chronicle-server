package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.AmbientAudioClassificationEvent
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
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sources.SourceDevice
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
import com.openlattice.chronicle.study.StudyApi.Companion.ENCRYPTED_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.SENSORS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.V4_CONTROLLER
import com.openlattice.chronicle.util.ValidParticipantId
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
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
@Validated
public open class StudyV4Controller @Inject constructor() {

    public companion object {
        // V4 sub-paths for the three new Android collection modules. Declared here (rather
        // than in the shared StudyApi contract) so the server-side endpoints are self-contained;
        // each is appended to ANDROID_PATH, mirroring INTERACTION_PATH / BATTERY_PATH.
        public const val AUDIO_ACTIVITY_PATH: String = "/audio-activity"
        public const val AUDIO_CONTENT_PATH: String = "/audio-content"
        public const val NOTIFICATION_ACTIVITY_PATH: String = "/notification-activity"

        // V4 sub-paths for the six newest Android collection modules (each appended to
        // ANDROID_PATH, mirroring INTERACTION_PATH / NOTIFICATION_ACTIVITY_PATH).
        public const val SLEEP_PATH: String = "/sleep"
        public const val ACTIVITY_RECOGNITION_PATH: String = "/activity-recognition"
        public const val HEALTH_CONNECT_PATH: String = "/health-connect"
        public const val CONNECTIVITY_STATE_PATH: String = "/connectivity-state"
        public const val APP_NETWORK_USAGE_PATH: String = "/app-network-usage"
        public const val DEVICE_SETTINGS_PATH: String = "/device-settings"
        public const val UPLOAD_DIAGNOSTICS_PATH: String = "/upload-diagnostics"

        // V4 sub-path for the ambient_audio module (iOS SoundAnalysis; appended to IOS_PATH).
        public const val AMBIENT_AUDIO_PATH: String = "/ambient-audio"
    }

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
        @RequestHeader("X-Chronicle-Enrollment-Code") enrollmentCode: String,
    ): com.openlattice.chronicle.study.EnrollmentPreviewResponse =
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
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody sourceDevice: SourceDevice,
        @RequestHeader("X-Chronicle-Enrollment-Code", required = false) enrollmentCode: String?,
        @RequestHeader("X-Chronicle-Manifest-Digest", required = false) manifestDigest: String?,
        @RequestHeader("X-Chronicle-Enrollment-Attempt-Id", required = false) enrollmentAttemptId: String?,
        @RequestHeader("X-Chronicle-Proposed-Api-Key", required = false) proposedApiKey: String?,
    ): com.openlattice.chronicle.study.EnrollmentResponse {
        return studyController.enrollV4(
            studyId,
            participantId,
            sourceDeviceId,
            sourceDevice,
            enrollmentCode,
            manifestDigest,
            enrollmentAttemptId,
            proposedApiKey,
        )
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidUsageEventDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody data: ChronicleData,
    ): Int {
        return studyController.uploadAndroidUsageEventData(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SENSORS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidSensorDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidSensorSample>,
    ): Int {
        return studyController.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + BATTERY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadBatteryTelemetryV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<BatterySample>,
    ): Int {
        return studyController.uploadBatteryTelemetry(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + INTERACTION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadInteractionEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidInteractionEvent>,
    ): Int {
        return studyController.uploadInteractionEvents(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + AUDIO_ACTIVITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAudioActivityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidAudioActivityEvent>,
    ): Int {
        return studyController.uploadAudioActivity(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + AUDIO_CONTENT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAudioContentV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidAudioContentEvent>,
    ): Int {
        return studyController.uploadAudioContent(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + NOTIFICATION_ACTIVITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadNotificationActivityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidNotificationActivityEvent>,
    ): Int {
        return studyController.uploadNotificationActivity(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SLEEP_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadSleepEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidSleepEvent>,
    ): Int {
        return studyController.uploadSleepEvents(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + ACTIVITY_RECOGNITION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadActivityRecognitionEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidActivityRecognitionEvent>,
    ): Int {
        return studyController.uploadActivityRecognitionEvents(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + HEALTH_CONNECT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadHealthMetricsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidHealthMetricEvent>,
    ): Int {
        return studyController.uploadHealthMetrics(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + CONNECTIVITY_STATE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadConnectivityStateEventsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidConnectivityStateEvent>,
    ): Int {
        return studyController.uploadConnectivityStateEvents(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + APP_NETWORK_USAGE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAppNetworkUsageV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidAppNetworkUsageEvent>,
    ): Int {
        return studyController.uploadAppNetworkUsage(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + DEVICE_SETTINGS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadDeviceSettingsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidDeviceSettingsEvent>,
    ): Int {
        return studyController.uploadDeviceSettings(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + UPLOAD_DIAGNOSTICS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadDiagnosticsV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 500) data: List<AndroidUploadDiagnosticEvent>,
    ): List<String> = studyController.uploadDiagnostics(studyId, participantId, sourceDeviceId, data)

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + SENSORS_PATH + AVAILABILITY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun reportAndroidSensorAvailabilityV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody availability: AndroidDeviceSensorAvailability,
    ): Int {
        return studyController.reportAndroidSensorAvailability(studyId, participantId, sourceDeviceId, availability)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadSensorDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<SensorDataSample>,
    ): Int {
        return studyController.uploadSensorData(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + COLLECTION_ACK_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun reportCollectionAcknowledgmentV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody acknowledgment: CollectionAcknowledgment,
    ): com.openlattice.chronicle.base.OK {
        return studyController.reportCollectionAcknowledgmentV4(studyId, participantId, sourceDeviceId, acknowledgment)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + COLLECTION_ACK_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun reportCollectionAcknowledgmentIosV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody acknowledgment: CollectionAcknowledgment,
    ): com.openlattice.chronicle.base.OK {
        // Same append-only trail and semantics as the android route; the acknowledgment
        // logic is platform-agnostic and the device row already records the platform.
        return studyController.reportCollectionAcknowledgmentV4(studyId, participantId, sourceDeviceId, acknowledgment)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + BATTERY_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosBatteryTelemetry(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<IosBatterySample>,
    ): Int {
        return studyController.uploadIosBatteryTelemetry(studyId, participantId, sourceDeviceId, data)
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + CONNECTIVITY_STATE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosConnectivityStateEvents(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidConnectivityStateEvent>,
    ): Int {
        // The sample schema is platform-neutral despite its historical Android name.
        return studyController.uploadConnectivityStateEvents(studyId, participantId, sourceDeviceId, data, platform = "ios")
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + HEALTH_CONNECT_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosHealthMetrics(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AndroidHealthMetricEvent>,
    ): Int {
        // HealthKit realization of the health_connect module; the record schema is
        // platform-neutral despite its historical Android name.
        return studyController.uploadHealthMetrics(studyId, participantId, sourceDeviceId, data, platform = "ios")
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + IOS_PATH + AMBIENT_AUDIO_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadIosAmbientAudio(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<AmbientAudioClassificationEvent>,
    ): Int {
        return studyController.uploadAmbientAudio(studyId, participantId, sourceDeviceId, data, platform = "ios")
    }

    @Timed
    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    @PostMapping(
        path = [STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH + ANDROID_PATH + ENCRYPTED_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun uploadAndroidEncryptedDataV4(
        @PathVariable(STUDY_ID) studyId: UUID,
        @ValidParticipantId @PathVariable(PARTICIPANT_ID) participantId: String,
        @RequestHeader("X-Chronicle-Device-Id") sourceDeviceId: String,
        @Valid @RequestBody @Size(max = 10_000) data: List<EncryptedEnvelope>,
    ): Int {
        return studyController.uploadAndroidEncryptedDataV4(studyId, participantId, sourceDeviceId, data)
    }
}
