package com.openlattice.chronicle.services.delete

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.studies.ParticipantProcessingService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.webhooks.ParticipantRef
import com.openlattice.chronicle.services.webhooks.WebhookPayload
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.webhooks.WebhookEventType
import java.net.InetAddress
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.web.server.ResponseStatusException

class ParticipantProcessingDataIntegrationTest : ServerSweepTestFixture() {
    @Before fun authenticate() = TestSecurityUtils.setupSecurityContext()
    @After fun clearAuthentication() = TestSecurityUtils.clearSecurityContext()

    @Test
    fun exportsWebhooksIngestAndErasureHonorAReversibleBlock() {
        val study = UUID.randomUUID()
        val participant = "processing-subject"
        seed(study, participant)
        postgres.createConnection("").use { seedEnrolledParticipant(it, study, "unblocked-control") }
        val configs = ChronicleContractTestSchema.storageConfigurations(postgres, maximumPoolSize = 4)
        DataSourceManager(configs, HealthCheckRegistry(), MetricRegistry()).use { sources ->
            val storage = StorageResolver(sources, ChronicleStorageConfiguration())
            RLSRequestContext.withSystemContext {
                verifyProcessingWorkflow(storage, study, participant)
            }
        }
    }

    private fun verifyProcessingWorkflow(storage: StorageResolver, study: UUID, participant: String) {
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val executor = Mockito.mock(ExecutorService::class.java)
        Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(executor).execute(any())
        val deliveries = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            deliveries.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("No content")
                .body("".toResponseBody()).build()
        }.build()
        val webhooks = WebhookService(storage, ids, executor, client,
            hostResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        val downloads = DataDownloadService(storage)
        fun exported(): List<Any?> = downloads.getParticipantsCollectionData(study, setOf(participant, "unblocked-control"),
            ParticipantDataType.BatteryTelemetry, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1))
            .map { it["participant_id"] }
        val processing = ParticipantProcessingService(storage, Mockito.mock(AuditingManager::class.java))
        val ingest = BatteryTelemetryUploadService(storage)
        val device = UUID.randomUUID()
        val initial = sample("initial")
        ingest.upload(study, participant, device, listOf(initial))
        ingest.upload(study, "unblocked-control", UUID.randomUUID(), listOf(initial))
        assertEquals(setOf(participant, "unblocked-control"), exported().toSet())
        seedWebhook(study)
        storage.getPlatformStorage().connection.use { connection ->
            assertEquals(1, webhooks.enqueueEvent(connection, study, WebhookEventType.DATA_SUBMITTED,
                WebhookPayload.DataSubmitted(ParticipantRef.fromParticipantId(participant), ParticipantDataType.BatteryTelemetry, 1)))
        }
        processing.block(study, participant, "Review participant request", statusRevision(storage, study, participant))
        assertEquals(listOf<Any?>("unblocked-control"), exported())
        assertThrows(CollectionUnavailableException::class.java) { ingest.upload(study, participant, device, listOf(sample("held"))) }
        webhooks.dispatchPendingDeliveries()
        assertEquals(0, deliveries.get())
        assertPendingWebhook(study)
        processing.unblock(study, participant, statusRevision(storage, study, participant))
        ingest.upload(study, participant, device, listOf(sample("held")))
        webhooks.dispatchPendingDeliveries()
        assertEquals(1, deliveries.get())
        assertEquals(3, exported().size)
        processing.block(study, participant, "Erase while processing is blocked", statusRevision(storage, study, participant))
        eraseBlocked(storage, study, participant)
        val absent = assertThrows(ResponseStatusException::class.java) { processing.unblock(study, participant, 0) }
        assertEquals(404, absent.statusCode.value())
    }

    private fun eraseBlocked(storage: StorageResolver, study: UUID, participant: String) {
        val erasure = DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java))
        val operation = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())
        assertEquals("COMPLETED", erasure.eraseNow(operation, study, "test-owner").status)
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
            connection.prepareStatement("SELECT count(*) FROM battery_telemetry WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, study); it.setString(2, participant)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
            }
        }
    }

    private fun statusRevision(storage: StorageResolver, study: UUID, participant: String): Long =
        storage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT status_revision FROM study_participants WHERE study_id = ? AND participant_id = ?").use {
                it.setObject(1, study); it.setString(2, participant)
                it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
            }
        }

    private fun sample(id: String) = BatterySample(id, OffsetDateTime.now(), "UTC", 80,
        BatteryChargingState.NOT_CHARGING, BatteryPlugType.UNPLUGGED, 200, 4000, BatteryHealth.GOOD)

    private fun seedWebhook(study: UUID) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("""
                INSERT INTO webhook_registrations (study_id, url, secret_hash, event_types, created_by)
                VALUES (?, 'https://example.org/fixture', 'fixture-secret', ?, 'fixture-researcher')
            """.trimIndent()).use {
                it.setObject(1, study)
                it.setArray(2, connection.createArrayOf("text", arrayOf(WebhookEventType.DATA_SUBMITTED.name)))
                it.executeUpdate()
            }
        }
    }

    private fun assertPendingWebhook(study: UUID) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT delivery_state, attempt_count FROM webhook_deliveries WHERE study_id = ?").use {
                it.setObject(1, study)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals("PENDING", rows.getString(1)); assertEquals(0, rows.getInt(2))
                }
            }
        }
    }
}
