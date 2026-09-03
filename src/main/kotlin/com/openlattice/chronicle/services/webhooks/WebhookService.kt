package com.openlattice.chronicle.services.webhooks

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.openlattice.chronicle.configuration.SsrfConfig
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SsrfException
import com.openlattice.chronicle.util.SsrfViolationType
import com.openlattice.chronicle.util.SsrfValidator
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookDeliveryInfo
import com.openlattice.chronicle.webhooks.WebhookDeliveryState
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.openlattice.chronicle.webhooks.WebhookRegistration
import jakarta.annotation.PreDestroy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

public class WebhookNotFoundException : RuntimeException("Webhook is unavailable")

public open class WebhookService(
    private val storageResolver: StorageResolver,
    private val idGenerationService: HazelcastIdGenerationService,
    private val deliveryExecutor: ExecutorService = newDeliveryExecutor(),
    private val httpClientTemplate: OkHttpClient = newHttpClientTemplate(),
    private val hostResolver: (String) -> Array<InetAddress> = { hostname ->
        InetAddress.getAllByName(hostname)
    },
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(WebhookService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        private const val INSERT_WEBHOOK_SQL = """
            INSERT INTO webhook_registrations
                (webhook_id, study_id, url, secret_hash, event_types, description, created_by)
            VALUES (?, ?, ?, ?, ?::text[], ?, ?)
        """

        private const val LIST_WEBHOOKS_SQL = """
            SELECT webhook_id, study_id, url, event_types, enabled, description, created_at
            FROM webhook_registrations
            WHERE study_id = ?
            ORDER BY created_at DESC
        """

        private const val DELETE_WEBHOOK_SQL = """
            DELETE FROM webhook_registrations WHERE webhook_id = ? AND study_id = ?
        """

        private const val ENQUEUE_EVENT_SQL = """
            INSERT INTO webhook_deliveries
                (delivery_id, webhook_id, event_type, payload, delivery_state)
            SELECT gen_random_uuid(), webhook_id, ?, ?::jsonb, 'PENDING'
            FROM webhook_registrations
            WHERE study_id = ?
              AND enabled = true
              AND ? = ANY(event_types)
        """

        private const val ENQUEUE_TEST_SQL = """
            INSERT INTO webhook_deliveries
                (delivery_id, webhook_id, event_type, payload, delivery_state)
            SELECT gen_random_uuid(), webhook_id, ?, ?::jsonb, 'PENDING'
            FROM webhook_registrations
            WHERE webhook_id = ?
              AND study_id = ?
              AND enabled = true
        """

        private const val LIST_DELIVERIES_SQL = """
            SELECT d.delivery_id, d.webhook_id, d.event_type, d.status, d.attempt_count,
                   d.created_at, d.last_attempt_at, d.delivery_state, d.outcome_code,
                   d.available_at, d.completed_at
            FROM webhook_registrations w
            LEFT JOIN webhook_deliveries d ON d.webhook_id = w.webhook_id
            WHERE w.webhook_id = ? AND w.study_id = ?
            ORDER BY d.created_at DESC
            LIMIT 50
        """

        private const val CLAIM_NEXT_DELIVERY_SQL = """
            WITH next_delivery AS (
                SELECT delivery_id
                FROM webhook_deliveries
                WHERE (
                    delivery_state = 'PENDING'
                    AND available_at <= now()
                ) OR (
                    delivery_state = 'IN_FLIGHT'
                    AND lease_expires_at <= now()
                )
                ORDER BY
                    CASE
                        WHEN delivery_state = 'PENDING' THEN available_at
                        ELSE lease_expires_at
                    END,
                    created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            ),
            claimed AS (
                UPDATE webhook_deliveries AS delivery
                SET delivery_state = 'IN_FLIGHT',
                    lease_token = ?,
                    lease_expires_at = now() + interval '60 seconds',
                    completed_at = NULL,
                    updated_at = now()
                FROM next_delivery
                WHERE delivery.delivery_id = next_delivery.delivery_id
                RETURNING delivery.delivery_id,
                          delivery.webhook_id,
                          delivery.event_type,
                          delivery.payload,
                          delivery.attempt_count
            )
            SELECT claimed.delivery_id,
                   claimed.webhook_id,
                   claimed.event_type,
                   claimed.payload,
                   claimed.attempt_count,
                   registration.url,
                   registration.secret_hash
            FROM claimed
            JOIN webhook_registrations AS registration
              ON registration.webhook_id = claimed.webhook_id
        """

        private const val COMPLETE_DELIVERY_SQL = """
            UPDATE webhook_deliveries
            SET delivery_state = 'SUCCEEDED',
                status = ?,
                attempt_count = attempt_count + 1,
                last_attempt_at = now(),
                outcome_code = NULL,
                completed_at = now(),
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE delivery_id = ? AND lease_token = ?
        """

        private const val RENEW_DELIVERY_LEASE_SQL = """
            UPDATE webhook_deliveries
            SET lease_expires_at = now() + interval '60 seconds',
                updated_at = now()
            WHERE delivery_id = ?
              AND lease_token = ?
              AND delivery_state = 'IN_FLIGHT'
        """

        private const val FAIL_DELIVERY_SQL = """
            UPDATE webhook_deliveries
            SET delivery_state = 'FAILED',
                status = ?,
                attempt_count = attempt_count + ?,
                last_attempt_at = CASE WHEN ? = 1 THEN now() ELSE last_attempt_at END,
                outcome_code = ?,
                completed_at = now(),
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE delivery_id = ? AND lease_token = ?
        """

        private const val RESCHEDULE_DELIVERY_SQL = """
            UPDATE webhook_deliveries
            SET delivery_state = 'PENDING',
                status = ?,
                attempt_count = attempt_count + 1,
                last_attempt_at = now(),
                outcome_code = ?,
                available_at = now() + (? * interval '1 second'),
                completed_at = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE delivery_id = ? AND lease_token = ?
        """

        private const val RELEASE_DELIVERY_SQL = """
            UPDATE webhook_deliveries
            SET delivery_state = 'PENDING',
                status = 0,
                outcome_code = ?,
                available_at = now(),
                completed_at = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE delivery_id = ? AND lease_token = ?
        """

        // SSRF protection config for webhook URLs - allows HTTPS to external hosts only.
        private val WEBHOOK_SSRF_CONFIG = SsrfConfig(
            allowedHosts = emptySet(), // We skip host allowlist — rely on IP validation only
            allowedProtocols = setOf("https"),
            blockPrivateIps = true,
            blockLocalhost = true,
            blockLinkLocal = true,
            blockMetadataEndpoints = true,
            validateRedirects = true,
            enabled = true
        )

        private const val EXECUTOR_SHUTDOWN_GRACE_SECONDS = 10L
        private const val EXECUTOR_FORCE_SHUTDOWN_SECONDS = 5L
        private const val MAX_DELIVERY_ATTEMPTS = 5
        private const val SCHEDULED_DISPATCH_TASKS = 4
        private const val NO_HTTP_STATUS = 0
        private const val HTTP_CALL_TIMEOUT_SECONDS = 30L
        private const val MAX_RETRY_AFTER_SECONDS = 3_600L
        private val deliveryThreadCounter = AtomicInteger()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun newDeliveryExecutor(): ExecutorService {
            val threadFactory = ThreadFactory { task ->
                Thread(task, "chronicle-webhook-delivery-${deliveryThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                        logger.error("Uncaught webhook worker failure on {}", thread.name, error)
                    }
                }
            }
            return ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(100),
                threadFactory,
                ThreadPoolExecutor.AbortPolicy(),
            )
        }

        private fun newHttpClientTemplate(): OkHttpClient {
            return OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        private fun hashSecret(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        public fun computeSignature(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return "sha256=" + mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        internal fun parseRetryAfterSeconds(value: String?, now: Instant = Instant.now()): Long? {
            if (value.isNullOrBlank()) return null
            val seconds = value.trim().toLongOrNull()
            if (seconds != null) {
                return seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
            }
            return try {
                val retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(now, retryAt).seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
            } catch (_: Exception) {
                null
            }
        }

        private fun isRetryableHttpStatus(status: Int): Boolean {
            return status == 408 || status == 425 || status == 429 || status in 500..599
        }

        internal fun parseDeliveryUrl(url: String): HttpUrl {
            val parsed = url.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Webhook URL is invalid")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Webhook URL must not include userinfo"
            }
            require(parsed.fragment == null) { "Webhook URL must not include a fragment" }
            return parsed
        }
    }

    public fun createWebhook(studyId: UUID, userId: String, request: WebhookCreateRequest): WebhookRegistration {
        validateCreateRequest(request)
        // C-5: Validate webhook URL against SSRF rules on registration
        validateWebhookUrl(request.url)

        val webhookId = idGenerationService.getNextId()
        val secretHash = if (request.secret.isNotBlank()) hashSecret(request.secret) else ""

        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_WEBHOOK_SQL).use { ps ->
                ps.setObject(1, webhookId)
                ps.setObject(2, studyId)
                ps.setString(3, request.url)
                ps.setString(4, secretHash)
                ps.setArray(5, PostgresArrays.createTextArray(
                    ps.connection, request.eventTypes.map { it.name }
                ))
                ps.setString(6, request.description)
                ps.setString(7, userId)
                ps.executeUpdate()
            }
        }

        logger.info(
            "Webhook {} created for studyRef {} by userRef {}",
            webhookId,
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
            LogSanitizer.stableFingerprint(userId, prefix = "user")
        )
        return WebhookRegistration(
            webhookId = webhookId,
            studyId = studyId,
            url = request.url,
            eventTypes = request.eventTypes,
            description = request.description,
            createdAt = OffsetDateTime.now()
        )
    }

    public fun listWebhooks(studyId: UUID): List<WebhookRegistration> {
        val webhooks = mutableListOf<WebhookRegistration>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_WEBHOOKS_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    webhooks.add(mapWebhookRegistration(rs))
                }
            }
        }
        return webhooks
    }

    public fun deleteWebhook(studyId: UUID, webhookId: UUID) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(DELETE_WEBHOOK_SQL).use { ps ->
                ps.setObject(1, webhookId)
                ps.setObject(2, studyId)
                val deleted = ps.executeUpdate()
                if (deleted == 0) throw WebhookNotFoundException()
            }
        }
        logger.info(
            "Webhook {} deleted for studyRef {}",
            webhookId,
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study")
        )
    }

    public fun getDeliveries(studyId: UUID, webhookId: UUID): List<WebhookDeliveryInfo> {
        val deliveries = mutableListOf<WebhookDeliveryInfo>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_DELIVERIES_SQL).use { ps ->
                ps.setObject(1, webhookId)
                ps.setObject(2, studyId)
                val rs = ps.executeQuery()
                if (!rs.next()) throw WebhookNotFoundException()
                do {
                    val deliveryId = rs.getObject("delivery_id", UUID::class.java)
                    if (deliveryId != null) {
                        deliveries.add(WebhookDeliveryInfo(
                            deliveryId = deliveryId,
                            webhookId = rs.getObject("webhook_id", UUID::class.java),
                            eventType = WebhookEventType.valueOf(rs.getString("event_type")),
                            status = rs.getInt("status"),
                            attemptCount = rs.getInt("attempt_count"),
                            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                            lastAttemptAt = rs.getObject("last_attempt_at", OffsetDateTime::class.java),
                            deliveryState = WebhookDeliveryState.valueOf(rs.getString("delivery_state")),
                            outcomeCode = rs.getString("outcome_code"),
                            availableAt = rs.getObject("available_at", OffsetDateTime::class.java),
                            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                        ))
                    }
                } while (rs.next())
            }
        }
        return deliveries
    }

    /**
     * Compatibility boundary for publishers that have already committed their domain change.
     *
     * Delivery rows commit before any executor submission. A caller that still owns its domain
     * transaction can use [enqueueEvent] so the domain write and outbox rows share one commit.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun fireEvent(studyId: UUID, eventType: WebhookEventType, data: Map<String, Any>) {
        try {
            val enqueued = storageResolver.getPlatformStorage().connection.use { connection ->
                enqueueEvent(connection, studyId, eventType, data)
            }
            if (enqueued > 0) {
                submitDispatchTask()
            }
        } catch (ex: RejectedExecutionException) {
            logger.warn(
                "Webhook event was persisted but immediate dispatch is unavailable for studyRef {} eventType {}",
                LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
                eventType,
            )
        } catch (ex: Exception) {
            logger.error(
                "Webhook event enqueue failed for studyRef {} eventType {}",
                LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
                eventType,
                ex,
            )
        }
    }

    /**
     * Persists one delivery per matching registration using the caller's transaction.
     * This method never submits executor work and therefore cannot escape a rollback.
     */
    public fun enqueueEvent(
        connection: Connection,
        studyId: UUID,
        eventType: WebhookEventType,
        data: Map<String, Any>,
    ): Int {
        val payload = eventPayload(studyId, eventType, data)
        connection.prepareStatement(ENQUEUE_EVENT_SQL).use { statement ->
            statement.setString(1, eventType.name)
            statement.setString(2, payload)
            statement.setObject(3, studyId)
            statement.setString(4, eventType.name)
            return statement.executeUpdate()
        }
    }

    public fun testWebhook(studyId: UUID, webhookId: UUID) {
        val eventType = WebhookEventType.STUDY_STATUS_CHANGED
        val payload = eventPayload(studyId, eventType, mapOf("test" to true))
        val inserted = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(ENQUEUE_TEST_SQL).use { statement ->
                statement.setString(1, eventType.name)
                statement.setString(2, payload)
                statement.setObject(3, webhookId)
                statement.setObject(4, studyId)
                statement.executeUpdate()
            }
        }
        if (inserted == 0) {
            throw WebhookNotFoundException()
        }

        try {
            submitDispatchTask()
        } catch (ex: RejectedExecutionException) {
            logger.warn(
                "Webhook test delivery was persisted but immediate dispatch is unavailable for webhook {}",
                webhookId,
            )
        }
    }

    private fun eventPayload(
        studyId: UUID,
        eventType: WebhookEventType,
        data: Map<String, Any>,
    ): String {
        return mapper.writeValueAsString(
            mapOf(
                "eventType" to eventType.name,
                "studyId" to studyId.toString(),
                "timestamp" to OffsetDateTime.now().toString(),
                "data" to data,
            )
        )
    }

    private fun submitDispatchTask() {
        deliveryExecutor.execute(DeliveryTask())
    }

    /**
     * Restart and lease-expiry recovery. Small claim tasks let PostgreSQL's SKIP LOCKED
     * distribute work without occupying executor threads during backoff.
     */
    @Scheduled(fixedDelay = 2_000L)
    public fun dispatchPendingDeliveries() {
        repeat(SCHEDULED_DISPATCH_TASKS) {
            try {
                submitDispatchTask()
            } catch (ex: RejectedExecutionException) {
                logger.debug("Webhook dispatcher queue is full; persisted deliveries remain pending")
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun claimNextDelivery(): ClaimedDelivery? {
        return try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val leaseToken = UUID.randomUUID()
                    connection.prepareStatement(CLAIM_NEXT_DELIVERY_SQL).use { statement ->
                        statement.setObject(1, leaseToken)
                        statement.executeQuery().use { resultSet ->
                            if (!resultSet.next()) {
                                null
                            } else {
                                ClaimedDelivery(
                                    deliveryId = resultSet.getObject("delivery_id", UUID::class.java),
                                    webhookId = resultSet.getObject("webhook_id", UUID::class.java),
                                    url = resultSet.getString("url"),
                                    secretHash = resultSet.getString("secret_hash"),
                                    eventType = WebhookEventType.valueOf(resultSet.getString("event_type")),
                                    payload = resultSet.getString("payload"),
                                    attemptCount = resultSet.getInt("attempt_count"),
                                    leaseToken = leaseToken,
                                )
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            logger.error("Failed to claim a pending webhook delivery", ex)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persistOutcome(claim: ClaimedDelivery, outcome: DeliveryOutcome) {
        try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val updated = when {
                        outcome.successful -> completeDelivery(connection, claim, outcome.status)
                        outcome.terminal -> failDelivery(connection, claim, outcome)
                        !outcome.consumeAttempt -> releaseDelivery(connection, claim, outcome.outcomeCode)
                        claim.attemptCount + 1 >= MAX_DELIVERY_ATTEMPTS ->
                            failDelivery(connection, claim, outcome)
                        else -> rescheduleDelivery(connection, claim, outcome)
                    }
                    if (updated == 0) {
                        logger.warn(
                            "Ignored stale webhook completion for delivery {} lease {}",
                            claim.deliveryId,
                            claim.leaseToken,
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            // Leave the lease intact. Expiry replays the same stable delivery ID,
            // preserving at-least-once behavior after a remote response.
            logger.error(
                "Failed to persist webhook outcome for delivery {}; lease will be replayed",
                claim.deliveryId,
                ex,
            )
        }
    }

    private fun completeDelivery(connection: Connection, claim: ClaimedDelivery, status: Int): Int {
        connection.prepareStatement(COMPLETE_DELIVERY_SQL).use { statement ->
            statement.setInt(1, status)
            statement.setObject(2, claim.deliveryId)
            statement.setObject(3, claim.leaseToken)
            return statement.executeUpdate()
        }
    }

    private fun failDelivery(
        connection: Connection,
        claim: ClaimedDelivery,
        outcome: DeliveryOutcome,
    ): Int {
        val consumed = if (outcome.consumeAttempt) 1 else 0
        connection.prepareStatement(FAIL_DELIVERY_SQL).use { statement ->
            statement.setInt(1, outcome.status)
            statement.setInt(2, consumed)
            statement.setInt(3, consumed)
            statement.setString(4, outcome.outcomeCode)
            statement.setObject(5, claim.deliveryId)
            statement.setObject(6, claim.leaseToken)
            return statement.executeUpdate()
        }
    }

    private fun rescheduleDelivery(
        connection: Connection,
        claim: ClaimedDelivery,
        outcome: DeliveryOutcome,
    ): Int {
        val nextAttempt = claim.attemptCount + 1
        val exponentialDelay = (1L shl (nextAttempt - 1).coerceAtMost(5)).coerceAtMost(30L)
        val delaySeconds = outcome.retryDelaySeconds
            ?.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
            ?: exponentialDelay
        connection.prepareStatement(RESCHEDULE_DELIVERY_SQL).use { statement ->
            statement.setInt(1, outcome.status)
            statement.setString(2, outcome.outcomeCode)
            statement.setLong(3, delaySeconds)
            statement.setObject(4, claim.deliveryId)
            statement.setObject(5, claim.leaseToken)
            return statement.executeUpdate()
        }
    }

    private fun releaseDelivery(
        connection: Connection,
        claim: ClaimedDelivery,
        outcomeCode: String?,
    ): Int {
        connection.prepareStatement(RELEASE_DELIVERY_SQL).use { statement ->
            statement.setString(1, outcomeCode)
            statement.setObject(2, claim.deliveryId)
            statement.setObject(3, claim.leaseToken)
            return statement.executeUpdate()
        }
    }

    /**
     * Validates a webhook URL against SSRF rules.
     * Called on registration AND before each delivery (prevents DNS rebinding).
     */
    // reason: SSRF validation legitimately rejects via several IllegalArgumentException paths; the
    // broad catch wraps any parser/resolver failure into a rejection while passing validation
    // errors through unchanged
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun validateWebhookUrl(url: String) {
        try {
            val httpUrl = parseDeliveryUrl(url)
            SsrfValidator.validateProtocol(httpUrl.scheme, WEBHOOK_SSRF_CONFIG)
            SsrfValidator.validateHostSafety(httpUrl.host, WEBHOOK_SSRF_CONFIG)
            SsrfValidator.validateAndResolve(httpUrl.host, WEBHOOK_SSRF_CONFIG, hostResolver)
        } catch (ex: IllegalArgumentException) {
            throw ex
        } catch (ex: Exception) {
            throw IllegalArgumentException("Webhook URL rejected by SSRF validation: ${ex.message}", ex)
        }
    }

    internal fun buildDeliveryClient(httpUrl: HttpUrl): OkHttpClient {
        val pinnedDns = SsrfValidator.createPinnedDns(httpUrl, WEBHOOK_SSRF_CONFIG, hostResolver)
        return httpClientTemplate.newBuilder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun renewDeliveryLease(claim: ClaimedDelivery): Boolean {
        return try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.prepareStatement(RENEW_DELIVERY_LEASE_SQL).use { statement ->
                        statement.setObject(1, claim.deliveryId)
                        statement.setObject(2, claim.leaseToken)
                        statement.executeUpdate() == 1
                    }
                }
            }
        } catch (ex: Exception) {
            logger.warn(
                "Could not renew webhook delivery lease for {}; request will not be sent",
                claim.deliveryId,
                ex,
            )
            false
        }
    }

    // One claim performs one HTTP attempt. Retry count and backoff live in PostgreSQL so
    // process restarts do not reset them and worker threads never sleep between attempts.
    @Suppress("TooGenericExceptionCaught")
    private fun deliverWebhook(claim: ClaimedDelivery): DeliveryOutcome {
        val httpUrl: HttpUrl
        val client: OkHttpClient
        try {
            // Parse once with OkHttp so the DNS pin and the request use the exact same
            // canonical hostname (including case folding and IDN normalization).
            httpUrl = parseDeliveryUrl(claim.url)
            SsrfValidator.validateProtocol(httpUrl.scheme, WEBHOOK_SSRF_CONFIG)
            SsrfValidator.validateHostSafety(httpUrl.host, WEBHOOK_SSRF_CONFIG)
            client = buildDeliveryClient(httpUrl)
        } catch (ex: SsrfException) {
            if (ex.violationType == SsrfViolationType.DNS_RESOLUTION_FAILED) {
                logger.warn("Webhook {} DNS resolution failed transiently", claim.webhookId)
                return DeliveryOutcome(
                    status = NO_HTTP_STATUS,
                    outcomeCode = "dns_resolution",
                )
            }
            logger.warn(
                "Webhook {} delivery blocked by SSRF validation: {}",
                claim.webhookId,
                LogSanitizer.sanitize(ex.message ?: ""),
            )
            return DeliveryOutcome(
                status = NO_HTTP_STATUS,
                outcomeCode = "ssrf_validation",
                terminal = true,
                consumeAttempt = false,
            )
        } catch (ex: Exception) {
            logger.warn(
                "Webhook {} delivery setup failed transiently: {}",
                claim.webhookId,
                LogSanitizer.sanitize(ex.message ?: ""),
            )
            return DeliveryOutcome(
                status = NO_HTTP_STATUS,
                outcomeCode = "delivery_setup",
            )
        }

        /*
         * DNS validation happens before this renewal. If it outlived the original
         * lease, another worker may already own the row; never send from a stale
         * claim. The renewed lease exceeds OkHttp's absolute call timeout.
         */
        if (!renewDeliveryLease(claim)) {
            return DeliveryOutcome(
                status = NO_HTTP_STATUS,
                outcomeCode = "lease_lost",
                consumeAttempt = false,
            )
        }

        try {
            // Keep the hostname in the request so OkHttp performs normal SNI and certificate
            // hostname verification; only DNS resolution is pinned. The stable delivery ID
            // lets receivers deduplicate the required at-least-once replay behavior.
            val request = Request.Builder()
                .url(httpUrl)
                .post(claim.payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("X-Chronicle-Event", claim.eventType.name)
                .header("X-Chronicle-Delivery", claim.deliveryId.toString())
                .header("X-Chronicle-Signature", computeSignature(claim.payload, claim.secretHash))
                .header("X-Chronicle-Signature-Key", "sha256-secret-hash")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return DeliveryOutcome(
                        status = response.code,
                        outcomeCode = null,
                        successful = true,
                    )
                }
                val retryable = isRetryableHttpStatus(response.code)
                return DeliveryOutcome(
                    status = response.code,
                    outcomeCode = "http_status",
                    terminal = !retryable,
                    retryDelaySeconds = if (retryable) {
                        parseRetryAfterSeconds(response.header("Retry-After"))
                    } else {
                        null
                    },
                )
            }
        } catch (ex: SSLException) {
            logger.warn(
                "Webhook TLS validation failed for delivery {} webhook {}",
                claim.deliveryId,
                claim.webhookId,
            )
            return DeliveryOutcome(
                status = NO_HTTP_STATUS,
                outcomeCode = "tls_failure",
                terminal = true,
            )
        } catch (ex: Exception) {
            /*
             * OkHttp reports ordinary socket timeouts as InterruptedIOException too.
             * Only the thread's interrupt flag is authoritative here; otherwise a
             * network timeout would poison this pooled worker and suppress retries.
             */
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                logger.warn(
                    "Webhook delivery interrupted for delivery {} webhook {} eventType {}",
                    claim.deliveryId,
                    claim.webhookId,
                    claim.eventType,
                )
                return DeliveryOutcome(
                    status = NO_HTTP_STATUS,
                    outcomeCode = "interrupted",
                    consumeAttempt = false,
                )
            }
            logger.warn(
                "Webhook delivery attempt for delivery {} webhook {} failed: {}",
                claim.deliveryId,
                claim.webhookId,
                LogSanitizer.sanitize(ex.message ?: ""),
            )
            return DeliveryOutcome(
                status = NO_HTTP_STATUS,
                outcomeCode = "transport_failure",
            )
        }
    }

    @PreDestroy
    public fun shutdown() {
        deliveryExecutor.shutdown()
        try {
            if (!deliveryExecutor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                deliveryExecutor.shutdownNow()
                if (!deliveryExecutor.awaitTermination(EXECUTOR_FORCE_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    logger.error("Webhook delivery executor did not terminate after forced shutdown")
                }
            }
        } catch (ex: InterruptedException) {
            deliveryExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            logger.warn("Webhook delivery executor shutdown was interrupted")
        }
    }

    private fun validateCreateRequest(request: WebhookCreateRequest) {
        require(request.url.isNotBlank()) { "Webhook URL is required" }
        require(request.url.length <= 2048) { "Webhook URL exceeds maximum length" }
        require(request.url.startsWith("https://", ignoreCase = true)) { "Webhook URL must use HTTPS" }
        require(request.secret.isNotBlank()) { "Webhook secret is required" }
        require(request.secret.length in 32..255) { "Webhook secret must be between 32 and 255 characters" }
        require(request.eventTypes.isNotEmpty()) { "At least one event type is required" }
        require(request.description.length <= 1000) { "Description exceeds maximum length" }
    }

    private inner class DeliveryTask : Runnable {
        @Suppress("TooGenericExceptionCaught")
        override fun run() {
            val claim = claimNextDelivery() ?: return
            val outcome = try {
                deliverWebhook(claim)
            } catch (ex: Exception) {
                logger.error(
                    "Webhook worker failed for delivery {} webhook {} eventType {}",
                    claim.deliveryId,
                    claim.webhookId,
                    claim.eventType,
                    ex,
                )
                DeliveryOutcome(
                    status = NO_HTTP_STATUS,
                    outcomeCode = if (Thread.currentThread().isInterrupted) {
                        "interrupted"
                    } else {
                        "worker_failure"
                    },
                    consumeAttempt = !Thread.currentThread().isInterrupted,
                )
            }
            persistOutcome(claim, outcome)
        }
    }

    private data class DeliveryOutcome(
        val status: Int,
        val outcomeCode: String?,
        val successful: Boolean = false,
        val terminal: Boolean = false,
        val consumeAttempt: Boolean = true,
        val retryDelaySeconds: Long? = null,
    )

    private data class ClaimedDelivery(
        val deliveryId: UUID,
        val webhookId: UUID,
        val url: String,
        val secretHash: String,
        val eventType: WebhookEventType,
        val payload: String,
        val attemptCount: Int,
        val leaseToken: UUID,
    )

    private fun mapWebhookRegistration(rs: ResultSet): WebhookRegistration {
        val eventTypesArray = rs.getArray("event_types")
        val eventTypes = if (eventTypesArray != null) {
            (eventTypesArray.array as Array<*>)
                .filterIsInstance<String>()
                .map { WebhookEventType.valueOf(it) }
                .toSet()
        } else emptySet()

        return WebhookRegistration(
            webhookId = rs.getObject("webhook_id", UUID::class.java),
            studyId = rs.getObject("study_id", UUID::class.java),
            url = rs.getString("url"),
            eventTypes = eventTypes,
            enabled = rs.getBoolean("enabled"),
            description = rs.getString("description"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }
}
