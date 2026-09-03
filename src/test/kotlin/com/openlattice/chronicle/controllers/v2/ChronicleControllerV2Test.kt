package com.openlattice.chronicle.controllers.v2

import com.google.common.base.Optional
import com.google.common.collect.HashMultimap
import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.controllers.kAny
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.upload.AppDataUploadManager
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.util.DeviceIdUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.security.InvalidParameterException
import java.util.UUID

/**
 * ChronicleControllerV2 is the LEGACY (#7) mobile ingestion surface, kept for older
 * Android clients that still post organization-scoped enroll/upload/status routes.
 * It is a thin pass-through: each endpoint resolves the real study id via
 * studyService.getStudyId and forwards to the injected manager (enrollment / upload /
 * surveys / study). Before this test it had ZERO coverage.
 *
 * The collaborators are private `@Inject lateinit` fields populated by Spring; we set
 * them directly via reflection (same approach as StudyV4ControllerTest). Each test
 * pins, per endpoint, that the request is forwarded to the correct manager method
 * with the resolved study id, and that the delegate's result is returned unchanged.
 * The deviceId passed to registerDevice/upload is derived by the static
 * DeviceIdUtils.deriveDeviceId, so we compute the expected value the same way.
 */
class ChronicleControllerV2Test {

    private val dataUploadManager = Mockito.mock(AppDataUploadManager::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val surveysManager = Mockito.mock(SurveysManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)

    private lateinit var controller: ChronicleControllerV2

    private val organizationId = UUID.randomUUID()
    private val studyId = UUID.randomUUID()
    private val realStudyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val datasourceId = "datasource-1"

    @Before
    fun setUp() {
        controller = ChronicleControllerV2()
        inject("dataUploadManager", dataUploadManager)
        inject("enrollmentManager", enrollmentManager)
        inject("surveysManager", surveysManager)
        inject("studyService", studyService)
        // organizationSettingsManager is injected but unused by the endpoints under test.
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(realStudyId)
    }

    private fun inject(field: String, value: Any) {
        ChronicleControllerV2::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(controller, value)
        }
    }

    @Test
    fun controllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun isRunningReturnsTrue() {
        assertTrue(controller.isRunning())
    }

    @Test
    fun enrollDelegatesToRegisterDeviceWithDerivedDeviceId() {
        val device = Mockito.mock(SourceDevice::class.java)
        val expectedDeviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, datasourceId)
        val expectedRegistrationId = UUID.randomUUID()
        Mockito.`when`(enrollmentManager.registerDevice(realStudyId, participantId, expectedDeviceId, device))
            .thenReturn(expectedRegistrationId)

        val result = controller.enroll(
            organizationId, studyId, participantId, datasourceId, Optional.of(device)
        )

        assertSame(expectedRegistrationId, result)
        // study id is resolved before delegation, and the derived device id is used
        verify(studyService).getStudyId(studyId)
        verify(enrollmentManager).registerDevice(realStudyId, participantId, expectedDeviceId, device)
        verify(studyService).updateLastDevicePing(realStudyId, participantId, device)
    }

    @Test(expected = InvalidParameterException::class)
    fun enrollWithoutSourceDeviceThrows() {
        controller.enroll(
            organizationId, studyId, participantId, datasourceId, Optional.absent()
        )
    }

    @Test
    fun uploadDelegatesToDataUploadManagerWithDerivedDeviceId() {
        val data: List<SetMultimap<UUID, Any>> = listOf(HashMultimap.create())
        val expectedDeviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, datasourceId)
        // upload has a defaulted `uploadedAt` parameter that the controller leaves to the
        // call-site default (OffsetDateTime.now()), so match it with kAny().
        Mockito.`when`(
            dataUploadManager.upload(
                kEq(realStudyId), kEq(participantId), kEq(expectedDeviceId), kAny(), kAny()
            )
        ).thenReturn(7)

        val result = controller.upload(organizationId, studyId, participantId, datasourceId, data)

        assertEquals(7, result)
        verify(dataUploadManager).upload(
            kEq(realStudyId), kEq(participantId), kEq(expectedDeviceId), kEq(data), kAny()
        )
    }

    @Test
    fun getParticipationStatusDelegatesToEnrollmentManager() {
        Mockito.`when`(enrollmentManager.getParticipationStatus(realStudyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)

        val result = controller.getParticipationStatus(organizationId, studyId, participantId)

        assertEquals(ParticipationStatus.ENROLLED, result)
        verify(enrollmentManager).getParticipationStatus(realStudyId, participantId)
    }

    @Test
    fun isNotificationsEnabledDelegatesToStudyService() {
        Mockito.`when`(studyService.isNotificationsEnabled(realStudyId)).thenReturn(true)

        val result = controller.isNotificationsEnabled(organizationId, studyId)

        assertTrue(result)
        verify(studyService).isNotificationsEnabled(realStudyId)
    }

    @Test
    fun getStudyQuestionnairesDelegatesToSurveysManager() {
        Mockito.`when`(surveysManager.getLegacyStudyQuestionnaires(organizationId, realStudyId))
            .thenReturn(emptyMap())

        val result = controller.getStudyQuestionnaires(organizationId, studyId)

        assertNotNull(result)
        verify(surveysManager).getLegacyStudyQuestionnaires(organizationId, realStudyId)
    }
}
