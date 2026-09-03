package com.openlattice.chronicle.services.jobs

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.JOBS
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CONTACT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DELETED_ROWS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.JOB_DEFINITION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.JOB_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SECURABLE_PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STATUS
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
@Service
public open class JobService(
    // reason: DI-injected dependency wired via ChronicleServerServicesPod.jobService(); kept for constructor parity
    @Suppress("UnusedPrivateProperty")
    private val idGenerationService: HazelcastIdGenerationService,
    private val storageResolver: StorageResolver,
    override val auditingManager: AuditingManager,
) : JobManager {
    public companion object {
        private val logger = LoggerFactory.getLogger(JobService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()
        private val JOB_COLUMNS_LIST = listOf(
            JOB_ID,
            SECURABLE_PRINCIPAL_ID,
            PRINCIPAL_TYPE,
            PRINCIPAL_ID,
            STATUS,
            CONTACT,
            JOB_DEFINITION,
            MESSAGE,
            DELETED_ROWS,
        )

        private val JOB_COLUMNS = JOB_COLUMNS_LIST.joinToString(",") { it.name }
        private val JOB_COLUMNS_BIND = JOB_COLUMNS_LIST.joinToString(",") {
            if (it.datatype == PostgresDatatype.JSONB) "?::jsonb" else "?"
        }
        private val INSERT_JOB_SQL = """
            INSERT INTO ${JOBS.name} ($JOB_COLUMNS) VALUES ($JOB_COLUMNS_BIND)
        """.trimIndent()
        private val INSERT_JOB_IDEMPOTENT_SQL = "$INSERT_JOB_SQL ON CONFLICT (${JOB_ID.name}) DO NOTHING"
        private val GET_JOBS_SQL = """
            SELECT * FROM ${JOBS.name} WHERE ${JOB_ID.name} = ANY(?)
        """.trimIndent()

        private val GET_NEXT_JOB_SQL = """
            UPDATE ${JOBS.name}
            SET ${STATUS.name} = '${JobStatus.RUNNING.name}',
                ${PostgresColumns.UPDATED_AT.name} = now(),
                ${PostgresColumns.COMPLETED_AT.name} = 'infinity',
                lease_token = ?,
                lease_expires_at = now() + interval '5 minutes'
            WHERE ${JOB_ID.name} = (
                SELECT ${JOB_ID.name}
                FROM ${JOBS.name}
                WHERE ${STATUS.name} = '${JobStatus.PENDING.name}'
                ORDER BY ${PostgresColumns.CREATED_AT.name} ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING *
        """.trimIndent()

        private val LOCK_OWNED_JOB_SQL = """
            SELECT ${JOB_ID.name}
            FROM ${JOBS.name}
            WHERE ${JOB_ID.name} = ?
              AND ${STATUS.name} = '${JobStatus.RUNNING.name}'
              AND lease_token = ?
              AND lease_expires_at > now()
            FOR UPDATE
        """.trimIndent()

        private val GET_OWNED_JOB_STUDY_SQL = """
            SELECT study_id
            FROM ${JOBS.name}
            WHERE ${JOB_ID.name} = ?
              AND ${STATUS.name} = '${JobStatus.RUNNING.name}'
              AND lease_token = ?
              AND lease_expires_at > now()
        """.trimIndent()

        private val COMPLETE_JOB_SQL = """
            UPDATE ${JOBS.name}
            SET ${STATUS.name} = ?,
                ${PostgresColumns.UPDATED_AT.name} = ?,
                ${PostgresColumns.COMPLETED_AT.name} = ?,
                ${DELETED_ROWS.name} = ?,
                lease_token = NULL,
                lease_expires_at = NULL
            WHERE ${JOB_ID.name} = ?
              AND ${STATUS.name} = '${JobStatus.RUNNING.name}'
              AND lease_token = ?
        """.trimIndent()

        private val CANCEL_JOB_SQL = """
            UPDATE ${JOBS.name}
            SET ${STATUS.name} = '${JobStatus.CANCELED.name}',
                ${PostgresColumns.UPDATED_AT.name} = ?,
                ${PostgresColumns.COMPLETED_AT.name} = ?,
                ${MESSAGE.name} = ?,
                lease_token = NULL,
                lease_expires_at = NULL
            WHERE ${JOB_ID.name} = ?
              AND ${STATUS.name} = '${JobStatus.RUNNING.name}'
              AND lease_token = ?
        """.trimIndent()

        private val MARK_UNCERTAIN_JOB_SQL = """
            UPDATE ${JOBS.name}
            SET ${STATUS.name} = '${JobStatus.STOPPING.name}',
                ${PostgresColumns.UPDATED_AT.name} = ?,
                ${PostgresColumns.COMPLETED_AT.name} = 'infinity',
                ${MESSAGE.name} = ?,
                lease_token = NULL,
                lease_expires_at = NULL
            WHERE ${JOB_ID.name} = ?
              AND ${STATUS.name} = '${JobStatus.RUNNING.name}'
              AND lease_token = ?
        """.trimIndent()

        private val EXPIRE_ABANDONED_JOBS_SQL = """
            WITH expired_jobs AS (
                SELECT ${JOB_ID.name}
                FROM ${JOBS.name}
                WHERE ${STATUS.name} = '${JobStatus.RUNNING.name}'
                  AND lease_expires_at <= now()
                FOR UPDATE SKIP LOCKED
            )
            UPDATE ${JOBS.name} AS job
            SET ${STATUS.name} = '${JobStatus.STOPPING.name}',
                ${PostgresColumns.UPDATED_AT.name} = now(),
                ${PostgresColumns.COMPLETED_AT.name} = 'infinity',
                ${MESSAGE.name} = CASE
                    WHEN COALESCE(job.${MESSAGE.name}, '') = ''
                        THEN 'Worker lease expired; execution outcome requires reconciliation'
                    ELSE left(
                        job.${MESSAGE.name}
                            || '; worker lease expired; execution outcome requires reconciliation',
                        4096
                    )
                END,
                lease_token = NULL,
                lease_expires_at = NULL
            FROM expired_jobs
            WHERE job.${JOB_ID.name} = expired_jobs.${JOB_ID.name}
        """.trimIndent()

        private const val MAX_AVAILABLE = 4
        private const val FINISHED_JOB_TTL = "'7d'"
        private const val MAX_FAILURE_MESSAGE_LENGTH = 4_096

        private val DELETE_TERMINAL_JOBS_AFTER_TTL = """
            DELETE FROM ${JOBS.name} 
            WHERE ${STATUS.name} IN ('${JobStatus.FINISHED.name}', '${JobStatus.CANCELED.name}')
            AND ${PostgresColumns.COMPLETED_AT.name} <= now() - INTERVAL $FINISHED_JOB_TTL
        """.trimIndent()

        private val DELETE_TERMINAL_PIPELINE_RUNS_AFTER_TTL = """
            DELETE FROM pipeline_runs AS run
            USING ${JOBS.name} AS job
            WHERE run.job_id = job.${JOB_ID.name}
              AND job.${STATUS.name} IN ('${JobStatus.FINISHED.name}', '${JobStatus.CANCELED.name}')
              AND job.${PostgresColumns.COMPLETED_AT.name} <= now() - INTERVAL $FINISHED_JOB_TTL
        """.trimIndent()
    }

    private val leaseTokens = ConcurrentHashMap<UUID, UUID>()
    private val available = Semaphore(MAX_AVAILABLE)
    private val threadCounter = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(MAX_AVAILABLE) { runnable ->
        Thread(runnable, "chronicle-job-worker-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val runner = java.util.concurrent.ConcurrentHashMap<Class<*>, ChronicleJobRunner<*>>()

    override fun createJob(connection: Connection, job: ChronicleJob): UUID {
        return createJobs(connection, listOf(job)).first()
    }

    override fun createJobs(connection: Connection, jobs: Iterable<ChronicleJob>): Iterable<UUID> {
        return insertJobs(connection, jobs, INSERT_JOB_SQL)
    }

    /** Inserts deterministic jobs once while returning their stable IDs on every retry. */
    public fun createJobsIdempotently(connection: Connection, jobs: Iterable<ChronicleJob>): Iterable<UUID> {
        return insertJobs(connection, jobs, INSERT_JOB_IDEMPOTENT_SQL)
    }

    private fun insertJobs(
        connection: Connection,
        jobs: Iterable<ChronicleJob>,
        sql: String,
    ): Iterable<UUID> {
        val jobIds = mutableListOf<UUID>()
        connection.prepareStatement(sql).use { ps ->
            jobs.forEach { job ->
                jobIds.add(job.id)
                var index = 1
                ps.setObject(index++, job.id)
                ps.setObject(index++, job.securablePrincipalId)
                ps.setString(index++, job.principal.type.name)
                ps.setString(index++, job.principal.id)
                ps.setString(index++, job.status.toString())
                ps.setString(index++, job.contact)
                ps.setString(index++, mapper.writeValueAsString(job.definition))
                ps.setString(index++, job.message)
                ps.setLong(index, job.deletedRows)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        return jobIds
    }

    override fun lockAndGetNextJob(connection: Connection): ChronicleJob? {
        val leaseToken = UUID.randomUUID()
        return connection.prepareStatement(GET_NEXT_JOB_SQL).use { ps ->
            ps.setObject(1, leaseToken)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val job = ResultSetAdapters.chronicleJob(rs)
                    leaseTokens[job.id] = leaseToken
                    job
                } else null
            }
        }
    }

    override fun unlockJob(jobId: UUID) {
        leaseTokens.remove(jobId)
    }

    override fun getJob(jobId: UUID): ChronicleJob {
        return getJobs(listOf(jobId)).values.first()
    }

    override fun getJobs(jobIds: Collection<UUID>): Map<UUID, ChronicleJob> {
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(storageResolver.getPlatformStorage(), GET_JOBS_SQL) { ps ->
                val pgJobIds = PostgresArrays.createUuidArray(ps.connection, jobIds)
                ps.setObject(1, pgJobIds)
                ps.executeQuery()
            }
        ) {
            val job = ResultSetAdapters.chronicleJob(it)
            job.id to job
        }.toMap()
    }

    // reason: boundary catch — a background executor task must not let any job failure escape its thread
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedRate = 10_000L)
    public fun tryAndAcquireTaskForExecutor() {
        logger.info("Attempting to acquire permit for executing background task.")
        expireAbandonedJobs()

        repeat(MAX_AVAILABLE) {
            if (!available.tryAcquire()) {
                logger.info("No permits available. Skipping chronicle job scheduling.")
                return
            }

            try {
                executor.execute {
                    try {
                        executeNextJob()
                    } finally {
                        available.release()
                    }
                }
            } catch (ex: RejectedExecutionException) {
                available.release()
                logger.info("Chronicle job executor is shutting down; persisted pending jobs remain queued.", ex)
                return
            }
        }
    }

    // reason: boundary catch — a failed handler must be converted into a durable terminal job state
    @Suppress("TooGenericExceptionCaught")
    private fun executeNextJob() {
        var job: ChronicleJob? = null
        var executionDispatched = false
        try {
            val claimedJob = storageResolver.getPlatformStorage().connection.use(::lockAndGetNextJob) ?: return
            job = claimedJob
            val leaseToken = checkNotNull(leaseTokens[claimedJob.id]) {
                "No lease token was recorded for claimed job ${claimedJob.id}"
            }
            val jobRunner = checkNotNull(runner[claimedJob.definition.javaClass]) {
                "No job handler is registered for ${claimedJob.definition.javaClass.name}"
            }
            logger.info("Found a job with type = {}", claimedJob.definition.javaClass.name)

            storageResolver.getPlatformStorage().connection.use { connection ->
                AuditedTransactionBuilder<Pair<UUID, List<AuditableEvent>>>(
                    connection,
                    auditingManager,
                )
                    .transaction { auditedConnection ->
                        lockOwnedJob(auditedConnection, claimedJob.id, leaseToken)
                        executionDispatched = true
                        val auditEvents = jobRunner.run(auditedConnection, claimedJob)
                        persistTerminalJob(auditedConnection, claimedJob, leaseToken)
                        claimedJob.id to auditEvents
                    }
                    .audit { it.second }
                    .buildAndRun()
            }
        } catch (ex: Exception) {
            job?.let { failedJob ->
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                failedJob.updatedAt = now
                try {
                    storageResolver.getPlatformStorage().connection.use { connection ->
                        val leaseToken = checkNotNull(leaseTokens[failedJob.id]) {
                            "No lease token was recorded for failed job ${failedJob.id}"
                        }
                        if (executionDispatched) {
                            failedJob.status = JobStatus.STOPPING
                            failedJob.completedAt = OffsetDateTime.MAX
                            persistUncertainJob(connection, failedJob.id, leaseToken, failureMessage(ex), now)
                        } else {
                            failedJob.status = JobStatus.CANCELED
                            failedJob.completedAt = now
                            persistCanceledJob(connection, failedJob.id, leaseToken, failureMessage(ex), now)
                        }
                    }
                } catch (persistenceFailure: Exception) {
                    ex.addSuppressed(persistenceFailure)
                }
            }
            logger.error("Task could not be completed", ex)
        } finally {
            job?.let { unlockJob(it.id) }
        }
    }

    private fun lockOwnedJob(connection: Connection, jobId: UUID, leaseToken: UUID) {
        val studyId = connection.prepareStatement(GET_OWNED_JOB_STUDY_SQL).use { ps ->
            ps.setObject(1, jobId)
            ps.setObject(2, leaseToken)
            ps.executeQuery().use { rs ->
                check(rs.next()) {
                    "Job $jobId was not owned by an unexpired current lease when execution began"
                }
                val resolvedStudyId = rs.getObject("study_id", UUID::class.java)
                check(!rs.next()) { "Job $jobId resolved to more than one lease row" }
                resolvedStudyId
            }
        }
        if (studyId != null) {
            connection.prepareStatement(
                """
                SELECT pg_advisory_xact_lock_shared(
                    hashtextextended('chronicle-deletion:' || ?::text, 0)
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, studyId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "Unable to acquire the study execution fence for job $jobId" }
                }
            }
        }
        connection.prepareStatement(LOCK_OWNED_JOB_SQL).use { ps ->
            ps.setObject(1, jobId)
            ps.setObject(2, leaseToken)
            ps.executeQuery().use { rs ->
                check(rs.next()) {
                    "Job $jobId was not owned by an unexpired current lease when execution began"
                }
                check(!rs.next()) { "Job $jobId resolved to more than one lease row" }
            }
        }
    }

    private fun persistTerminalJob(connection: Connection, job: ChronicleJob, leaseToken: UUID) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val terminalStatus = if (job.status == JobStatus.CANCELED) JobStatus.CANCELED else JobStatus.FINISHED
        job.status = terminalStatus
        job.updatedAt = now
        job.completedAt = now

        connection.prepareStatement(COMPLETE_JOB_SQL).use { ps ->
            ps.setString(1, terminalStatus.name)
            ps.setObject(2, now)
            ps.setObject(3, now)
            ps.setLong(4, job.deletedRows)
            ps.setObject(5, job.id)
            ps.setObject(6, leaseToken)
            check(ps.executeUpdate() == 1) {
                "Job ${job.id} was not owned by the current lease when execution completed"
            }
        }
    }

    private fun persistCanceledJob(
        connection: Connection,
        jobId: UUID,
        leaseToken: UUID,
        message: String,
        completedAt: OffsetDateTime,
    ) {
        connection.prepareStatement(CANCEL_JOB_SQL).use { ps ->
            ps.setObject(1, completedAt)
            ps.setObject(2, completedAt)
            ps.setString(3, message)
            ps.setObject(4, jobId)
            ps.setObject(5, leaseToken)
            check(ps.executeUpdate() == 1) {
                "Job $jobId was not owned by the current lease when failure was recorded"
            }
        }
    }

    private fun persistUncertainJob(
        connection: Connection,
        jobId: UUID,
        leaseToken: UUID,
        failure: String,
        updatedAt: OffsetDateTime,
    ) {
        val message = "Execution outcome requires reconciliation: $failure".take(MAX_FAILURE_MESSAGE_LENGTH)
        val updated = connection.prepareStatement(MARK_UNCERTAIN_JOB_SQL).use { ps ->
            ps.setObject(1, updatedAt)
            ps.setString(2, message)
            ps.setObject(3, jobId)
            ps.setObject(4, leaseToken)
            ps.executeUpdate()
        }
        if (updated != 1) {
            connection.prepareStatement(
                "SELECT ${STATUS.name} FROM ${JOBS.name} WHERE ${JOB_ID.name} = ?",
            ).use { ps ->
                ps.setObject(1, jobId)
                ps.executeQuery().use { rs ->
                    check(
                        rs.next()
                            && rs.getString(STATUS.name) in setOf(
                                JobStatus.STOPPING.name,
                                JobStatus.FINISHED.name,
                                JobStatus.CANCELED.name,
                            )
                    ) {
                        "Job $jobId was not owned by the current lease when reconciliation was requested"
                    }
                }
            }
        }
    }

    // reason: boundary catch — lease reconciliation is best effort and must not make enqueue callers fail
    @Suppress("TooGenericExceptionCaught")
    private fun expireAbandonedJobs() {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                val expired = connection.prepareStatement(EXPIRE_ABANDONED_JOBS_SQL).executeUpdate()
                if (expired > 0) {
                    logger.warn(
                        "Marked {} Chronicle jobs for reconciliation because their worker leases expired.",
                        expired,
                    )
                }
            }
        } catch (ex: Exception) {
            logger.error("Unable to reconcile expired Chronicle job leases.", ex)
        }
    }

    private fun failureMessage(ex: Exception): String {
        val detail = ex.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            ex.javaClass.simpleName
        } else {
            "${ex.javaClass.simpleName}: $detail"
        }.take(MAX_FAILURE_MESSAGE_LENGTH)
    }

    @Scheduled(fixedRate = 60 * 60 * 1000L)
    public fun clearFinishedJobs() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                val pipelineRunDeleteCount = connection.prepareStatement(
                    DELETE_TERMINAL_PIPELINE_RUNS_AFTER_TTL,
                ).executeUpdate()
                val jobDeleteCount = connection.prepareStatement(DELETE_TERMINAL_JOBS_AFTER_TTL).executeUpdate()
                connection.commit()
                logger.info(
                    "Expired {} jobs and {} retained pipeline runs.",
                    jobDeleteCount,
                    pipelineRunDeleteCount,
                )
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    @Autowired(required = false)
    public fun registerJobHandlers(jobRunners: Set<ChronicleJobRunner<*>>) {
        jobRunners.forEach { runner -> this.runner[runner.accepts()] = runner }
    }

    @PreDestroy
    public fun shutdown() {
        executor.shutdown()
        try {
            val workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS)
            if (!workersStopped) {
                executor.shutdownNow()
            }
        } catch (ex: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
