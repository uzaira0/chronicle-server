package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.AmbientAudioClassificationEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.EnrollmentResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.UUID

/**
 * StudyV4Controller is the CURRENT mobile ingestion surface (enroll + android
 * usage/sensors/battery + iOS sensors + sensor-availability). It is a pure
 * pass-through: every v4 endpoint forwards to the injected legacy StudyController.
 *
 * Before this test the v4 surface had ZERO coverage, and 5 of its 6 endpoints are
 * absent from chronicle.yaml — so the GET-only contract fuzzer cannot reach them
 * either. The risk for a pass-through layer is mis-delegation: a copy-paste that
 * routes /battery to uploadAndroidSensorData, or that drops the X-Chronicle-Device-Id
 * header. These tests pin, per endpoint, that the request is forwarded verbatim to
 * the correct StudyController method (and to no other), and that its result is
 * returned unchanged. StudyController is mocked; the delegate's own behavior is
 * covered by StudyControllerTest and the Testcontainers e2e suite.
 */
class StudyV4ControllerTest {

    private val studyController = Mockito.mock(StudyController::class.java)
    private lateinit var controller: StudyV4Controller

    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val deviceId = "device-abc"

    @Before
    fun setUp() {
        controller = StudyV4Controller()
        // studyController is a private lateinit field populated by Spring @Inject; set it directly.
        StudyV4Controller::class.java.getDeclaredField("studyController").apply {
            isAccessible = true
            set(controller, studyController)
        }
    }

    @Test
    fun controllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun uploadDiagnosticsForwardsAuthenticatedPathIdentityAndBody() {
        val diagnostics = listOf(Mockito.mock(AndroidUploadDiagnosticEvent::class.java))
        val acknowledged = listOf(UUID.randomUUID().toString())
        Mockito.`when`(
            studyController.uploadDiagnostics(studyId, participantId, deviceId, diagnostics),
        ).thenReturn(acknowledged)

        assertSame(
            acknowledged,
            controller.uploadDiagnosticsV4(studyId, participantId, deviceId, diagnostics),
        )
        verify(studyController).uploadDiagnostics(studyId, participantId, deviceId, diagnostics)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun enrollV4ForwardsTheReplaySafeEnrollmentHeaders() {
        val device = Mockito.mock(SourceDevice::class.java)
        val expected = Mockito.mock(EnrollmentResponse::class.java)
        val enrollmentCode = "a".repeat(64)
        val manifestDigest = "b".repeat(64)
        val attemptId = UUID.randomUUID().toString()
        val proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        Mockito.`when`(
            studyController.enrollV4(
                studyId,
                participantId,
                deviceId,
                device,
                enrollmentCode,
                manifestDigest,
                attemptId,
                proposedApiKey,
            ),
        ).thenReturn(expected)

        val result = controller.enrollV4(
            studyId,
            participantId,
            deviceId,
            device,
            enrollmentCode,
            manifestDigest,
            attemptId,
            proposedApiKey,
        )

        assertSame("enrollV4 must return the delegate's response unchanged", expected, result)
        verify(studyController).enrollV4(
            studyId,
            participantId,
            deviceId,
            device,
            enrollmentCode,
            manifestDigest,
            attemptId,
            proposedApiKey,
        )
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun enrollmentPreviewForwardsTheScopedOneTimeCode() {
        val enrollmentCode = "c".repeat(64)
        val expected = Mockito.mock(EnrollmentPreviewResponse::class.java)
        Mockito.`when`(studyController.getEnrollmentPreviewV4(studyId, participantId, enrollmentCode))
            .thenReturn(expected)

        val result = controller.getEnrollmentPreviewV4(studyId, participantId, enrollmentCode)

        assertSame("preview must return the authoritative delegate response unchanged", expected, result)
        verify(studyController).getEnrollmentPreviewV4(studyId, participantId, enrollmentCode)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadAndroidUsageEventDataV4DelegatesToUsageUpload() {
        val data = Mockito.mock(ChronicleData::class.java)
        Mockito.`when`(studyController.uploadAndroidUsageEventData(studyId, participantId, deviceId, data))
            .thenReturn(11)

        val result = controller.uploadAndroidUsageEventDataV4(studyId, participantId, deviceId, data)

        assertEquals(11, result)
        verify(studyController).uploadAndroidUsageEventData(studyId, participantId, deviceId, data)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadAndroidSensorDataV4DelegatesToAndroidSensorUpload() {
        val data = listOf(Mockito.mock(AndroidSensorSample::class.java))
        Mockito.`when`(studyController.uploadAndroidSensorData(studyId, participantId, deviceId, data))
            .thenReturn(22)

        val result = controller.uploadAndroidSensorDataV4(studyId, participantId, deviceId, data)

        assertEquals(22, result)
        verify(studyController).uploadAndroidSensorData(studyId, participantId, deviceId, data)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadBatteryTelemetryV4DelegatesToBatteryUploadNotSensors() {
        // Regression guard: the battery endpoint must hit uploadBatteryTelemetry and
        // nothing else — a mis-paste to the sensor path would be caught by
        // verifyNoMoreInteractions below.
        val data = listOf(Mockito.mock(BatterySample::class.java))
        Mockito.`when`(studyController.uploadBatteryTelemetry(studyId, participantId, deviceId, data))
            .thenReturn(33)

        val result = controller.uploadBatteryTelemetryV4(studyId, participantId, deviceId, data)

        assertEquals(33, result)
        verify(studyController).uploadBatteryTelemetry(studyId, participantId, deviceId, data)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun reportAndroidSensorAvailabilityV4DelegatesToAvailability() {
        val availability = Mockito.mock(AndroidDeviceSensorAvailability::class.java)
        Mockito.`when`(studyController.reportAndroidSensorAvailability(studyId, participantId, deviceId, availability))
            .thenReturn(44)

        val result = controller.reportAndroidSensorAvailabilityV4(studyId, participantId, deviceId, availability)

        assertEquals(44, result)
        verify(studyController).reportAndroidSensorAvailability(studyId, participantId, deviceId, availability)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadIosAmbientAudioDelegatesToAmbientAudioUpload() {
        val data = listOf(Mockito.mock(AmbientAudioClassificationEvent::class.java))
        Mockito.`when`(studyController.uploadAmbientAudio(studyId, participantId, deviceId, data, "ios"))
            .thenReturn(66)

        val result = controller.uploadIosAmbientAudio(studyId, participantId, deviceId, data)

        assertEquals(66, result)
        verify(studyController).uploadAmbientAudio(studyId, participantId, deviceId, data, "ios")
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadSensorDataV4DelegatesToIosSensorUpload() {
        val data = listOf(Mockito.mock(SensorDataSample::class.java))
        Mockito.`when`(studyController.uploadSensorData(studyId, participantId, deviceId, data))
            .thenReturn(55)

        val result = controller.uploadSensorDataV4(studyId, participantId, deviceId, data)

        assertEquals(55, result)
        verify(studyController).uploadSensorData(studyId, participantId, deviceId, data)
        verifyNoMoreInteractions(studyController)
    }
}
