package com.openlattice.chronicle.services.quality

import com.openlattice.chronicle.services.studies.StudyManager
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class DataQualitySchedulerTest {
    private val studyManager = Mockito.mock(StudyManager::class.java)
    private val qualityService = Mockito.mock(DataQualityService::class.java)
    private val scheduler = DataQualityScheduler(studyManager, qualityService)

    @Test
    fun oneStudyFailureDoesNotStarveRemainingStudiesOrCleanup() {
        val failingStudy = UUID.randomUUID()
        val healthyStudy = UUID.randomUUID()
        whenever(studyManager.getAllStudyIds()).thenReturn(listOf(failingStudy, healthyStudy))
        doThrow(IllegalStateException("synthetic failure"))
            .`when`(qualityService)
            .generateAlerts(failingStudy)
        whenever(qualityService.generateAlerts(healthyStudy)).thenReturn(2)

        scheduler.evaluateAllStudies()

        verify(qualityService).generateAlerts(failingStudy)
        verify(qualityService).generateAlerts(healthyStudy)
        verify(qualityService).cleanupOldAlerts()
    }

    @Test
    fun studyEnumerationFailureStillRunsCleanup() {
        doThrow(IllegalStateException("synthetic enumeration failure"))
            .`when`(studyManager)
            .getAllStudyIds()

        scheduler.evaluateAllStudies()

        verify(qualityService).cleanupOldAlerts()
    }
}
