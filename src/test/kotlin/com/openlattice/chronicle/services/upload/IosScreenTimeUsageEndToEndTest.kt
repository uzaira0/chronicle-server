package com.openlattice.chronicle.services.upload

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.audit.recordingAuditService
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.RANDOM_DEVICE_ID_HEADER
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.openlattice.chronicle.controllers.ChronicleServerExceptionHandler
import com.openlattice.chronicle.controllers.StudyController
import com.openlattice.chronicle.controllers.StudyV4Controller
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.seedCollectionDecision
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * iOS Screen Time over the real v4 route into `ios_screen_time_usage`, as the runtime database role: a retried
 * capture stores nothing new, a later capture of the same window is stored beside it, the export keeps the newest
 * capture of each window, and erasing the participant removes the rows while a purge cutoff fences replays.
 */
class IosScreenTimeUsageEndToEndTest : ServerSweepTestFixture() {
    private val mapper = JacksonSecurityConfig().secureObjectMapper()
    private val sourceDevice = "ios-device-1"

    private data class Window(val start: OffsetDateTime, val end: OffsetDateTime)

    @Test fun acceptedUnicodeLabelsFitTheCaptureKeyAndRetriesRemainIdempotent() = withRoute { route ->
        val random = java.util.Random(42)
        val labels = List(2) { buildString { repeat(500) { append((0x400 + random.nextInt(0x4000)).toChar()) } } }
        val capture = record(Window(HOUR, HOUR.plusHours(1)), HOUR.plusMinutes(30), 120, app = labels[0])
            .copy(bundleIdentifier = "com.example.app", categoryName = labels[1])
        assertEquals(1, route.upload(PARTICIPANT, capture))
        assertEquals(1, route.upload(PARTICIPANT, capture.copy(id = UUID.randomUUID())))
        assertEquals(listOf(labels[0] to 120), route.exported(HOUR, HOUR.plusHours(1)))
    }

    @Test fun unusedAmbientAudioUploadIsNotExposed() = withRoute { route -> route.assertAmbientAudioRetired() }

    @Test fun bareAppleLabelsSurviveRetryRecaptureAndExportWithoutAppDomainCollisions() = withRoute { route ->
        val window = Window(HOUR, HOUR.plusHours(1))
        val first = record(window, HOUR.plusMinutes(30), 120, app = "example.com")
            .copy(bundleIdentifier = null, categoryName = "Travel")
        val web = record(window, first.capturedAt, 60, web = "example.com").copy(categoryName = "Travel", pickupCount = null)
        val emptyDomain = web.copy(id = UUID.randomUUID(), webDomain = "", durationSeconds = 30)
        val rows = listOf(first, web, emptyDomain)
        assertEquals(3, route.upload(PARTICIPANT, *rows.toTypedArray()))
        assertEquals(3, route.upload(PARTICIPANT, *rows.map { it.copy(id = UUID.randomUUID()) }.toTypedArray()))
        assertEquals(3, route.stored(PARTICIPANT).size)
        val later = rows.map { it.copy(id = UUID.randomUUID(), capturedAt = HOUR.plusMinutes(45),
            durationSeconds = it.durationSeconds + 10) }
        assertEquals(3, route.upload(PARTICIPANT, *later.toTypedArray()))
        assertEquals(6, route.stored(PARTICIPANT).size)
        val expected = setOf("example.com" to 130, "example.com" to 70, "" to 40)
        assertEquals(expected, route.exported(HOUR, HOUR.plusHours(1)).toSet())
        assertEquals(mapOf(
            (ScreenTimeUsageRowKind.application to "example.com") to 130.0,
            (ScreenTimeUsageRowKind.webDomain to "example.com") to 70.0,
            (ScreenTimeUsageRowKind.webDomain to "") to 40.0,
        ), route.deltaTotals())
    }

    @Test fun aRowWhoseLabelsContradictItsKindIsRefusedAsARowDefect() = withRoute { route ->
        val window = Window(HOUR, HOUR.plusHours(1))
        val app = record(window, HOUR.plusMinutes(30), 120, app = "Maps")
        val code = route.refusalCode(PARTICIPANT, app, app.copy(id = UUID.randomUUID(), webDomain = "example.com"))
        assertEquals(UploadLimits.UPLOAD_ROW_REJECTED_CODE, code)
        assertEquals(emptyList<Pair<String, Int>>(), route.stored(PARTICIPANT))
    }

    @Test fun emptyDomainsInDifferentCategoriesHaveIndependentDeltas() = withRoute { route ->
        val window = Window(HOUR, HOUR.plusHours(1))
        val first = record(window, HOUR.plusMinutes(30), 60, web = "")
            .copy(categoryName = "Travel", pickupCount = null)
        val second = first.copy(id = UUID.randomUUID(), categoryName = "Education", durationSeconds = 120)
        assertEquals(2, route.upload(PARTICIPANT, first, second))
        val later = listOf(first, second).map {
            it.copy(id = UUID.randomUUID(), capturedAt = HOUR.plusMinutes(45), durationSeconds = it.durationSeconds + 30)
        }
        assertEquals(2, route.upload(PARTICIPANT, *later.toTypedArray()))
        assertEquals(setOf("" to 90, "" to 150), route.exported(HOUR, HOUR.plusHours(1)).toSet())
        assertEquals(mapOf((ScreenTimeUsageRowKind.webDomain to "") to 240.0), route.deltaTotals())
    }

    @Test fun screenTimeRequiresEnabledAppUsage() = withRoute { route ->
        route.disableAppUsage()
        val capture = record(Window(HOUR, HOUR.plusHours(1)), HOUR.plusMinutes(30), 120, app = "Maps")
        route.upload(PARTICIPANT, capture, expectedStatus = 403)
        assertEquals(emptyList<Pair<String, Int>>(), route.stored(PARTICIPANT))
        assertEquals(false, route.uploadStatus().containsKey(PARTICIPANT))
    }

    @Test fun repeatedCapturesAreIdempotentExportNewestAndEraseWithTheParticipant() = withRoute { route ->
        val testStarted = Instant.now()
        val window = Window(HOUR, HOUR.plusHours(1))
        val first = record(window, capturedAt = HOUR.plusMinutes(70), seconds = 300, app = "Instagram")
        val web = record(window, capturedAt = HOUR.plusMinutes(70), seconds = 45, web = "example.com")

        assertEquals(2, route.upload(PARTICIPANT, first, web))
        // A lost response: the phone sends the same capture again with fresh record ids.
        assertEquals(2, route.upload(PARTICIPANT, first.copy(id = UUID.randomUUID()), web.copy(id = UUID.randomUUID())))
        assertEquals(listOf("Instagram" to 300, "example.com" to 45), route.stored(PARTICIPANT))

        // The next run re-reads the hour with a grown total.
        val later = first.copy(id = UUID.randomUUID(), capturedAt = HOUR.plusMinutes(130), durationSeconds = 900)
        assertEquals(1, route.upload(PARTICIPANT, later))
        assertEquals(listOf("Instagram" to 300, "example.com" to 45, "Instagram" to 900), route.stored(PARTICIPANT))
        assertEquals(setOf("Instagram" to 900, "example.com" to 45), route.exported(HOUR.minusDays(1), HOUR.plusDays(1)).toSet())
        assertEquals("a window belongs to the range holding its start", emptyList<Pair<String, Int>>(),
            route.exported(HOUR.plusHours(1), HOUR.plusDays(1)))

        route.upload(CONTROL, first.copy(id = UUID.randomUUID()))
        assertEquals(listOf(AuditEventType.SCREEN_TIME_UPLOAD), route.uploadAuditEvents().distinct())
        val status = route.uploadStatus().getValue(PARTICIPANT)
        assertEquals(3L, status.committedRows)
        // Latest committed is the server's receipt time, never the phone's capture clock (HOUR is months ago).
        assertTrue(status.lastCommittedAt!!.toInstant() >= testStarted.minusSeconds(60))
        assertEquals(window.end.toInstant(), status.lastObservationEndAt!!.toInstant())
        assertEquals(listOf(HOUR.toLocalDate()), route.iosUniqueDates(PARTICIPANT))

        route.erase(PARTICIPANT, DataDeletionMode.WITHDRAW_AND_ERASE)
        assertEquals(emptyList<Pair<String, Int>>(), route.stored(PARTICIPANT))
        assertEquals(false, route.uploadStatus().containsKey(PARTICIPANT))
        assertEquals("another participant keeps its rows", listOf("Instagram" to 300), route.stored(CONTROL))
    }

    @Test fun aPurgeCutoffFencesReplayedCapturesAndAdmitsNewOnes() = withRoute { route ->
        val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val old = record(Window(now.minusHours(2), now.minusHours(1)), capturedAt = now.minusMinutes(30), seconds = 600, app = "Maps")
        assertEquals(1, route.upload(PARTICIPANT, old))
        route.erase(PARTICIPANT, DataDeletionMode.COLLECTED_DATA_PURGE)
        assertEquals(emptyList<Pair<String, Int>>(), route.stored(PARTICIPANT))

        // The phone replays the erased capture next to one of an hour still being measured.
        val open = record(Window(now.minusMinutes(10), now.plusMinutes(50)), capturedAt = now.plusMinutes(1), seconds = 60, app = "Maps")
        assertEquals(2, route.upload(PARTICIPANT,
            old.copy(id = UUID.randomUUID(), capturedAt = now.plusMinutes(2)), open))
        assertEquals(listOf("Maps" to 60), route.stored(PARTICIPANT))
    }

    private fun record(window: Window, capturedAt: OffsetDateTime, seconds: Int, app: String? = null, web: String? = null) =
        ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            rowKind = if (web == null) ScreenTimeUsageRowKind.application else ScreenTimeUsageRowKind.webDomain,
            capturedAt = capturedAt,
            observationStart = window.start,
            observationEnd = window.end,
            timezoneIdentifier = "America/Santiago",
            appName = app,
            bundleIdentifier = app?.let { "com.example.${it.lowercase()}" },
            webDomain = web,
            durationSeconds = seconds,
            pickupCount = 2,
        )

    private inner class Route(
        private val study: UUID,
        private val storage: StorageResolver,
        private val controller: StudyController,
        private val mvc: MockMvc,
        private val audit: RecordingAuditingManager,
    ) {
        private val participantScope = RLSConnectionContext("ios-device", setOf(study), isAdmin = false)

        fun uploadStatus() = RLSRequestContext.withContext(participantScope) { controller.getIosUploadStatus(study) }

        fun disableAppUsage() {
            val settings = AndroidDataCollectionSetting(modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = false),
            ), settingsVersion = 2)
            postgres.createConnection("").use { connection ->
                connection.prepareStatement("""
                    UPDATE studies SET settings = jsonb_set(settings, '{DataCollection}', ?::jsonb) WHERE study_id = ?
                """.trimIndent()).use {
                    it.setString(1, mapper.writeValueAsString(settings))
                    it.setObject(2, study)
                    assertEquals(1, it.executeUpdate())
                }
            }
        }

        fun assertAmbientAudioRetired() {
            val response = mvc.perform(post("/chronicle/v4/study/$study/participant/$PARTICIPANT/ios/ambient-audio")
                .contextPath("/chronicle").header(RANDOM_DEVICE_ID_HEADER, sourceDevice)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn().response
            assertEquals(404, response.status)
            assertEquals(false, ParticipantDataType.entries.any { it.name == "AmbientAudio" })
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT to_regclass('public.ambient_audio_events')").use { rows ->
                        check(rows.next())
                        assertEquals(null, rows.getString(1))
                    }
                }
            }
        }

        fun upload(participant: String, vararg records: ScreenTimeUsageRecord, expectedStatus: Int = 200): Int {
            val response = send(participant, records.toList())
            assertEquals(response.contentAsString, expectedStatus, response.status)
            return if (response.status == 200) response.contentAsString.toInt() else 0
        }

        /** The ApiError code of a refused upload. */
        fun refusalCode(participant: String, vararg records: ScreenTimeUsageRecord): String {
            val response = send(participant, records.toList())
            assertEquals(response.contentAsString, 400, response.status)
            return mapper.readTree(response.contentAsString).path("code").asText()
        }

        private fun send(participant: String, records: List<ScreenTimeUsageRecord>): MockHttpServletResponse {
            val envelope = ScreenTimeUsageEnvelope(randomDeviceId = sourceDevice, studyId = study.toString(),
                participantId = participant, generatedAt = OffsetDateTime.now(), records = records)
            return RLSRequestContext.withContext(participantScope) {
                mvc.perform(post("/chronicle/v4/study/$study/participant/$participant/ios/screen-time")
                    .contextPath("/chronicle")
                    .header(RANDOM_DEVICE_ID_HEADER, sourceDevice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(envelope))).andReturn().response
            }
        }

        /** Every stored row of [participant] as (app or domain, seconds), in capture order. */
        fun stored(participant: String): List<Pair<String, Int>> = postgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                SELECT coalesce(app_name, web_domain), duration_seconds, random_device_id FROM ios_screen_time_usage
                WHERE study_id = ? AND participant_id = ? ORDER BY captured_at, duration_seconds DESC
            """.trimIndent()).use { statement ->
                statement.setObject(1, study); statement.setString(2, participant)
                statement.executeQuery().use { rows ->
                    generateSequence {
                        if (!rows.next()) return@generateSequence null
                        assertEquals(RandomDeviceIdUtils.deriveRandomDeviceId(study, participant, sourceDevice),
                            rows.getObject(3, UUID::class.java))
                        rows.getString(1) to rows.getInt(2)
                    }.toList()
                }
            }
        }

        fun exported(start: OffsetDateTime, end: OffsetDateTime): List<Pair<String, Int>> =
            RLSRequestContext.withContext(participantScope) {
                DataDownloadService(storage).getParticipantsCollectionData(
                    study, setOf(PARTICIPANT), ParticipantDataType.IOSScreenTime, start, end,
                ).map { row -> ((row["app_name"] as String).ifEmpty { row["web_domain"] as String }) to row["duration_seconds"] as Int }
            }

        fun uploadAuditEvents(): List<AuditEventType> = audit.events.map { it.eventType }

        fun deltaTotals(): Map<Pair<ScreenTimeUsageRowKind, String>, Double> = postgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                SELECT row_kind, entity_key, sum(usage_delta_seconds) FROM screen_time_usage_deltas
                WHERE study_id = ? AND participant_id = ? GROUP BY row_kind, entity_key
            """.trimIndent()).use { statement ->
                statement.setObject(1, study); statement.setString(2, PARTICIPANT)
                statement.executeQuery().use { rows ->
                    generateSequence {
                        if (rows.next()) (ScreenTimeUsageRowKind.valueOf(rows.getString(1)) to rows.getString(2)) to rows.getDouble(3)
                        else null
                    }.toMap()
                }
            }
        }

        fun iosUniqueDates(participant: String): List<LocalDate> = postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT unnest(ios_unique_dates) FROM participant_stats WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, study); it.setString(2, participant)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getObject(1, LocalDate::class.java) else null }.toList()
                }
            }
        }

        fun erase(participant: String, mode: DataDeletionMode) {
            val orchestrator = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
            val operation = RLSRequestContext.withSystemContext {
                orchestrator.quarantineParticipant(study, participant, mode, "screen-time-test", UUID.randomUUID())
            }
            assertEquals("COMPLETED", orchestrator.eraseNow(operation, study, "screen-time-owner").status)
        }
    }

    private fun withRoute(test: (Route) -> Unit) {
        val study = UUID.randomUUID()
        seed(study, PARTICIPANT, features = setOf(StudyFeature.CHRONICLE_DATA_COLLECTION))
        postgres.createConnection("").use { connection ->
            seedEnrolledParticipant(connection, study, CONTROL)
            listOf(PARTICIPANT, CONTROL).forEach { participant ->
                seedCollectionDecision(connection, study, participant,
                    RandomDeviceIdUtils.deriveRandomDeviceId(study, participant, sourceDevice), UUID.randomUUID(),
                    CollectionAcknowledgment(setOf(CollectionModuleId.USAGE_EVENTS), OffsetDateTime.now(), settingsVersion = 1))
            }
        }
        val configs = ChronicleContractTestSchema.storageConfigurations(postgres, maximumPoolSize = 4)
        TestSecurityUtils.setupSecurityContext()
        try {
            DataSourceManager(configs, HealthCheckRegistry(), MetricRegistry()).use { sources ->
                val storage = StorageResolver(sources, ChronicleStorageConfiguration())
                val (auditService, audit) = recordingAuditService()
                val studyController = controller(storage, auditService)
                AnnotationConfigApplicationContext().use { context ->
                    context.beanFactory.registerSingleton("studyController", studyController)
                    context.register(StudyV4Controller::class.java)
                    context.refresh()
                    val v4 = context.getBean(StudyV4Controller::class.java)
                    val mvc = MockMvcBuilders.standaloneSetup(v4)
                        .setControllerAdvice(ChronicleServerExceptionHandler(mock(), ErrorSanitizationConfig()))
                        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()
                    test(Route(study, storage, studyController, mvc, audit))
                }
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    private fun controller(storage: StorageResolver, auditService: AuditService): StudyController {
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
        val enrollment = Mockito.mock(EnrollmentManager::class.java)
        Mockito.`when`(enrollment.getDeviceParticipationStatus(any(), any())).thenReturn(ParticipationStatus.ENROLLED)
        Mockito.`when`(enrollment.isKnownDatasource(any(), any(), any())).thenReturn(true)
        val hazelcast = mock<HazelcastInstance>()
        Mockito.`when`(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(mock())
        return StudyController(
            hazelcastInstance = hazelcast,
            storageResolver = storage,
            enrollmentService = mock(),
            studyService = studyService(storage),
            screenTimeUsageUploadService = ScreenTimeUsageUploadService(storage),
            androidSensorDataUploadService = mock(),
            batteryTelemetryUploadService = mock(),
            interactionEventsUploadService = mock(),
            appAudioActivityUploadService = mock(),
            appAudioContentUploadService = mock(),
            notificationActivityUploadService = mock(),
            sleepEventsUploadService = mock(),
            activityRecognitionEventsUploadService = mock(),
            healthMetricsUploadService = mock(),
            connectivityStateEventsUploadService = mock(),
            appNetworkUsageUploadService = mock(),
            deviceSettingsUploadService = mock(),
            uploadDiagnosticsUploadService = mock(),
            appDataUploadService = mock(),
            downloadService = mock(),
            enrollmentManager = enrollment,
            authorizationManager = authorization,
            auditingManager = mock(),
            auditService = auditService,
            studySettingsAuditService = mock(),
            participantCollectionAcknowledgmentService = ParticipantCollectionAcknowledgmentService(storage),
            studySettingsNotificationService = mock(),
            apiKeyService = mock(),
            dataDeletionOrchestrator = mock(),
            studyLifecycleService = mock(),
            webhookService = WebhookService(storage, mock()),
            enrollmentManifestService = mock(),
        )
    }

    private companion object {
        const val PARTICIPANT = "screen-time-subject"
        const val CONTROL = "screen-time-control"
        val HOUR: OffsetDateTime = OffsetDateTime.parse("2026-06-24T10:00:00-04:00")
    }
}
