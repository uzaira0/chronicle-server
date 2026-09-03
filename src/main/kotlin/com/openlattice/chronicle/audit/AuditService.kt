/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.openlattice.chronicle.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

/**
 * Service for comprehensive audit logging that writes to BOTH database and log files.
 * This is critical for HIPAA compliance - all PHI access must be tracked with who, what, when, and outcome.
 *
 * Features:
 * - Dual-write: Every audit event goes to both database (for querying/retention) and log file (for SIEM)
 * - Async processing: Non-blocking to avoid impacting request performance
 * - Batch processing: Efficient database writes using batching
 * - Fail-safe: If database write fails, log file still captures the event
 *
 * The log file output is JSON formatted and suitable for SIEM integration tools like:
 * - Splunk
 * - ELK Stack (Elasticsearch, Logstash, Kibana)
 * - local structured audit logs
 * - DataDog
 */
public open class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(AuditService::class.java)

        // Dedicated audit logger for file output (configured in logback)
        private val auditLogger = LoggerFactory.getLogger("AUDIT")

        // Configuration
        private const val BATCH_SIZE = 100
        private const val FLUSH_INTERVAL_MS = 2000L // 2 seconds (L-4: reduced from 5s to minimize DB write lag)
        private const val THREAD_POOL_SIZE = 1
        private const val MAX_FLUSH_RETRIES = 3
        private const val MAX_QUEUED_EVENTS = 10_000
        private const val MAX_AUDIT_STRING_LENGTH = 500
        private val auditErrorSanitizer = ErrorSanitizationConfig(maxMessageLength = MAX_AUDIT_STRING_LENGTH)
        private val redactedAdditionalDataKeyParts = setOf("token", "secret", "password", "apikey", "api_key")
        private val fingerprintedAdditionalDataKeys = setOf(
            "participantid",
            "participantids",
            "deviceid",
            "deviceids",
            "sourcedeviceid",
            "datasourceid",
            "principalid",
            "targetprincipalid",
            "userid"
        )

        internal fun sanitizeForPersistence(entry: AuditLogEntry): AuditLogEntry {
            return entry.copy(
                ipAddress = LogSanitizer.stableFingerprint(entry.ipAddress, prefix = "ip"),
                userAgent = entry.userAgent?.let { LogSanitizer.sanitize(it, MAX_AUDIT_STRING_LENGTH) },
                errorMessage = sanitizeAuditErrorMessage(entry.errorMessage),
                requestPath = entry.requestPath?.let { LogSanitizer.sanitizeRequestPath(it) },
                requestMethod = entry.requestMethod?.let { LogSanitizer.sanitize(it, 32) },
                additionalData = sanitizeAdditionalData(entry.additionalData)
            )
        }

        private fun sanitizeAuditErrorMessage(message: String?): String? {
            return message?.let {
                auditErrorSanitizer.sanitizeMessage(LogSanitizer.sanitize(it, MAX_AUDIT_STRING_LENGTH))
            }
        }

        private fun sanitizeAdditionalData(data: Map<String, Any>?): Map<String, Any>? {
            return data?.mapValues { (key, value) -> sanitizeAdditionalDataValue(key, value) }
        }

        private fun sanitizeAdditionalDataValue(key: String, value: Any?): Any {
            val normalizedKey = key.lowercase().replace(Regex("[^a-z0-9]"), "")
            return when {
                redactedAdditionalDataKeyParts.any { normalizedKey.contains(it) } -> "[REDACTED]"
                normalizedKey in fingerprintedAdditionalDataKeys -> fingerprintAuditValue(value, prefixForKey(normalizedKey))
                value == null -> "[null]"
                value is String -> LogSanitizer.sanitize(value, MAX_AUDIT_STRING_LENGTH)
                value is Number || value is Boolean -> value
                value is Collection<*> -> value.take(50).map { item ->
                    item?.toString()?.let { LogSanitizer.sanitize(it, MAX_AUDIT_STRING_LENGTH) } ?: "[null]"
                }
                value is Map<*, *> -> value.entries.take(50).associate { (nestedKey, nestedValue) ->
                    val nestedKeyString = nestedKey?.toString() ?: "[null]"
                    LogSanitizer.sanitize(nestedKeyString, 100) to sanitizeAdditionalDataValue(nestedKeyString, nestedValue)
                }
                else -> LogSanitizer.sanitize(value.toString(), MAX_AUDIT_STRING_LENGTH)
            }
        }

        private fun fingerprintAuditValue(value: Any?, prefix: String): Any {
            return when (value) {
                null -> "$prefix:[null]"
                is Collection<*> -> value.take(50).map { item ->
                    LogSanitizer.stableFingerprint(item?.toString(), prefix = prefix)
                }
                else -> LogSanitizer.stableFingerprint(value.toString(), prefix = prefix)
            }
        }

        private fun prefixForKey(normalizedKey: String): String {
            return when {
                normalizedKey.contains("participant") -> "participant"
                normalizedKey.contains("device") || normalizedKey.contains("datasource") -> "device"
                normalizedKey.contains("principal") || normalizedKey == "userid" -> "principal"
                else -> "id"
            }
        }
    }

    private val eventQueue: ArrayBlockingQueue<AuditLogEntry> = ArrayBlockingQueue(MAX_QUEUED_EVENTS)
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(THREAD_POOL_SIZE)
    private val isRunning = AtomicBoolean(true)
    private val flushTaskScheduled = AtomicBoolean(false)
    private val flushInProgress = AtomicBoolean(false)
    private val consecutiveFlushFailures = AtomicInteger(0)

    @PostConstruct
    public fun init() {
        // Schedule periodic batch flushing
        executor.scheduleAtFixedRate(
            { flushBatch() },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        logger.info("AuditService initialized with batch size {} and flush interval {}ms", BATCH_SIZE, FLUSH_INTERVAL_MS)
    }

    @PreDestroy
    public fun shutdown() {
        isRunning.set(false)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // Drain every remaining batch synchronously after scheduled work has
        // stopped. Failed batches are retried by flushBatch and eventually fall
        // back to the already-written audit file rather than looping forever.
        var attempts = 0
        val maxDrainAttempts = (MAX_QUEUED_EVENTS / BATCH_SIZE) + MAX_FLUSH_RETRIES
        while (eventQueue.isNotEmpty() && attempts < maxDrainAttempts) {
            flushBatch()
            attempts++
        }
        logger.info("AuditService shutdown complete")
    }

    /**
     * Logs a single audit event asynchronously.
     * This method returns immediately and processes the event in the background.
     */
    public fun log(entry: AuditLogEntry) {
        val sanitizedEntry = sanitizeForPersistence(entry)
        // Always write to log file immediately (synchronous) for SIEM
        writeToLogFile(sanitizedEntry)

        // Queue for async database write
        if (!eventQueue.offer(sanitizedEntry)) {
            logger.error(
                "Audit database queue is full ({} events); retaining the event only in the audit log file",
                MAX_QUEUED_EVENTS,
            )
            return
        }

        // Trigger batch processing if queue is large enough
        if (eventQueue.size >= BATCH_SIZE) {
            requestFlush()
        }
    }

    private fun requestFlush() {
        if (!flushTaskScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                flushBatch()
            } finally {
                flushTaskScheduled.set(false)
                if (eventQueue.size >= BATCH_SIZE && isRunning.get()) {
                    requestFlush()
                }
            }
        }
    }

    /**
     * Logs an audit event using the builder pattern.
     */
    public fun log(builder: AuditLogEntryBuilder.() -> Unit) {
        val entry = AuditLogEntryBuilder().apply(builder).build()
        log(entry)
    }

    /**
     * Logs a data access event.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logAccess(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        action: AuditAction,
        resourceType: String,
        resourceId: UUID?,
        studyId: UUID? = null,
        success: Boolean = true,
        errorMessage: String? = null,
        phiFields: List<String>? = null,
        additionalData: Map<String, Any>? = null
    ) {
        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            studyId = studyId,
            success = success,
            errorMessage = errorMessage,
            accessedPHI = phiFields?.isNotEmpty() ?: false,
            phiFields = phiFields,
            additionalData = additionalData
        )
        log(entry)
    }

    /**
     * Logs a data export event with detailed tracking.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logDataExport(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        studyId: UUID,
        exportType: String,
        recordCount: Int,
        participantIds: Set<String>? = null,
        phiFields: List<String>? = null
    ) {
        val additionalData = mutableMapOf<String, Any>(
            "exportType" to exportType,
            "recordCount" to recordCount
        )
        participantIds?.let { additionalData["participantCount"] = it.size }

        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            action = AuditAction.EXPORT,
            resourceType = "StudyData",
            studyId = studyId,
            success = true,
            accessedPHI = true, // Data exports always involve PHI
            phiFields = phiFields ?: listOf("participant_data", "usage_data", "sensor_data"),
            additionalData = additionalData
        )
        log(entry)
    }

    /**
     * Logs an authentication event.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logAuthEvent(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        userAgent: String?,
        eventType: AuditAction,
        success: Boolean,
        errorMessage: String? = null,
        additionalData: Map<String, Any>? = null
    ) {
        check(eventType in setOf(
            AuditAction.LOGIN,
            AuditAction.LOGOUT,
            AuditAction.LOGIN_FAILED,
            AuditAction.TOKEN_REFRESH,
            AuditAction.SESSION_EXPIRED
        )) { "Invalid auth event type: $eventType" }

        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            userAgent = userAgent,
            action = eventType,
            resourceType = "Authentication",
            success = success,
            errorMessage = errorMessage,
            additionalData = additionalData
        )
        log(entry)
    }

    /**
     * Logs an unauthorized access attempt.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logUnauthorizedAccess(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        userAgent: String?,
        resourceType: String,
        resourceId: UUID?,
        studyId: UUID? = null,
        attemptedAction: AuditAction,
        reason: String
    ) {
        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            userAgent = userAgent,
            action = AuditAction.UNAUTHORIZED_ACCESS,
            resourceType = resourceType,
            resourceId = resourceId,
            studyId = studyId,
            success = false,
            errorMessage = reason,
            additionalData = mapOf(
                "attemptedAction" to attemptedAction.name,
                "securityEvent" to true
            )
        )
        log(entry)
    }

    /**
     * Logs a participant data access event (specifically for PHI).
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logParticipantDataAccess(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        studyId: UUID,
        participantId: String,
        action: AuditAction,
        phiFields: List<String>,
        success: Boolean = true,
        errorMessage: String? = null
    ) {
        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            action = action,
            resourceType = "Participant",
            studyId = studyId,
            success = success,
            errorMessage = errorMessage,
            accessedPHI = true,
            phiFields = phiFields,
            additionalData = mapOf("participantId" to participantId)
        )
        log(entry)
    }

    /**
     * Logs sensor data upload from mobile devices.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logSensorDataUpload(
        studyId: UUID,
        participantId: String,
        deviceId: String,
        ipAddress: String,
        sensorTypes: List<String>,
        recordCount: Int,
        success: Boolean = true,
        errorMessage: String? = null
    ) {
        val entry = AuditLogEntry(
            ipAddress = ipAddress,
            action = AuditAction.SENSOR_DATA_UPLOAD,
            resourceType = "SensorData",
            studyId = studyId,
            success = success,
            errorMessage = errorMessage,
            accessedPHI = true,
            phiFields = listOf("sensor_data"),
            additionalData = mapOf(
                "participantId" to participantId,
                "deviceId" to deviceId,
                "sensorTypes" to sensorTypes,
                "recordCount" to recordCount
            )
        )
        log(entry)
    }

    /**
     * Logs study modification events.
     */
    // reason: public audit API signature — params map 1:1 to AuditLogEntry fields, must not change
    @Suppress("LongParameterList")
    public fun logStudyModification(
        userId: UUID?,
        userRole: String?,
        ipAddress: String,
        studyId: UUID,
        action: AuditAction,
        changes: Map<String, Any>? = null,
        success: Boolean = true,
        errorMessage: String? = null
    ) {
        val entry = AuditLogEntry(
            userId = userId,
            userRole = userRole,
            ipAddress = ipAddress,
            action = action,
            resourceType = "Study",
            resourceId = studyId,
            studyId = studyId,
            success = success,
            errorMessage = errorMessage,
            additionalData = changes
        )
        log(entry)
    }

    /**
     * Query methods for compliance reporting - delegate to repository
     */
    public fun findByUserId(userId: UUID, limit: Int = 100): List<AuditLogEntry> =
        auditLogRepository.findByUserId(userId, limit)
    public fun findByStudyId(studyId: UUID, limit: Int = 100): List<AuditLogEntry> =
        auditLogRepository.findByStudyId(studyId, limit)
    public fun findByDateRange(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> =
        auditLogRepository.findByDateRange(start, end, limit)
    public fun findPhiAccessEvents(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> =
        auditLogRepository.findPhiAccessEvents(start, end, limit)
    public fun findFailedOperations(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> =
        auditLogRepository.findFailedOperations(start, end, limit)
    public fun findSecurityEvents(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> =
        auditLogRepository.findSecurityEvents(start, end, limit)
    public fun getActionCounts(start: Instant, end: Instant): Map<AuditAction, Long> =
        auditLogRepository.getActionCounts(start, end)

    /**
     * Writes the audit entry to the log file in JSON format for SIEM integration.
     * This is done synchronously to ensure no audit events are lost.
     */
    // reason: boundary catch — audit log-file write must not leak any failure type past this point
    @Suppress("TooGenericExceptionCaught")
    private fun writeToLogFile(entry: AuditLogEntry) {
        try {
            val jsonString = objectMapper.writeValueAsString(entry)
            auditLogger.info(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to write audit entry to log file: ${entry.id}", e)
        }
    }

    /**
     * Flushes queued events to the database in batches.
     */
    // reason: boundary catch — async DB flush must not propagate any failure type to the scheduler
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    internal fun flushBatch() {
        if (!flushInProgress.compareAndSet(false, true)) return
        try {
            flushOneBatch()
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun flushOneBatch() {
        if (eventQueue.isEmpty()) return

        val batch = mutableListOf<AuditLogEntry>()
        while (batch.size < BATCH_SIZE && eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { batch.add(it) }
        }

        if (batch.isNotEmpty()) {
            try {
                val saved = auditLogRepository.saveBatch(batch)
                if (saved != batch.size) {
                    throw IllegalStateException("Only saved $saved/${batch.size} audit entries to database")
                }
                consecutiveFlushFailures.set(0)
            } catch (e: Exception) {
                if (consecutiveFlushFailures.incrementAndGet() < MAX_FLUSH_RETRIES) {
                    logger.error(
                        "Failed to flush {} audit entries to database — re-queuing for retry (attempt {}/{})",
                        batch.size,
                        consecutiveFlushFailures.get(),
                        MAX_FLUSH_RETRIES,
                        e
                    )
                    // Re-queue events so they are not silently discarded.
                    // They have already been written to the log file as a fallback.
                    batch.forEach { entry ->
                        if (!eventQueue.offer(entry)) {
                            logger.error(
                                "Audit database queue filled while re-queuing a failed batch; " +
                                    "event {} remains only in the audit log file",
                                entry.id,
                            )
                        }
                    }
                } else {
                    logger.error(
                        "Failed to flush {} audit entries to database after {} consecutive failures " +
                            "— dropping events (already written to audit log file as fallback)",
                        batch.size,
                        consecutiveFlushFailures.get(),
                        e
                    )
                    consecutiveFlushFailures.set(0) // Allow recovery after transient outage
                }
            }
        }
    }
}

/**
 * Interface for components that need audit logging capabilities.
 * Similar to the existing AuditingComponent but for the new comprehensive audit system.
 */
public interface AuditingCapable {
    public val auditService: AuditService

    public fun audit(builder: AuditLogEntryBuilder.() -> Unit) {
        auditService.log(builder)
    }
}
