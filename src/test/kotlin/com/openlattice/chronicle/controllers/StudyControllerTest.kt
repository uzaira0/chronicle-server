package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.services.studies.StudySettingsRevisionMismatchException
import com.openlattice.chronicle.services.studies.checkLockedStudyRevision
import com.openlattice.chronicle.services.studies.mergeStudySetting
import com.openlattice.chronicle.study.ParticipantDataType

import com.openlattice.chronicle.services.studies.CollectionUploadAuthorization
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.services.studies.CollectionUploadRequest
import com.openlattice.chronicle.services.studies.AuthorizedHealthScope
import com.openlattice.chronicle.services.upload.stubNoPurgeCutoff
import com.openlattice.chronicle.services.upload.stubOpenCollection
import com.openlattice.chronicle.services.upload.stubUploadReceiptTime
import com.openlattice.chronicle.storage.ParticipantCollectionGate

import com.openlattice.chronicle.collection.SensorCollectionModules
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.audit.accessedPHI
import com.openlattice.chronicle.audit.success
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.util.PaginationDefaults
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.collectionSettingsForFeatures
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.auditing.AuditObservation
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.EnrollmentAuthorization
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.Permission
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
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRecord
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRowKind
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.DeviceCollectionSettingsSnapshot
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.survey.SurveySettings
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.OffsetDateTime
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*

class StudyControllerTest {

    @Test
    fun deviceSqlBoundsRowsBeforeJdbcBuffersThePage() {
        val postgres = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        val schema = "device_page_" + UUID.randomUUID().toString().replace("-", "")
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("""CREATE VIEW $schema.devices AS SELECT '$study'::uuid AS study_id,
                    'bounded-participant'::text AS participant_id, md5(n::text)::uuid AS random_device_id,
                    'Android'::text AS device_type, '{}'::jsonb AS source_device FROM generate_series(1, 50) n""")
            }
        }
        com.zaxxer.hikari.HikariDataSource().use { pool ->
            pool.jdbcUrl = postgres.jdbcUrl
            pool.username = postgres.username
            pool.password = postgres.password
            pool.schema = schema
            pool.maximumPoolSize = 1
            val rowsReturned = java.util.concurrent.atomic.AtomicInteger()
            val observedPool = observedDevicePool(pool, rowsReturned)
            Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(observedPool)
            Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
            val page = controller.getStudyDevices(study, 1, 0)
            assertEquals(1, page.values.sumOf { it.size })
            assertEquals("SQL must bound rows before JDBC buffers them, including one continuation row", 2, rowsReturned.get())
        }
    }

    private fun observedDevicePool(
        pool: com.zaxxer.hikari.HikariDataSource,
        rowsReturned: java.util.concurrent.atomic.AtomicInteger,
    ): com.zaxxer.hikari.HikariDataSource = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java).also { observed ->
        Mockito.`when`(observed.connection).thenAnswer {
            val actual = pool.connection
            val connection = Mockito.mock(Connection::class.java, org.mockito.AdditionalAnswers.delegatesTo<Connection>(actual))
            Mockito.doAnswer { invocation ->
                observedDeviceStatement(actual.prepareStatement(invocation.getArgument<String>(0)), rowsReturned)
            }.`when`(connection).prepareStatement(kAnyString())
            connection
        }
    }

    private fun observedDeviceStatement(
        statement: PreparedStatement,
        rowsReturned: java.util.concurrent.atomic.AtomicInteger,
    ): PreparedStatement = Mockito.mock(
        PreparedStatement::class.java, org.mockito.AdditionalAnswers.delegatesTo<PreparedStatement>(statement),
    ).also { observed ->
        Mockito.doAnswer {
            // Count the actual database result, then replay the same deterministic SELECT for the controller.
            rowsReturned.set(statement.executeQuery().use(::countDeviceRows))
            statement.executeQuery()
        }.`when`(observed).executeQuery()
    }

    private fun countDeviceRows(rows: ResultSet): Int {
        var count = 0
        while (rows.next()) count++
        return count
    }

    @Test
    fun organizationStudyPageRecordsOneAuditBatch() {
        val organization = UUID.randomUUID()
        val studies = List(3) { Study(studyId = UUID.randomUUID(), title = "page", contact = "pi@example.com") }
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getOrgStudies(organization, 100, 0)).thenReturn(studies)
        assertEquals(studies, controller.getOrgStudies(organization, 100, 0))
        val observation = argumentCaptor<AuditObservation>()
        verify(auditingManager).recordObservations(observation.capture())
        assertEquals(studies.map { it.id }, observation.firstValue.events.map { it.study })
    }

    private val hazelcastInstance = Mockito.mock(HazelcastInstance::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val enrollmentService = Mockito.mock(EnrollmentService::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val screenTimeUsageUploadService = Mockito.mock(ScreenTimeUsageUploadService::class.java)
    private val androidSensorDataUploadService = Mockito.mock(AndroidSensorDataUploadService::class.java)
    private val batteryTelemetryUploadService = Mockito.mock(BatteryTelemetryUploadService::class.java)
    private val interactionEventsUploadService = Mockito.mock(InteractionEventsUploadService::class.java)
    private val appAudioActivityUploadService = Mockito.mock(AppAudioActivityUploadService::class.java)
    private val appAudioContentUploadService = Mockito.mock(AppAudioContentUploadService::class.java)
    private val notificationActivityUploadService = Mockito.mock(NotificationActivityUploadService::class.java)
    private val sleepEventsUploadService = Mockito.mock(SleepEventsUploadService::class.java)
    private val activityRecognitionEventsUploadService = Mockito.mock(ActivityRecognitionEventsUploadService::class.java)
    private val healthMetricsUploadService = Mockito.mock(HealthMetricsUploadService::class.java)
    private val connectivityStateEventsUploadService = Mockito.mock(ConnectivityStateEventsUploadService::class.java)
    private val appNetworkUsageUploadService = Mockito.mock(AppNetworkUsageUploadService::class.java)
    private val deviceSettingsUploadService = Mockito.mock(DeviceSettingsUploadService::class.java)
    private val uploadDiagnosticsUploadService = Mockito.mock(UploadDiagnosticsUploadService::class.java)
    private val appDataUploadService = Mockito.mock(AppDataUploadService::class.java)
    private val downloadService = Mockito.mock(DataDownloadService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val audit = RecordingAuditingManager()
    private val auditService = AuditService(audit)
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

        stubUploadAuthorization { modules -> CollectionUploadAuthorization(modules, emptySet(), 1, emptySet()) }

        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<CollectionUploadRequest>(0)
            participantCollectionAcknowledgmentService.withCollectionUploadRecheck(request, setOf(CollectionModuleId.HEALTH_CONNECT),
                allowPartial = false) {
                invocation.getArgument<(AuthorizedHealthScope) -> Any?>(1).invoke(AuthorizedHealthScope(
                    request.studyId, request.participantId, request.randomDeviceId, HealthConnectRecordType.entries.toSet()))
            }
        }.`when`(participantCollectionAcknowledgmentService).withHealthUploadRecheck<Any?>(kAny(), kAny())

        controller = StudyController(
            hazelcastInstance, storageResolver,
            enrollmentService, studyService, screenTimeUsageUploadService,
            androidSensorDataUploadService, batteryTelemetryUploadService,
            interactionEventsUploadService,
            appAudioActivityUploadService, appAudioContentUploadService,
            notificationActivityUploadService,
            sleepEventsUploadService, activityRecognitionEventsUploadService, healthMetricsUploadService,
            connectivityStateEventsUploadService, appNetworkUsageUploadService, deviceSettingsUploadService,
            uploadDiagnosticsUploadService,
            appDataUploadService, downloadService,
            enrollmentManager, authorizationManager, auditingManager,
            auditService, studySettingsAuditService,
            participantCollectionAcknowledgmentService, studySettingsNotificationService,
            apiKeyService, dataDeletionOrchestrator, studyLifecycleService, webhookService,
            enrollmentManifestService,
        )
        Mockito.doCallRealMethod().`when`(studySettingsAuditService)
            .recordSettingsDiff(kAny(), kAny(), kAny(), kAny())

        TestSecurityUtils.setupSecurityContext()
        Mockito.`when`(webhookService.withDataSubmission(kAny(), kAnyString(), kAny(), kAny())).thenAnswer {
            it.getArgument<() -> Int>(3).invoke()
        }
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kEq(EnumSet.of(Permission.WRITE)),
        )).thenReturn(true)
    }

    /**
     * Device-data writes run inside the atomic upload recheck. The real service opens a transaction; here it runs the
     * write with [authorize]'s answer for the batch's modules, so these tests still exercise the upload path.
     */
    private fun stubUploadAuthorization(authorize: (Set<CollectionModuleId>) -> CollectionUploadAuthorization) {
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(CollectionUploadAuthorization) -> Any?>(4).invoke(authorize(invocation.getArgument(1)))
        }.`when`(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
            kAny(), kAny(), Mockito.anyBoolean(), Mockito.anyBoolean(), kAny(),
        )
    }

    private fun stubConsentedUploadModules(modules: Set<CollectionModuleId>) =
        stubUploadAuthorization { requested -> CollectionUploadAuthorization(requested.intersect(modules), requested - modules, 1, emptySet()) }

    @Test
    fun testReadOnlyResearcherCannotWriteThroughMobileUploadGate() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(false)
        assertThrows(ForbiddenException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, "participant-1", "device-1", emptyList())
        }
        Mockito.verifyNoInteractions(androidSensorDataUploadService)
    }

    @Test
    fun testUploadCannotUseAnotherDeviceWithAMatchingKeyHeader() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val keyId = UUID.randomUUID()
        SecurityContextHolder.getContext().setAuthentication(ApiKeyAuthenticationToken(
            principal = "apikey:$keyId", keyId = keyId, studyId = studyId,
            participantId = participantId,
            randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, "device-1"),
            scope = ApiKeyScope.WRITE, authorities = emptyList(),
        ))
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, participantId, "device-2", emptyList())
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
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
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
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        stubUploadAuthorization { modules ->
            if (com.openlattice.chronicle.services.studies.collectionIsHalted(settings, listOf(settings), decisions, modules)) {
                throw com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participantId, randomDeviceId)
            }
            CollectionUploadAuthorization(modules, emptySet(), 1, emptySet())
        }
        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadInteractionEventsV4(studyId, participantId, sourceRandomDeviceId, emptyList())
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        Mockito.verifyNoInteractions(interactionEventsUploadService)
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
        val revision = 7L
        Mockito.`when`(rows.getLong(1)).thenReturn(revision)
        val service = StudyService(storageResolver, authorizationManager, mock(), mock(), mock(), idGenerationService,
            mock(), auditingManager, hazelcastInstance, mock(), webhookService)
        fun save() = service.persistLockedStudySettingsMutation(studyId, revision, studySettingsAuditService) { prior ->
            mergeStudySetting(prior, StudySettingType.Notifications, setting)
        }
        save()
        val audit = Mockito.mockingDetails(studySettingsAuditService).invocations.single { it.method.name == "recordSettingsChange" }
        assertTrue(audit.arguments[6] === connection)
        val order = Mockito.inOrder(studySettingsAuditService, connection)
        order.verify(studySettingsAuditService).recordSettingsChange(kAny(), kAnyString(), org.mockito.kotlin.anyOrNull(), kAny(),
            org.mockito.kotlin.anyOrNull(), kAny(), org.mockito.kotlin.eq(connection))
        order.verify(connection).commit()
        Mockito.clearInvocations(connection)
        Mockito.doAnswer { throw java.sql.SQLException("Audit write unavailable") }.`when`(studySettingsAuditService)
            .recordSettingsChange(kAny(), kAnyString(), org.mockito.kotlin.anyOrNull(), kAny(), org.mockito.kotlin.anyOrNull(),
                kAny(), org.mockito.kotlin.eq(connection))
        assertThrows(java.sql.SQLException::class.java) {
            save()
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
        val sourceRandomDeviceId: String,
        val enrollmentCode: String,
        val manifestDigest: String,
        val attemptId: UUID,
        val proposedApiKey: String,
        val randomDeviceId: UUID,
        val sourceDevice: AndroidDevice,
        val hardStoppedAt: OffsetDateTime = OffsetDateTime.parse("2026-10-01T12:00:00Z"),
    )

    private fun replayEnrollmentFixture(): ReplayEnrollmentFixture {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000321")
        val participantId = "participant-1"
        val sourceRandomDeviceId = "device-source-1"
        return ReplayEnrollmentFixture(
            studyId = studyId,
            participantId = participantId,
            sourceRandomDeviceId = sourceRandomDeviceId,
            enrollmentCode = "a".repeat(64),
            manifestDigest = "b".repeat(64),
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000654"),
            proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV", // gitleaks:allow -- deterministic test credential
            randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId),
            sourceDevice = AndroidDevice(
                device = "test-device",
                model = "test-model",
                codename = "test-codename",
                brand = "test-brand",
                osVersion = "15",
                sdkVersion = "35",
                product = "test-product",
                randomDeviceId = sourceRandomDeviceId,
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
                fixture.sourceRandomDeviceId,
                fixture.sourceDevice,
                fixture.proposedApiKey,
            ),
        ).thenReturn(EnrollmentAuthorization.AUTHORIZED)
        Mockito.`when`(studyService.getStudyId(fixture.studyId)).thenReturn(fixture.studyId)
        Mockito.`when`(
            enrollmentService.registerDevice(
                fixture.studyId,
                fixture.participantId,
                fixture.randomDeviceId,
                fixture.sourceDevice,
            ),
        ).thenReturn(fixture.randomDeviceId)
        Mockito.`when`(enrollmentService.getDeviceCollectionStatus(fixture.studyId, fixture.participantId)).thenReturn(
            MobileParticipationStatus(
                ParticipationStatus.ENROLLED, fixture.hardStoppedAt.plusDays(1), hardStop = HardStop(2, fixture.hardStoppedAt),
            ),
        )
        Mockito.`when`(response.rawKey).thenReturn(fixture.proposedApiKey)
        SecurityContextHolder.getContext().authentication = MobileEnrollmentAuthenticationToken(fixture.studyId)
        return response
    }

    private fun enroll(fixture: ReplayEnrollmentFixture) = controller.enrollV4(
        fixture.studyId,
        fixture.participantId,
        fixture.sourceRandomDeviceId,
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
                fixture.randomDeviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenReturn(apiKeyResponse)

        val response = enroll(fixture)

        assertEquals(fixture.randomDeviceId, response.chronicleId)
        assertEquals(fixture.proposedApiKey, response.apiKey)
        assertEquals(2L, response.hardStopGeneration)
        Mockito.inOrder(enrollmentManifestService, enrollmentService).apply {
            verify(enrollmentManifestService).authorizeEnrollmentAttempt(
                fixture.studyId,
                fixture.participantId,
                fixture.enrollmentCode,
                fixture.manifestDigest,
                fixture.attemptId.toString(),
                fixture.sourceRandomDeviceId,
                fixture.sourceDevice,
                fixture.proposedApiKey,
            )
            verify(enrollmentService).registerDevice(
                fixture.studyId,
                fixture.participantId,
                fixture.randomDeviceId,
                fixture.sourceDevice,
            )
        }
        verify(apiKeyService).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.randomDeviceId,
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
                fixture.randomDeviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenReturn(apiKeyResponse)

        val first = enroll(fixture)
        val replay = enroll(fixture)

        assertEquals(fixture.randomDeviceId, first.chronicleId)
        assertEquals(first, replay)
        assertEquals(fixture.proposedApiKey, replay.apiKey)
        verify(apiKeyService, Mockito.times(2)).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.randomDeviceId,
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
                fixture.randomDeviceId,
                fixture.attemptId,
                fixture.proposedApiKey,
            ),
        ).thenThrow(IllegalStateException("simulated transaction failure")).thenReturn(apiKeyResponse)

        assertThrows(IllegalStateException::class.java) { enroll(fixture) }
        val recovered = enroll(fixture)

        assertEquals(fixture.randomDeviceId, recovered.chronicleId)
        assertEquals(fixture.proposedApiKey, recovered.apiKey)
        verify(enrollmentManifestService, Mockito.times(2)).authorizeEnrollmentAttempt(
            fixture.studyId,
            fixture.participantId,
            fixture.enrollmentCode,
            fixture.manifestDigest,
            fixture.attemptId.toString(),
            fixture.sourceRandomDeviceId,
            fixture.sourceDevice,
            fixture.proposedApiKey,
        )
        verify(enrollmentService, Mockito.times(2)).registerDevice(
            fixture.studyId,
            fixture.participantId,
            fixture.randomDeviceId,
            fixture.sourceDevice,
        )
        verify(apiKeyService, Mockito.times(2)).installMobileApiKey(
            fixture.studyId,
            fixture.participantId,
            fixture.randomDeviceId,
            fixture.attemptId,
            fixture.proposedApiKey,
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
            studyLifecycleService.requestStudyDeletion(
                kEq(studyId),
                kAnyString(),
            )
        ).thenReturn(operationId)

        val result = controller.destroyStudy(studyId).toList()

        assertEquals(listOf(operationId), result)
        verify(studyLifecycleService).requestStudyDeletion(
            kEq(studyId),
            kAnyString(),
        )
    }

    /**
     * The database drops mid-request (pool exhausted, Postgres restarting). The researcher's create, edit or delete
     * fails with the real cause, and the audit trail records the failed attempt instead of nothing.
     */
    @Test
    fun studyWritesThatFailRethrowTheCauseAndAuditTheFailedAttempt() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val outage = java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available")
        val pool = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(pool.connection).thenThrow(outage)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(pool)
        Mockito.`when`(studyService.createStudy(kAny<Study>(), kAny<(Connection, UUID) -> Unit>())).thenAnswer { throw outage }
        Mockito.`when`(studyLifecycleService.requestStudyDeletion(kEq(studyId), kAnyString())).thenAnswer { throw outage }
        val writes = listOf<Pair<AuditEventType, () -> Any?>>(
            AuditEventType.STUDY_CREATE to { controller.createStudy(Study(title = "Pilot study", contact = "pi@example.cl")) },
            AuditEventType.STUDY_UPDATE to {
                val previous = RequestContextHolder.getRequestAttributes()
                val request = MockHttpServletRequest().apply { addHeader("If-Match", "\"3\"") }
                RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request, MockHttpServletResponse()))
                try {
                    controller.updateStudy(studyId, StudyUpdate(title = "Revised"), false)
                } finally {
                    RequestContextHolder.setRequestAttributes(previous)
                }
            },
            AuditEventType.STUDY_DELETE to { controller.destroyStudy(studyId) },
        )
        for ((action, write) in writes) {
            audit.observations.clear()
            val thrown = assertThrows(Exception::class.java) { write() }
            assertTrue("$action rethrows the outage, got $thrown", thrown === outage || thrown.cause === outage)
            val failure = audit.events.last { it.eventType == action }
            assertFalse("$action audited as failed", failure.success)
            assertTrue(audit.events.none { it.eventType == action && it.success })
        }
        verify(studyService, never()).refreshStudyCache(kAny())
    }

    @Test
    fun openingAStudyThatNoLongerExistsIsNotFoundAndAuditedAsAFailedView() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getStudy(studyId)).thenThrow(NoSuchElementException("gone"))

        assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) { controller.getStudy(studyId) }

        val view = audit.events.single { it.eventType == AuditEventType.VIEW }
        assertFalse(view.success)
        assertEquals(studyId, view.study)
    }

    @Test
    fun bulkParticipantDeleteRefusesMoreIdsThanOneRequestMayCarry() {
        val studyId = UUID.randomUUID()
        val tooMany = (0..PaginationDefaults.MAX_BULK_IDS).map { "p-$it" }.toSet()

        val refused = assertThrows(IllegalArgumentException::class.java) { controller.deleteStudyParticipants(studyId, tooMany) }

        assertEquals("Cannot delete more than ${PaginationDefaults.MAX_BULK_IDS} participants in a single request", refused.message)
        Mockito.verifyNoInteractions(dataDeletionOrchestrator, studyLifecycleService)
    }

    @Test
    fun allStudiesRefusesAListItWouldHaveToTruncate() {
        Mockito.`when`(authorizationManager.listAuthorizedObjectsOfType(kAny(), kEq(SecurableObjectType.Study), kAny()))
            .thenReturn(List(PaginationDefaults.MAX_BULK_IDS + 1) { AclKey(UUID.randomUUID()) })

        assertThrows(IllegalArgumentException::class.java) { controller.getAllStudies(100, 0) }
        verify(studyService, never()).getStudies(kAny())
    }

    @Test
    fun testAndroidUsageUploadUsesRealParticipantIdForServiceLookup() {
        val studyId = UUID.randomUUID()
        val participantId = "android-pixel-aws-20260702"
        val sourceRandomDeviceId = "pixel-device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
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
                    clientEventId = UUID.randomUUID(),
                )
            )
        )
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        Mockito.`when`(
            appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny())
        ).thenReturn(setOf((data.single() as ChronicleUsageEvent).clientEventId!!))

        val result = controller.uploadAndroidUsageEventDataV4(studyId, participantId, sourceRandomDeviceId, data)

        assertEquals(setOf((data.single() as ChronicleUsageEvent).clientEventId), result.acceptedIds)
        verify(appDataUploadService).uploadAndroidUsageEvents(
            kEq(studyId),
            kEq(participantId),
            kEq(randomDeviceId),
            kEq(listOf(data.single() as ChronicleUsageEvent)),
            kAny(),
        )
        verify(webhookService).withDataSubmission(
            kEq(studyId),
            kEq(participantId),
            kEq(ParticipantDataType.UsageEvents),
            kAny(),
        )
    }

    @Test
    fun testMixedUsageUploadStripsUnauthorizedOptionalFields() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceRandomDeviceId = "device-1"
        val usage = TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1).single() as ChronicleUsageEvent
        val withActivity = usage.copy(activityClass = "MainActivity", user = "")
        val withUser = usage.copy(activityClass = null, user = "Child", clientEventId = UUID.randomUUID())
        val plain = usage.copy(activityClass = null, user = "", clientEventId = UUID.randomUUID())
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        val device = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, device)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.USAGE_EVENTS))
        val submitted = setOf(withActivity.clientEventId!!, withUser.clientEventId!!, plain.clientEventId!!)
        Mockito.`when`(appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny()))
            .thenReturn(submitted)

        val receipt = controller.uploadAndroidUsageEventDataV4(
            studyId, participantId, sourceRandomDeviceId, ChronicleData(listOf(withActivity, withUser, plain)),
        )
        // The rows are kept; the activity class and user they were not authorized for never reach storage.
        assertEquals(submitted, receipt.acceptedIds)
        assertEquals(emptySet<UUID>(), receipt.discardedIds)
        verify(appDataUploadService).uploadAndroidUsageEvents(
            kEq(studyId), kEq(participantId), kEq(device),
            kEq(listOf(withActivity.copy(activityClass = null), withUser.copy(user = ""), plain)), kAny(),
        )
    }

    @Test
    fun testMixedSensorUploadReturnsRetryableCollectionHaltBeforeStoringAnyRows() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-1"
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        val light = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.light, OffsetDateTime.now(), "UTC")
        val accelerometer = light.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.accelerometer)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.SENSOR_LIGHT))
        Mockito.`when`(androidSensorDataUploadService.upload(kAny(), kAnyString(), kAny(), kAnyList())).thenReturn(1)

        val error = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId, listOf(light, accelerometer))
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
        Mockito.verifyNoInteractions(webhookService)
    }

    @Test
    fun testAndroidSensorUploadRejectsNotEnrolledParticipantWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "not-enrolled"
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, participantId, "device-1", emptyList())
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
            controller.uploadAndroidSensorDataV4(studyId, participantId, "device-1", emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testAndroidSensorUploadRejectsUnknownDataSourceWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "enrolled"
        val sourceRandomDeviceId = "unknown-device"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(false)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId, emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testMobileUploadsRejectInactiveParticipationStatusesWithoutWritingData() {
        val studyId = UUID.randomUUID()
        val participantId = "not-enrolled"
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)

        var expectedAuditCount = 0
        ParticipationStatus.values().filter {
            it !in setOf(ParticipationStatus.ENROLLED, ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)
        }.forEach { status ->
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
            val calls = mobileUploadCalls(studyId, participantId, sourceRandomDeviceId)
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
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        val cutoff = OffsetDateTime.parse("2026-07-12T04:00:00Z")
        val connection = Mockito.mock(java.sql.Connection::class.java)
        val statement = Mockito.mock(java.sql.PreparedStatement::class.java)
        val rows = Mockito.mock(java.sql.ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getString("lifecycle_status")).thenReturn("ACTIVE")
        Mockito.`when`(rows.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(cutoff)
        com.openlattice.chronicle.services.upload.stubNoClosedCaptureWindows(rows)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            if (!ParticipantCollectionGate(connection).isActive(studyId, participantId, invocation.getArgument(3))) {
                throw com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participantId, randomDeviceId)
            }
            invocation.getArgument<(CollectionUploadAuthorization) -> Any?>(4)
                .invoke(CollectionUploadAuthorization(invocation.getArgument(1), emptySet(), 1, emptySet()))
        }.`when`(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
            kAny(), kAny(), Mockito.anyBoolean(), Mockito.anyBoolean(), kAny(),
        )

        val queuedStatuses = listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)
        for (status in queuedStatuses) {
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
            Mockito.`when`(rows.getString("participation_status")).thenReturn(status.name)
            for (capturedAt in listOf(null, cutoff, cutoff.plusSeconds(1))) {
                // The acknowledgment's admission under PAUSED/COMPLETED runs through the real route (
                // ConsentCaptureAuthorizationTest.aDecisionDeliveredAfterCompletionLandsAndItsRowsUpload).
                val calls = mobileUploadCalls(studyId, participantId, sourceRandomDeviceId, capturedAt, includeAcknowledgment = false)
                calls.forEach { (name, upload) ->
                    if (name == "Android sensor availability") {
                        val error = assertThrows("$name must reject $status capture at $capturedAt", ResponseStatusException::class.java) { upload() }
                        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
                    } else upload()
                }
            }
        }

        val dataSource = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(statement.connection).thenReturn(connection)
        stubUploadReceiptTime(connection)
        for (status in queuedStatuses) {
            Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(status)
            Mockito.`when`(rows.getString("participation_status")).thenReturn(status.name)
            // Availability is a current-state report, refused while stopped whatever its phone stamp (one clock).
            mobileUploadCalls(studyId, participantId, sourceRandomDeviceId, cutoff.minusSeconds(1), includeAcknowledgment = false)
                .filter { (name, _) -> name != "Android sensor availability" }.forEach { (_, upload) -> upload() }
        }
        verify(screenTimeUsageUploadService, Mockito.times(8)).upload(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kAny())
        verify(appDataUploadService, Mockito.times(6)).uploadAndroidUsageEvents(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kAnyList(),
            org.mockito.kotlin.any())
        listOf(androidSensorDataUploadService, batteryTelemetryUploadService, interactionEventsUploadService,
            appAudioActivityUploadService, appAudioContentUploadService,
            notificationActivityUploadService, sleepEventsUploadService, activityRecognitionEventsUploadService,
            healthMetricsUploadService, connectivityStateEventsUploadService, appNetworkUsageUploadService,
            deviceSettingsUploadService).forEach { service ->
            assertEquals("Every batch stream reaches its row admission even when its maximum crosses the cutoff", 8,
                Mockito.mockingDetails(service).invocations.count { it.method.name == "upload" })
        }
        verify(batteryTelemetryUploadService, Mockito.times(8)).upload(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kAnyList())
        verify(batteryTelemetryUploadService, Mockito.times(8)).uploadIos(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kAnyList())
        verify(statement, Mockito.never()).executeUpdate()
    }

    @Test
    fun availabilityReceiptUpdatesLastUploadUsingTheDatabaseReceiptInstant() {
        val studyId = UUID.randomUUID()
        val participantId = "availability-only"
        val sourceDeviceId = "availability-device"
        val deviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceDeviceId)
        val receivedAt = OffsetDateTime.parse("2026-10-24T12:00:00Z")
        val source = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java)
        val connection = Mockito.mock(java.sql.Connection::class.java)
        val statement = Mockito.mock(java.sql.PreparedStatement::class.java)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)).thenReturn(true)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(source)
        Mockito.`when`(source.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        Mockito.`when`(statement.connection).thenReturn(connection)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        stubUploadReceiptTime(connection, receivedAt)

        assertEquals(1, controller.reportAndroidSensorAvailability(studyId, participantId, sourceDeviceId,
            AndroidDeviceSensorAvailability(reportedAt = receivedAt.minusDays(1))))

        verify(statement).setObject(3, receivedAt)
        verify(statement, Mockito.times(2)).executeUpdate()
        verify(source).connection
    }

    @Test
    fun testMobileUploadsRejectUnknownDataSourceWithoutAcknowledgingData() {
        val studyId = UUID.randomUUID()
        val participantId = "enrolled"
        val sourceRandomDeviceId = "unknown-device"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(false)

        val calls = mobileUploadCalls(studyId, participantId, sourceRandomDeviceId)
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
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        Mockito.`when`(
            participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, randomDeviceId),
        ).thenReturn(true)

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId, emptyList())
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Collection is halted pending required consent", error.reason)
        verify(androidSensorDataUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAnyList())
    }

    @Test
    fun testMobileUploadRejectionLogsAndAuditsOnlySanitizedReferences() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-phi-sentinel"
        val sourceRandomDeviceId = "source-device-phi-sentinel"
        val derivedRandomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)

        val capture = ScopedLogCapture(StudyController::class.java, pattern = "%m")
        val captured = capture.messages

        try {
            assertThrows(ResponseStatusException::class.java) {
                controller.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId, emptyList())
            }
        } finally {
            capture.close()
        }

        val log = captured.joinToString("\n")
        assertTrue("participant fingerprint missing from rejection log: $log", log.contains("participant:"))
        assertTrue("device fingerprint missing from rejection log: $log", log.contains("device:"))
        assertTrue("sanitized device label missing from rejection log: $log", log.contains("dataSourceRef"))
        assertFalse("raw participant identifier leaked into rejection log: $log", log.contains(participantId))
        assertFalse("raw source-random device identifier leaked into rejection log: $log", log.contains(sourceRandomDeviceId))
        assertFalse("raw derived random device identifier leaked into rejection log: $log", log.contains(derivedRandomDeviceId.toString()))

        val auditEntry = audit.events.single()
        assertFalse(auditEntry.success)
        assertEquals("error.enrollment.notEnrolled", auditEntry.data["reason"])
        assertEquals(studyId, auditEntry.study)
    }

    @Test
    fun cachedStudyCannotAdvertiseTheRevisionOfAWriteAwaitingCacheEviction() {
        val studyId = UUID.randomUUID()
        val cached = Study(studyId = studyId, title = "before concurrent commit", contact = "test@example.com")
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(cached)
        val response = MockHttpServletResponse()
        val previous = RequestContextHolder.getRequestAttributes()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest(), response))
        try {
            assertEquals(cached, controller.getStudy(studyId))
            assertEquals("\"0\"", response.getHeader("ETag"))
        } finally {
            RequestContextHolder.setRequestAttributes(previous)
        }
    }

    @Test
    fun settingsReadsKeepTheBodyAndRevisionFromTheSameCachedStudy() {
        val studyId = UUID.randomUUID()
        val cachedSetting = SurveySettings(appUsageThresholdInSeconds = 42)
        val cached = Study(studyId = studyId, title = "cached settings", contact = "test@example.com",
            settings = StudySettings(mapOf(StudySettingType.Survey to cachedSetting)))
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(cached)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(mapOf(
            StudySettingType.Survey to SurveySettings(appUsageThresholdInSeconds = 999),
        ))
        val response = MockHttpServletResponse()
        val previous = RequestContextHolder.getRequestAttributes()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest(), response))
        try {
            assertEquals(cachedSetting, controller.getStudySettings(studyId)[StudySettingType.Survey])
            assertEquals("\"0\"", response.getHeader("ETag"))
            assertEquals(cachedSetting, controller.getStudySetting(studyId, StudySettingType.Survey))
            assertEquals("\"0\"", response.getHeader("ETag"))
        } finally {
            RequestContextHolder.setRequestAttributes(previous)
        }
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
                randomDeviceId = null,
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

        val events = argumentCaptor<AuditObservation.Read>()
        verify(auditingManager, Mockito.atLeastOnce()).recordObservations(events.capture())
        val studyRead = events.allValues.flatMap { it.events }.first { it.eventType == AuditEventType.GET_STUDY }
        assertEquals("apikey:$keyId", studyRead.principal.id)
        assertEquals(keyId, studyRead.securablePrincipalId)
        assertTrue(audit.events.isNotEmpty() && audit.events.all { it.securablePrincipalId == keyId })
    }

    // ===================== Collection loop closure: acknowledgments =====================

    @Test
    fun testGetStudyCollectionAcknowledgmentsDelegatesToService() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val entry = CollectionAcknowledgmentEntry(
            studyId = studyId,
            participantId = "p-1",
            sourceRandomDeviceId = "dev-1",
            acknowledgedModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            acknowledgedAt = OffsetDateTime.now(),
        )
        Mockito.`when`(participantCollectionAcknowledgmentService.getAcknowledgments(kAny(), kAnyInt(), kAnyInt()))
            .thenReturn(listOf(entry))

        val result = controller.getStudyCollectionAcknowledgments(studyId, 50, 0)
        assertEquals(1, result.size)
        assertEquals(setOf(CollectionModuleId.SENSOR_ACCELEROMETER), result[0].acknowledgedModules)
        verify(participantCollectionAcknowledgmentService).getAcknowledgments(kAny(), kAnyInt(), kAnyInt())
        val event = audit.events.single()
        assertEquals(AuditEventType.PARTICIPANT_DATA_ACCESS, event.eventType)
        assertTrue(event.accessedPHI)
        assertEquals(studyId, event.study)
        assertEquals(1, event.data["participantCount"])
    }

    @Test
    fun testReportCollectionAcknowledgmentRecordsWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceRandomDeviceId = "dev-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        val apiKeyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        Mockito.`when`(
            participantCollectionAcknowledgmentService.isCollectionHalted(studyId, participantId, randomDeviceId),
        ).thenReturn(true)
        val ack = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            acknowledgedAt = OffsetDateTime.now(),
            appVersion = "1.0",
            settingsVersion = 7,
            disclosureVersion = "consent-2026-08-17",
            manifestDigest = "a".repeat(64),
        )
        SecurityContextHolder.getContext().setAuthentication(ApiKeyAuthenticationToken(
            principal = "apikey:$apiKeyId",
            keyId = apiKeyId,
            studyId = studyId,
            participantId = participantId,
            randomDeviceId = randomDeviceId,
            scope = ApiKeyScope.WRITE,
            authorities = emptyList(),
        ))
        Mockito.`when`(
            participantCollectionAcknowledgmentService.recordAcknowledgment(
                kAny(), kAnyString(), kAnyString(), kAny(), kAny(),
            )
        ).thenReturn(
            CollectionAcknowledgmentEntry(
                studyId = studyId, participantId = participantId, sourceRandomDeviceId = sourceRandomDeviceId,
                acknowledgedModules = ack.acknowledgedModules, acknowledgedAt = ack.acknowledgedAt,
            )
        )

        val result = controller.reportCollectionAcknowledgmentV4(studyId, participantId, sourceRandomDeviceId, ack)
        assertNotNull(result)
        verify(participantCollectionAcknowledgmentService).recordAcknowledgment(
            kEq(studyId), kEq(participantId), kEq(sourceRandomDeviceId), kEq(apiKeyId), kEq(ack),
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
            acknowledgedModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
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
        val sourceRandomDeviceId = "unknown-device"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(false)
        val ack = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            acknowledgedAt = OffsetDateTime.now(),
        )

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.reportCollectionAcknowledgmentV4(studyId, participantId, sourceRandomDeviceId, ack)
        }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(participantCollectionAcknowledgmentService, never()).recordAcknowledgment(
            kAny(), kAnyString(), kAnyString(), kAny(), kAny(),
        )
    }

    @Test
    fun testUploadScreenTimeDataWritesTheScreenTimeTableWhenEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceRandomDeviceId = "ios-device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:01:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            randomDeviceId = sourceRandomDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    rowKind = ScreenTimeUsageRowKind.application,
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
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        Mockito.`when`(
            screenTimeUsageUploadService.upload(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kEq(envelope))
        ).thenReturn(1)

        val result = controller.uploadScreenTimeData(studyId, participantId, sourceRandomDeviceId, envelope)

        assertEquals(1, result)
        verify(screenTimeUsageUploadService).upload(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kEq(envelope))
        verify(webhookService).withDataSubmission(kEq(studyId), kEq(participantId), kEq(ParticipantDataType.IOSScreenTime), kAny())
    }

    @Test
    fun testUploadScreenTimeDataRejectsWhenNotEnrolled() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val sourceRandomDeviceId = "ios-device-1"
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:01:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            randomDeviceId = sourceRandomDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    rowKind = ScreenTimeUsageRowKind.application,
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
            controller.uploadScreenTimeData(studyId, participantId, sourceRandomDeviceId, envelope)
        }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Participant or data source is not enrolled", error.reason)
        verify(screenTimeUsageUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAny())
    }

    @Test
    fun testUploadScreenTimeDataRejectsEnvelopeIdentityMismatchBeforeLookup() {
        val studyId = UUID.randomUUID()
        val capturedAt = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            randomDeviceId = "different-device",
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
        verify(screenTimeUsageUploadService, never()).upload(kAny(), kAnyString(), kAny(), kAny())
    }

    @Test
    fun theParticipantPageRunsItsQueryOnceHoweverOftenTheResponseIsRead() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val studyId = UUID.randomUUID()
        var queries = 0
        val lazyRows = Iterable { queries += 1; emptyList<Participant>().iterator() }
        Mockito.`when`(studyService.getStudyParticipants(studyId, 100, 0)).thenReturn(lazyRows)

        val page = controller.getStudyParticipants(studyId, 100, 0)
        // The audit row counts it and Jackson writes it: two reads, one query.
        page.count()
        page.toList()

        assertEquals(1, queries)
    }

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

    // ===================== Generalized DataCollection read =====================





    @Test
    fun testGetStudySettingReturnsStoredDataCollectionSetting() {
        // New setting storage round-trip: a stored AndroidDataCollectionSetting is
        // returned verbatim from the existing typed endpoint — no new route.
        val studyId = UUID.randomUUID()
        val stored = CollectionDefaults.androidDataCollectionSetting()
        val snapshot = DeviceCollectionSettingsSnapshot(stored, 1)
        Mockito.`when`(participantCollectionAcknowledgmentService.collectionSettingsSnapshotForDevice(studyId))
            .thenReturn(snapshot)
        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        assertEquals(stored, result)
        verify(studyService, never()).getStudy(studyId)
    }

    @Test
    fun testFeatureOffPublicCollectionReadStopsEveryCollectionModule() {
        val studyId = UUID.randomUUID()
        val versioned = CollectionDefaults.androidDataCollectionSetting()
        val projected = listOf(StudySettings(mapOf(StudySettingType.DataCollection to versioned)), StudySettings(emptyMap()))
            .map { settings ->
                val materialized = StudySettings(settings + (StudySettingType.DataCollection to settings.androidDataCollection()))
                collectionSettingsForFeatures(materialized, emptySet()).androidDataCollection()
            }
        val first = DeviceCollectionSettingsSnapshot(projected[0], 1)
        val second = DeviceCollectionSettingsSnapshot(projected[1], 2)
        Mockito.`when`(participantCollectionAcknowledgmentService.collectionSettingsSnapshotForDevice(studyId))
            .thenReturn(first, second)
        repeat(2) {
            val read = controller.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
            assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), read.effectiveEnabledModuleIds())
        }
    }

    @Test
    fun testPublicStudySettingRejectsUnknownStudyBeforeReadingSettings() {
        val studyId = UUID.randomUUID()

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.getStudySetting(studyId, StudySettingType.DataCollection)
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        verify(studyService, never()).getStudySettings(studyId)
        verify(studyService, never()).getStudy(studyId)
    }

    @Test
    fun testDetailsRevisionCheckRejectsOnlyStaleWriteUnderStudyRowLock() {
        // Absent precondition: no read at all. Matching: passes. Stale: mismatch with the current revision.
        for ((expectedRevision, stale) in listOf(null to false, 2L to false, 1L to true)) {
            val studyId = UUID.randomUUID()
            val connection = Mockito.mock(Connection::class.java)
            val statement = Mockito.mock(PreparedStatement::class.java)
            val resultSet = Mockito.mock(ResultSet::class.java)
            Mockito.`when`(connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ? FOR UPDATE"))
                .thenReturn(statement)
            Mockito.`when`(connection.prepareStatement("SELECT settings_revision FROM studies WHERE study_id = ?"))
                .thenReturn(statement)
            Mockito.`when`(statement.executeQuery()).thenReturn(resultSet)
            Mockito.`when`(resultSet.next()).thenReturn(true)
            Mockito.`when`(resultSet.getLong(1)).thenReturn(2L)

            if (stale) {
                val error = assertThrows(StudySettingsRevisionMismatchException::class.java) {
                    checkLockedStudyRevision(connection, studyId, expectedRevision)
                }
                assertEquals(2L, error.currentRevision)
            } else {
                checkLockedStudyRevision(connection, studyId, expectedRevision)
            }
            if (expectedRevision == null) {
                verify(connection, never()).prepareStatement(Mockito.anyString())
                verify(studyService, never()).getStudySettingsRevision(connection, studyId)
            }
        }
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
        // A changed title updates the stored row; an equal one matches none and keeps the revision.
        Mockito.`when`(update.executeUpdate()).thenReturn(1, 0)
        Mockito.`when`(bump.executeQuery()).thenReturn(revision)
        Mockito.`when`(revision.next()).thenReturn(true)
        Mockito.`when`(revision.getLong(1)).thenReturn(1L)

        val studyId = UUID.randomUUID()
        service.updateStudy(connection, studyId, StudyUpdate(title = "Revised title"))
        service.updateStudy(connection, studyId, StudyUpdate(title = "Revised title"))

        verify(update, Mockito.times(2)).executeUpdate()
        verify(bump).executeQuery()
    }



    @Test
    fun testGetStudySettingDataCollectionMissingWithEmptySensorsEnablesNoSensor() {
        // old -> new fallback, empty legacy: no DataCollection, no AndroidSensor at all
        // -> safe default, no per-sensor module enabled (no privacy-sensitive module enabled).
        val studyId = UUID.randomUUID()
        val snapshot = DeviceCollectionSettingsSnapshot(StudySettings(emptyMap()).androidDataCollection(), 0)
        Mockito.`when`(participantCollectionAcknowledgmentService.collectionSettingsSnapshotForDevice(studyId))
            .thenReturn(snapshot)
        val result = controller.getStudySetting(studyId, StudySettingType.DataCollection)
        assertTrue(result is AndroidDataCollectionSetting)
        val setting = result as AndroidDataCollectionSetting
        assertTrue(
            "missing setting must not enable any sensor module",
            SensorCollectionModules.sensorModuleIds.none { setting.modules.getValue(it).enabled },
        )
    }



    private fun mobileUploadCalls(
        studyId: UUID,
        participantId: String,
        sourceRandomDeviceId: String,
        capturedAt: OffsetDateTime? = OffsetDateTime.parse("2026-07-12T03:10:17Z"),
        includeAcknowledgment: Boolean = true,
    ): List<Pair<String, () -> Unit>> {
        val timestamp = capturedAt ?: OffsetDateTime.MIN
        val batteryData = if (capturedAt == null) emptyList() else listOf(
            BatterySample(
                id = UUID.randomUUID().toString(),
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
            randomDeviceId = sourceRandomDeviceId,
            studyId = studyId.toString(),
            participantId = participantId,
            generatedAt = timestamp,
            records = if (capturedAt == null) emptyList() else listOf(ScreenTimeUsageRecord(
                id = UUID.randomUUID(), rowKind = ScreenTimeUsageRowKind.application, capturedAt = timestamp,
                observationStart = timestamp.minusSeconds(1), observationEnd = timestamp,
                timezoneIdentifier = "UTC", appName = "Maps", durationSeconds = 1,
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
                    clientEventId = UUID.randomUUID(),
                )
            )
        )
        val acknowledgment = CollectionAcknowledgment(
            acknowledgedModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            acknowledgedAt = timestamp,
        )

        val uploads: List<Pair<String, () -> Unit>> = listOf(
            "iOS Screen Time" to {
                controller.uploadScreenTimeData(studyId, participantId, sourceRandomDeviceId, screenTimeEnvelope)
            },
            "Android usage" to {
                controller.uploadAndroidUsageEventDataV4(studyId, participantId, sourceRandomDeviceId, androidUsageData)
            },
            "Android sensors" to {
                controller.uploadAndroidSensorDataV4(studyId, participantId, sourceRandomDeviceId,
                    if (capturedAt == null) emptyList() else listOf(AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.light, timestamp,
                        "UTC")))
            },
            "Android sensor availability" to {
                controller.reportAndroidSensorAvailability(
                    studyId,
                    participantId,
                    sourceRandomDeviceId,
                    AndroidDeviceSensorAvailability(reportedAt = capturedAt),
                )
            },
            "Android battery" to {
                controller.uploadBatteryTelemetryV4(studyId, participantId, sourceRandomDeviceId, batteryData)
            },
            "iOS battery" to {
                controller.uploadIosBatteryTelemetry(studyId, participantId, sourceRandomDeviceId, iosBatteryData)
            },
            "interaction events" to {
                controller.uploadInteractionEventsV4(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "audio activity" to {
                controller.uploadAudioActivity(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "audio content" to {
                controller.uploadAudioContent(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "notification activity" to {
                controller.uploadNotificationActivity(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "sleep events" to {
                controller.uploadSleepEvents(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "activity recognition" to {
                controller.uploadActivityRecognitionEvents(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "health metrics" to {
                controller.uploadHealthMetrics(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "connectivity state" to {
                controller.uploadConnectivityStateEvents(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "app network usage" to {
                controller.uploadAppNetworkUsage(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
            "device settings" to {
                controller.uploadDeviceSettings(studyId, participantId, sourceRandomDeviceId, mobileCapturedEvents(capturedAt))
            },
        )
        return uploads + if (!includeAcknowledgment) emptyList() else listOf<Pair<String, () -> Unit>>("collection acknowledgment" to {
                controller.reportCollectionAcknowledgmentV4(
                    studyId,
                    participantId,
                    sourceRandomDeviceId,
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
            screenTimeUsageUploadService,
            androidSensorDataUploadService,
            batteryTelemetryUploadService,
            interactionEventsUploadService,
            appAudioActivityUploadService,
            appAudioContentUploadService,
            notificationActivityUploadService,
            sleepEventsUploadService,
            activityRecognitionEventsUploadService,
            healthMetricsUploadService,
            connectivityStateEventsUploadService,
            appNetworkUsageUploadService,
            deviceSettingsUploadService,
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
        assertEquals(expectedCount, audit.events.size)
        assertTrue(audit.events.all { !it.success })
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
        val sourceRandomDeviceId = "device-1"
        val randomDeviceId = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participantId, sourceRandomDeviceId)
        val sample = (TestDataFactory.chronicleUsageEvents(studyId, participantId, count = 1).single() as ChronicleUsageEvent)
            .copy(appPackageName = "android", interactionType = "Battery Low", activityClass = "battery:low", user = "")
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participantId)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participantId, randomDeviceId)).thenReturn(true)
        stubConsentedUploadModules(setOf(CollectionModuleId.DEVICE_LIFECYCLE))
        Mockito.`when`(appDataUploadService.uploadAndroidUsageEvents(kAny(), kAnyString(), kAny(), kAnyList(), kAny()))
            .thenReturn(setOf(sample.clientEventId!!))

        assertEquals(setOf(sample.clientEventId), controller.uploadAndroidUsageEventDataV4(studyId, participantId, sourceRandomDeviceId,
            ChronicleData(listOf(sample))).acceptedIds)
        verify(appDataUploadService).uploadAndroidUsageEvents(kEq(studyId), kEq(participantId), kEq(randomDeviceId), kEq(listOf(sample)), kAny())
        verify(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
            kEq(CollectionUploadRequest(studyId, participantId, randomDeviceId, null, null)),
            kEq(setOf(CollectionModuleId.DEVICE_LIFECYCLE)), kEq(true), kEq(true), kAny(),
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

    /**
     * The phone reports which sensors it has while Postgres is unreachable. The phone gets the real error (so it
     * retries) and the audit trail records a failed submission, not a stored one.
     */
    @Test
    fun sensorAvailabilityReportThatCannotBeStoredIsAuditedAsAFailedSubmission() {
        val studyId = UUID.randomUUID()
        val participant = "participant-1"
        val source = "device-1"
        val device = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participant, source)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participant)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participant, device)).thenReturn(true)
        val outage = java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available")
        val pool = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(pool.connection).thenThrow(outage)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(pool)

        val thrown = assertThrows(Exception::class.java) {
            controller.reportAndroidSensorAvailability(studyId, participant, source,
                com.openlattice.chronicle.android.AndroidDeviceSensorAvailability(reportedAt = OffsetDateTime.parse("2026-10-01T00:00:00Z")))
        }

        assertTrue("outage rethrown, got $thrown", thrown === outage || thrown.cause === outage)
        val submission = audit.events.single { it.eventType == AuditEventType.DATA_SUBMISSION }
        assertFalse(submission.success)
        assertEquals(studyId, submission.study)
    }

    /** The dashboard's devices and sensor-availability views fail on a database outage and are audited as failed reads. */
    @Test
    fun researcherDeviceViewsThatFailAreAuditedAsFailedParticipantDataAccess() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        val outage = java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available")
        val pool = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(pool.connection).thenThrow(outage)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(pool)
        val views = listOf<Pair<String, () -> Any?>>(
            "Device" to { controller.getStudyDevices(studyId) },
            "SensorData" to { controller.getStudySensorAvailability(studyId) },
        )
        for ((resource, view) in views) {
            audit.observations.clear()
            assertSame(outage, assertThrows(Exception::class.java) { view() })
            val access = audit.events.single { it.eventType == AuditEventType.PARTICIPANT_DATA_ACCESS }
            assertFalse("$resource read audited as failed", access.success)
            assertEquals(studyId, access.study)
        }
    }

    /**
     * The diagnostics view of the dashboard needs study read access; a reader's page is audited as a PHI read with
     * its row count, and a caller without read access is refused before any query runs.
     */
    @Test
    fun uploadDiagnosticsViewIsAuditedForReadersAndRefusedToOthers() {
        val studyId = UUID.randomUUID()
        val pool = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.getPlatformReadStorage()).thenReturn(pool)
        Mockito.`when`(pool.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)

        assertThrows(ForbiddenException::class.java) {
            controller.getAndroidDiagnostics(studyId, null, null, null, null, null, null, null, 50)
        }
        Mockito.verifyNoInteractions(pool)
        assertTrue(audit.events.none { it.eventType == AuditEventType.PARTICIPANT_DATA_ACCESS && it.success })

        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kEq(EnumSet.of(Permission.READ))))
            .thenReturn(true)
        val page = controller.getAndroidDiagnostics(studyId, "participant-1", null, null, null, null, null, null, 50)

        assertEquals(emptyList<Any>(), page.items)
        val access = audit.events.single { it.eventType == AuditEventType.PARTICIPANT_DATA_ACCESS }
        assertTrue(access.success)
        assertTrue(access.accessedPHI)
        assertEquals(studyId, access.study)
        assertEquals(0, access.data["rowCount"])
    }

    /**
     * The daily app-usage survey export selects survey days in the study's own zone (America/Santiago for the pilot),
     * so a dashboard export of "October" never picks up the UTC day boundary. The file is named after the type.
     */
    @Test
    fun dailySurveyExportUsesTheStudyZoneForItsDayBounds() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getStudySettings(studyId)).thenReturn(mapOf(
            StudySettingType.TimeUseDiary to com.openlattice.chronicle.timeusediary.TimeUseDiarySettings(timeZone = "America/Santiago"),
        ))
        val start = OffsetDateTime.parse("2026-10-01T00:00:00-03:00")
        val end = OffsetDateTime.parse("2026-11-01T00:00:00-03:00")
        Mockito.`when`(downloadService.getParticipantsAppUsageSurveyData(kAny(), kAny(), kAny(), kAny(), kAny()))
            .thenReturn(emptyList())
        val response = MockHttpServletResponse()

        controller.getParticipantsData(studyId, com.openlattice.chronicle.controllers.SynchronousExportRequest(
            ParticipantDataType.AppUsageSurvey, setOf("participant-1"), start, end), response)

        verify(downloadService).getParticipantsAppUsageSurveyData(studyId, setOf("participant-1"), start, end,
            java.time.ZoneId.of("America/Santiago"))
        assertTrue(response.getHeader("Content-Disposition").orEmpty().contains("AppUsageSurvey_"))
    }

    /** Only CSV and JSON are export formats; a YAML request is refused before any participant data is read. */
    @Test
    fun exportInAFormatThatIsNotOfferedIsRefusedBeforeReadingData() {
        val error = assertThrows(IllegalStateException::class.java) {
            controller.getParticipantsData(UUID.randomUUID(), com.openlattice.chronicle.controllers.SynchronousExportRequest(
                ParticipantDataType.UsageEvents, setOf("participant-1"), responseType = com.openlattice.chronicle.data.FileType.yaml,
            ), MockHttpServletResponse())
        }
        assertEquals("Requested file type must be json or CSV.", error.message)
        Mockito.verifyNoInteractions(downloadService)
    }

    /**
     * Participant notes: a participant without a notes row is not found (and no PHI read is audited); a note edit
     * that fails in the database is rethrown and audited as a failed update.
     */
    @Test
    fun participantNotesThatAreMissingOrCannotBeSavedAreNotAuditedAsSuccess() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        Mockito.`when`(studyService.getParticipantAnnotations(studyId, "participant-1")).thenReturn(null)

        assertThrows(NoSuchElementException::class.java) { controller.getParticipantAnnotations(studyId, "participant-1") }
        assertTrue(audit.events.none { it.eventType == AuditEventType.PARTICIPANT_DATA_ACCESS })

        val outage = java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available")
        val update = com.openlattice.chronicle.study.ParticipantAnnotationsUpdate(participantNotes = Optional.of("Called on Monday"))
        Mockito.doAnswer { throw outage }.`when`(studyService).updateParticipantAnnotations(studyId, "participant-1", update)

        assertSame(outage, assertThrows(Exception::class.java) {
            controller.updateParticipantAnnotations(studyId, "participant-1", update, "\"1\"")
        })
        val failed = audit.events.single { it.eventType == AuditEventType.UPDATE }
        assertFalse(failed.success)
    }

    @Test
    fun testSensorAvailabilityDisabledRejectsBeforeStorage() {
        val studyId = UUID.randomUUID()
        val participant = "participant-1"
        val source = "device-1"
        val device = RandomDeviceIdUtils.deriveRandomDeviceId(studyId, participant, source)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(studyId, participant)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(studyId, participant, device)).thenReturn(true)
        Mockito.doThrow(com.openlattice.chronicle.services.studies.CollectionHaltedException(studyId, participant, device))
            .`when`(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
                kEq(CollectionUploadRequest(studyId, participant, device, null, null)),
                kEq(setOf(CollectionModuleId.SENSOR_AVAILABILITY)), kEq(false), kEq(false), kAny(),
            )
        val error = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.reportAndroidSensorAvailability(studyId, participant, source,
                com.openlattice.chronicle.android.AndroidDeviceSensorAvailability(reportedAt = OffsetDateTime.parse("2026-10-01T00:00:00Z")))
        }
        assertEquals(403, error.statusCode.value())
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun testMixedSensorReceiptIdentifiesStoredAndDiscardedRows() {
        val study = UUID.randomUUID()
        val participant = "participant-1"
        val source = "device-1"
        val device = RandomDeviceIdUtils.deriveRandomDeviceId(study, participant, source)
        val accepted = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.accelerometer, OffsetDateTime.now(), "UTC")
        val discarded = accepted.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.gyroscope)
        val light = accepted.copy(id = UUID.randomUUID(), sensor = AndroidSensorType.light)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(study, participant)).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollmentManager.isKnownDatasource(study, participant, device)).thenReturn(true)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<(CollectionUploadAuthorization) -> Any?>(4).invoke(CollectionUploadAuthorization(
                setOf(CollectionModuleId.SENSOR_ACCELEROMETER), setOf(CollectionModuleId.SENSOR_GYROSCOPE, CollectionModuleId.SENSOR_LIGHT),
                2, emptySet()))
        }.`when`(participantCollectionAcknowledgmentService).withCollectionUploadRecheck<Any?>(
            kEq(CollectionUploadRequest(study, participant, device, null, null)), kAny(),
            kEq(true), kEq(true), kAny(),
        )
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(kAnyString())).thenReturn(statement)
        stubOpenCollection(connection)
        stubNoPurgeCutoff(connection)
        val receipt = controller.uploadAndroidSensorDataReceiptV4(study, participant, source, listOf(accepted, discarded, light))
        receipt.validateSubmitted(setOf(accepted.id, discarded.id, light.id))
        assertEquals(setOf(accepted.id), receipt.acceptedIds)
        assertEquals(setOf(discarded.id, light.id), receipt.discardedIds)
        verify(androidSensorDataUploadService).upload(study, participant, device, listOf(accepted))

        val cutoff = accepted.timestamp.plusSeconds(1)
        val atStop = accepted.copy(id = UUID.randomUUID(), timestamp = cutoff)
        val lateLight = light.copy(id = UUID.randomUUID(), timestamp = cutoff.plusSeconds(1))
        val state = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(state.executeQuery()).thenReturn(rows)
        Mockito.`when`(rows.next()).thenReturn(true)
        Mockito.`when`(rows.getString("lifecycle_status")).thenReturn("ACTIVE")
        Mockito.`when`(rows.getString("participation_status")).thenReturn("PAUSED")
        Mockito.`when`(rows.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(cutoff)
        com.openlattice.chronicle.services.upload.stubNoClosedCaptureWindows(rows)
        Mockito.`when`(connection.prepareStatement(ParticipantCollectionGate.WINDOW_QUERY)).thenReturn(state)
        Mockito.`when`(enrollmentManager.getDeviceParticipationStatus(study, participant)).thenReturn(ParticipationStatus.PAUSED)
        val submitted = listOf(accepted, discarded, light, atStop, lateLight)
        val stopped = controller.uploadAndroidSensorDataReceiptV4(study, participant, source, submitted)
        stopped.validateSubmitted(submitted.mapTo(linkedSetOf()) { it.id })
        // One clock: a paused participant's rows are judged by consent only, never by their
        // phone stamp against the server's cutoff.
        assertEquals(setOf(accepted.id, atStop.id), stopped.acceptedIds)
        assertEquals(setOf(discarded.id, light.id, lateLight.id), stopped.discardedIds)
        verify(androidSensorDataUploadService).upload(study, participant, device, listOf(accepted, atStop))
    }

}
