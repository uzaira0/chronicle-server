package com.openlattice.chronicle.services.quality

import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.DataQualityAlert
import com.openlattice.chronicle.study.DataQualityConfig
import com.openlattice.chronicle.study.DataQualityDashboard
import com.openlattice.chronicle.study.ParticipantQualityScore
import com.openlattice.chronicle.study.StudySettingType
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class DataQualityService(
    private val storageResolver: StorageResolver,
    private val studyService: StudyService,
    private val clock: Clock = Clock.systemUTC(),
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DataQualityService::class.java)

        private val INSERT_ALERT_SQL = """
            INSERT INTO data_quality_alerts
                (alert_id, study_id, participant_id, alert_type, message, score, created_at,
                 evaluation_start, evaluation_end, threshold)
            VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, ?)
            ON CONFLICT (study_id, participant_id, alert_type, evaluation_start, evaluation_end)
            DO NOTHING
        """.trimIndent()

        private val GET_RECENT_ALERTS_SQL = """
            SELECT alert_id, study_id, participant_id, alert_type, message, score, created_at
            FROM data_quality_alerts
            WHERE study_id = ?
            ORDER BY created_at DESC
            LIMIT 50
        """.trimIndent()

        private val CLEANUP_OLD_ALERTS_SQL = """
            DELETE FROM data_quality_alerts WHERE created_at < now() - interval '30 days'
        """.trimIndent()
    }

    public fun getDataQualityDashboard(studyId: UUID): DataQualityDashboard {
        try {
            val config = getQualityConfig(studyId)
            val allStats = studyService.getStudyParticipantStats(studyId)
            val (windowStart, windowEnd) = evaluationWindow(config)

            val participantScores = allStats.values.map { stats ->
                computeParticipantScore(stats, config, windowStart, windowEnd)
            }

            val activeParticipants = participantScores.count { it.overallScore > 0 }
            val belowThreshold = participantScores.count {
                it.overallScore > 0 && it.overallScore < config.alertThresholdPercent
            }
            val overallCompleteness = if (participantScores.isNotEmpty()) {
                participantScores.map { it.overallScore }.average()
            } else 0.0

            val recentAlerts = getRecentAlerts(studyId)

            ChronicleMetrics.dataQualityEvaluationsTotal.labels("dashboard", "success").inc()
            return DataQualityDashboard(
                studyId = studyId,
                overallCompleteness = overallCompleteness,
                totalParticipants = allStats.size,
                activeParticipants = activeParticipants,
                belowThreshold = belowThreshold,
                participantScores = participantScores,
                recentAlerts = recentAlerts,
                config = config,
            )
        } catch (exception: Exception) {
            ChronicleMetrics.dataQualityEvaluationsTotal.labels("dashboard", "failed").inc()
            throw exception
        }
    }

    public open fun generateAlerts(studyId: UUID): Int {
        val config = getQualityConfig(studyId)
        val allStats = studyService.getStudyParticipantStats(studyId)
        val (windowStart, windowEnd) = evaluationWindow(config)
        val evaluationStart = windowStart.atStartOfDay().atOffset(ZoneOffset.UTC)
        val evaluationEnd = windowEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)

        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_ALERT_SQL).use { ps ->
                for (stats in allStats.values) {
                    val score = computeParticipantScore(stats, config, windowStart, windowEnd)
                    addAlertIfBelowThreshold(
                        ps,
                        studyId,
                        stats,
                        score,
                        config,
                        evaluationStart,
                        evaluationEnd,
                    )
                }
                val batchResults = ps.executeBatch()
                if (batchResults == null) return@use 0
                batchResults.sumOf { result ->
                    when {
                        result > 0 -> result
                        result == Statement.SUCCESS_NO_INFO -> 1
                        else -> 0
                    }
                }
            }
        }
    }

    private fun addAlertIfBelowThreshold(
        ps: PreparedStatement,
        studyId: UUID,
        stats: ParticipantStats,
        score: ParticipantQualityScore,
        config: DataQualityConfig,
        evaluationStart: OffsetDateTime,
        evaluationEnd: OffsetDateTime,
    ) {
        if (score.overallScore > 0 && score.overallScore < config.alertThresholdPercent) {
            val formattedScore = String.format(Locale.US, "%.1f", score.overallScore)
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, studyId)
            ps.setString(3, stats.participantId)
            ps.setString(4, "LOW_QUALITY")
            ps.setString(
                5,
                "Participant quality score $formattedScore% " +
                    "is below threshold ${config.alertThresholdPercent}%"
            )
            ps.setDouble(6, score.overallScore)
            ps.setObject(7, evaluationStart)
            ps.setObject(8, evaluationEnd)
            ps.setDouble(9, config.alertThresholdPercent.toDouble())
            ps.addBatch()
        }
    }

    public fun cleanupOldAlerts() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(CLEANUP_OLD_ALERTS_SQL).use { ps ->
                val deleted = ps.executeUpdate()
                if (deleted > 0) {
                    logger.info("Cleaned up {} old data quality alerts", deleted)
                }
            }
        }
    }

    private fun getQualityConfig(studyId: UUID): DataQualityConfig {
        val study = studyService.getStudy(studyId)
        val settings = study.settings
        val config = settings[StudySettingType.DataQuality] as? DataQualityConfig
        return config ?: DataQualityConfig()
    }

    private fun computeParticipantScore(
        stats: ParticipantStats,
        config: DataQualityConfig,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): ParticipantQualityScore {
        val expectedDays = computeExpectedDays(config.expectedDaysPerWeek, config.evaluationWindowDays)

        val androidDaysInWindow = stats.androidUniqueDates.count { it in windowStart..windowEnd }
        val iosDaysInWindow = stats.iosUniqueDates.count { it in windowStart..windowEnd }
        val tudDaysInWindow = stats.tudUniqueDates.count { it in windowStart..windowEnd }

        val androidScore = if (expectedDays > 0) (androidDaysInWindow.toDouble() / expectedDays * 100).coerceAtMost(100.0) else 0.0
        val iosScore = if (expectedDays > 0) (iosDaysInWindow.toDouble() / expectedDays * 100).coerceAtMost(100.0) else 0.0
        val tudScore = if (expectedDays > 0) (tudDaysInWindow.toDouble() / expectedDays * 100).coerceAtMost(100.0) else 0.0

        // Overall is the max of available platform scores (participant may only have one platform)
        val scores = listOf(androidScore, iosScore, tudScore).filter { it > 0 }
        val overallScore = if (scores.isNotEmpty()) scores.average() else 0.0

        val lastActivity = listOfNotNull(
            stats.androidLastPing, stats.androidLastDate,
            stats.iosLastPing, stats.iosLastDate,
            stats.tudLastDate
        ).maxOrNull()

        return ParticipantQualityScore(
            participantId = stats.participantId,
            androidScore = androidScore,
            iosScore = iosScore,
            tudScore = tudScore,
            overallScore = overallScore,
            androidDaysInWindow = androidDaysInWindow,
            iosDaysInWindow = iosDaysInWindow,
            tudDaysInWindow = tudDaysInWindow,
            lastActivity = lastActivity,
        )
    }

    private fun computeExpectedDays(daysPerWeek: Int, windowDays: Int): Int {
        val weeks = windowDays / 7.0
        return (weeks * daysPerWeek).toInt().coerceAtLeast(1)
    }

    private fun evaluationWindow(config: DataQualityConfig): Pair<LocalDate, LocalDate> {
        val windowEnd = LocalDate.now(clock)
        val windowStart = windowEnd.minusDays(config.evaluationWindowDays.toLong() - 1)
        return windowStart to windowEnd
    }

    private fun getRecentAlerts(studyId: UUID): List<DataQualityAlert> {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_RECENT_ALERTS_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                val alerts = mutableListOf<DataQualityAlert>()
                while (rs.next()) {
                    alerts.add(
                        DataQualityAlert(
                            alertId = rs.getObject("alert_id", UUID::class.java),
                            studyId = rs.getObject("study_id", UUID::class.java),
                            participantId = rs.getString("participant_id"),
                            alertType = rs.getString("alert_type"),
                            message = rs.getString("message"),
                            score = rs.getDouble("score"),
                            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                        )
                    )
                }
                alerts
            }
        }
    }
}
