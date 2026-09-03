package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.audit.AuditLogEntry
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.DeviceIdUtils
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.deletion.DeleteStudyTableData
import com.openlattice.chronicle.deletion.StudyDeletionStorage
import com.openlattice.chronicle.deletion.StudyDeletionTable
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudySettingsAuditService
import com.openlattice.chronicle.services.studies.StudySettingsNotificationService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.AppDataUploadService
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
import com.openlattice.chronicle.services.crypto.StudyEncryptionKeyService
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.services.upload.ScreenTimeCaptureSource
import com.openlattice.chronicle.services.upload.ScreenTimeConfidence
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRecord
import com.openlattice.chronicle.services.upload.UserIdentificationChoice
import com.openlattice.chronicle.services.upload.UserIdentificationEnvelope
import com.openlattice.chronicle.services.upload.UserIdentificationRecord
import com.openlattice.chronicle.services.upload.UserIdentificationTrigger
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.webhooks.WebhookEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class StudyControllerTest {

    private val hazelcastInstance = Mockito.mock(HazelcastInstance::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val enrollmentService = Mockito.mock(EnrollmentService::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val sensorDataUploadService = Mockito.mock(SensorDataUploadService::class.java)
    private val androidSensorDataUploadService = Mockito.mock(AndroidSensorDataUploadService::class.java)
    private val batteryTelemetryUploadService = Mockito.mock(BatteryTelemetryUploadService::class.java)
    private val interactionEventsUploadService = Mockito.mock(InteractionEventsUploadService::class.java)
    private val appAudioActivityUploadService = Mockito.mock(AppAudioActivityUploadService::class.java)
    private val ambientAudioUploadService = Mockito.mock(AmbientAudioUploadService::class.java)
    private val appAudioContentUploadService = Mockito.mock(AppAudioContentUploadService::class.java)
    private val notificationActivityUploadService = Mockito.mock(NotificationActivityUploadService::class.java)
    private val sleepEventsUploadService = Mockito.mock(SleepEventsUploadService::class.java)
    private val activityRecognitionEventsUploadService = Mockito.mock(ActivityRecognitionEventsUploadService::class.java)
    private val healthMetricsUploadService = Mockito.mock(HealthMetricsUploadService::class.java)
    private val connectivityStateEventsUploadService = Mockito.mock(ConnectivityStateEventsUploadService::class.java)
    private val appNetworkUsageUploadService = Mockito.mock(AppNetworkUsageUploadService::class.java)
    private val deviceSettingsUploadService = Mockito.mock(DeviceSettingsUploadService::class.java)
    private val uploadDiagnosticsUploadService = Mockito.mock(UploadDiagnosticsUploadService::class.java)
    private val encryptedPayloadUploadService = Mockito.mock(EncryptedPayloadUploadService::class.java)
    private val studyEncryptionKeyService = Mockito.mock(StudyEncryptionKeyService::class.java)
    private val appDataUploadService = Mockito.mock(AppDataUploadService::class.java)
    private val downloadService = Mockito.mock(DataDownloadService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val jobService = Mockito.mock(JobService::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)
    private val studySettingsAuditService = Mockito.mock(StudySettingsAuditService::class.java)
    private val participantCollectionAcknowledgmentService =
        Mockito.mock(ParticipantCollectionAcknowledgmentService::class.java)
    private val studySettingsNotificationService = Mockito.mock(StudySettingsNotificationService::class.java)
    private val dataDeletionOrchestrator = Mockito.mock(DataDeletionOrchestrator::class.java)
    private val studyLifecycleService = Mockito.mock(StudyLifecycleService::class.java)
    private val webhookService = Mockito.mock(WebhookService::class.java)
    private val apiKeyService = Mockito.mock(com.openlattice.chronicle.services.apikeys.ApiKeyService::class.java)
    private val enrollmentManifestService = Mockito.mock(EnrollmentManifestService::class.java)

    private lateinit var controller: StudyController

    @Before
    fun setUp() {
        val mockMap = Mockito.mock(IMap::class.java) as IMap<UUID, Study>
        Mockito.`when`(hazelcastInstance.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(mockMap)

        controller = StudyController(
            hazelcastInstance, storageResolver, idGenerationService,
            enrollmentService, studyService, sensorDataUploadService,
            androidSensorDataUploadService, batteryTelemetryUploadService,
            interactionEventsUploadService,
            appAudioActivityUploadService, ambientAudioUploadService, appAudioContentUploadService,
            notificationActivityUploadService,
            sleepEventsUploadService, activityRecognitionEventsUploadService, healthMetricsUploadService,
            connectivityStateEventsUploadService, appNetworkUsageUploadService, deviceSettingsUploadService,
            uploadDiagnosticsUploadService,
            encryptedPayloadUploadService, studyEncryptionKeyService, appDataUploadService, downloadService,
            enrollmentManager, authorizationManager, auditingManager,
            jobService, auditService, studySettingsAuditService,
            participantCollectionAcknowledgmentService, studySettingsNotificationService,
            apiKeyService, dataDeletionOrchestrator, studyLifecycleService, webhookService,
        )
        StudyController::class.java.getDeclaredField("enrollmentManifestService").apply {
            isAccessible = true
            set(controller, enrollmentManifestService)
        }

        TestSecurityUtils.setupSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testCreateStudyStampsCallerDataCollectionRevisionBeforePersistenceAndAudit() {
        val studyId = UUID.randomUUID()
        val submittedSetting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            ),
            settingsVersion = 999,
        )
        val expectedSetting = submittedSetting.copy(
            settingsVersion = AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION,
        )
        val submittedStudy = Study(
            title = "server-stamped study",
            contact = "research@example.org",
            settings = StudySettings(mapOf(StudySettingType.DataCollection to submittedSetting)),
        )
        Mockito.`when`(studyService.createStudy(kAny())).thenReturn(studyId)
        Mockito.`when`(
            studySettingsAuditService.generateChangeSummary(
                StudySettingType.DataCollection,
                null,
                expectedSetting,
            ),
        ).thenReturn("Initial server-issued collection settings")

        assertEquals(studyId, controller.createStudy(submittedStudy))

        val persistedStudy = argumentCaptor<Study>().apply {
            verify(studyService).createStudy(capture())
        }.firstValue
        assertEquals(expectedSetting, persistedStudy.settings[StudySettingType.DataCollection])
        val auditInvocation = Mockito.mockingDetails(studySettingsAuditService).invocations.single {
            it.method.name == "recordSettingsChange"
        }
        assertEquals(expectedSetting, auditInvocation.arguments[5])
    }

    @Test
    fun testCreateStudyRejectsCollectionPolicyThePlayClientDoesNotEnforce() {
        val submittedStudy = Study(
            title = "unsupported collection policy",
            contact = "research@example.org",
            settings = StudySettings(
                mapOf(
                    StudySettingType.DataCollection to AndroidDataCollectionSetting(
                        modules = mapOf(
                            CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(
                                enabled = true,
                                collectionCadence = CollectionCadence(intervalSeconds = 60),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            controller.createStudy(submittedStudy)
        }

        assertTrue(error.message.orEmpty().contains("usage_events.collectionCadence is not supported"))
        verify(studyService, never()).createStudy(kAny())
    }

    private data class ReplayEnrollmentFixture(
        val studyId: UUID,
        val participantId: String,
        val sourceDeviceId: String,
        val enrollmentCode: String,
        val manifestDigest: String,
        val attemptId: UUID,
        val proposedApiKey: String,
        val deviceId: UUID,
        val sourceDevice: AndroidDevice,
    )

    private fun replayEnrollmentFixture(): ReplayEnrollmentFixture {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000321")
        val participantId = "participant-1"
        val sourceDeviceId = "device-source-1"
        return ReplayEnrollmentFixture(
            studyId = studyId,
            participantId = participantId,
            sourceDeviceId = sourceDeviceId,
            enrollmentCode = "a".repeat(64),
            manifestDigest = "b".repeat(64),
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000654"),
            proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV", // gitleaks:allow -- deterministic test credential
            deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId),
            sourceDevice = AndroidDevice(
                device = "test-device",
                model = "test-model",
                codename = "test-codename",
                brand = "test-brand",
                osVersion = "15",
                sdkVersion = "35",
                product = "test-product",
                deviceId = sourceDeviceId,
            ),
        )
    }

    private fun stubBoundEnrollment(
        fixture: ReplayEnrollmentFixture,
    ): com.openlattice.chronicle.apikey.ApiKeyCreateResponse {
        val response = Mockito.mock(com.openlattice.chronicle.apikey.ApiKeyCreateResponse::class.java)
        Mockito.`when`(
            enrollmentManifestService.authorizeEnrollmentAttempt(
                fixture.studyId,
                fixture.participantId,
                fixture.enrollmentCode,
                fixture.manifestDigest,
                fixture.attemptId.toString(),
                fixture.sourceDeviceId,
                fixture.sourceDevice,
                fixture.proposedApiKey,
            ),
        ).thenReturn(true)
        Mockito.`when`(studyService.getStudyId(fixture.studyId)).thenReturn(fixture.studyId)
        Mockito.`when`(
            enrollmentService.registerDevice(
                fixture.studyId,
                fixture.participantId,
                fixture.deviceId,
                fixture.sourceDevice,
            ),
        ).thenReturn(fixture.deviceId)
        Mockito.`when`(response.rawKey).thenReturn(fixture.proposedApiKey)
        SecurityContextHolder.getContext().authentication = MobileEnrollmentAuthenticationToken(fixture.studyId)
        return response
    }

    private fun enroll(fixture: ReplayEnrollmentFixture) = controller.enrollV4(
        fixture.studyId,
        fixture.participantId,
        fixture.sourceDeviceId,
        fixture.sourceDevice,
        fixture.enrollmentCode,
        fixture.manifestDigest,
        fixture.attemptId.toString(),
        fixture.proposedApiKey,
    )

    @Test
    fun `v4 enrollment durably binds the attempt before convergent device and key installation`() {
        val fixture = replayEnrollmentFixture()
        val apiKeyResponse = stubBoundEnrollment(fixture)
        Mockito.`when`(
            apiKeyService.installMobileApiKey(
                fixture.studyId,
                fixture.participantId,
                fixture.deviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenReturn(apiKeyResponse)

        val response = enroll(fixture)

        assertEquals(fixture.deviceId, response.chronicleId)
        assertEquals(fixture.proposedApiKey, response.apiKey)
        Mockito.inOrder(enrollmentManifestService, enrollmentService).apply {
            verify(enrollmentManifestService).authorizeEnrollmentAttempt(
                fixture.studyId,
                fixture.participantId,
                fixture.enrollmentCode,
                fixture.manifestDigest,
                fixture.attemptId.toString(),
                fixture.sourceDeviceId,
                fixture.sourceDevice,
                fixture.proposedApiKey,
            )
            verify(enrollmentService).registerDevice(
                fixture.studyId,
                fixture.participantId,
                fixture.deviceId,
                fixture.sourceDevice,
            )
        }
        verify(apiKeyService).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.deviceId,
            fixture.attemptId,
            fixture.proposedApiKey,
        )
    }

    @Test
    fun `lost response replay converges on the same device and proposed key`() {
        val fixture = replayEnrollmentFixture()
        val apiKeyResponse = stubBoundEnrollment(fixture)
        Mockito.`when`(
            apiKeyService.installMobileApiKey(
                fixture.studyId,
                fixture.participantId,
                fixture.deviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenReturn(apiKeyResponse)

        val first = enroll(fixture)
        val replay = enroll(fixture)

        assertEquals(fixture.deviceId, first.chronicleId)
        assertEquals(first, replay)
        assertEquals(fixture.proposedApiKey, replay.apiKey)
        verify(apiKeyService, Mockito.times(2)).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.deviceId,
            fixture.attemptId,
            fixture.proposedApiKey,
        )
    }

    @Test
    fun `bound enrollment recovers after device registration but before key installation completes`() {
        val fixture = replayEnrollmentFixture()
        val apiKeyResponse = stubBoundEnrollment(fixture)
        Mockito.`when`(
            apiKeyService.installMobileApiKey(
                fixture.studyId,
                fixture.participantId,
                fixture.deviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenThrow(IllegalStateException("simulated transaction failure")).thenReturn(apiKeyResponse)

        assertThrows(IllegalStateException::class.java) { enroll(fixture) }
        val recovered = enroll(fixture)

        assertEquals(fixture.deviceId, recovered.chronicleId)
        assertEquals(fixture.proposedApiKey, recovered.apiKey)
        verify(enrollmentManifestService, Mockito.times(2)).authorizeEnrollmentAttempt(
            fixture.studyId,
            fixture.participantId,
            fixture.enrollmentCode,
            fixture.manifestDigest,
            fixture.attemptId.toString(),
            fixture.sourceDeviceId,
            fixture.sourceDevice,
            fixture.proposedApiKey,
        )
        verify(enrollmentService, Mockito.times(2)).registerDevice(
            fixture.studyId,
            fixture.participantId,
            fixture.deviceId,
            fixture.sourceDevice,
        )
        verify(apiKeyService, Mockito.times(2)).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.deviceId,
            fixture.attemptId,
            fixture.proposedApiKey,
        )
    }

    @Test
    fun testCreateStudyDeletionJobsCoversAllStudyDataTables() {
        val studyId = UUID.randomUUID()
        val ids = StudyDeletionStorage.values().map { UUID.randomUUID() }
        var index = 0
        Mockito.`when`(idGenerationService.getNextId()).thenAnswer { ids[index++] }
        Mockito.`when`(storageResolver.resolveDataSourceName(studyId)).thenReturn("event-store-a")

        val jobs = controller.createStudyDeletionJobs(studyId, "local-contact")
        val definitions = jobs.map { it.definition as DeleteStudyTableData }

        assertEquals(StudyDeletionStorage.values().size, jobs.size)
        assertEquals(
            StudyDeletionTable.values().toSet(),
            definitions.flatMap { it.tables }.toSet()
        )
        assertTrue(definitions.all { it.studyId == studyId })
        assertTrue(jobs.all { it.contact == "local-contact" })

        val eventDefinition = definitions.single { definition ->
            definition.tables.all { it.storage == StudyDeletionStorage.EVENT }
        }
        assertEquals("event-store-a", eventDefinition.eventDataSourceName)
        assertTrue(definitions.single { definition ->
            definition.tables.all { it.storage == StudyDeletionStorage.PLATFORM }
        }.eventDataSourceName == null)
    }

    @Test
    fun testStudyDeletionTablesMatchParticipantPurgeCoverage() {
        assertEquals(
            listOf(
                "chronicle_usage_events",
                "chronicle_usage_stats",
                "preprocessed_usage_events",
                "sensor_data",
                "android_sensor_data",
                "battery_telemetry",
                "interaction_events",
                "app_audio_activity",
                "app_audio_content",
                "ambient_audio_events",
                "notification_activity",
                "sleep_events",
                "activity_recognition_events",
                "health_metrics",
                "connectivity_state_events",
                "app_network_usage",
                "device_settings",
                "upload_diagnostics",
                "app_usage_survey",
                "questionnaire_submissions",
                "time_use_diary_submissions",
                "participant_stats",
                "upload_buffer",
            ),
            StudyDeletionTable.dataTableNames()
        )
    }

    @Test
    fun testDestroyStudyUsesAtomicLifecycleDeletionSchedule() {
        val studyId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        Mockito.`when`(
            authorizationManager.checkIfHasPermissions(
                kEq(AclKey(studyId)),
                kAny(),
                kAny(),
            )
        ).thenReturn(true)
        Mockito.`when`(
            studyLifecycleService.scheduleImmediateStudyDeletion(
                kEq(studyId),
                kAnyString(),
            )
        ).thenReturn(operationId)

        val result = controller.destroyStudy(studyId).toList()

        assertEquals(listOf(operationId), result)
        verify(studyLifecycleService).scheduleImmediateStudyDeletion(
            kEq(studyId),
            kAnyString(),
        )
        verify(dataDeletionOrchestrator, never()).quarantineStudy(
            kAny(),
            kAnyString(),
            kAny(),
            kAny(),
        )
    }

    // getAllStudies relies on Principals.getCurrentPrincipals() and internal authz lookups,
    // which cannot be unit-tested with simple mocks. Covered by integration tests.

    @Test
    fun testAndroidUsageUploadUsesRealParticipantIdForServiceLookup() {
        val studyId = UUID.randomUUID()
        val participantId = "android-pixel-aws-20260702"
        val sourceDeviceId = "pixel-device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val data = ChronicleData(
            listOf(
                ChronicleUsageEvent(
                    studyId = studyId,
                    participantId = participantId,
                    appPackageName = "com.example.app",
                    interactionType = "Move to Foreground",
                    timestamp = OffsetDateTime.parse("2026-07-02T21:18:19Z"),
                    timezone = "UTC",
                    user = "0",
                    applicationLabel = "Example",
                )
            )
        )
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(
            appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny())
        ).thenReturn(1)

        val result = controller.uploadAndroidUsageEventData(studyId, participantId, sourceDeviceId, data)

        assertEquals(1, result)
        verify(appDataUploadService).uploadAndroidUsageEvents(
            kEq(studyId),
            kEq(participantId),
            kEq(deviceId),
            kEq(listOf(data.single() as ChronicleUsageEvent)),
            kAny(),
        )
        verify(webhookService).fireEvent(
            kEq(studyId),
            kEq(WebhookEventType.DATA_SUBMITTED),
            kAnyMap(),
        )
    }

    @Test
    fun testParticipantEnrollmentDelegatesTransactionalPublicationToStudyService() {
        val studyId = UUID.randomUUID()
        val candidateId = UUID.randomUUID()
        val participant = Participant(
            participantId = "participant-001",
            candidate = Candidate(candidateId),
            participationStatus = ParticipationStatus.ENROLLED,
        )
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny()))
            .thenReturn(true)
        Mockito.`when`(studyService.registerParticipant(studyId, participant)).thenReturn(candidateId)

        val result = controller.registerParticipant(studyId, participant)

        assertEquals(candidateId, result)
        verify(webhookService, Mockito.never()).fireEvent(
            kEq(studyId),
            kEq(WebhookEventType.PARTICIPANT_ENROLLED),
            kAnyMap(),
        )
    }

    @Test
    fun testAndroidSensorUploadRejectsNotEnrolledParticipantWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "not-enrolled"
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, "device-1", emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testAndroidSensorUploadRejectsUnknownDataSourceWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "enrolled"
        val sourceDeviceId = "unknown-device"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(false)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testMobileUploadsRejectEveryNonEnrolledParticipationStatusWithoutWritingData() {
        val studyId = UUID.randomUUID()
        val participantId = "not-enrolled"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)

        var expectedAuditCount = 0
        ParticipationStatus.values().filter { it != ParticipationStatus.ENROLLED }.forEach { status ->
            Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId)).thenReturn(status)
            val calls = mobileUploadCalls(studyId, participantId, sourceDeviceId)
            expectedAuditCount += calls.size

            calls.forEach { (name, upload) ->
                val error = assertThrows(
                    "$name must reject participation status $status",
                    ResponseStatusException::class.java,
                ) { upload() }

                assertEquals("$name must return HTTP 403", HttpStatus.FORBIDDEN, error.statusCode)
                assertEquals("Participant or data source is not enrolled", error.reason)
            }
        }

        assertNoMobileUploadWriteInteractions()
        assertFailureAuditCount(expectedAuditCount)
    }

    @Test
    fun testMobileUploadsRejectUnknownDataSourceWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "enrolled"
        val sourceDeviceId = "unknown-device"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(false)

        val calls = mobileUploadCalls(studyId, participantId, sourceDeviceId)
        calls.forEach { (name, upload) ->
            val error = assertThrows(
                "$name must reject an unknown data source",
                ResponseStatusException::class.java,
            ) { upload() }

            assertEquals("$name must return HTTP 403", HttpStatus.FORBIDDEN, error.statusCode)
            assertEquals("Participant or data source is not enrolled", error.reason)
        }

        assertNoMobileUploadWriteInteractions()
        assertFailureAuditCount(calls.size)
    }

    @Test
    fun testUnresolvedRequiredConsentBlocksDataUploadUntilConsentIsResolved() {
        val studyId = UUID.randomUUID()
        val participantId = "collection-halted"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(
            participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, deviceId),
        ).thenReturn(true)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Collection is halted pending required consent", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testMobileUploadRejectionLogsAndAuditsOnlySanitizedReferences() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-phi-sentinel"
        val sourceDeviceId = "source-device-phi-sentinel"
        val derivedDeviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val captured = CopyOnWriteArrayList<String>()
        val appender: Appender = object : AbstractAppender(
            "mobile-upload-rejection-capture",
            null,
            null,
            true,
            Property.EMPTY_ARRAY,
        ) {
            override fun append(event: LogEvent) {
                captured.add(event.message.formattedMessage)
            }
        }.also { it.start() }
        val coreLogger = LogManager.getLogger(StudyController::class.java) as Logger
        coreLogger.addAppender(appender)

        try {
            assertThrows(ResponseStatusException::class.java) {
                controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, emptyList())
            }
        } finally {
            coreLogger.removeAppender(appender)
            appender.stop()
        }

        val log = captured.joinToString("\n")
        assertTrue("participant fingerprint missing from rejection log: $log", log.contains("participant:"))
        assertTrue("device fingerprint missing from rejection log: $log", log.contains("device:"))
        assertTrue("sanitized device label missing from rejection log: $log", log.contains("dataSourceRef"))
        assertFalse("raw participant identifier leaked into rejection log: $log", log.contains(participantId))
        assertFalse("raw source-device identifier leaked into rejection log: $log", log.contains(sourceDeviceId))
        assertFalse("raw derived device identifier leaked into rejection log: $log", log.contains(derivedDeviceId.toString()))

        val auditCaptor = argumentCaptor<AuditLogEntry>()
        verify(auditService).log(auditCaptor.capture())
        val auditEntry = auditCaptor.firstValue
        assertFalse(auditEntry.success)
        assertEquals("Participant or data source is not enrolled", auditEntry.errorMessage)
        assertEquals(studyId, auditEntry.studyId)
    }

    @Test
    fun testGetStudyDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val study = Mockito.mock(Study::class.java)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(study)

        val result = controller.getStudy(studyId)
        assertNotNull(result)
        verify(studyService).getStudy(studyId)
    }

    // ===================== Collection loop closure: acknowledgments =====================

    @Test
    fun testGetStudyCollectionAcknowledgmentsDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val entry = CollectionAcknowledgmentEntry(
            studyId = studyId,
            participantId = "p-1",
            sourceDeviceId = "dev-1",
            acknowledgedModules = setOf(CollectionModuleId.HARDWARE_SENSORS),
            acknowledgedAt = OffsetDateTime.now(),
        )
        Mockito.`when`(participantCollectionAcknowledgmentService.getAcknowledgments(kAny(), kAnyInt(), kAnyInt()))
            .thenReturn(listOf(entry))

        val result = controller.getStudyCollectionAcknowledgments(studyId, 50, 0)
        assertEquals(1, result.size)
        assertEquals(setOf(CollectionModuleId.HARDWARE_SENSORS), result[0].acknowledgedModules)
        verify(participantCollectionAcknowledgmentService).getAcknowledgments(kAny(), kAnyInt(), kAnyInt())
    }

    @Test
    fun testReportCollectionAcknowledgmentRecordsWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "dev-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val apiKeyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(
            participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, deviceId),
        ).thenReturn(true)
        val ack = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.HARDWARE_SENSORS),
            acknowledgedAt = OffsetDateTime.now(),
            appVersion = "1.0",
            settingsVersion = 7,
            disclosureVersion = "consent-2026-08-17",
            manifestDigest = "a".repeat(64),
        )
        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(
            principal = "apikey:$apiKeyId",
            keyId = apiKeyId,
            studyId = studyId,
            participantId = participantId,
            deviceId = deviceId,
            scope = ApiKeyScope.WRITE,
            authorities = emptyList(),
        )
        Mockito.`when`(
            participantCollectionAcknowledgmentService.recordAcknowledgment(
                kAny(), kAnyString(), kAnyString(), kAny(), kAny(),
            )
        ).thenReturn(
            CollectionAcknowledgmentEntry(
                studyId = studyId, participantId = participantId, sourceDeviceId = sourceDeviceId,
                acknowledgedModules = ack.acknowledgedModules, acknowledgedAt = ack.acknowledgedAt,
            )
        )

        val result = controller.reportCollectionAcknowledgmentV4(studyId, participantId, sourceDeviceId, ack)
        assertNotNull(result)
        verify(participantCollectionAcknowledgmentService).recordAcknowledgment(
            kEq(studyId), kEq(participantId), kEq(sourceDeviceId), kEq(apiKeyId), kEq(ack),
        )
    }

    @Test
    fun testReportCollectionAcknowledgmentRejectsNotEnrolledWithoutRecording() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)
        val ack = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.HARDWARE_SENSORS),
            acknowledgedAt = OffsetDateTime.now(),
        )

        // A 403 (not a silent 200) so the device keeps the acknowledgment queued and retries.
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.reportCollectionAcknowledgmentV4(studyId, participantId, "dev-1", ack)
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(participantCollectionAcknowledgmentService, never()).recordAcknowledgment(
            kAny(), kAnyString(), kAnyString(), kAny(), kAny(),
        )
    }

    @Test
    fun testReportCollectionAcknowledgmentRejectsUnknownDataSourceWithoutRecording() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "unknown-device"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(false)
        val ack = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.HARDWARE_SENSORS),
            acknowledgedAt = OffsetDateTime.now(),
        )

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.reportCollectionAcknowledgmentV4(studyId, participantId, sourceDeviceId, ack)
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(participantCollectionAcknowledgmentService, never()).recordAcknowledgment(
            kAny(), kAnyString(), kAnyString(), kAny(), kAny(),
        )
    }

    @Test
    fun testUploadScreenTimeDataWritesExistingIosSensorPipelineWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "ios-device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:01:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    source = ScreenTimeCaptureSource.shortcutSnapshot,
                    confidence = ScreenTimeConfidence.externalShortcut,
                    capturedAt = end,
                    observationStart = start,
                    observationEnd = end,
                    timezoneIdentifier = "UTC",
                    appName = "Maps",
                    bundleIdentifier = "com.apple.Maps",
                    categoryName = "Travel",
                    durationSeconds = 60,
                )
            ),
        )
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(
            sensorDataUploadService.upload(kEq(studyId), kEq(participantId), kEq(deviceId), kAnyList<SensorDataSample>())
        ).thenReturn(1)

        val result = controller.uploadScreenTimeData(studyId, participantId, sourceDeviceId, envelope)

        assertEquals(1, result)
        verify(sensorDataUploadService).upload(
            kEq(studyId),
            kEq(participantId),
            kEq(deviceId),
            kAnyList<SensorDataSample>(),
        )
    }

    @Test
    fun testUploadScreenTimeDataRejectsWhenNotEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "ios-device-1"
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:01:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    source = ScreenTimeCaptureSource.shortcutSnapshot,
                    confidence = ScreenTimeConfidence.externalShortcut,
                    capturedAt = end,
                    observationStart = start,
                    observationEnd = end,
                    timezoneIdentifier = "UTC",
                    appName = "Maps",
                    durationSeconds = 60,
                )
            ),
        )
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadScreenTimeData(studyId, participantId, sourceDeviceId, envelope)
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(sensorDataUploadService, never()).upload(
            kAny(),
            kAnyString(),
            kAny(),
            kAnyList<SensorDataSample>(),
        )
    }

    @Test
    fun testUploadScreenTimeDataRejectsEnvelopeIdentityMismatchBeforeLookup() {
        val studyId = UUID.randomUUID()
        val capturedAt = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = "different-device",
            studyId = UUID.randomUUID().toString(),
            participantId = "different-participant",
            generatedAt = capturedAt,
            records = emptyList(),
        )

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadScreenTimeData(studyId, "p-123", "ios-device-1", envelope)
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        verify(enrollmentManager, never()).getParticipationStatus(kAny(), kAnyString())
        verify(sensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList<SensorDataSample>())
    }

    @Test
    fun testUploadUserIdentificationDataWritesExistingIosSensorPipelineWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "ios-device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val capturedAt = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val envelope = UserIdentificationEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = capturedAt,
            records = listOf(
                UserIdentificationRecord(
                    id = UUID.randomUUID(),
                    capturedAt = capturedAt,
                    timezoneIdentifier = "UTC",
                    trigger = UserIdentificationTrigger.manualInApp,
                    choice = UserIdentificationChoice.participant,
                    sourceLabel = "manual-start",
                )
            ),
        )
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(
            sensorDataUploadService.upload(kEq(studyId), kEq(participantId), kEq(deviceId), kAnyList<SensorDataSample>())
        ).thenReturn(1)

        val result = controller.uploadUserIdentificationData(studyId, participantId, sourceDeviceId, envelope)

        assertEquals(1, result)
        verify(sensorDataUploadService).upload(
            kEq(studyId),
            kEq(participantId),
            kEq(deviceId),
            kAnyList<SensorDataSample>(),
        )
    }

    @Test
    fun testUploadUserIdentificationDataRejectsWhenNotEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "ios-device-1"
        val capturedAt = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val envelope = UserIdentificationEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = capturedAt,
            records = listOf(
                UserIdentificationRecord(
                    id = UUID.randomUUID(),
                    capturedAt = capturedAt,
                    timezoneIdentifier = "UTC",
                    trigger = UserIdentificationTrigger.manualInApp,
                    choice = UserIdentificationChoice.someoneElse,
                )
            ),
        )
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadUserIdentificationData(studyId, participantId, sourceDeviceId, envelope)
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(sensorDataUploadService, never()).upload(
            kAny(),
            kAnyString(),
            kAny(),
            kAnyList<SensorDataSample>(),
        )
    }

    @Test
    fun testUploadUserIdentificationDataRejectsEnvelopeIdentityMismatchBeforeLookup() {
        val studyId = UUID.randomUUID()
        val capturedAt = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val envelope = UserIdentificationEnvelope(
            deviceId = "different-device",
            studyId = UUID.randomUUID().toString(),
            participantId = "different-participant",
            generatedAt = capturedAt,
            records = emptyList(),
        )

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadUserIdentificationData(studyId, "p-123", "ios-device-1", envelope)
        }

        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        verify(enrollmentManager, never()).getParticipationStatus(kAny(), kAnyString())
        verify(sensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList<SensorDataSample>())
    }

    // getStudyParticipants relies on ensureAuthenticated()/ensureReadAccess() and
    // studyService.getStudyParticipants(studyId, limit, offset), which require
    // Principals static context. Covered by integration tests.

    // getParticipantStats relies on ensureReadAccess() and internal pagination/auth,
    // which require Principals static context. Covered by integration tests.

    @Test
    fun testIsKnownParticipantDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        // Controller delegates to enrollmentService, not enrollmentManager
        Mockito.`when`(enrollmentService.isKnownParticipant(studyId, participantId)).thenReturn(true)

        val result = controller.isKnownParticipant(studyId, participantId)
        assertTrue(result)
    }

    @Test
    fun testIsKnownParticipantReturnsFalseForUnknown() {
        val studyId = UUID.randomUUID()
        val participantId = "unknown"
        Mockito.`when`(enrollmentService.isKnownParticipant(studyId, participantId)).thenReturn(false)

        val result = controller.isKnownParticipant(studyId, participantId)
        assertFalse(result)
    }

    // getStudyDevices uses direct SQL via storageResolver, not a service delegation.
    // Cannot be unit-tested without a real DB connection. Covered by integration tests.

    // ===================== Phase 9A: generalized DataCollection read =====================

    @Test
    fun testGetAndroidSensorSettingsReturnsStoredSetting() {
        // Regression baseline: the existing AndroidSensor endpoint is unchanged.
        val studyId = UUID.randomUUID()
        val stored = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer), samplingRateHz = 11)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId))
            .thenReturn(mapOf(StudySettingType.AndroidSensor to stored))

        val result = controller.getAndroidSensorSettings(studyId)
        assertEquals(stored, result)
        assertEquals(11, result.samplingRateHz)
    }

    @Test
    fun testGetAndroidSensorSettingsFallsBackToNoSensors() {
        // Missing AndroidSensor setting still returns NO_SENSORS, unchanged.
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        assertEquals(AndroidSensorSetting.NO_SENSORS, controller.getAndroidSensorSettings(studyId))
    }

    @Test
    fun testGetStudySettingReturnsStoredDataCollectionSetting() {
        // New setting storage round-trip: a stored AndroidDataCollectionSetting is
        // returned verbatim from the existing typed endpoint — no new route.
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        val stored = CollectionDefaults.androidDataCollectionSetting()
        Mockito.`when`(studyService.getStudySettings(studyId))
            .thenReturn(mapOf(StudySettingType.DataCollection to stored))

        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        assertEquals(stored, result)
    }

    @Test
    fun testGetStudySettingDataCollectionMissingFallsBackToLegacyAndroidSensor() {
        // old -> new fallback: no DataCollection setting, but a legacy AndroidSensor
        // setting with sensors -> the named sensor's per-sensor module derived and enabled.
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        val legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.gyroscope))
        Mockito.`when`(studyService.getStudySettings(studyId))
            .thenReturn(mapOf(StudySettingType.AndroidSensor to legacy))

        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        val setting = result as AndroidDataCollectionSetting
        val gyro = setting.modules.getValue(CollectionModuleId.SENSOR_GYROSCOPE)
        assertTrue("legacy AndroidSensor with sensors must enable the named per-sensor module", gyro.enabled)
        assertEquals(legacy, gyro.sensorPolicy)
    }

    @Test
    fun testGetStudySettingDataCollectionMissingWithEmptyLegacyEnablesNoSensor() {
        // old -> new fallback, empty legacy: no DataCollection, no AndroidSensor at all
        // -> safe default, no per-sensor module enabled (no privacy-sensitive module enabled).
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        val setting = result as AndroidDataCollectionSetting
        assertTrue(
            "missing setting must not enable any sensor module",
            setting.modules.values.none { it.enabled },
        )
    }

    @Test
    fun testGetStudySettingAndroidSensorStillReturnsLegacyShape() {
        // old endpoint returns old settings: AndroidSensor via the typed endpoint is
        // unchanged — the new DataCollection branch does not affect it.
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        val stored = AndroidSensorSetting(sensors = setOf(AndroidSensorType.light))
        Mockito.`when`(studyService.getStudySettings(studyId))
            .thenReturn(mapOf(StudySettingType.AndroidSensor to stored))

        val result = controller.getStudySetting(studyId, StudySettingType.AndroidSensor)
        assertTrue(result is AndroidSensorSetting)
        assertEquals(stored, result)
    }

    @Test
    fun testGetStudySettingEncryptionReturnsDisabledDefaultWhenAbsent() {
        // W2 e2ee: the Encryption setting is mobile-readable (permitAll) and MUST return a
        // disabled default for a study with no e2ee — never throw (device key sync depends on a
        // 200), and never expose private key material (the DTO is public-key-only).
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        val result = controller.getStudySetting(studyId, StudySettingType.Encryption)
        assertTrue(result is com.openlattice.chronicle.study.StudyEncryptionSetting)
        val setting = result as com.openlattice.chronicle.study.StudyEncryptionSetting
        assertFalse("absent Encryption setting must default to disabled", setting.enabled)
        assertEquals("default must carry no public key", "", setting.publicKeyPem)
        assertFalse("DTO must never contain private key material", setting.publicKeyPem.contains("PRIVATE"))
    }

    @Test
    fun testGetStudySettingMissingNonFallbackTypeStillThrows() {
        // Behavior pin: only DataCollection/AndroidSensor/Sensor get a fallback. Other
        // missing setting types still throw — Phase 9A must not broaden the fallback.
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny()))
            .thenReturn(true)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(emptyMap())

        try {
            controller.getStudySetting(studyId, StudySettingType.DataQuality)
            fail("Expected NoSuchElementException for a missing non-fallback setting type")
        } catch (_: NoSuchElementException) {
            // expected — getValue() throws for missing keys
        }
    }

    @Test
    fun testProvisionStudyEncryptionDeniedWithoutOwnerDoesNotProvisionKey() {
        // W2 e2ee: provisioning (which mints + stores a study private key) is OWNER-gated. A caller
        // that is authenticated (set up in @Before) but lacks OWNER must be rejected, and NO keypair
        // may be generated or stored as a side effect of the denied request.
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny()))
            .thenReturn(false)

        assertThrows(ForbiddenException::class.java) {
            controller.provisionStudyEncryption(studyId)
        }
        verify(studyEncryptionKeyService, never()).provision(kAny())
    }

    @Test
    fun testProvisionStudyEncryptionFailsClosedEvenForOwnerUntilExportExists() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny()))
            .thenReturn(true)

        val failure = assertThrows(ResponseStatusException::class.java) {
            controller.provisionStudyEncryption(studyId)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.statusCode)
        verify(studyEncryptionKeyService, never()).provision(kAny())
    }

    @Test
    fun testEncryptedUploadFailsBeforeAcceptingCiphertextWithoutAnExportPath() {
        val failure = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidEncryptedDataV4(
                UUID.randomUUID(),
                "participant-release-gate",
                "device-release-gate",
                emptyList(),
            )
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.statusCode)
        verify(encryptedPayloadUploadService, never()).upload(kAny(), kAny(), kAny(), kAny())
    }

    private fun mobileUploadCalls(
        studyId: UUID,
        participantId: String,
        sourceDeviceId: String,
    ): List<Pair<String, () -> Unit>> {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val sensorData = listOf(
            SensorDataSample(
                id = UUID.randomUUID(),
                dateRecorded = timestamp,
                duration = 1.0,
                data = "{}",
                device = "{}",
                timezone = "UTC",
                sensor = SensorType.pedometer,
                startDate = timestamp,
                endDate = timestamp.plusSeconds(1),
            )
        )
        val batteryData = listOf(
            BatterySample(
                id = "battery-sample-1",
                timestamp = timestamp,
                timezone = "UTC",
                levelPercent = 80,
                chargingState = BatteryChargingState.DISCHARGING,
                plugType = BatteryPlugType.UNPLUGGED,
                temperatureDeciC = 312,
                voltageMillivolts = 4_100,
                health = BatteryHealth.GOOD,
            )
        )
        val screenTimeEnvelope = ScreenTimeUsageEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = timestamp,
            records = emptyList(),
        )
        val userIdentificationEnvelope = UserIdentificationEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = timestamp,
            records = emptyList(),
        )
        val androidUsageData = ChronicleData(
            listOf(
                ChronicleUsageEvent(
                    studyId = studyId,
                    participantId = participantId,
                    appPackageName = "com.example.app",
                    interactionType = "Move to Foreground",
                    timestamp = timestamp,
                    timezone = "UTC",
                    user = "0",
                    applicationLabel = "Example",
                )
            )
        )
        val acknowledgment = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.HARDWARE_SENSORS),
            acknowledgedAt = timestamp,
        )

        return listOf(
            "iOS SensorKit" to {
                controller.uploadSensorData(studyId, participantId, sourceDeviceId, sensorData)
            },
            "iOS Screen Time" to {
                controller.uploadScreenTimeData(studyId, participantId, sourceDeviceId, screenTimeEnvelope)
            },
            "iOS user identification" to {
                controller.uploadUserIdentificationData(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    userIdentificationEnvelope,
                )
            },
            "Android usage" to {
                controller.uploadAndroidUsageEventData(studyId, participantId, sourceDeviceId, androidUsageData)
            },
            "Android sensors" to {
                controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, emptyList())
            },
            "Android sensor availability" to {
                controller.reportAndroidSensorAvailability(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    Mockito.mock(AndroidDeviceSensorAvailability::class.java),
                )
            },
            "Android battery" to {
                controller.uploadBatteryTelemetry(studyId, participantId, sourceDeviceId, batteryData)
            },
            "iOS battery" to {
                controller.uploadIosBatteryTelemetry(studyId, participantId, sourceDeviceId, emptyList())
            },
            "interaction events" to {
                controller.uploadInteractionEvents(studyId, participantId, sourceDeviceId, emptyList())
            },
            "audio activity" to {
                controller.uploadAudioActivity(studyId, participantId, sourceDeviceId, emptyList())
            },
            "ambient audio" to {
                controller.uploadAmbientAudio(studyId, participantId, sourceDeviceId, emptyList())
            },
            "audio content" to {
                controller.uploadAudioContent(studyId, participantId, sourceDeviceId, emptyList())
            },
            "notification activity" to {
                controller.uploadNotificationActivity(studyId, participantId, sourceDeviceId, emptyList())
            },
            "sleep events" to {
                controller.uploadSleepEvents(studyId, participantId, sourceDeviceId, emptyList())
            },
            "activity recognition" to {
                controller.uploadActivityRecognitionEvents(studyId, participantId, sourceDeviceId, emptyList())
            },
            "health metrics" to {
                controller.uploadHealthMetrics(studyId, participantId, sourceDeviceId, emptyList())
            },
            "connectivity state" to {
                controller.uploadConnectivityStateEvents(studyId, participantId, sourceDeviceId, emptyList())
            },
            "app network usage" to {
                controller.uploadAppNetworkUsage(studyId, participantId, sourceDeviceId, emptyList())
            },
            "device settings" to {
                controller.uploadDeviceSettings(studyId, participantId, sourceDeviceId, emptyList())
            },
            "collection acknowledgment" to {
                controller.reportCollectionAcknowledgmentV4(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    acknowledgment,
                )
            },
        )
    }

    private fun assertNoMobileUploadWriteInteractions() {
        Mockito.verifyNoInteractions(
            sensorDataUploadService,
            androidSensorDataUploadService,
            batteryTelemetryUploadService,
            interactionEventsUploadService,
            appAudioActivityUploadService,
            ambientAudioUploadService,
            appAudioContentUploadService,
            notificationActivityUploadService,
            sleepEventsUploadService,
            activityRecognitionEventsUploadService,
            healthMetricsUploadService,
            connectivityStateEventsUploadService,
            appNetworkUsageUploadService,
            deviceSettingsUploadService,
            encryptedPayloadUploadService,
            appDataUploadService,
            participantCollectionAcknowledgmentService,
            storageResolver,
            webhookService,
        )
    }

    private fun assertFailureAuditCount(expectedCount: Int) {
        val auditCaptor = argumentCaptor<AuditLogEntry>()
        verify(auditService, Mockito.times(expectedCount)).log(auditCaptor.capture())
        assertEquals(expectedCount, auditCaptor.allValues.size)
        assertTrue(auditCaptor.allValues.all { !it.success })
    }

}
