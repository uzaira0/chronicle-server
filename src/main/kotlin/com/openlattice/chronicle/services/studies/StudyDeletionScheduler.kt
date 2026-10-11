package com.openlattice.chronicle.services.studies

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
    public fun processScheduledDeletions() {
        try {
            RLSRequestContext.withSystemContext {
                lifecycleService.executeScheduledDeletions()
            }
        } catch (ex: Exception) {
            logger.error("Error reconciling scheduled study deletions", ex)
        }
        try {
            val verifiedOperations = dataDeletionOrchestrator.processDueOperations()
            if (verifiedOperations > 0) {
                logger.info("Completed and verified {} quarantined data deletion operations", verifiedOperations)
            }
        } catch (ex: Exception) {
            logger.error("Error processing quarantined data deletions", ex)
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
        }
    }
    @Scheduled(cron = "@daily")
    @Suppress("TooGenericExceptionCaught")
    public fun reportOverdueRetentionHolds() {
        try {
            dataDeletionOrchestrator.reportOverdueRetentionHolds()
        } catch (ex: Exception) {
            logger.error("Error reviewing overdue retention holds", ex)
        }
    }

}
