package com.openlattice.chronicle.services.export

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

public open class ExportFileCleanupScheduler(
    private val exportService: ExportService,
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(ExportFileCleanupScheduler::class.java)
    }

    // reason: boundary catch — scheduled cleanup must log-and-continue so a single failure does not
    // kill the recurring task
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedRate = 3_600_000) // Every hour
    public fun cleanupExpiredExportFiles() {
        try {
            exportService.cleanupExpiredExportFiles()
        } catch (ex: Exception) {
            logger.error("Error cleaning up expired export files", ex)
        }
    }
}
