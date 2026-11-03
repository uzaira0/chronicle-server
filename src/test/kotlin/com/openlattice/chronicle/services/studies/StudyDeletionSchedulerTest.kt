package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import org.junit.Test
import org.mockito.Mockito

class StudyDeletionSchedulerTest {
    @Test
    fun dueErasureOperationsAreProcessed() {
        val lifecycle = Mockito.mock(StudyLifecycleService::class.java)
        val deletion = Mockito.mock(DataDeletionOrchestrator::class.java)
        StudyDeletionScheduler(lifecycle, deletion).processScheduledDeletions()
        Mockito.verify(deletion).processDueOperations()
    }
}
