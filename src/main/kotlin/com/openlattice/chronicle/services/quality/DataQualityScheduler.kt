package com.openlattice.chronicle.services.quality

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/** Local-only daily quality projection. Failures are isolated per study and never notify externally. */
public open class DataQualityScheduler(
    private val studyManager: StudyManager,
    private val dataQualityService: DataQualityService,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(DataQualityScheduler::class.java)
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    @Suppress("TooGenericExceptionCaught")
    public open fun evaluateAllStudies() {
        var successfulStudies = 0
        var failedStudies = 0
        var newAlerts = 0

        val studyIds = try {
            studyManager.getAllStudyIds()
        } catch (exception: Exception) {
            ChronicleMetrics.dataQualityEvaluationsTotal.labels("enumeration", "failed").inc()
            logger.error("data_quality outcome=study_enumeration_failed", exception)
            emptySet()
        }

        studyIds.forEach { studyId ->
            try {
                newAlerts += dataQualityService.generateAlerts(studyId)
                successfulStudies += 1
                ChronicleMetrics.dataQualityEvaluationsTotal.labels("scheduled", "success").inc()
            } catch (exception: Exception) {
                failedStudies += 1
                ChronicleMetrics.dataQualityEvaluationsTotal.labels("scheduled", "failed").inc()
                logger.error(
                    "data_quality outcome=failed study_ref={} failure_code={}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    exception::class.simpleName ?: "QualityEvaluationFailure",
                    exception,
                )
            }
        }

        try {
            dataQualityService.cleanupOldAlerts()
        } catch (exception: Exception) {
            ChronicleMetrics.dataQualityEvaluationsTotal.labels("cleanup", "failed").inc()
            logger.error("data_quality outcome=cleanup_failed", exception)
        }
        if (newAlerts > 0) ChronicleMetrics.dataQualityAlertsTotal.inc(newAlerts.toDouble())
        logger.info(
            "data_quality outcome=batch_complete successful_studies={} failed_studies={} new_alerts={}",
            successfulStudies,
            failedStudies,
            newAlerts,
        )
    }
}
