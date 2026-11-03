package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.sql.SQLTransientConnectionException

class RecalculateParticipantStatsFailureTest {
    /**
     * The twelve-hourly stats rebuild runs while Postgres is unreachable. The failure is counted on the
     * background-task failure metric the operator alerts on, and the scheduler thread is not killed by it.
     */
    @Test
    fun aRebuildThatCannotReachTheDatabaseIsCountedAndDoesNotEscapeTheScheduler() {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storageResolver.getDefaultEventStorage())
            .thenAnswer { throw SQLTransientConnectionException("HikariPool-1 - Connection is not available") }
        val dependencies = RecalculateParticipantStatsTaskDependencies(storageResolver, Mockito.mock(StudyManager::class.java))
        val task = object : RecalculateParticipantStatsTask() {
            override fun getDependency(): RecalculateParticipantStatsTaskDependencies = dependencies
        }
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal
            .labels(ChronicleMetrics.BackgroundTask.PARTICIPANT_STATS.label)
        val before = failures.get()

        task.runTask()

        assertEquals(before + 1, failures.get(), 0.0)
        Mockito.verify(storageResolver).getDefaultEventStorage()
    }
}
