package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

public open class StudyDeletionScheduler(
    private val lifecycleService: StudyLifecycleService,
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyDeletionScheduler::class.java)
        private const val ONE_HOUR_MS = 3_600_000L
    }

    // reason: boundary catch — scheduled task must log any failure type and not propagate past the scheduler
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedRate = ONE_HOUR_MS)
    public fun processScheduledDeletions(): Unit = RLSRequestContext.withSystemContext {
        try {
            // Only resumes erasures an owner started; a pending request is never erased here.
            val verifiedOperations = dataDeletionOrchestrator.processDueOperations()
            if (verifiedOperations > 0) {
                logger.info("Resumed and verified {} owner-started data deletion operations", verifiedOperations)
            }
        } catch (ex: Exception) {
            logger.error("Error resuming owner-started data deletions", ex)
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.DELETION_RESUME)
        }
    }

    // reason: boundary catch — scheduled task must log any failure type and not propagate past the scheduler
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedRate = ONE_HOUR_MS)
    public fun autoArchiveExpiredStudies() {
        try {
            RLSRequestContext.withSystemContext {
                lifecycleService.autoArchiveExpiredStudies()
            }
        } catch (ex: Exception) {
            logger.error("Error auto-archiving expired studies", ex)
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.AUTO_ARCHIVE)
        }
    }
}
