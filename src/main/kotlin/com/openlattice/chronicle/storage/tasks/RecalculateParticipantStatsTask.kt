package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.Task
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.participantStatsAndroidPostgresSql
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class RecalculateParticipantStatsTask : HazelcastFixedRateTask<RecalculateParticipantStatsTaskDependencies> {
    internal companion object {
        private val logger = LoggerFactory.getLogger(RecalculateParticipantStatsTask::class.java)
        private const val TIMEOUT_HOURS = 4L

        private val executor: ListeningExecutorService =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1))
    }

    override fun getInitialDelay(): Long = 0

    override fun getPeriod(): Long = 12

    override fun getTimeUnit(): TimeUnit = TimeUnit.HOURS

    // reason: boundary catch — a scheduled background task must log and absorb any failure type so the fixed-rate scheduler keeps running
    @Suppress("TooGenericExceptionCaught")
    override fun runTask() {
        val f = executor.submit {
            RLSRequestContext.withSystemContext {
                recalculateParticipantStats()
            }
        }
        try {
            f.get(TIMEOUT_HOURS, TimeUnit.HOURS)
        } catch (timeoutException: TimeoutException) {
            logger.error("Timed out after $TIMEOUT_HOURS hours when recalculating participant stats", timeoutException)
            f.cancel(true)
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.PARTICIPANT_STATS)
        } catch (ex: Exception) {
            logger.error("Exception when recalculating participant stats.", ex)
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.PARTICIPANT_STATS)
        }
    }

    override fun getName(): String = Task.RECALCULATE_PARTICIPANT_STATS.name
    override fun getDependenciesClass(): Class<out RecalculateParticipantStatsTaskDependencies> =
        RecalculateParticipantStatsTaskDependencies::class.java

    private fun recalculateParticipantStats() {
        logger.info("Starting recalculation of participant stats...")
        with(getDependency()) {
            val events = storageResolver.getDefaultEventStorage().dataSource
            recalculateParticipantStats(studyService, studyService.getAllStudyIds(), events)
        }
    }

    /** Rebuilds the iOS then Android unique-date stats of every study in [studyIds] from the stored events. */
    internal fun recalculateParticipantStats(studyService: StudyManager, studyIds: Iterable<UUID>, hds: HikariDataSource) {
        recalculateParticipantStats(studyService, studyIds, hds, ParticipantStat.Ios)
        recalculateParticipantStats(studyService, studyIds, hds, ParticipantStat.Android)
    }

    private fun recalculateParticipantStats(
        studyService: StudyManager,
        studyIds: Iterable<UUID>,
        hds: HikariDataSource,
        statType: ParticipantStat
    ) {
        val sql = getParticipantStatsSql(statType)
        studyIds.asSequence().forEach { studyId ->
            logger.info("Recalculating participant stats for study $studyId")
            BasePostgresIterable(
                StreamingReadTransaction(
                    hds,
                    sql,
                    fetchSize = 65536,
                ) {
                    it.setString(1, studyId.toString())
                }
            ) {
                ParticipantKey(
                    UUID.fromString(it.getString(STUDY_ID.name)),
                    it.getString(PARTICIPANT_ID.name)
                ) to ResultSetAdapters.uniqueDates(it)
            }
                .toMap()
                .forEach {
                    studyService.insertOrUpdateParticipantStats(
                        when (statType) {
                            ParticipantStat.Ios -> ParticipantStats(
                                studyId = it.key.studyId,
                                participantId = it.key.participantId,
                                iosUniqueDates = it.value
                            )

                            ParticipantStat.Android -> ParticipantStats(
                                studyId = it.key.studyId,
                                participantId = it.key.participantId,
                                androidUniqueDates = it.value
                            )
                        }
                    )
                }

        }
    }

    private fun getParticipantStatsSql(
        statType: ParticipantStat
    ): String {
        return when (statType) {
            ParticipantStat.Ios -> ScreenTimeUsageCaptures.CAPTURE_DAYS_SQL

            ParticipantStat.Android -> participantStatsAndroidPostgresSql
        }
    }
}

internal enum class ParticipantStat {
    Android,
    Ios,
}
