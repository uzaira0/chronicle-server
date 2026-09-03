package com.openlattice.chronicle.pipeline

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PIPELINE_RUNS
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettingType
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class PipelineService(
    private val storageResolver: StorageResolver,
    private val jobService: JobService,
    private val idGenerationService: HazelcastIdGenerationService,
    private val studyService: StudyService,
    override val auditingManager: AuditingManager,
) : AuditingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(PipelineService::class.java)
        private const val POSTGRES_INFINITY_YEAR = 9999

        private val INSERT_PIPELINE_RUN_SQL = """
            INSERT INTO ${PIPELINE_RUNS.name}
                (run_id, study_id, job_id, status, steps_completed, total_steps, input_rows, output_rows, started_at, completed_at)
            VALUES (?, ?, ?, ?, 0, ?, 0, 0, ?, 'infinity')
        """.trimIndent()

        private val GET_PIPELINE_RUNS_SQL = """
            SELECT run_id, study_id, job_id, status, steps_completed, total_steps, input_rows, output_rows, started_at, completed_at, error_message
            FROM ${PIPELINE_RUNS.name}
            WHERE study_id = ?
            ORDER BY started_at DESC
        """.trimIndent()

        private val GET_PIPELINE_RUN_SQL = """
            SELECT run_id, study_id, job_id, status, steps_completed, total_steps, input_rows, output_rows, started_at, completed_at, error_message
            FROM ${PIPELINE_RUNS.name}
            WHERE study_id = ? AND run_id = ?
        """.trimIndent()
    }

    public fun triggerPipeline(studyId: UUID): PipelineRunInfo {
        val config = getPipelineConfig(studyId)
        check(config.enabled) { "Pipeline is not enabled for study $studyId" }
        PipelineJobRunner.validateConfig(config)

        val runId = idGenerationService.getNextId()
        val jobId = idGenerationService.getNextId()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val totalSteps = config.steps.size

        val definition = PipelineJobDefinition(studyId = studyId, config = config)
        val job = ChronicleJob(
            id = jobId,
            definition = definition,
        )

        storageResolver.getPlatformStorage().connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                jobService.createJobs(connection, listOf(job))
                connection.prepareStatement(INSERT_PIPELINE_RUN_SQL).use { ps ->
                    ps.setObject(1, runId)
                    ps.setObject(2, studyId)
                    ps.setObject(3, jobId)
                    ps.setString(4, PipelineRunStatus.PENDING.name)
                    ps.setInt(5, totalSteps)
                    ps.setObject(6, now)
                    check(ps.executeUpdate() == 1) { "Pipeline run $runId was not inserted" }
                }
                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
        jobService.tryAndAcquireTaskForExecutor()

        logger.info("Pipeline triggered for study {} with run {} and job {}", studyId, runId, jobId)

        recordEvent(
            AuditableEvent(
                AclKey(studyId),
                Principals.getCurrentSecurablePrincipal().id,
                Principals.getCurrentUser(),
                eventType = AuditEventType.TRIGGER_PIPELINE,
                data = mapOf("runId" to runId, "config" to config),
                study = studyId
            )
        )

        return PipelineRunInfo(
            runId = runId,
            studyId = studyId,
            status = PipelineRunStatus.PENDING,
            totalSteps = totalSteps,
            startedAt = now,
        )
    }

    public fun listPipelineRuns(studyId: UUID): List<PipelineRunInfo> {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_PIPELINE_RUNS_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                val runs = mutableListOf<PipelineRunInfo>()
                while (rs.next()) {
                    runs.add(mapRunInfo(rs))
                }
                runs
            }
        }
    }

    public fun getPipelineRun(studyId: UUID, runId: UUID): PipelineRunInfo {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_PIPELINE_RUN_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setObject(2, runId)
                val rs = ps.executeQuery()
                check(rs.next()) { "Pipeline run $runId not found for study $studyId" }
                mapRunInfo(rs)
            }
        }
    }

    private fun getPipelineConfig(studyId: UUID): PipelineConfig {
        val study = studyService.getStudy(studyId)
        val settings = study.settings
        return settings[StudySettingType.Pipeline] as? PipelineConfig
            ?: PipelineConfig()
    }

    private fun mapRunInfo(rs: java.sql.ResultSet): PipelineRunInfo {
        val completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)
        return PipelineRunInfo(
            runId = rs.getObject("run_id", UUID::class.java),
            studyId = rs.getObject("study_id", UUID::class.java),
            status = PipelineRunStatus.valueOf(rs.getString("status")),
            stepsCompleted = rs.getInt("steps_completed"),
            totalSteps = rs.getInt("total_steps"),
            inputRows = rs.getLong("input_rows"),
            outputRows = rs.getLong("output_rows"),
            startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
            completedAt = if (completedAt != null && completedAt.year < POSTGRES_INFINITY_YEAR) completedAt else null,
            errorMessage = rs.getString("error_message"),
        )
    }
}
