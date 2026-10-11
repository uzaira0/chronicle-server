package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorSample
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
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.DeviceIdUtils
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
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
import com.openlattice.chronicle.authorization.Permission
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
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.zaxxer.hikari.HikariDataSource
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
import java.time.LocalDate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
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

        // Device-data writes run inside the atomic collection-halt recheck. The real service opens
        // a transaction; here it just runs the write so these tests still exercise the upload path.
        Mockito.`when`(
            participantCollectionAcknowledgmentService.withCollectionHaltRecheck<Any?>(
                kAny<UUID>(), kAnyString(), kAny<UUID>(), kAny<Set<CollectionModuleId>>(), org.mockito.kotlin.anyOrNull(),
                Mockito.anyBoolean(),
                kAny<Function1<Set<CollectionModuleId>, Any?>>(),
            ),
        ).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument<Any>(6) as (Set<CollectionModuleId>) -> Any?)
                .invoke(invocation.getArgument(3))
        }

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
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kEq(EnumSet.of(Permission.WRITE)),
        )).thenReturn(true)
    }

    private fun stubConsentedUploadModules(modules: Set<CollectionModuleId>) {
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Function1<Set<CollectionModuleId>, Any?>>(6).invoke(modules)
        }.`when`(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Any?>(
            kAny(), kAnyString(), kAny(), kAny<Set<CollectionModuleId>>(), org.mockito.kotlin.anyOrNull(),
            Mockito.anyBoolean(),
            kAny<Function1<Set<CollectionModuleId>, Any?>>(),
        )
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testOversizedJsonExportReturnsItsPooledConnectionBeforeResponding413() {
        val postgres = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
        }).use { pool ->
            val studyId = UUID.randomUUID()
            val participants = setOf("participant-1")
            val start = OffsetDateTime.parse("2026-10-01T00:00:00Z")
            val end = start.plusDays(1)
            val rows = com.openlattice.chronicle.converters.PostgresDownloadWrapper(
                com.geekbeast.postgres.streams.BasePostgresIterable<Map<String, Any>>(
                    com.geekbeast.postgres.streams.PreparedStatementHolderSupplier(
                        pool, "SELECT generate_series(1, ${SynchronousExportLimits.MAX_JSON_ROWS + 2})", 100,
                    ) {},
                ) { emptyMap() },
            )
            Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
            Mockito.`when`(downloadService.getParticipantsUsageEventsData(studyId, participants, start, end))
                .thenReturn(rows)

            val error = assertThrows(ResponseStatusException::class.java) {
                controller.getParticipantsData(studyId, com.openlattice.chronicle.study.ParticipantDataType.UsageEvents,
                    participants, start, end, com.openlattice.chronicle.data.FileType.json, null, null,
                    org.springframework.mock.web.MockHttpServletResponse())
            }
            assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.statusCode)
            assertEquals(0, pool.hikariPoolMXBean.activeConnections)
        }
    }

    @Test
    fun wideJsonRowsAreRejectedBelowTheRowLimitWithoutConsumingTheRest() {
        val studyId = UUID.randomUUID()
        val participants = setOf("participant-1")
        val start = OffsetDateTime.parse("2026-10-01T00:00:00Z")
        val end = start.plusDays(1)
        val wideRow = mapOf<String, Any>("title" to "x".repeat(256 * 1024))
        var consumed = 0
        var closed = false
        val rows = generateSequence { wideRow }.onEach { consumed++ }.iterator()
        val cursor = object : Iterator<Map<String, Any>> by rows, AutoCloseable {
            override fun close() { closed = true }
        }
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(downloadService.getParticipantsUsageEventsData(studyId, participants, start, end))
            .thenReturn(Iterable { cursor })
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getParticipantsData(studyId, com.openlattice.chronicle.study.ParticipantDataType.UsageEvents,
                participants, start, end, com.openlattice.chronicle.data.FileType.json, null, null,
                org.springframework.mock.web.MockHttpServletResponse())
        }
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, error.statusCode)
        assertTrue(consumed < SynchronousExportLimits.MAX_JSON_ROWS)
        assertTrue(closed)
    }

    @Test
    fun testAnonymousUnsignedV3EnrollmentRejectsBeforeCreatingDeviceOrKey() {
        val fixture = replayEnrollmentFixture()
        SecurityContextHolder.clearContext()
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.enroll(fixture.studyId, fixture.participantId, fixture.sourceDeviceId, fixture.sourceDevice)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, error.statusCode)
        Mockito.verifyNoInteractions(enrollmentService, apiKeyService)
    }

    @Test
    fun testReadOnlyResearcherCannotWriteThroughMobileUploadGate() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
        assertThrows(ForbiddenException::class.java) {
            controller.uploadAndroidSensorData(studyId, "participant-1", "device-1", emptyList())
        }
        Mockito.verifyNoInteractions(androidSensorDataUploadService)
    }

    @Test
    fun testLegacyUploadCannotUseAnotherDeviceWithAMatchingKeyHeader() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val keyId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(
            principal = "apikey:$keyId", keyId = keyId, studyId = studyId,
            participantId = participantId,
            deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, "device-1"),
            scope = ApiKeyScope.WRITE, authorities = emptyList(),
        )
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, "device-2", emptyList())
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        Mockito.verifyNoInteractions(androidSensorDataUploadService)
    }

    @Test
    fun testParticipantVerificationRequiresStudyReadAccessBeforeLookup() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
        assertThrows(ForbiddenException::class.java) {
            controller.isKnownParticipant(UUID.randomUUID(), "participant-1")
        }
        Mockito.verifyNoInteractions(enrollmentService)
    }

    @Test
    fun testDeclinedOptionalModuleReturns403WithoutWritingData() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val module = CollectionModuleId.INTERACTION_EVENTS
        val settings = AndroidDataCollectionSetting(
            modules = linkedMapOf(module to com.openlattice.chronicle.collection.CollectionModuleSetting(enabled = true)),
            settingsVersion = 1,
        )
        val decisions = mapOf(module to com.openlattice.chronicle.services.studies.IssuedCollectionDecision(
            com.openlattice.chronicle.services.studies.IssuedCollectionDecisionState.DECLINED, 1,
        ))
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            val modules = invocation.getArgument<Set<CollectionModuleId>>(3)
            if (com.openlattice.chronicle.services.studies.collectionIsHalted(settings, listOf(settings), decisions, modules)) {
                throw com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participantId, deviceId)
            }
            invocation.getArgument<Function1<Set<CollectionModuleId>, Any?>>(6).invoke(modules)
        }.`when`(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Any?>(
            kAny(), kAnyString(), kAny(), kAny<Set<CollectionModuleId>>(), org.mockito.kotlin.anyOrNull(),
            Mockito.anyBoolean(),
            kAny<Function1<Set<CollectionModuleId>, Any?>>(),
        )
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadInteractionEvents(studyId, participantId, sourceDeviceId, emptyList())
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        Mockito.verifyNoInteractions(interactionEventsUploadService)
    }

    @Test
    fun testAndroidDiagnosticsHistoryEndpointChecksReadAccessAndAppliesFilters() {
        val studyId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val day = LocalDate.parse("2026-09-25")
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(storageResolver.getPlatformReadStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.next()).thenReturn(false)

        val page = controller.getAndroidDiagnostics(
            studyId = studyId,
            participantId = "participant-1",
            deviceId = deviceId,
            fromDay = day,
            toDay = day,
            moduleFamily = "LOCAL_STORE",
            issueCode = "LOCAL_WRITE_FAILED",
            cursor = null,
            limit = 25,
        )

        assertTrue(page.items.isEmpty())
        val sql = argumentCaptor<String>()
        verify(connection).prepareStatement(sql.capture())
        assertTrue(sql.firstValue.contains("FROM upload_diagnostics"))
        assertTrue(sql.firstValue.contains("FROM data_quality_alerts"))
        assertTrue(sql.firstValue.contains("module_family = ?"))
        assertTrue(sql.firstValue.contains("issue_code = ?"))
        assertTrue("Page keys must be limited before code aggregation", sql.firstValue.indexOf("LIMIT ?") < sql.firstValue.indexOf("jsonb_agg"))
        verify(statement, Mockito.atLeast(2)).setObject(Mockito.anyInt(), Mockito.eq(studyId))
    }

    @Test
    fun testAndroidDiagnosticsRejectsOversizedCursorBeforeQueryingStorage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            com.openlattice.chronicle.services.upload.UploadDiagnosticsQueryService(storageResolver).getPage(
                UUID.randomUUID(),
                cursor = "2026-09-01~${"a".repeat(1020)}~_~0~_",
            )
        }
        assertTrue(error.message.orEmpty().contains("1024"))
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun testAndroidDiagnosticsRejectsInvalidCursorDateBeforeQueryingStorage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            com.openlattice.chronicle.services.upload.UploadDiagnosticsQueryService(storageResolver).getPage(
                UUID.randomUUID(),
                cursor = "bad~cA~~0~_",
            )
        }
        assertTrue(error.message.orEmpty().contains("cursor date"))
        Mockito.verifyNoInteractions(storageResolver)
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
            modules = mapOf(com.openlattice.chronicle.study.StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
            contact = "research@example.org",
            settings = StudySettings(mapOf(StudySettingType.DataCollection to submittedSetting)),
        )
        Mockito.`when`(studyService.createStudy(kAny<Study>(), kAny<(Connection, UUID) -> Unit>())).thenAnswer { invocation ->
            invocation.getArgument<(Connection, UUID) -> Unit>(1)(Mockito.mock(Connection::class.java), studyId)
            studyId
        }
        Mockito.`when`(
            studySettingsAuditService.generateChangeSummary(
                StudySettingType.DataCollection,
                null,
                expectedSetting,
            ),
        ).thenReturn("Initial server-issued collection settings")

        assertEquals(studyId, controller.createStudy(submittedStudy))

        val persistedStudy = argumentCaptor<Study>().apply {
            verify(studyService).createStudy(capture(), kAny())
        }.firstValue
        assertEquals(expectedSetting, persistedStudy.settings[StudySettingType.DataCollection])
        val auditInvocation = Mockito.mockingDetails(studySettingsAuditService).invocations.single {
            it.method.name == "recordSettingsChange"
        }
        assertEquals(expectedSetting, auditInvocation.arguments[5])
    }

    @Test
    fun settingsAuditUsesTheMutationConnectionAndFailureRollsBack() {
        val studyId = UUID.randomUUID()
        val pool = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(pool)
        Mockito.`when`(pool.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getString(1)).thenReturn("{}")
        Mockito.`when`(studySettingsAuditService.generateChangeSummary(kAny(), org.mockito.kotlin.anyOrNull(), kAny()))
            .thenReturn("Changed settings")
        val setting = com.openlattice.chronicle.notifications.StudyNotificationSettings("", "test")
        controller.updateStudySettings(studyId, StudySettingType.Notifications, setting)
        val audit = Mockito.mockingDetails(studySettingsAuditService).invocations.single { it.method.name == "recordSettingsChange" }
        assertTrue(audit.arguments[7] === connection)
        val order = Mockito.inOrder(studySettingsAuditService, connection)
        order.verify(studySettingsAuditService).recordSettingsChange(kAny(), kAnyString(), org.mockito.kotlin.anyOrNull(), kAny(),
            org.mockito.kotlin.anyOrNull(), kAny(), kAnyString(), org.mockito.kotlin.eq(connection))
        order.verify(connection).commit()
        Mockito.clearInvocations(connection)
        Mockito.doAnswer { throw java.sql.SQLException("Audit write unavailable") }.`when`(studySettingsAuditService)
            .recordSettingsChange(kAny(), kAnyString(), org.mockito.kotlin.anyOrNull(), kAny(), org.mockito.kotlin.anyOrNull(),
                kAny(), kAnyString(), org.mockito.kotlin.eq(connection))
        assertThrows(java.sql.SQLException::class.java) {
            controller.updateStudySettings(studyId, StudySettingType.Notifications, setting)
        }
        verify(connection).rollback()
        verify(connection, never()).commit()
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
        verify(studyService, never()).createStudy(kAny<Study>(), kAny<(Connection, UUID) -> Unit>())
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
    fun testMixedUsageUploadDropsUnconsentedRowsAndRedactsOptionalFields() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val usage = TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1).single() as ChronicleUsageEvent
        val lifecycle = usage.copy(appPackageName = "android", interactionType = "Battery Low")
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.USAGE_EVENTS))
        Mockito.`when`(appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny()))
            .thenReturn(1)

        assertEquals(1, controller.uploadAndroidUsageEventData(
            studyId, participantId, sourceDeviceId, ChronicleData(listOf(usage, lifecycle)),
        ))
        verify(appDataUploadService).uploadAndroidUsageEvents(
            kEq(studyId), kEq(participantId), kEq(deviceId),
            kEq(listOf(usage.copy(activityClass = null, user = ""))), kAny(),
        )
    }

    @Test
    fun testLegacyMixedSensorUploadReturnsRetryableCollectionHaltBeforeStoringAnyRows() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val light = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.light, OffsetDateTime.now(), "UTC")
        val accelerometer = light.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.accelerometer)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.SENSOR_LIGHT))
        Mockito.`when`(androidSensorDataUploadService.upload(kAny(), kAnyString(), kAny(), kAnyList())).thenReturn(1)

        val error = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId, listOf(light, accelerometer))
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorData(studyId, participantId, "device-1", emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testResearcherDeletedParticipantUploadGetsNotEnrolledInsteadOfConflict() {
        val studyId = UUID.randomUUID()
        val participantId = "deleted-by-researcher"
        // The deletion quarantine hides the row, so the researcher read throws (mapped to 409).
        Mockito.`when`(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenThrow(IllegalStateException("No row returned"))
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
    fun testMobileUploadsRejectInactiveParticipationStatusesWithoutWritingData() {
        val studyId = UUID.randomUUID()
        val participantId = "not-enrolled"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)

        var expectedAuditCount = 0
        ParticipationStatus.values().filter {
            it !in setOf(ParticipationStatus.ENROLLED, ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)
        }.forEach { status ->
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
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
    fun testPausedAndCompletedBatchUploadsReachPerRecordAdmission() {
        val studyId = UUID.randomUUID()
        val participantId = "queued-participant"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val cutoff = OffsetDateTime.parse("2026-07-12T04:00:00Z")
        val connection = Mockito.mock(java.sql.Connection::class.java)
        val statement = Mockito.mock(java.sql.PreparedStatement::class.java)
        val rows = Mockito.mock(java.sql.ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getString("lifecycle_status")).thenReturn("ACTIVE")
        Mockito.`when`(rows.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(cutoff)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            val capturedAt = invocation.getArgument<java.time.Instant?>(4)
            if (!com.openlattice.chronicle.storage.ParticipantCollectionGate.isActive(connection, studyId, participantId, capturedAt,
                    invocation.getArgument(5))) {
                throw com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participantId, deviceId)
            }
            invocation.getArgument<Function1<Set<CollectionModuleId>, Any?>>(6).invoke(invocation.getArgument(3))
        }.`when`(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Any?>(
            kAny(), kAnyString(), kAny(), kAny<Set<CollectionModuleId>>(), org.mockito.kotlin.anyOrNull(),
            Mockito.anyBoolean(),
            kAny<Function1<Set<CollectionModuleId>, Any?>>(),
        )

        val queuedStatuses = listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)
        for (status in queuedStatuses) {
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
            Mockito.`when`(rows.getString("participation_status")).thenReturn(status.name)
            for (capturedAt in listOf(null, cutoff, cutoff.plusSeconds(1))) {
                mobileUploadCalls(studyId, participantId, sourceDeviceId, capturedAt).forEach { (name, upload) ->
                    if (name == "Android sensor availability" || name == "collection acknowledgment") {
                        val error = assertThrows("$name must reject $status capture at $capturedAt", ResponseStatusException::class.java) { upload() }
                        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
                    } else upload()
                }
            }
        }

        val dataSource = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        for (status in queuedStatuses) {
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
            Mockito.`when`(rows.getString("participation_status")).thenReturn(status.name)
            mobileUploadCalls(studyId, participantId, sourceDeviceId, cutoff.minusSeconds(1), includeAcknowledgment = false)
                .forEach { (_, upload) -> upload() }
        }
        verify(sensorDataUploadService, Mockito.times(24)).upload(kEq(studyId), kEq(participantId), kEq(deviceId), kAnyList())
        verify(appDataUploadService, Mockito.times(6)).uploadAndroidUsageEvents(kEq(studyId), kEq(participantId), kEq(deviceId), kAnyList(),
            org.mockito.kotlin.any())
        listOf(androidSensorDataUploadService, batteryTelemetryUploadService, interactionEventsUploadService,
            appAudioActivityUploadService, ambientAudioUploadService, appAudioContentUploadService,
            notificationActivityUploadService, sleepEventsUploadService, activityRecognitionEventsUploadService,
            healthMetricsUploadService, connectivityStateEventsUploadService, appNetworkUsageUploadService,
            deviceSettingsUploadService).forEach { service ->
            assertEquals("Every batch stream reaches its row admission even when its maximum crosses the cutoff", 8,
                Mockito.mockingDetails(service).invocations.count { it.method.name == "upload" })
        }
        verify(batteryTelemetryUploadService, Mockito.times(8)).uploadIos(kEq(studyId), kEq(participantId), kAnyList())
        verify(statement, Mockito.times(2)).executeUpdate()
    }

    @Test
    fun testMobileUploadsRejectUnknownDataSourceWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "enrolled"
        val sourceDeviceId = "unknown-device"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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

    @Test
    fun testResearcherApiKeyActsOnItsOwnStudyWithinScopeAndIsAuditedAsTheKey() {
        val studyId = UUID.randomUUID()
        val otherStudyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        // The key holds no ACEs, so the ACL store refuses it everywhere.
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(Mockito.mock(Study::class.java))
        Mockito.`when`(studyService.isValidStudy(kAny())).thenReturn(true)
        val participant = Participant(
            participantId = "participant-001",
            candidate = Candidate(UUID.randomUUID()),
            participationStatus = ParticipationStatus.ENROLLED,
        )
        fun authenticate(scope: ApiKeyScope) {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(
                principal = "apikey:$keyId",
                keyId = keyId,
                studyId = studyId,
                participantId = null,
                deviceId = null,
                scope = scope,
                authorities = emptyList(),
            )
        }

        authenticate(ApiKeyScope.READ_ONLY)
        assertNotNull(controller.getStudy(studyId))
        assertThrows(ForbiddenException::class.java) { controller.registerParticipant(studyId, participant) }
        assertThrows(ForbiddenException::class.java) { controller.getStudy(otherStudyId) }

        authenticate(ApiKeyScope.WRITE)
        controller.registerParticipant(studyId, participant)
        verify(studyService).registerParticipant(studyId, participant)

        val events = argumentCaptor<List<AuditableEvent>>()
        verify(auditingManager, Mockito.atLeastOnce()).recordEvents(events.capture())
        val studyRead = events.allValues.flatten().first { it.eventType == AuditEventType.GET_STUDY }
        assertEquals("apikey:$keyId", studyRead.principal.id)
        assertEquals(keyId, studyRead.securablePrincipalId)
        val logged = argumentCaptor<AuditLogEntry>()
        verify(auditService, Mockito.atLeastOnce()).log(logged.capture())
        assertTrue(logged.allValues.isNotEmpty() && logged.allValues.all { it.userId == keyId })
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
        val audit = argumentCaptor<AuditLogEntry>()
        verify(auditService).log(audit.capture())
        assertEquals(com.openlattice.chronicle.audit.AuditAction.PARTICIPANT_DATA_ACCESS, audit.firstValue.action)
        assertTrue(audit.firstValue.accessedPHI)
        assertEquals(studyId, audit.firstValue.studyId)
        assertEquals(1, audit.firstValue.additionalData?.get("participantCount"))
    }

    @Test
    fun testReportCollectionAcknowledgmentRecordsWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceDeviceId = "dev-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val apiKeyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        verify(enrollmentManager, never()).getDeviceParticipationStatus(kAny(), kAnyString())
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
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
        verify(enrollmentManager, never()).getDeviceParticipationStatus(kAny(), kAnyString())
        verify(sensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList<SensorDataSample>())
    }

    // getStudyParticipants relies on ensureAuthenticated()/ensureReadAccess() and
    // studyService.getStudyParticipants(studyId, limit, offset), which require
    // Principals static context. Covered by integration tests.

    // getParticipantStats relies on ensureReadAccess() and internal pagination/auth,
    // which require Principals static context. Covered by integration tests.

    @Test
    fun testIsKnownParticipantDelegatesToService() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        // Controller delegates to enrollmentService, not enrollmentManager
        Mockito.`when`(enrollmentService.isKnownParticipant(studyId, participantId)).thenReturn(true)

        val result = controller.isKnownParticipant(studyId, participantId)
        assertTrue(result)
    }

    @Test
    fun testIsKnownParticipantReturnsFalseForUnknown() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
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

        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(Study(
            title = "Collection enabled", contact = "research@example.org",
            modules = mapOf(com.openlattice.chronicle.study.StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
        ))
        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        assertEquals(stored, result)
    }

    @Test
    fun testFeatureOffPublicCollectionReadStopsVersionedAndLegacySensors() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(Study(
            title = "Collection disabled", contact = "research@example.org", modules = emptyMap(),
        ))
        val versioned = CollectionDefaults.androidDataCollectionSetting()
        val legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.gyroscope))
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(
            mapOf(StudySettingType.DataCollection to versioned),
            mapOf(StudySettingType.AndroidSensor to legacy),
        )
        repeat(2) {
            val read = controller.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
            assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), read.effectiveEnabledModuleIds())
        }
    }

    @Test
    fun testPublicStudySettingRejectsUnknownStudyBeforeReadingSettings() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(false)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getStudySetting(studyId, StudySettingType.DataCollection)
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        verify(studyService, never()).getStudySettings(studyId)
    }

    @Test
    fun testDetailsRevisionCheckRejectsStaleWriteUnderStudyRowLock() {
        val studyId = UUID.randomUUID()
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ? FOR UPDATE"))
            .thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.next()).thenReturn(true)
        Mockito.`when`(studyService.getStudySettingsRevision(connection, studyId)).thenReturn(2L)

        val error = assertThrows(StudySettingsRevisionMismatchException::class.java) {
            checkLockedStudyRevision(connection, studyId, 1L, studyService)
        }
        assertEquals(2L, error.currentRevision)
    }

    @Test
    fun testDetailsOnlyUpdateAdvancesSettingsRevision() {
        val service = Mockito.mock(StudyService::class.java, Mockito.CALLS_REAL_METHODS)
        val connection = Mockito.mock(Connection::class.java)
        val update = Mockito.mock(PreparedStatement::class.java)
        val bump = Mockito.mock(PreparedStatement::class.java)
        val revision = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { invocation ->
            if ((invocation.getArgument<String>(0)).contains("SET settings_revision")) bump else update
        }
        Mockito.`when`(bump.executeQuery()).thenReturn(revision)
        Mockito.`when`(revision.next()).thenReturn(true)
        Mockito.`when`(revision.getLong(1)).thenReturn(1L)

        service.updateStudy(connection, UUID.randomUUID(), StudyUpdate(title = "Revised title"))

        verify(update).executeUpdate()
        verify(bump).executeQuery()
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

        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(Study(
            title = "Collection enabled", contact = "research@example.org",
            modules = mapOf(com.openlattice.chronicle.study.StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
        ))
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

        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(Study(
            title = "Collection enabled", contact = "research@example.org",
            modules = mapOf(com.openlattice.chronicle.study.StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
        ))
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
        capturedAt: OffsetDateTime? = OffsetDateTime.parse("2026-07-12T03:10:17Z"),
        includeAcknowledgment: Boolean = true,
    ): List<Pair<String, () -> Unit>> {
        val timestamp = capturedAt ?: OffsetDateTime.MIN
        val sensorData = if (capturedAt == null) emptyList() else listOf(
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
        val batteryData = if (capturedAt == null) emptyList() else listOf(
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
        val iosBatteryData = batteryData.map {
            IosBatterySample(it.id, it.timestamp, it.timezone, it.levelPercent, it.chargingState)
        }
        val screenTimeEnvelope = ScreenTimeUsageEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = timestamp,
            records = if (capturedAt == null) emptyList() else listOf(ScreenTimeUsageRecord(
                id = UUID.randomUUID(), source = ScreenTimeCaptureSource.shortcutSnapshot,
                confidence = ScreenTimeConfidence.externalShortcut, capturedAt = timestamp,
                observationStart = timestamp.minusSeconds(1), observationEnd = timestamp,
                timezoneIdentifier = "UTC", appName = "Maps", durationSeconds = 1,
            )),
        )
        val userIdentificationEnvelope = UserIdentificationEnvelope(
            deviceId = sourceDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = timestamp,
            records = if (capturedAt == null) emptyList() else listOf(UserIdentificationRecord(
                UUID.randomUUID(), timestamp, "UTC", UserIdentificationTrigger.manualInApp, UserIdentificationChoice.participant,
            )),
        )
        val androidUsageData = ChronicleData(
            if (capturedAt == null) emptyList() else listOf(
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

        val uploads: List<Pair<String, () -> Unit>> = listOf(
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
                controller.uploadAndroidSensorData(studyId, participantId, sourceDeviceId,
                    if (capturedAt == null) emptyList() else listOf(AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.light, timestamp,
                        "UTC")))
            },
            "Android sensor availability" to {
                controller.reportAndroidSensorAvailability(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    AndroidDeviceSensorAvailability(reportedAt = capturedAt),
                )
            },
            "Android battery" to {
                controller.uploadBatteryTelemetry(studyId, participantId, sourceDeviceId, batteryData)
            },
            "iOS battery" to {
                controller.uploadIosBatteryTelemetry(studyId, participantId, sourceDeviceId, iosBatteryData)
            },
            "interaction events" to {
                controller.uploadInteractionEvents(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "audio activity" to {
                controller.uploadAudioActivity(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "ambient audio" to {
                controller.uploadAmbientAudio(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "audio content" to {
                controller.uploadAudioContent(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "notification activity" to {
                controller.uploadNotificationActivity(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "sleep events" to {
                controller.uploadSleepEvents(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "activity recognition" to {
                controller.uploadActivityRecognitionEvents(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "health metrics" to {
                controller.uploadHealthMetrics(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "connectivity state" to {
                controller.uploadConnectivityStateEvents(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "app network usage" to {
                controller.uploadAppNetworkUsage(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
            "device settings" to {
                controller.uploadDeviceSettings(studyId, participantId, sourceDeviceId, mobileCapturedEvents(capturedAt))
            },
        )
        return uploads + if (!includeAcknowledgment) emptyList() else listOf<Pair<String, () -> Unit>>("collection acknowledgment" to {
                controller.reportCollectionAcknowledgmentV4(
                    studyId,
                    participantId,
                    sourceDeviceId,
                    acknowledgment,
                )
            })
    }

    private inline fun <reified T : Any> mobileCapturedEvents(capturedAt: OffsetDateTime?): List<T> =
        if (capturedAt == null) emptyList() else listOf(Mockito.mock(T::class.java) { invocation ->
            when (invocation.method.name) {
                "getTimestamp" -> capturedAt
                "getEndMillis", "getBucketEndMillis" -> capturedAt.toInstant().toEpochMilli()
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

    private fun assertNoMobileUploadWriteInteractions(allowCollectionRecheck: Boolean = false) {
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
            storageResolver,
            webhookService,
        )
        if (allowCollectionRecheck) {
            verify(participantCollectionAcknowledgmentService, never()).recordAcknowledgment(kAny(), kAnyString(), kAnyString(), kAny(), kAny())
        } else {
            Mockito.verifyNoInteractions(participantCollectionAcknowledgmentService)
        }
    }

    private fun assertFailureAuditCount(expectedCount: Int) {
        val auditCaptor = argumentCaptor<AuditLogEntry>()
        verify(auditService, Mockito.times(expectedCount)).log(auditCaptor.capture())
        assertEquals(expectedCount, auditCaptor.allValues.size)
        assertTrue(auditCaptor.allValues.all { !it.success })
    }


    @Test fun obsoleteStudyPermissionsRoutesAreAbsent() {
        org.junit.Assert.assertFalse(StudyController::class.java.methods.any {
            it.name == "getStudyPermissions" || it.name == "updateStudyPermissions"
        })
        org.junit.Assert.assertFalse(com.openlattice.chronicle.study.StudyApi::class.java.methods.any {
            it.name == "getStudyPermissions" || it.name == "updateStudyPermissions"
        })
    }

    @Test
    fun testLifecycleUploadKeepsDeviceStateWithoutActivityClassConsent() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceDeviceId = "device-1"
        val deviceId = DeviceIdUtils.deriveDeviceId(studyId, participantId, sourceDeviceId)
        val sample = (TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1).single() as ChronicleUsageEvent)
            .copy(appPackageName = "android", interactionType = "Battery Low", activityClass = "battery:low", user = "")
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.DEVICE_LIFECYCLE))
        Mockito.`when`(appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny())).thenReturn(1)

        assertEquals(1, controller.uploadAndroidUsageEventData(studyId, participantId, sourceDeviceId, ChronicleData(listOf(sample))))
        verify(appDataUploadService).uploadAndroidUsageEvents(kEq(studyId), kEq(participantId), kEq(deviceId), kEq(listOf(sample)), kAny())
        verify(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Int>(
            kEq(studyId), kEq(participantId), kEq(deviceId), kEq(setOf(CollectionModuleId.DEVICE_LIFECYCLE)), org.mockito.kotlin.anyOrNull(), kEq(
                true), kAny(),
        )
    }

    @Test
    fun testBulkParticipantDeletionRequiresOwnerBeforeQuarantine() {
        val studyId = UUID.randomUUID()
        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenAnswer {
            com.openlattice.chronicle.authorization.Permission.OWNER !in it.getArgument<Set<com.openlattice.chronicle.authorization.Permission>>(2)
        }
        assertThrows(ForbiddenException::class.java) {
            controller.deleteStudyParticipants(studyId, setOf("participant-1"))
        }
        Mockito.verifyNoInteractions(dataDeletionOrchestrator)
    }

    @Test
    fun testSensorAvailabilityDisabledRejectsBeforeStorage() {
        val studyId = UUID.randomUUID()
        val participant = "participant-1"
        val source = "device-1"
        val device = DeviceIdUtils.deriveDeviceId(studyId, participant, source)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participant)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participant, device)).thenReturn(true)
        for (reportedAt in listOf(null, OffsetDateTime.parse("2026-10-01T00:00:00Z"))) {
            Mockito.doThrow(com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participant, device))
                .`when`(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Any?>(
                kEq(studyId), kEq(participant), kEq(device), kEq(setOf(CollectionModuleId.SENSOR_AVAILABILITY)),
                org.mockito.kotlin.eq(reportedAt?.toInstant()), kEq(false), kAny(),
            )
            val error = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
                controller.reportAndroidSensorAvailability(studyId, participant, source,
                    com.openlattice.chronicle.android.AndroidDeviceSensorAvailability(reportedAt = reportedAt))
            }
            assertEquals(403, error.statusCode.value())
            verify(participantCollectionAcknowledgmentService).withCollectionHaltRecheck<Any?>(
                kEq(studyId), kEq(participant), kEq(device), kEq(setOf(CollectionModuleId.SENSOR_AVAILABILITY)),
                org.mockito.kotlin.eq(reportedAt?.toInstant()), kEq(false), kAny(),
            )
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun testMixedSensorReceiptIdentifiesStoredDiscardedAndHeldRows() {
        val study = UUID.randomUUID()
        val participant = "participant-1"
        val source = "device-1"
        val device = DeviceIdUtils.deriveDeviceId(study, participant, source)
        val accepted = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.accelerometer, OffsetDateTime.now(), "UTC")
        val discarded = accepted.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.gyroscope)
        val held = accepted.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.light)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(study, participant)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(study, participant, device)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.getArgument<Any>(8) as (com.openlattice.chronicle.services.studies.CollectionUploadAuthorization) -> Any?)
                .invoke(com.openlattice.chronicle.services.studies.CollectionUploadAuthorization(
                    setOf(CollectionModuleId.SENSOR_ACCELEROMETER), setOf(CollectionModuleId.SENSOR_GYROSCOPE),
                    setOf(CollectionModuleId.SENSOR_LIGHT), 2))
        }.`when`(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
            kEq(study), kEq(participant), kEq(device), kAny(), org.mockito.kotlin.isNull(), kEq(true), org.mockito.kotlin.anyOrNull(), kEq(true),
                kAny(),
        )
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(Mockito.mock(ResultSet::class.java))
        val receipt = controller.uploadAndroidSensorDataReceiptV4(study, participant, source, listOf(accepted, discarded, held))
        receipt.validateSubmitted(setOf(accepted.id, discarded.id, held.id))
        assertEquals(setOf(accepted.id), receipt.acceptedIds)
        assertEquals(setOf(discarded.id), receipt.discardedIds)
        assertEquals(setOf(held.id), receipt.heldIds)
        verify(androidSensorDataUploadService).upload(study, participant, device, listOf(accepted))

        val cutoff = accepted.timestamp.plusSeconds(1)
        val atStop = accepted.copy(id = UUID.randomUUID(), timestamp = cutoff)
        val lateHeld = held.copy(id = UUID.randomUUID(), timestamp = cutoff.plusSeconds(1))
        val state = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(state.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getString("lifecycle_status")).thenReturn("ACTIVE")
        Mockito.`when`(rows.getString("participation_status")).thenReturn("PAUSED")
        Mockito.`when`(rows.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(cutoff)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenAnswer { invocation ->
            if (invocation.getArgument<String>(0).contains("LEFT JOIN LATERAL")) state else statement
        }
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(study, participant)).thenReturn(ParticipationStatus.PAUSED)
        val submitted = listOf(accepted, discarded, held, atStop, lateHeld)
        val stopped = controller.uploadAndroidSensorDataReceiptV4(study, participant, source, submitted)
        stopped.validateSubmitted(submitted.mapTo(linkedSetOf()) { it.id })
        assertEquals(setOf(accepted.id), stopped.acceptedIds)
        assertEquals(setOf(discarded.id, atStop.id, lateHeld.id), stopped.discardedIds)
        assertEquals(setOf(held.id), stopped.heldIds)
        verify(androidSensorDataUploadService, Mockito.times(2)).upload(study, participant, device, listOf(accepted))
    }

}
