package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.study.StudyApi.Companion.ANDROID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.COLLECTION_ACK_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.IOS_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_ID_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.PARTICIPANT_PATH
import com.openlattice.chronicle.study.StudyApi.Companion.STUDY_ID_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.web.bind.annotation.PostMapping
import java.util.UUID

/**
 * StudyV4Controller is the CURRENT mobile ingestion surface (enroll + android
 * usage/sensors/battery + iOS sensors + sensor-availability). It is a pure
 * pass-through: every v4 endpoint forwards to the injected legacy StudyController.
 *
 * Before this test the v4 surface had ZERO coverage, and 5 of its 6 endpoints are
 * absent from chronicle.yaml — so the GET-only contract fuzzer cannot reach them
 * either. The risk for a pass-through layer is mis-delegation: a copy-paste that
 * routes /battery to uploadAndroidSensorData, or that drops the X-Chronicle-Random-Device-Id
 * header. These tests pin, per endpoint, that the request is forwarded verbatim to
 * the correct StudyController method (and to no other), and that its result is
 * returned unchanged. StudyController is mocked; the delegate's own behavior is
 * covered by StudyControllerTest and the Testcontainers e2e suite.
 */
class StudyV4ControllerTest {

    private val studyController = Mockito.mock(StudyController::class.java)
    private val applicationContext = AnnotationConfigApplicationContext()
    private lateinit var controller: StudyV4Controller

    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val randomDeviceId = "device-abc"

    @Before
    fun setUp() {
        applicationContext.beanFactory.registerSingleton("studyController", studyController)
        applicationContext.register(StudyV4Controller::class.java)
        applicationContext.refresh()
        controller = applicationContext.getBean(StudyV4Controller::class.java)
    }

    @After
    fun tearDown() {
        applicationContext.close()
    }

    

    @Test
    fun collectionAcknowledgmentHasOneHandlerForBothPlatforms() {
        val handler = StudyV4Controller::class.java.methods.single { it.name == "reportCollectionAcknowledgmentV4" }
        val participantPath = STUDY_ID_PATH + PARTICIPANT_PATH + PARTICIPANT_ID_PATH
        assertEquals(
            setOf(ANDROID_PATH, IOS_PATH).map { participantPath + it + COLLECTION_ACK_PATH }.toSet(),
            handler.getAnnotation(PostMapping::class.java).path.toSet(),
        )
    }

    @Test
    fun sensorReceiptForwardsSettingsEvidenceAndReturnsTheReceipt() {
        val samples = listOf(Mockito.mock(AndroidSensorSample::class.java))
        val receipt = UploadReceipt(setOf(UUID.randomUUID()), emptySet(), 4)
        Mockito.`when`(studyController.uploadAndroidSensorDataReceiptV4(studyId, participantId, randomDeviceId, samples, 4, 7L))
            .thenReturn(receipt)

        assertSame(receipt, controller.uploadAndroidSensorDataReceiptV4(studyId, participantId, randomDeviceId, samples, 4, 7L))
        verify(studyController).uploadAndroidSensorDataReceiptV4(studyId, participantId, randomDeviceId, samples, 4, 7L)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadDiagnosticsForwardsAuthenticatedPathIdentityAndBody() {
        val diagnostics = listOf(Mockito.mock(AndroidUploadDiagnosticEvent::class.java))
        val acknowledged = UploadReceipt(setOf(UUID.randomUUID()), emptySet(), 0)
        Mockito.`when`(
            studyController.uploadDiagnostics(studyId, participantId, randomDeviceId, diagnostics),
        ).thenReturn(acknowledged)

        assertSame(
            acknowledged,
            controller.uploadDiagnosticsV4(studyId, participantId, randomDeviceId, diagnostics),
        )
        verify(studyController).uploadDiagnostics(studyId, participantId, randomDeviceId, diagnostics)
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
                randomDeviceId,
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
            randomDeviceId,
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
            randomDeviceId,
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
        val receipt = UploadReceipt(setOf(UUID.randomUUID()), emptySet(), 1)
        Mockito.`when`(studyController.uploadAndroidUsageEventDataV4(studyId, participantId, randomDeviceId, data, 2, 7L))
            .thenReturn(receipt)

        val result = controller.uploadAndroidUsageEventDataV4(studyId, participantId, randomDeviceId, data, 2, 7L)

        assertEquals(receipt, result)
        verify(studyController).uploadAndroidUsageEventDataV4(studyId, participantId, randomDeviceId, data, 2, 7L)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadAndroidSensorDataV4DelegatesToAndroidSensorUpload() {
        val data = listOf(Mockito.mock(AndroidSensorSample::class.java))
        Mockito.`when`(studyController.uploadAndroidSensorDataV4(studyId, participantId, randomDeviceId, data))
            .thenReturn(22)

        val result = controller.uploadAndroidSensorDataV4(studyId, participantId, randomDeviceId, data)

        assertEquals(22, result)
        verify(studyController).uploadAndroidSensorDataV4(studyId, participantId, randomDeviceId, data)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun uploadBatteryTelemetryV4DelegatesToBatteryUploadNotSensors() {
        // Regression guard: the battery endpoint must hit uploadBatteryTelemetry and
        // nothing else — a mis-paste to the sensor path would be caught by
        // verifyNoMoreInteractions below.
        val data = listOf(Mockito.mock(BatterySample::class.java))
        val receipt = UploadReceipt(setOf(UUID.randomUUID()), emptySet(), 3)
        Mockito.`when`(studyController.uploadBatteryTelemetryV4(studyId, participantId, randomDeviceId, data, 3, 7L))
            .thenReturn(receipt)

        val result = controller.uploadBatteryTelemetryV4(studyId, participantId, randomDeviceId, data, 3, 7L)

        assertSame(receipt, result)
        verify(studyController).uploadBatteryTelemetryV4(studyId, participantId, randomDeviceId, data, 3, 7L)
        verifyNoMoreInteractions(studyController)
    }

    @Test
    fun reportAndroidSensorAvailabilityV4DelegatesToAvailability() {
        val availability = Mockito.mock(AndroidDeviceSensorAvailability::class.java)
        Mockito.`when`(studyController.reportAndroidSensorAvailability(studyId, participantId, randomDeviceId, availability))
            .thenReturn(44)

        val result = controller.reportAndroidSensorAvailabilityV4(studyId, participantId, randomDeviceId, availability)

        assertEquals(44, result)
        verify(studyController).reportAndroidSensorAvailability(studyId, participantId, randomDeviceId, availability)
        verifyNoMoreInteractions(studyController)
    }

}
