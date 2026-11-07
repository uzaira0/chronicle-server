package com.openlattice.chronicle.services.webhooks

import com.openlattice.chronicle.configuration.WebhookConfiguration
import com.openlattice.chronicle.controllers.kAnyString
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.participantaccess.EnrollmentDisclosureActivity
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.tests.EnrollmentDisclosureActivityResultFixture
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.zaxxer.hikari.HikariDataSource
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.web.server.ResponseStatusException

class WebhookServiceTest {
    private companion object {
        private const val VALID_SECRET = "0123456789abcdef0123456789abcdef"
    }

    private lateinit var storageResolver: StorageResolver
    private lateinit var idGenerationService: HazelcastIdGenerationService
    private lateinit var service: WebhookService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet
    private lateinit var deliveryExecutor: ExecutorService

    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)
        deliveryExecutor = Mockito.mock(ExecutorService::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        val erasureCheck = Mockito.mock(PreparedStatement::class.java)
        val erasureResult = Mockito.mock(ResultSet::class.java)
        `when`(erasureCheck.executeQuery()).thenReturn(erasureResult)
        `when`(erasureResult.next()).thenReturn(true)
        `when`(erasureResult.getBoolean(1)).thenReturn(true)
        `when`(erasureResult.getBoolean("processing_allowed")).thenReturn(true)
        `when`(mockConnection.prepareStatement(kAnyString())).thenAnswer { invocation ->
            if (invocation.getArgument<String>(0).contains("SELECT chronicle_participant_mutation_allowed")) erasureCheck else mockPs
        }
        `when`(mockRs.getObject("study_id", UUID::class.java)).thenReturn(UUID.randomUUID())
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service = WebhookService(storageResolver, idGenerationService, deliveryExecutor)
    }

    @Test
    fun testParticipantWebhookOutboxDropsRawLabelsButKeepsInternalDeletionScope() {
        val participantId = "SENTINEL-WEBHOOK-7c3a"
        val reference = ParticipantRef.fromParticipantId(participantId)
        val payloads = mapOf(
            WebhookEventType.PARTICIPANT_ENROLLED to WebhookPayload.ParticipantEnrolled(reference, ParticipationStatus.ENROLLED.name),
            WebhookEventType.DATA_SUBMITTED to WebhookPayload.DataSubmitted(reference, ParticipantDataType.UsageEvents, 2),
        )
        payloads.forEach { (event, payload) -> service.enqueueEvent(mockConnection, UUID.randomUUID(), event, payload) }
        val bodies = ArgumentCaptor.forClass(String::class.java)
        verify(mockPs, Mockito.times(payloads.size)).setString(Mockito.eq(2), bodies.capture())
        verify(mockPs, Mockito.times(payloads.size)).setString(3, participantId)
        assertTrue(bodies.allValues.all { it.contains(reference.value) })
        assertTrue(bodies.allValues.none { it.contains(participantId) || it.contains("participantId") })
        assertFalse(reference.toString().contains(participantId))
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    /** Self-host: the backend has no route off the host, so webhooks are refused and never delivered. */
    @Test
    fun disabledWebhooksRefuseRegistrationWithTheReasonAndNeverDeliver() {
        val disabled = WebhookService(storageResolver, idGenerationService, deliveryExecutor,
            hostResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) },
            settings = WebhookConfiguration(enabled = false))
        val request = WebhookCreateRequest(url = "https://example.org/callback", secret = VALID_SECRET,
            eventTypes = setOf(WebhookEventType.DATA_SUBMITTED))
        val refused = assertThrows(ResponseStatusException::class.java) {
            disabled.createWebhook(UUID.randomUUID(), "staff", request)
        }
        assertEquals(409, refused.statusCode.value())
        assertTrue(refused.reason!!, refused.reason!!.contains("no outbound network access"))
        val test = assertThrows(ResponseStatusException::class.java) {
            disabled.testWebhook(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals(refused.reason, test.reason)
        assertEquals(0, disabled.enqueueEvent(mockConnection, UUID.randomUUID(), WebhookEventType.DATA_SUBMITTED,
            WebhookPayload.DataSubmitted(ParticipantRef.fromParticipantId("p"), ParticipantDataType.UsageEvents, 1)))
        disabled.dispatchPendingDeliveries()
        verify(idGenerationService, never()).getNextId()
        verify(storageResolver, never()).getPlatformStorage()
        verify(mockConnection, never()).prepareStatement(kAnyString())
        verify(deliveryExecutor, never()).execute(Mockito.any())
    }

    @Test
    fun webhooksStayOnUnlessADeploymentTurnsThemOff() {
        assertTrue(WebhookConfiguration().enabled)
        val yaml = com.geekbeast.mappers.mappers.ObjectMappers.getYamlMapper()
        assertFalse(yaml.readValue("webhooks:\n  enabled: false\n", com.openlattice.chronicle.configuration.ChronicleConfiguration::class.java)
            .webhooks.enabled)
        assertTrue(yaml.readValue("storageConfiguration: {}\n", com.openlattice.chronicle.configuration.ChronicleConfiguration::class.java)
            .webhooks.enabled)
    }

    @Test
    fun recipientsCannotBeAddedDuringActiveOrPendingEnrollment() {
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())
        val lock = Mockito.mock(PreparedStatement::class.java)
        val lockRows = Mockito.mock(ResultSet::class.java)
        val activity = Mockito.mock(PreparedStatement::class.java)
        `when`(lock.executeQuery()).thenReturn(lockRows)
        `when`(lockRows.next()).thenReturn(true)
        `when`(mockConnection.prepareStatement(kAnyString())).thenAnswer { invocation ->
            if (invocation.getArgument<String>(0).contains("FOR UPDATE")) lock else activity
        }
        service = WebhookService(storageResolver, idGenerationService, deliveryExecutor,
            hostResolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        val request = WebhookCreateRequest(url = "https://example.org/callback", secret = VALID_SECRET,
            eventTypes = setOf(WebhookEventType.DATA_SUBMITTED))
        for (activityCase in listOf(
            EnrollmentDisclosureActivity(hasActiveEnrollment = true, pendingParticipantIds = emptyList()),
            EnrollmentDisclosureActivity(hasActiveEnrollment = false, pendingParticipantIds = listOf("pending-participant")),
        )) {
            Mockito.doReturn(EnrollmentDisclosureActivityResultFixture.resultSet(activityCase))
                .`when`(activity).executeQuery()
            val error = assertThrows(ResponseStatusException::class.java) {
                service.createWebhook(UUID.randomUUID(), "staff", request)
            }
            assertEquals(409, error.statusCode.value())
        }
        verify(mockConnection, Mockito.times(2)).rollback()
        verify(activity, never()).executeUpdate()
    }

    // --- createWebhook tests ---

    @Test
    fun testCreateWebhookRejectsInvalidCredentialsAndDestinations() {
        val valid = WebhookCreateRequest(
            url = "https://example.com/callback",
            secret = VALID_SECRET,
            eventTypes = setOf(WebhookEventType.PARTICIPANT_ENROLLED),
        )
        val invalid = listOf(
            valid.copy(secret = ""), valid.copy(secret = "too-short"),
        ) + listOf(
            "http://example.com/callback", "https://user:password@example.com/callback",
            "https://example.com/callback#token", "http://localhost:8080/callback",
            "http://127.0.0.1/callback", "http://192.168.1.1/callback", "http://10.0.0.1/callback",
            "http://169.254.169.254/latest/meta-data/",
        ).map { valid.copy(url = it) }
        invalid.forEach { request ->
            assertThrows(IllegalArgumentException::class.java) {
                service.createWebhook(UUID.randomUUID(), "user", request)
            }
        }
    }

    // --- listWebhooks tests ---

    @Test
    fun testListWebhooksReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.listWebhooks(UUID.randomUUID())

        assertTrue(result.isEmpty())
    }

    @Test
    fun testListWebhooksSetsStudyIdParameter() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(false)

        service.listWebhooks(studyId)

        verify(mockPs).setObject(1, studyId)
    }

    // --- deleteWebhook tests ---

    @Test
    fun testDeleteWebhookSucceeds() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()

        service.deleteWebhook(studyId, webhookId)

        verify(mockPs).setObject(1, webhookId)
        verify(mockPs).setObject(2, studyId)
    }

    @Test
    fun testDeleteWebhookThrowsWhenNotFound() {
        `when`(mockPs.executeUpdate()).thenReturn(0)

        assertThrows(WebhookNotFoundException::class.java) {
            service.deleteWebhook(UUID.randomUUID(), UUID.randomUUID())
        }
    }

    // --- getDeliveries tests ---

    @Test
    fun testGetDeliveriesReturnsEmptyList() {
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(null)
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()

        val result = service.getDeliveries(studyId, webhookId)

        assertTrue(result.isEmpty())
        verify(mockPs).setObject(1, webhookId)
        verify(mockPs).setObject(2, studyId)
    }

    @Test
    fun testGetDeliveriesRejectsMissingOrCrossStudyWebhook() {
        `when`(mockRs.next()).thenReturn(false)

        assertThrows(WebhookNotFoundException::class.java) {
            service.getDeliveries(UUID.randomUUID(), UUID.randomUUID())
        }
    }

    private fun publishFixtureEvent() = PinnedPlatformConnection.committing(mockHds, mockConnection) {
        service.withDataSubmission(UUID.randomUUID(), "fixture-participant", ParticipantDataType.UsageEvents) { 1 }
    }

    @Test
    fun dataSubmissionRequiresItsTransactionBeforeExecutingTheWrite() {
        var written = false
        assertThrows(IllegalStateException::class.java) {
            service.withDataSubmission(UUID.randomUUID(), "fixture-participant", ParticipantDataType.UsageEvents) {
                written = true
                1
            }
        }
        assertFalse(written)
        verify(mockPs, never()).executeUpdate()
    }

    @Test
    fun dataSubmissionWithoutWebhooksDoesNotDispatch() {
        `when`(mockPs.executeUpdate()).thenReturn(0)

        publishFixtureEvent()

        verify(mockPs).executeUpdate()
        verify(deliveryExecutor, never()).execute(Mockito.any(Runnable::class.java))
    }

    @Test
    fun committedDataSubmissionKeepsItsOutboxWhenDispatchIsUnavailable() {
        Mockito.doThrow(RejectedExecutionException("queue full"))
            .`when`(deliveryExecutor).execute(Mockito.any(Runnable::class.java))

        publishFixtureEvent()

        verify(mockPs).executeUpdate()
        verify(deliveryExecutor).execute(Mockito.any(Runnable::class.java))
    }

    // --- computeSignature tests ---

    @Test
    fun testComputeSignatureStartsWithSha256() {
        val sig = WebhookService.computeSignature("payload", "secret")

        assertTrue(sig.startsWith("sha256="))
    }

    @Test
    fun testComputeSignatureIsDeterministic() {
        val sig1 = WebhookService.computeSignature("payload", "secret")
        val sig2 = WebhookService.computeSignature("payload", "secret")

        assertEquals(sig1, sig2)
    }

    @Test
    fun testComputeSignatureDiffersForDifferentPayloads() {
        val sig1 = WebhookService.computeSignature("payload1", "secret")
        val sig2 = WebhookService.computeSignature("payload2", "secret")

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun testComputeSignatureDiffersForDifferentSecrets() {
        val sig1 = WebhookService.computeSignature("payload", "secret1")
        val sig2 = WebhookService.computeSignature("payload", "secret2")

        assertNotEquals(sig1, sig2)
    }

    @Test
    fun testComputeSignatureHasCorrectLength() {
        val sig = WebhookService.computeSignature("payload", "secret")

        // "sha256=" + 64 hex chars
        assertEquals("sha256=".length + 64, sig.length)
    }

    // --- testWebhook test ---

    @Test
    fun testTestWebhookQueuesOnlyTheRouteBoundWebhook() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()

        service.testWebhook(studyId, webhookId)

        verify(mockPs).setObject(3, webhookId)
        verify(mockPs).setObject(4, studyId)
        verify(mockPs).executeUpdate()
        verify(deliveryExecutor).execute(Mockito.any(Runnable::class.java))
    }

    @Test
    fun testTestWebhookRejectsMissingDisabledOrCrossStudyWebhook() {
        `when`(mockPs.executeUpdate()).thenReturn(0)

        assertThrows(WebhookNotFoundException::class.java) {
            service.testWebhook(UUID.randomUUID(), UUID.randomUUID())
        }

        verify(deliveryExecutor, never()).execute(Mockito.any(Runnable::class.java))
    }

    @Test
    fun testTestWebhookKeepsAcceptedDeliveryWhenImmediateWakeupIsRejected() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()
        Mockito.doThrow(RejectedExecutionException("queue full"))
            .`when`(deliveryExecutor).execute(Mockito.any(Runnable::class.java))

        service.testWebhook(studyId, webhookId)

        verify(mockPs).setObject(3, webhookId)
        verify(mockPs).setObject(4, studyId)
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testDeliveryUrlUsesOneCanonicalHostnameForDnsAndRequest() {
        val httpUrl = WebhookService.parseDeliveryUrl("https://MiXeD.Example.COM/callback")
        val request = Request.Builder().url(httpUrl).build()

        assertEquals("mixed.example.com", httpUrl.host)
        assertEquals(httpUrl.host, request.url.host)
    }

    @Test
    fun testDeliveryClientDisablesProxyRedirectsAndReusesOneResolution() {
        val resolutions = AtomicInteger()
        val proxyTemplate = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8888)))
            .build()
        service = WebhookService(
            storageResolver,
            idGenerationService,
            deliveryExecutor,
            proxyTemplate,
        ) { hostname ->
            resolutions.incrementAndGet()
            assertEquals("mixed.example.com", hostname)
            arrayOf(InetAddress.getByName("93.184.216.34"))
        }

        val client = service.buildDeliveryClient(
            WebhookService.parseDeliveryUrl("https://MiXeD.Example.COM/callback")
        )

        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(30_000, client.callTimeoutMillis)
        assertEquals(1, resolutions.get())
        client.dns.lookup("mixed.example.com")
        client.dns.lookup("mixed.example.com")
        assertEquals(1, resolutions.get())
    }

    @Test
    fun testRetryAfterSupportsBoundedSecondsAndHttpDate() {
        val now = Instant.parse("2026-07-28T12:00:00Z")

        assertEquals(120L, WebhookService.parseRetryAfterSeconds("120", now))
        assertEquals(
            60L,
            WebhookService.parseRetryAfterSeconds("Tue, 28 Jul 2026 12:01:00 GMT", now),
        )
        assertEquals(3_600L, WebhookService.parseRetryAfterSeconds("999999", now))
        assertEquals(null, WebhookService.parseRetryAfterSeconds("not-a-date", now))
    }

    @Test
    fun testCapturedDeliveryTaskExecutesConfiguredRequestAndRecordsOutcome() {
        val webhookId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        val payload = """{"eventType":"DATA_SUBMITTED","data":{"records":2}}"""
        val capturedRequests = mutableListOf<Request>()
        val template = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequests.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        service = WebhookService(
            storageResolver,
            idGenerationService,
            deliveryExecutor,
            template,
        ) { arrayOf(InetAddress.getByName("93.184.216.34")) }
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(deliveryId)
        `when`(mockRs.getObject("webhook_id", UUID::class.java)).thenReturn(webhookId)
        `when`(mockRs.getString("event_type")).thenReturn(WebhookEventType.DATA_SUBMITTED.name)
        `when`(mockRs.getString("payload")).thenReturn(payload)
        `when`(mockRs.getString("url")).thenReturn("https://example.com/callback")
        `when`(mockRs.getString("secret_hash")).thenReturn("secret-hash")
        `when`(mockRs.getInt("attempt_count")).thenReturn(0)
        val taskCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        publishFixtureEvent()
        verify(deliveryExecutor).execute(taskCaptor.capture())
        taskCaptor.value.run()

        assertEquals(1, capturedRequests.size)
        assertEquals("example.com", capturedRequests.single().url.host)
        assertEquals(WebhookEventType.DATA_SUBMITTED.name, capturedRequests.single().header("X-Chronicle-Event"))
        assertEquals(deliveryId.toString(), capturedRequests.single().header("X-Chronicle-Delivery"))
        assertNotNull(capturedRequests.single().header("X-Chronicle-Signature"))
        verify(mockPs).setInt(1, 204)
        verify(mockPs).setObject(2, deliveryId)
        verify(mockPs, Mockito.times(3)).executeUpdate()
    }

    @Test
    fun testExpiredClaimDoesNotSendAfterDnsValidation() {
        val webhookId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        val requests = AtomicInteger()
        val template = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        service = WebhookService(
            storageResolver,
            idGenerationService,
            deliveryExecutor,
            template,
        ) { arrayOf(InetAddress.getByName("93.184.216.34")) }
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(deliveryId)
        `when`(mockRs.getObject("webhook_id", UUID::class.java)).thenReturn(webhookId)
        `when`(mockRs.getString("event_type")).thenReturn(WebhookEventType.DATA_SUBMITTED.name)
        `when`(mockRs.getString("payload")).thenReturn("{}")
        `when`(mockRs.getString("url")).thenReturn("https://example.com/callback")
        `when`(mockRs.getString("secret_hash")).thenReturn("secret-hash")
        `when`(mockRs.getInt("attempt_count")).thenReturn(0)
        `when`(mockPs.executeUpdate()).thenReturn(1, 0, 0)
        val taskCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        publishFixtureEvent()
        verify(deliveryExecutor).execute(taskCaptor.capture())
        taskCaptor.value.run()

        assertEquals(0, requests.get())
        verify(mockPs).setString(1, "lease_lost")
    }

    @Test
    fun testStalledDnsExpiresBeforeBorrowingOrTakingDeletionFence() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val template = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(204).message("No Content").body(ByteArray(0).toResponseBody(null)).build()
        }.build()
        service = WebhookService(storageResolver, idGenerationService, deliveryExecutor, template) {
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            arrayOf(InetAddress.getByName("93.184.216.34"))
        }
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(UUID.randomUUID())
        `when`(mockRs.getObject("webhook_id", UUID::class.java)).thenReturn(UUID.randomUUID())
        `when`(mockRs.getString("event_type")).thenReturn(WebhookEventType.DATA_SUBMITTED.name)
        `when`(mockRs.getString("payload")).thenReturn("{}")
        `when`(mockRs.getString("url")).thenReturn("https://example.com/callback")
        `when`(mockRs.getString("secret_hash")).thenReturn("secret-hash")
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        publishFixtureEvent()
        verify(deliveryExecutor).execute(task.capture())
        Mockito.clearInvocations(mockHds, mockConnection)
        val running = worker.submit { task.value.run() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The claim checkout has closed; delivery must not borrow a second connection during DNS.
            verify(mockHds, Mockito.times(1)).getConnection()
            verify(mockConnection, never()).setAutoCommit(false)
            running.get(6, TimeUnit.SECONDS)
            verify(mockPs).setString(2, "dns_resolution")
        } finally { release.countDown(); running.get(5, TimeUnit.SECONDS); worker.shutdownNow() }
    }

    @Test
    fun testDnsResolutionFailureIsRetried() {
        val webhookId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        service = WebhookService(
            storageResolver,
            idGenerationService,
            deliveryExecutor,
            OkHttpClient(),
        ) { throw UnknownHostException("synthetic DNS outage") }
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(deliveryId)
        `when`(mockRs.getObject("webhook_id", UUID::class.java)).thenReturn(webhookId)
        `when`(mockRs.getString("event_type")).thenReturn(WebhookEventType.DATA_SUBMITTED.name)
        `when`(mockRs.getString("payload")).thenReturn("{}")
        `when`(mockRs.getString("url")).thenReturn("https://example.com/callback")
        `when`(mockRs.getString("secret_hash")).thenReturn("secret-hash")
        `when`(mockRs.getInt("attempt_count")).thenReturn(0)
        val taskCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        publishFixtureEvent()
        verify(deliveryExecutor).execute(taskCaptor.capture())
        taskCaptor.value.run()

        verify(mockPs).setString(2, "dns_resolution")
        verify(mockPs).setLong(3, 1L)
    }

    @Test
    fun testEventPersistenceDoesNotDependOnVolatileIdGeneration() {
        `when`(idGenerationService.getNextId()).thenThrow(IllegalStateException("id service unavailable"))
        Mockito.doThrow(RejectedExecutionException("queue full"))
            .`when`(deliveryExecutor).execute(Mockito.any(Runnable::class.java))

        publishFixtureEvent()

        verify(mockPs).executeUpdate()
        verify(idGenerationService, never()).getNextId()
    }

    @Test
    fun testInterruptedDeliveryPreservesFlagAndRecordsOutcome() {
        val webhookId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        val template = OkHttpClient.Builder()
            .addInterceptor {
                Thread.currentThread().interrupt()
                throw IOException("synthetic interruption")
            }
            .build()
        service = WebhookService(
            storageResolver,
            idGenerationService,
            deliveryExecutor,
            template,
        ) { arrayOf(InetAddress.getByName("93.184.216.34")) }
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject("delivery_id", UUID::class.java)).thenReturn(deliveryId)
        `when`(mockRs.getObject("webhook_id", UUID::class.java)).thenReturn(webhookId)
        `when`(mockRs.getString("event_type")).thenReturn(WebhookEventType.DATA_SUBMITTED.name)
        `when`(mockRs.getString("payload")).thenReturn("{}")
        `when`(mockRs.getString("url")).thenReturn("https://example.com/callback")
        `when`(mockRs.getString("secret_hash")).thenReturn("secret-hash")
        `when`(mockRs.getInt("attempt_count")).thenReturn(0)
        val taskCaptor = ArgumentCaptor.forClass(Runnable::class.java)

        publishFixtureEvent()
        verify(deliveryExecutor).execute(taskCaptor.capture())
        try {
            taskCaptor.value.run()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        verify(mockPs).setString(1, "interrupted")
        verify(mockPs).setObject(2, deliveryId)
        verify(mockPs, Mockito.times(3)).executeUpdate()
    }

    @Test
    fun testForcedShutdownRecordsQueuedDelivery() {
        val taskCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        publishFixtureEvent()
        verify(deliveryExecutor).execute(taskCaptor.capture())
        `when`(deliveryExecutor.awaitTermination(10L, TimeUnit.SECONDS)).thenReturn(false)
        `when`(deliveryExecutor.shutdownNow()).thenReturn(listOf(taskCaptor.value))
        `when`(deliveryExecutor.awaitTermination(5L, TimeUnit.SECONDS)).thenReturn(true)

        service.shutdown()

        verify(deliveryExecutor).shutdown()
        verify(deliveryExecutor).shutdownNow()
        // Queued tasks have not claimed a row. Their work already exists as PENDING
        // in PostgreSQL and the next scheduler run will recover it.
        verify(mockPs, Mockito.times(1)).executeUpdate()
    }

    @Test
    fun testShutdownWaitsForActiveDeliveriesBeforeForcing() {
        `when`(
            deliveryExecutor.awaitTermination(Mockito.eq(10L), Mockito.eq(TimeUnit.SECONDS))
        ).thenReturn(true)

        service.shutdown()

        verify(deliveryExecutor).shutdown()
        verify(deliveryExecutor).awaitTermination(10L, TimeUnit.SECONDS)
        verify(deliveryExecutor, never()).shutdownNow()
    }
}
