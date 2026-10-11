package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import org.junit.Test
import org.mockito.Mockito

class StudyDeletionSchedulerTest {
    @Test
    fun reconciliationFailureDoesNotStarveDueErasureOperations() {
        val lifecycle = Mockito.mock(StudyLifecycleService::class.java)
        val deletion = Mockito.mock(DataDeletionOrchestrator::class.java)
        Mockito.doThrow(IllegalStateException("synthetic reconciliation failure"))
            .`when`(lifecycle).executeScheduledDeletions()
        StudyDeletionScheduler(lifecycle, deletion).processScheduledDeletions()
        Mockito.verify(deletion).processDueOperations()
    }
    @Test
    fun overdueHoldReviewsRunDailyWithoutReleasingHolds() {
        val deletion = Mockito.mock(DataDeletionOrchestrator::class.java)
        StudyDeletionScheduler(Mockito.mock(StudyLifecycleService::class.java), deletion).reportOverdueRetentionHolds()
        Mockito.verify(deletion).reportOverdueRetentionHolds()
        val schedule = StudyDeletionScheduler::class.java.getMethod("reportOverdueRetentionHolds")
            .getAnnotation(org.springframework.scheduling.annotation.Scheduled::class.java)
        org.junit.Assert.assertEquals("@daily", schedule.cron)
        Mockito.verifyNoMoreInteractions(deletion)
    }

    @Test
    fun overdueReviewIncludesOnlyUnreleasedHoldsBeforeTheDeadline() {
        val postgres = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
            maximumPoolSize = 1
        }).use { pool ->
            val deadline = java.time.OffsetDateTime.parse("2026-10-08T00:00:00Z")
            pool.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE TEMP TABLE retention_holds (hold_id UUID, operation_id UUID, review_at TIMESTAMPTZ, released_at TIMESTAMPTZ)")
                }
                connection.prepareStatement("INSERT INTO retention_holds VALUES (?, ?, ?, ?)").use {
                    listOf(deadline.minusSeconds(1) to null, deadline to null, deadline.minusSeconds(1) to deadline).forEach { (review, released) ->
                        it.setObject(1, java.util.UUID.randomUUID()); it.setObject(2, java.util.UUID.randomUUID())
                        it.setObject(3, review); it.setObject(4, released); it.addBatch()
                    }
                    it.executeBatch()
                }
            }
            val storage = Mockito.mock(com.openlattice.chronicle.storage.StorageResolver::class.java)
            Mockito.`when`(storage.getPlatformStorage()).thenReturn(pool)
            val service = DataDeletionOrchestrator(storage, Mockito.mock(com.openlattice.chronicle.auditing.AuditingManager::class.java),
                java.time.Clock.fixed(deadline.toInstant(), java.time.ZoneOffset.UTC))
            org.junit.Assert.assertEquals(1, service.reportOverdueRetentionHolds())
            org.junit.Assert.assertEquals(1.0, com.openlattice.chronicle.observability.ChronicleMetrics.retentionHoldsOverdue.get(), 0.0)
            pool.connection.use { connection ->
                connection.createStatement().use { it.executeUpdate("DELETE FROM retention_holds") }
            }
            org.junit.Assert.assertEquals(0, service.reportOverdueRetentionHolds())
            org.junit.Assert.assertEquals(0.0, com.openlattice.chronicle.observability.ChronicleMetrics.retentionHoldsOverdue.get(), 0.0)
        }
    }

}
