package com.openlattice.chronicle.services.export

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.download.DataDownloadManager
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.EXPORT_JOBS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.COMPLETED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ERROR_MESSAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPORT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FILE_PATH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FORMAT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REQUEST
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ROW_COUNT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.webhooks.WebhookEventType
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.io.OutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

public open class ExportService(
    private val storageResolver: StorageResolver,
    private val downloadManager: DataDownloadManager,
    private val idGenerationService: HazelcastIdGenerationService,
    private val webhookService: WebhookService,
    private val exportExecutor: ExecutorService = newExportExecutor(),
    private val leaseExecutor: ScheduledExecutorService = newLeaseExecutor(),
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ExportService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()
        private val exportThreadCounter = AtomicInteger()
        private val leaseThreadCounter = AtomicInteger()

        private const val MAX_EXPORT_ATTEMPTS = 3
        private const val MAX_EXPORT_RECOVERIES = 3
        private const val EXPORT_LEASE_SECONDS = 300L
        private const val EXPORT_LEASE_RENEW_SECONDS = 60L
        private const val EXHAUSTED_RECOVERY_CLEANUP_BACKOFF_SECONDS = 300L
        private const val SCHEDULED_DISPATCH_TASKS = 4
        private const val EXECUTOR_SHUTDOWN_GRACE_SECONDS = 10L
        private const val EXECUTOR_FORCE_SHUTDOWN_SECONDS = 5L
        private const val POSTGRES_INFINITY_YEAR = 9999
        /**
         * Returns only a stable category and opaque correlation reference to study readers.
         * Throwable text may contain database hosts, SQL, filesystem paths, or credentials and
         * must never cross into export_jobs.error_message.
         */
        internal fun withFailureCause(summary: String, failure: Throwable): String {
            var root: Throwable = failure
            while (root.cause != null && root.cause !== root) {
                root = root.cause!!
            }
            val category = failureCategory(root)
            val reference = UUID.randomUUID().toString()
            val diagnosticHash = LogSanitizer.stableFingerprint(
                "${root::class.java.name}:${root.message.orEmpty()}",
                prefix = "export-failure",
            )
            logger.error(
                "Export failure reference {} category {} diagnosticType {} diagnosticHash {}",
                reference,
                category,
                root::class.java.name,
                diagnosticHash,
            )
            return "$summary (category=$category; reference=$reference)"
        }

        private fun failureCategory(failure: Throwable): String = when (failure) {
            is ExportResourceLimitException -> "capacity"
            is java.sql.SQLException -> "database"
            is java.net.ConnectException,
            is java.net.SocketException,
            is java.net.UnknownHostException -> "network"
            is java.io.IOException -> "storage"
            is SecurityException -> "authorization"
            is IllegalArgumentException -> "invalid-request"
            else -> "internal"
        }

        private val INSERT_EXPORT_JOB_SQL = """
            INSERT INTO ${EXPORT_JOBS.name}
                (${EXPORT_ID.name}, ${STUDY_ID.name}, ${STATUS.name}, ${FORMAT.name}, ${REQUEST.name}, ${CREATED_BY.name})
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
        """.trimIndent()

        private val GET_EXPORT_JOB_SQL = """
            SELECT * FROM ${EXPORT_JOBS.name}
            WHERE ${EXPORT_ID.name} = ? AND ${STUDY_ID.name} = ?
        """.trimIndent()

        private val LOCK_EXPORT_FOR_DOWNLOAD_SQL = """
            SELECT job.*,
                   EXISTS (
                       SELECT 1
                       FROM export_job_revocations AS revocation
                       WHERE revocation.export_id = job.export_id
                   ) AS revoked
            FROM ${EXPORT_JOBS.name} AS job
            WHERE job.${EXPORT_ID.name} = ? AND job.${STUDY_ID.name} = ?
            FOR UPDATE OF job
        """.trimIndent()

        private val LOCK_EXPORT_FOR_COMPLETION_SQL = """
            SELECT job.${STATUS.name},
                   EXISTS (
                       SELECT 1
                       FROM export_job_revocations AS revocation
                       WHERE revocation.export_id = job.export_id
                   ) AS revoked
            FROM ${EXPORT_JOBS.name} AS job
            WHERE job.${EXPORT_ID.name} = ?
              AND job.${STUDY_ID.name} = ?
              AND job.status = 'RUNNING'
              AND job.lease_token = ?
            FOR UPDATE OF job
        """.trimIndent()

        private val CLAIM_NEXT_EXPORT_SQL = """
            WITH candidate AS (
                SELECT
                    job.export_id,
                    job.status = 'RUNNING' AS was_recovery
                FROM ${EXPORT_JOBS.name} AS job
                WHERE (
                        (job.status = 'PENDING' AND job.available_at <= now())
                        OR
                        (
                            job.status = 'RUNNING'
                            AND job.lease_expires_at <= now()
                            AND job.recovery_count < $MAX_EXPORT_RECOVERIES
                        )
                    )
                  AND job.attempt_count < $MAX_EXPORT_ATTEMPTS
                  AND NOT EXISTS (
                      SELECT 1
                      FROM export_job_revocations AS revocation
                      WHERE revocation.export_id = job.export_id
                  )
                ORDER BY
                    CASE WHEN job.status = 'PENDING' THEN job.available_at ELSE job.lease_expires_at END,
                    job.created_at
                FOR UPDATE OF job SKIP LOCKED
                LIMIT 1
            )
            UPDATE ${EXPORT_JOBS.name} AS job
            SET status = 'RUNNING',
                lease_token = ?,
                lease_expires_at = now() + (? * interval '1 second'),
                recovery_count = job.recovery_count +
                    CASE WHEN candidate.was_recovery THEN 1 ELSE 0 END,
                updated_at = now(),
                error_message = NULL,
                completed_at = 'infinity'
            FROM candidate
            WHERE job.export_id = candidate.export_id
            RETURNING job.export_id, job.study_id, job.request::text AS request_json,
                      job.format, job.created_by, job.attempt_count,
                      job.recovery_count, job.lease_token
        """.trimIndent()

        private val RENEW_EXPORT_LEASE_SQL = """
            UPDATE ${EXPORT_JOBS.name}
            SET lease_expires_at = now() + (? * interval '1 second'),
                updated_at = now()
            WHERE export_id = ? AND lease_token = ? AND status = 'RUNNING'
        """.trimIndent()

        private val LOCK_EXPORT_FOR_FAILURE_SQL = """
            SELECT status
            FROM ${EXPORT_JOBS.name}
            WHERE export_id = ? AND study_id = ? AND lease_token = ? AND status = 'RUNNING'
            FOR UPDATE
        """.trimIndent()

        private val COMPLETE_EXPORT_SQL = """
            UPDATE ${EXPORT_JOBS.name}
            SET status = 'COMPLETED',
                completed_at = now(),
                download_token = NULL,
                row_count = ?,
                error_message = NULL,
                file_path = ?,
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE export_id = ? AND lease_token = ? AND status = 'RUNNING'
        """.trimIndent()

        private val LIST_EXPORT_JOBS_SQL = """
            SELECT * FROM ${EXPORT_JOBS.name}
            WHERE ${STUDY_ID.name} = ?
            ORDER BY ${CREATED_AT.name} DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        private fun newExportExecutor(): ExecutorService {
            val threadFactory = ThreadFactory { task ->
                Thread(task, "chronicle-export-${exportThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                        logger.error("Uncaught export worker failure on {}", thread.name, error)
                    }
                }
            }
            return ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(20),
                threadFactory,
                ThreadPoolExecutor.AbortPolicy(),
            )
        }

        private fun newLeaseExecutor(): ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "chronicle-export-lease-${leaseThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }.apply {
                removeOnCancelPolicy = true
            }
    }

    internal data class ClaimedExport(
        val exportId: UUID,
        val studyId: UUID,
        val requestJson: String,
        val format: ExportFormat,
        val createdBy: String,
        val attemptCount: Int,
        val recoveryCount: Int,
        val leaseToken: UUID,
    )

    private data class ExportCleanupCandidate(
        val exportId: UUID,
        val studyId: UUID,
        val filePath: String,
        val status: String,
    )

    public fun createAsyncExport(studyId: UUID, userId: String, request: ExportRequest): ExportJobInfo {
        val exportId = idGenerationService.getNextId()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_EXPORT_JOB_SQL).use { ps ->
                ps.setObject(1, exportId)
                ps.setObject(2, studyId)
                ps.setString(3, ExportJobStatus.PENDING.name)
                ps.setString(4, request.format.name)
                ps.setString(5, mapper.writeValueAsString(request))
                ps.setString(6, userId)
                ps.executeUpdate()
            }
        }

        try {
            submitDispatchTask()
        } catch (_: RejectedExecutionException) {
            logger.debug("Export dispatcher is full; persisted export {} remains pending", exportId)
        }

        logger.info("Async export {} created for study {} by user {}", exportId, studyId, userId)
        return ExportJobInfo(
            exportId = exportId,
            studyId = studyId,
            status = ExportJobStatus.PENDING,
            format = request.format,
            createdAt = now
        )
    }

    public fun getExportStatus(studyId: UUID, exportId: UUID): ExportJobInfo {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_EXPORT_JOB_SQL).use { ps ->
                ps.setObject(1, exportId)
                ps.setObject(2, studyId)
                val rs = ps.executeQuery()
                check(rs.next()) { "Export job $exportId not found for study $studyId" }
                return mapExportJobInfo(rs)
            }
        }
    }

    public fun getExportStatusForDownload(studyId: UUID, exportId: UUID, requestingUserId: String): ExportJobInfo {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_EXPORT_JOB_SQL).use { ps ->
                ps.setObject(1, exportId)
                ps.setObject(2, studyId)
                val rs = ps.executeQuery()
                check(rs.next()) { "Export job $exportId not found for study $studyId" }
                val createdBy = rs.getString(CREATED_BY.name)
                check(createdBy == requestingUserId) {
                    "Export job $exportId was not created by requesting user"
                }
                return mapExportJobInfo(rs)
            }
        }
    }

    public fun listExports(studyId: UUID, limit: Int = 50, offset: Int = 0): List<ExportJobInfo> {
        val jobs = mutableListOf<ExportJobInfo>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_EXPORT_JOBS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    jobs.add(mapExportJobInfo(rs))
                }
            }
        }
        return jobs
    }

    public fun streamExportFile(studyId: UUID, exportId: UUID, requestingUserId: String, outputStream: OutputStream): ExportJobInfo {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            var primaryFailure: Exception? = null
            var exportStorageLock: AutoCloseable? = null
            try {
                acquireExportStudyTransactionLock(connection, studyId)
                val jobInfo = connection.prepareStatement(LOCK_EXPORT_FOR_DOWNLOAD_SQL).use { statement ->
                    statement.setObject(1, exportId)
                    statement.setObject(2, studyId)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Export job $exportId not found for study $studyId" }
                        check(resultSet.getString(CREATED_BY.name) == requestingUserId) {
                            "Export job $exportId was not created by requesting user"
                        }
                        check(!resultSet.getBoolean("revoked")) {
                            "Export job $exportId has been revoked by a data-erasure request"
                        }
                        mapExportJobInfo(resultSet)
                    }
                }
                check(jobInfo.status == ExportJobStatus.COMPLETED) {
                    "Export job $exportId is not completed (status: ${jobInfo.status})"
                }
                exportStorageLock = ExportFileWriter.acquireStudyExportLock(studyId)
                val filePath = checkNotNull(jobInfo.filePath) { "Export file not found for job $exportId" }
                try {
                    ExportFileWriter.verifyManagedExportFile(filePath)
                } catch (missingArtifact: IllegalStateException) {
                    connection.prepareStatement(
                        """
                        UPDATE ${EXPORT_JOBS.name}
                        SET status = 'FAILED',
                            file_path = NULL,
                            download_token = NULL,
                            error_message = 'Export artifact is unavailable; create a new export',
                            updated_at = now()
                        WHERE export_id = ? AND study_id = ? AND status = 'COMPLETED'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, exportId)
                        statement.setObject(2, studyId)
                        check(statement.executeUpdate() == 1) {
                            "Missing export artifact state changed while locked"
                        }
                    }
                    connection.prepareStatement(
                        "DELETE FROM export_capacity_reservations WHERE export_id = ?",
                    ).use { statement ->
                        statement.setObject(1, exportId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    throw missingArtifact
                }
                ExportFileWriter.copyManagedExportFile(filePath, outputStream)
                connection.commit()
                return jobInfo
            } catch (ex: Exception) {
                primaryFailure = ex
                try {
                    if (!connection.autoCommit) {
                        connection.rollback()
                    }
                } catch (rollbackFailure: Exception) {
                    ex.addSuppressed(rollbackFailure)
                }
                throw ex
            } finally {
                try {
                    exportStorageLock?.close()
                } catch (lockFailure: Exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(lockFailure)
                    } else {
                        logger.error(
                            "Failed to release export storage lock after streaming export {}",
                            exportId,
                            lockFailure,
                        )
                    }
                }
                try {
                    connection.autoCommit = previousAutoCommit
                } catch (restoreFailure: Exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(restoreFailure)
                    } else {
                        throw restoreFailure
                    }
                }
            }
        }
    }

    private fun submitDispatchTask() {
        exportExecutor.execute(ExportTask())
    }

    /**
     * PostgreSQL is the durable queue. Scheduler ticks recover both persisted
     * PENDING work and RUNNING work whose owner disappeared and lease expired.
     */
    @Scheduled(fixedDelay = 2_000L)
    public fun dispatchPendingExports() {
        repeat(SCHEDULED_DISPATCH_TASKS) {
            try {
                submitDispatchTask()
            } catch (_: RejectedExecutionException) {
                logger.debug("Export dispatcher queue is full; persisted jobs remain pending")
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun claimNextExport(): ClaimedExport? {
        return try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val leaseToken = UUID.randomUUID()
                    connection.prepareStatement(CLAIM_NEXT_EXPORT_SQL).use { statement ->
                        statement.setObject(1, leaseToken)
                        statement.setLong(2, EXPORT_LEASE_SECONDS)
                        statement.executeQuery().use { resultSet ->
                            if (!resultSet.next()) {
                                null
                            } else {
                                ClaimedExport(
                                    exportId = resultSet.getObject("export_id", UUID::class.java),
                                    studyId = resultSet.getObject("study_id", UUID::class.java),
                                    requestJson = resultSet.getString("request_json"),
                                    format = ExportFormat.valueOf(resultSet.getString("format")),
                                    createdBy = resultSet.getString("created_by"),
                                    attemptCount = resultSet.getInt("attempt_count"),
                                    recoveryCount = resultSet.getInt("recovery_count"),
                                    leaseToken = resultSet.getObject("lease_token", UUID::class.java),
                                )
                            }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            logger.error("Failed to claim a pending export", ex)
            null
        }
    }

    /**
     * Compatibility seam retained for focused tests. Production execution
     * always enters through a PostgreSQL claim owned by [ExportTask].
     */
    internal fun executeExport(
        exportId: UUID,
        studyId: UUID,
        request: ExportRequest,
        createdBy: String = "chronicle-export-worker",
    ) {
        executeClaimedExport(
            ClaimedExport(
                exportId = exportId,
                studyId = studyId,
                requestJson = mapper.writeValueAsString(request),
                format = request.format,
                createdBy = createdBy,
                attemptCount = 0,
                recoveryCount = 0,
                leaseToken = UUID.randomUUID(),
            ),
            request,
            startHeartbeat = false,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeClaimedExport(
        claim: ClaimedExport,
        request: ExportRequest,
        startHeartbeat: Boolean = true,
    ) {
        val leaseActive = AtomicBoolean(true)
        val heartbeat = if (startHeartbeat) startLeaseHeartbeat(claim, leaseActive) else null
        try {
            require(request.format == claim.format) {
                "Stored request format does not match export job format"
            }
            RLSRequestContext.withExportWorkerContext(claim.createdBy, claim.studyId) {
                val startDate = request.startDate ?: OffsetDateTime.MIN
                val endDate = request.endDate ?: OffsetDateTime.MAX
                require(startDate.isBefore(endDate)) { "Export startDate must be before endDate" }
                if (request.startDate != null && request.endDate != null) {
                    require(!endDate.isAfter(startDate.plusDays(366))) {
                        "Explicit export date range too large (max 366 days)."
                    }
                }
                require(request.dataTypes.isNotEmpty()) { "Export requires at least one data type" }

                val participantIds = request.participantIds
                val dataByType = linkedMapOf<String, Iterable<Map<String, Any>>>()
                request.dataTypes.sortedBy { it.name }.forEach { dataType ->
                    check(leaseActive.get()) { "Export lease was lost before data retrieval completed" }
                    dataByType[dataType.name] = loadDataForExport(
                        claim.studyId,
                        participantIds,
                        dataType,
                        startDate,
                        endDate,
                    )
                }

                check(leaseActive.get()) { "Export lease was lost before file generation" }
                val result = completeExportAndEnqueue(claim, request, dataByType)
                if (result == null) {
                    logger.info("Ignored stale or revoked completion for export {}", claim.exportId)
                } else {
                    logger.info(
                        "Export {} completed with {} rows at {}",
                        claim.exportId,
                        result.rowCount,
                        result.path,
                    )
                }
            }
        } catch (ex: Exception) {
            logger.error("Export {} execution failed", claim.exportId, ex)
            persistExportFailure(claim, claim.format, ex)
        } finally {
            heartbeat?.cancel(false)
        }
    }

    /**
     * Resolves one public export data type to its authoritative researcher-download source.
     * Kept as a focused seam so dispatch coverage does not need to create files or claim jobs.
     */
    internal fun loadDataForExport(
        studyId: UUID,
        participantIds: Set<String>,
        dataType: ParticipantDataType,
        startDate: OffsetDateTime,
        endDate: OffsetDateTime,
    ): Iterable<Map<String, Any>> = when (dataType) {
        ParticipantDataType.UsageEvents ->
            downloadManager.getParticipantsUsageEventsData(
                studyId,
                participantIds,
                startDate,
                endDate,
            )
        ParticipantDataType.Preprocessed ->
            downloadManager.getPreprocessedUsageEventsData(
                studyId,
                participantIds,
                startDate,
                endDate,
            )
        ParticipantDataType.AppUsageSurvey ->
            downloadManager.getParticipantsAppUsageSurveyData(
                studyId,
                participantIds,
                startDate,
                endDate,
            )
        ParticipantDataType.IOSSensor ->
            downloadManager.getParticipantsSensorData(
                studyId,
                participantIds,
                SensorType.entries.toSet(),
                startDate,
                endDate,
            )
        ParticipantDataType.AndroidSensor ->
            downloadManager.getParticipantsAndroidSensorData(
                studyId,
                participantIds,
                startDate,
                endDate,
            )
        ParticipantDataType.SensorAvailability,
        ParticipantDataType.BatteryTelemetry,
        ParticipantDataType.InteractionEvents,
        ParticipantDataType.AudioActivity,
        ParticipantDataType.AudioContent,
        ParticipantDataType.NotificationActivity,
        ParticipantDataType.SleepEvents,
        ParticipantDataType.ActivityRecognition,
        ParticipantDataType.HealthMetrics,
        ParticipantDataType.ConnectivityState,
        ParticipantDataType.AppNetworkUsage,
        ParticipantDataType.DeviceSettings ->
            downloadManager.getParticipantsCollectionData(
                studyId,
                participantIds,
                dataType,
                startDate,
                endDate,
            )
    }

    private fun startLeaseHeartbeat(
        claim: ClaimedExport,
        leaseActive: AtomicBoolean,
    ): ScheduledFuture<*> {
        return leaseExecutor.scheduleAtFixedRate(
            {
                if (!renewExportLease(claim)) {
                    leaseActive.set(false)
                }
            },
            EXPORT_LEASE_RENEW_SECONDS,
            EXPORT_LEASE_RENEW_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun renewExportLease(claim: ClaimedExport): Boolean {
        return try {
            RLSRequestContext.withExportWorkerContext(claim.createdBy, claim.studyId) {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.prepareStatement(RENEW_EXPORT_LEASE_SQL).use { statement ->
                        statement.setLong(1, EXPORT_LEASE_SECONDS)
                        statement.setObject(2, claim.exportId)
                        statement.setObject(3, claim.leaseToken)
                        statement.executeUpdate() == 1
                    }
                }
            }
        } catch (ex: Exception) {
            logger.warn("Could not renew lease for export {}", claim.exportId, ex)
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun completeExportAndEnqueue(
        claim: ClaimedExport,
        request: ExportRequest,
        dataByType: Map<String, Iterable<Map<String, Any>>>,
    ): ExportWriteResult? {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            var primaryFailure: Exception? = null
            var capacityReserved = false
            var exportStorageLock: AutoCloseable? = null
            acquireExportStudyLock(connection, claim.studyId)
            try {
                if (!reserveExportCapacity(claim)) {
                    return null
                }
                capacityReserved = true
                connection.autoCommit = false
                val revoked = connection.prepareStatement(LOCK_EXPORT_FOR_COMPLETION_SQL).use { statement ->
                    statement.setObject(1, claim.exportId)
                    statement.setObject(2, claim.studyId)
                    statement.setObject(3, claim.leaseToken)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            connection.rollback()
                            return null
                        }
                        resultSet.getBoolean("revoked")
                    }
                }

                exportStorageLock = ExportFileWriter.acquireStudyExportLock(claim.studyId)
                ExportFileWriter.deleteExportArtifactsForErasure(claim.exportId, null)
                if (revoked) {
                    failRevokedExport(connection, claim)
                    connection.commit()
                    ChronicleMetrics.exportJobsTotal.labels("failed").inc()
                    return null
                }

                val result = ExportFileWriter.writeMultiDataTypeExport(
                    dataByType,
                    claim.format,
                    claim.exportId,
                    claim.leaseToken,
                )
                connection.prepareStatement(COMPLETE_EXPORT_SQL).use { statement ->
                    statement.setLong(1, result.rowCount)
                    statement.setString(2, result.path.toString())
                    statement.setObject(3, claim.exportId)
                    statement.setObject(4, claim.leaseToken)
                    check(statement.executeUpdate() == 1) { "Export lease was lost during completion" }
                }
                webhookService.enqueueEvent(
                    connection,
                    claim.studyId,
                    WebhookEventType.EXPORT_COMPLETED,
                    mapOf(
                        "exportId" to claim.exportId.toString(),
                        "format" to claim.format.name,
                        "dataTypes" to request.dataTypes.map { it.name }.sorted(),
                        "rowCount" to result.rowCount,
                    ),
                )
                connection.commit()
                ChronicleMetrics.exportJobsTotal.labels("completed").inc()
                return result
            } catch (ex: Exception) {
                primaryFailure = ex
                try {
                    connection.rollback()
                } catch (rollbackFailure: Exception) {
                    ex.addSuppressed(rollbackFailure)
                }
                throw ex
            } finally {
                try {
                    exportStorageLock?.close()
                } catch (lockFailure: Exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(lockFailure)
                    } else {
                        logger.error(
                            "Failed to release export storage lock after completing export {}",
                            claim.exportId,
                            lockFailure,
                        )
                    }
                }
                try {
                    connection.autoCommit = previousAutoCommit
                } catch (restoreFailure: Exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(restoreFailure)
                    } else {
                        logger.error(
                            "Failed to restore autocommit after completing export {}",
                            claim.exportId,
                            restoreFailure,
                        )
                    }
                }
                releaseExportStudyLock(connection, claim.studyId, claim.exportId)
                if (capacityReserved) {
                    releaseExportCapacityReservation(claim)
                }
            }
        }
    }

    /**
     * Persists the worst-case byte budget under a global PostgreSQL admission
     * lock. The reservation remains visible while this process writes, so
     * independent server replicas cannot each admit the full shared volume.
     */
    internal fun reserveExportCapacity(claim: ClaimedExport): Boolean {
        val requestedBytes = ExportFileWriter.defaultCapacityReservationBytes
        return RLSRequestContext.withSystemContext {
            storageResolver.getPlatformStorage().connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    acquireExportCapacityTransactionLock(connection)
                    // The actual filesystem work runs on one daemon worker. Waiting here is
                    // bounded to one second, while the advisory lock prevents publication/
                    // release from invalidating the combined managed/free-space snapshot.
                    val sampledCapacity = freshStorageCapacityForExportAdmission()
                    val stillOwned = connection.prepareStatement(
                        """
                        SELECT export_id
                        FROM export_jobs
                        WHERE export_id = ?
                          AND study_id = ?
                          AND status = 'RUNNING'
                          AND lease_token = ?
                        FOR SHARE
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, claim.exportId)
                        statement.setObject(2, claim.studyId)
                        statement.setObject(3, claim.leaseToken)
                        statement.executeQuery().use { it.next() }
                    }
                    if (!stillOwned) {
                        connection.rollback()
                        return@withSystemContext false
                    }

                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            DELETE FROM export_capacity_reservations AS reservation
                            USING export_jobs AS job
                            WHERE job.export_id = reservation.export_id
                              AND (
                                  job.status <> 'RUNNING'
                                  OR job.lease_token IS DISTINCT FROM reservation.lease_token
                              )
                            """.trimIndent(),
                        )
                    }
                    val reservedByOthers = connection.prepareStatement(
                        """
                        SELECT COALESCE(SUM(reserved_bytes), 0)
                        FROM export_capacity_reservations
                        WHERE export_id <> ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, claim.exportId)
                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next()) { "Unable to read export capacity reservations" }
                            resultSet.getLong(1)
                        }
                    }
                    val managedBytes = sampledCapacity.managedArtifactBytesAtSample
                    val projectedManagedBytes = saturatingAdd(
                        saturatingAdd(managedBytes, reservedByOthers),
                        requestedBytes,
                    )
                    if (projectedManagedBytes > ExportFileWriter.maximumManagedArtifactBytes) {
                        ChronicleMetrics.exportStorageAdmissionRejectionsTotal.labels("aggregate_limit").inc()
                        throw ExportResourceLimitException(
                            "Export storage exceeds the managed artifact limit " +
                                "(${ExportFileWriter.maximumManagedArtifactBytes} bytes)",
                        )
                    }

                    val requiredUsableBytes = saturatingAdd(
                        saturatingAdd(reservedByOthers, requestedBytes),
                        ExportFileWriter.minimumFreeBytes,
                    )
                    if (sampledCapacity.usableBytes < requiredUsableBytes) {
                        ChronicleMetrics.exportStorageAdmissionRejectionsTotal.labels("free_space_floor").inc()
                        throw ExportResourceLimitException(
                            "Export storage does not have the required free-space reserve " +
                                "(${ExportFileWriter.minimumFreeBytes} bytes)",
                        )
                    }

                    connection.prepareStatement(
                        """
                        INSERT INTO export_capacity_reservations (
                            export_id, study_id, lease_token, reserved_bytes, updated_at
                        ) VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (export_id) DO UPDATE
                        SET study_id = EXCLUDED.study_id,
                            lease_token = EXCLUDED.lease_token,
                            reserved_bytes = EXCLUDED.reserved_bytes,
                            updated_at = now()
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, claim.exportId)
                        statement.setObject(2, claim.studyId)
                        statement.setObject(3, claim.leaseToken)
                        statement.setLong(4, requestedBytes)
                        check(statement.executeUpdate() == 1) { "Export capacity reservation was not persisted" }
                    }
                    connection.commit()
                    true
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    internal open fun freshStorageCapacityForExportAdmission(): ExportStorageCapacity =
        ExportFileWriter.freshStorageCapacityForAdmission()

    @Suppress("TooGenericExceptionCaught")
    internal fun releaseExportCapacityReservation(claim: ClaimedExport): Boolean {
        return try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        acquireExportCapacityTransactionLock(connection)
                        val released = connection.prepareStatement(
                            """
                            DELETE FROM export_capacity_reservations
                            WHERE export_id = ? AND lease_token = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, claim.exportId)
                            statement.setObject(2, claim.leaseToken)
                            statement.executeUpdate() == 1
                        }
                        connection.commit()
                        released
                    } catch (exception: Exception) {
                        connection.rollback()
                        throw exception
                    } finally {
                        connection.autoCommit = previousAutoCommit
                    }
                }
            }
        } catch (exception: Exception) {
            logger.error(
                "Failed to release capacity reservation for export {}; recovery will reconcile it",
                claim.exportId,
                exception,
            )
            false
        }
    }

    private fun acquireExportCapacityTransactionLock(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-export-capacity', 0))",
            ).use { resultSet ->
                check(resultSet.next()) { "Export capacity lock was not acquired" }
            }
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun failRevokedExport(connection: Connection, claim: ClaimedExport) {
        connection.prepareStatement(
            """
            UPDATE ${EXPORT_JOBS.name}
            SET status = 'FAILED',
                completed_at = now(),
                error_message = 'Export revoked by an active data-erasure request',
                file_path = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE export_id = ? AND lease_token = ? AND status = 'RUNNING'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, claim.exportId)
            statement.setObject(2, claim.leaseToken)
            check(statement.executeUpdate() == 1) { "Export lease was lost while applying revocation" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persistExportFailure(
        claim: ClaimedExport,
        format: ExportFormat,
        failure: Exception,
    ) {
        try {
            RLSRequestContext.withExportWorkerContext(claim.createdBy, claim.studyId) {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    var exportStorageLock: AutoCloseable? = null
                    acquireExportStudyLock(connection, claim.studyId)
                    try {
                        connection.autoCommit = false
                        val stillOwned = connection.prepareStatement(LOCK_EXPORT_FOR_FAILURE_SQL).use { statement ->
                            statement.setObject(1, claim.exportId)
                            statement.setObject(2, claim.studyId)
                            statement.setObject(3, claim.leaseToken)
                            statement.executeQuery().use { it.next() }
                        }
                        exportStorageLock = ExportFileWriter.acquireStudyExportLock(claim.studyId)
                        if (!stillOwned) {
                            connection.rollback()
                            ExportFileWriter.deleteExportAttemptArtifacts(
                                claim.exportId,
                                format,
                                claim.leaseToken,
                                includeCanonical = false,
                            )
                            logger.warn(
                                "Ignored stale failure for export {} lease {}",
                                claim.exportId,
                                claim.leaseToken,
                            )
                            return@withExportWorkerContext
                        }

                        ExportFileWriter.deleteExportAttemptArtifacts(
                            claim.exportId,
                            format,
                            claim.leaseToken,
                            includeCanonical = true,
                        )
                        val interrupted = failure is InterruptedException || Thread.currentThread().isInterrupted
                        val terminal = failure is IllegalArgumentException ||
                            failure is ExportResourceLimitException
                        val nextAttemptCount = if (interrupted) {
                            claim.attemptCount
                        } else {
                            claim.attemptCount + 1
                        }
                        val exhausted = nextAttemptCount >= MAX_EXPORT_ATTEMPTS
                        val status = if (terminal || exhausted) ExportJobStatus.FAILED else ExportJobStatus.PENDING
                        val delaySeconds = (1L shl claim.attemptCount.coerceAtMost(5)).coerceAtMost(30L)
                        val errorMessage = when {
                            failure is ExportResourceLimitException ->
                                withFailureCause("Export resource limit exceeded", failure)
                            failure is IllegalArgumentException ->
                                withFailureCause("Export request is invalid", failure)
                            interrupted -> "Export worker stopped; retry pending"
                            exhausted -> withFailureCause(
                                "Export failed after $MAX_EXPORT_ATTEMPTS attempts",
                                failure,
                            )
                            else -> withFailureCause("Export generation failed; retry scheduled", failure)
                        }
                        connection.prepareStatement(
                            """
                            UPDATE ${EXPORT_JOBS.name}
                            SET status = ?,
                                attempt_count = ?,
                                available_at = CASE
                                    WHEN ? = 'PENDING' THEN now() + (? * interval '1 second')
                                    ELSE available_at
                                END,
                                completed_at = CASE
                                    WHEN ? = 'FAILED' THEN now()
                                    ELSE 'infinity'::timestamptz
                                END,
                                error_message = ?,
                                file_path = NULL,
                                lease_token = NULL,
                                lease_expires_at = NULL,
                                updated_at = now()
                            WHERE export_id = ? AND lease_token = ? AND status = 'RUNNING'
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status.name)
                            statement.setInt(2, nextAttemptCount.coerceAtMost(MAX_EXPORT_ATTEMPTS))
                            statement.setString(3, status.name)
                            statement.setLong(4, delaySeconds)
                            statement.setString(5, status.name)
                            statement.setString(6, errorMessage)
                            statement.setObject(7, claim.exportId)
                            statement.setObject(8, claim.leaseToken)
                            check(statement.executeUpdate() == 1) {
                                "Export lease was lost while persisting failure"
                            }
                        }
                        connection.commit()
                        ChronicleMetrics.exportJobsTotal.labels(
                            if (status == ExportJobStatus.FAILED) "failed" else "retry",
                        ).inc()
                    } catch (ex: Exception) {
                        connection.rollback()
                        throw ex
                    } finally {
                        try {
                            exportStorageLock?.close()
                        } catch (lockFailure: Exception) {
                            failure.addSuppressed(lockFailure)
                            logger.error(
                                "Failed to release export storage lock after persisting failure for {}",
                                claim.exportId,
                                lockFailure,
                            )
                        }
                        connection.autoCommit = previousAutoCommit
                        releaseExportStudyLock(connection, claim.studyId, claim.exportId)
                    }
                }
            }
        } catch (persistenceFailure: Exception) {
            failure.addSuppressed(persistenceFailure)
            logger.error(
                "Failed to persist retry state for export {}; lease expiry will recover it",
                claim.exportId,
                failure,
            )
        }
    }

    private fun acquireExportStudyLock(connection: Connection, studyId: UUID) {
        connection.prepareStatement(
            """
            SELECT pg_advisory_lock_shared(
                hashtextextended('chronicle-deletion:' || ?::text, 0)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Export study lock was not acquired" }
            }
        }
    }

    private fun acquireExportStudyTransactionLock(connection: Connection, studyId: UUID) {
        connection.prepareStatement(
            """
            SELECT pg_advisory_xact_lock_shared(
                hashtextextended('chronicle-deletion:' || ?::text, 0)
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Export download study lock was not acquired" }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun releaseExportStudyLock(connection: Connection, studyId: UUID, exportId: UUID) {
        try {
            connection.prepareStatement(
                """
                SELECT pg_advisory_unlock_shared(
                    hashtextextended('chronicle-deletion:' || ?::text, 0)
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next() || !resultSet.getBoolean(1)) {
                        logger.error("Export {} did not hold the expected study lock", exportId)
                    }
                }
            }
        } catch (ex: Exception) {
            // Closing this connection releases every session-level advisory lock.
            logger.error("Failed to release study lock for export {}; connection close will release it", exportId, ex)
        }
    }

    /**
     * Expired leases have their own bounded crash budget. Once exhausted, the
     * artifact and any cross-process capacity reservation are removed before
     * the durable row becomes terminal.
     */
    internal fun terminalizeOneExhaustedRecovery(): Boolean {
        var recoveryCandidate: Pair<UUID, UUID>? = null
        return try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    var exportStorageLock: AutoCloseable? = null
                    connection.autoCommit = false
                    try {
                        val candidate = connection.prepareStatement(
                        """
                        SELECT export_id, study_id
                        FROM export_jobs
                        WHERE status = 'RUNNING'
                          AND lease_expires_at <= now()
                          AND recovery_count >= ?
                          AND recovery_cleanup_available_at <= now()
                        ORDER BY lease_expires_at, created_at
                        LIMIT 1
                        """.trimIndent(),
                        ).use { statement ->
                            statement.setInt(1, MAX_EXPORT_RECOVERIES)
                            statement.executeQuery().use { resultSet ->
                                if (!resultSet.next()) {
                                    null
                                } else {
                                    resultSet.getObject("export_id", UUID::class.java) to
                                        resultSet.getObject("study_id", UUID::class.java)
                                }
                            }
                        }
                        if (candidate == null) {
                            connection.rollback()
                            return@withSystemContext false
                        }
                        recoveryCandidate = candidate

                        acquireExportStudyTransactionLock(connection, candidate.second)
                        acquireExportCapacityTransactionLock(connection)
                        val lockedFilePath = connection.prepareStatement(
                        """
                        SELECT file_path
                        FROM export_jobs
                        WHERE export_id = ?
                          AND study_id = ?
                          AND status = 'RUNNING'
                          AND lease_expires_at <= now()
                          AND recovery_count >= ?
                          AND recovery_cleanup_available_at <= now()
                        FOR UPDATE SKIP LOCKED
                        """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, candidate.first)
                            statement.setObject(2, candidate.second)
                            statement.setInt(3, MAX_EXPORT_RECOVERIES)
                            statement.executeQuery().use { resultSet ->
                                if (!resultSet.next()) null else Optional.ofNullable(resultSet.getString("file_path"))
                            }
                        }
                        if (lockedFilePath == null) {
                            connection.rollback()
                            return@withSystemContext false
                        }

                        exportStorageLock = ExportFileWriter.acquireStudyExportLock(candidate.second)
                        ExportFileWriter.deleteExportArtifactsForErasure(
                            candidate.first,
                            lockedFilePath.orElse(null),
                        )
                        connection.prepareStatement(
                            "DELETE FROM export_capacity_reservations WHERE export_id = ?",
                        ).use { statement ->
                            statement.setObject(1, candidate.first)
                            statement.executeUpdate()
                        }
                        connection.prepareStatement(
                        """
                        UPDATE export_jobs
                        SET status = 'FAILED',
                            completed_at = now(),
                            download_token = NULL,
                            file_path = NULL,
                            error_message = 'Export worker recovery budget exhausted',
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE export_id = ? AND status = 'RUNNING'
                        """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, candidate.first)
                            check(statement.executeUpdate() == 1) {
                                "Exhausted export recovery changed while locked"
                            }
                        }
                        connection.commit()
                        true
                    } catch (exception: Exception) {
                        connection.rollback()
                        throw exception
                    } finally {
                        try {
                            exportStorageLock?.close()
                        } catch (lockFailure: Exception) {
                            logger.error(
                                "Failed to release storage lock after exhausted export recovery",
                                lockFailure,
                            )
                        }
                        connection.autoCommit = previousAutoCommit
                    }
                }
            }
        } catch (exception: Exception) {
            recoveryCandidate?.let { candidate ->
                deferExhaustedRecoveryCleanup(candidate, exception)
            }
            throw exception
        }
    }

    private fun deferExhaustedRecoveryCleanup(
        candidate: Pair<UUID, UUID>,
        cleanupFailure: Exception,
    ) {
        try {
            RLSRequestContext.withSystemContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE export_jobs
                        SET recovery_cleanup_available_at =
                                now() + (? * interval '1 second'),
                            error_message = ?,
                            updated_at = now()
                        WHERE export_id = ?
                          AND study_id = ?
                          AND status = 'RUNNING'
                          AND recovery_count >= ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, EXHAUSTED_RECOVERY_CLEANUP_BACKOFF_SECONDS)
                        statement.setString(
                            2,
                            "Export recovery cleanup deferred after " +
                                (cleanupFailure::class.simpleName ?: "cleanup failure"),
                        )
                        statement.setObject(3, candidate.first)
                        statement.setObject(4, candidate.second)
                        statement.setInt(5, MAX_EXPORT_RECOVERIES)
                        statement.executeUpdate()
                    }
                }
            }
        } catch (deferFailure: Exception) {
            cleanupFailure.addSuppressed(deferFailure)
            logger.error(
                "Failed to defer cleanup retry for exhausted export {}",
                candidate.first,
                deferFailure,
            )
        }
    }

    @PreDestroy
    public fun shutdown() {
        leaseExecutor.shutdownNow()
        exportExecutor.shutdown()
        try {
            if (!exportExecutor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                exportExecutor.shutdownNow()
                if (!exportExecutor.awaitTermination(EXECUTOR_FORCE_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    logger.error("Export executor did not terminate after forced shutdown")
                }
            }
        } catch (_: InterruptedException) {
            exportExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            logger.warn("Export executor shutdown was interrupted")
        }
    }

    private inner class ExportTask : Runnable {
        @Suppress("TooGenericExceptionCaught")
        override fun run() {
            try {
                terminalizeOneExhaustedRecovery()
            } catch (exception: Exception) {
                logger.error("Failed to terminalize an exhausted export recovery", exception)
            }
            val claim = claimNextExport() ?: return
            val request = try {
                mapper.readValue(claim.requestJson, ExportRequest::class.java)
            } catch (ex: Exception) {
                persistExportFailure(
                    claim,
                    claim.format,
                    IllegalArgumentException("Stored export request is invalid", ex),
                )
                return
            }
            executeClaimedExport(claim, request)
        }
    }

    internal fun runNextExportTask() {
        ExportTask().run()
    }

    public fun cleanupExpiredExportFiles() {
        var cleaned = 0
        RLSRequestContext.withSystemContext {
            repeat(100) {
                val removed = cleanupOneExpiredExport()
                if (!removed) return@withSystemContext
                cleaned += 1
            }
        }
        if (cleaned > 0) {
            logger.info("Cleaned {} terminal export artifacts", cleaned)
        }
    }

    private fun cleanupOneExpiredExport(): Boolean {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            var exportStorageLock: AutoCloseable? = null
            connection.autoCommit = false
            try {
                val candidate = connection.prepareStatement(
                    """
                    SELECT export_id, study_id
                    FROM ${EXPORT_JOBS.name}
                    WHERE file_path IS NOT NULL
                      AND (
                          (status = 'COMPLETED' AND completed_at < now() - interval '24 hours')
                          OR status = 'FAILED'
                      )
                    ORDER BY completed_at
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            null
                        } else {
                            resultSet.getObject("export_id", UUID::class.java) to
                                resultSet.getObject("study_id", UUID::class.java)
                        }
                    }
                }
                if (candidate == null) {
                    connection.rollback()
                    return false
                }
                acquireExportStudyTransactionLock(connection, candidate.second)
                val expired = connection.prepareStatement(
                    """
                    SELECT export_id, study_id, file_path, status
                    FROM ${EXPORT_JOBS.name}
                    WHERE export_id = ?
                      AND study_id = ?
                      AND file_path IS NOT NULL
                      AND (
                          (status = 'COMPLETED' AND completed_at < now() - interval '24 hours')
                          OR status = 'FAILED'
                      )
                    FOR UPDATE SKIP LOCKED
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, candidate.first)
                    statement.setObject(2, candidate.second)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            null
                        } else {
                            ExportCleanupCandidate(
                                exportId = resultSet.getObject("export_id", UUID::class.java),
                                studyId = resultSet.getObject("study_id", UUID::class.java),
                                filePath = resultSet.getString("file_path"),
                                status = resultSet.getString("status"),
                            )
                        }
                    }
                }
                if (expired == null) {
                    connection.rollback()
                    return false
                }
                exportStorageLock = ExportFileWriter.acquireStudyExportLock(expired.studyId)
                ExportFileWriter.deleteExportArtifactsForErasure(expired.exportId, expired.filePath)
                connection.prepareStatement(
                    "DELETE FROM export_capacity_reservations WHERE export_id = ?",
                ).use { statement ->
                    statement.setObject(1, expired.exportId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    UPDATE ${EXPORT_JOBS.name}
                    SET status = 'FAILED',
                        file_path = NULL,
                        download_token = NULL,
                        error_message = CASE
                            WHEN ? = 'COMPLETED' THEN 'Export expired; create a new export'
                            ELSE error_message
                        END,
                        updated_at = now()
                    WHERE export_id = ? AND status = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, expired.status)
                    statement.setObject(2, expired.exportId)
                    statement.setString(3, expired.status)
                    check(statement.executeUpdate() == 1) { "Expired export changed while locked" }
                }
                connection.commit()
                return true
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                try {
                    exportStorageLock?.close()
                } catch (lockFailure: Exception) {
                    logger.error("Failed to release storage lock after export cleanup", lockFailure)
                }
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun mapExportJobInfo(rs: ResultSet): ExportJobInfo {
        val completedAt = rs.getObject(COMPLETED_AT.name, OffsetDateTime::class.java)
            ?.takeUnless { it.year >= POSTGRES_INFINITY_YEAR }
        return ExportJobInfo(
            exportId = rs.getObject(EXPORT_ID.name, UUID::class.java),
            studyId = rs.getObject(STUDY_ID.name, UUID::class.java),
            status = ExportJobStatus.valueOf(rs.getString(STATUS.name)),
            format = ExportFormat.valueOf(rs.getString(FORMAT.name)),
            createdAt = rs.getObject(CREATED_AT.name, OffsetDateTime::class.java),
            completedAt = completedAt,
            downloadToken = null,
            rowCount = rs.getLong(ROW_COUNT.name),
            errorMessage = rs.getString(ERROR_MESSAGE.name),
            filePath = rs.getString(FILE_PATH.name)
        )
    }
}
