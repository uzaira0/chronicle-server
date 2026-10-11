package com.openlattice.chronicle.studies.tasks

import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID
import java.util.concurrent.TimeUnit

class StudyLimitsEnforcementTaskTest {
    @Test
    fun retentionExpiryPublishesMetricWithoutRevokingAccessOrDeletingData() {
        val limits = Mockito.mock(StudyLimitsManager::class.java)
        val studies = Mockito.mock(StudyManager::class.java)
        val study = UUID.randomUUID()
        Mockito.`when`(limits.getStudiesExceedingDurationLimit()).thenReturn(setOf(study))
        Mockito.`when`(limits.getStudiesExcceedingDataRetentionPeriod()).thenReturn(setOf(study))
        val deps = StudyLimitsEnforcementTaskDependencies(Mockito.mock(StorageResolver::class.java), limits, studies)
        val task = object : StudyLimitsEnforcementTask() {
            override fun getDependency() = deps
        }
        assertEquals(0L, task.getInitialDelay())
        assertEquals(1L, task.getPeriod())
        assertEquals(TimeUnit.HOURS, task.getTimeUnit())
        task.runTask()
        Mockito.verify(studies).pauseEnrolledParticipants(study)
        assertEquals(1.0, com.openlattice.chronicle.observability.ChronicleMetrics.studiesRetentionExpired.get(), 0.0)
        Mockito.verifyNoMoreInteractions(studies)
        Mockito.`when`(limits.getStudiesExcceedingDataRetentionPeriod()).thenReturn(emptySet())
        task.runTask()
        assertEquals(0.0, com.openlattice.chronicle.observability.ChronicleMetrics.studiesRetentionExpired.get(), 0.0)
    }

    @Test
    fun pauseFailureDoesNotStarveOtherStudiesOrRetentionExpiry() {
        val limits = Mockito.mock(StudyLimitsManager::class.java)
        val studies = Mockito.mock(StudyManager::class.java)
        val failingStudy = UUID.randomUUID()
        val healthyStudy = UUID.randomUUID()
        Mockito.`when`(limits.getStudiesExceedingDurationLimit()).thenReturn(linkedSetOf(failingStudy, healthyStudy))
        Mockito.`when`(limits.getStudiesExcceedingDataRetentionPeriod()).thenReturn(setOf(healthyStudy))
        Mockito.doThrow(IllegalStateException("synthetic pause failure")).`when`(studies).pauseEnrolledParticipants(failingStudy)
        val dependencies = StudyLimitsEnforcementTaskDependencies(Mockito.mock(StorageResolver::class.java), limits, studies)
        object : StudyLimitsEnforcementTask() {
            override fun getDependency() = dependencies
        }.runTask()
        Mockito.verify(studies).pauseEnrolledParticipants(healthyStudy)
        assertEquals(1.0, com.openlattice.chronicle.observability.ChronicleMetrics.studiesRetentionExpired.get(), 0.0)
    }
}
