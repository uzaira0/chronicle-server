package com.openlattice.chronicle.services.quality

import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class DataQualitySchedulerTest {
    private val studyManager = Mockito.mock(StudyManager::class.java)
    private val qualityService = Mockito.mock(DataQualityService::class.java)
    private val scheduler = DataQualityScheduler(studyManager, qualityService)

    @Test
    fun backgroundEvaluationInstallsAndRestoresSystemRlsContext() {
        val studyId = UUID.randomUUID()
        RLSRequestContext.clear()
        whenever(studyManager.getAllStudyIds()).thenAnswer {
            assertTrue(RLSRequestContext.current()?.isAdmin == true)
            listOf(studyId)
        }
        whenever(qualityService.generateAlerts(studyId)).thenAnswer {
            assertTrue(RLSRequestContext.current()?.isAdmin == true)
            0
        }
        scheduler.evaluateAllStudies()
        verify(qualityService).generateAlerts(studyId)
        assertNull(RLSRequestContext.current())
    }

    @Test
    fun oneStudyFailureDoesNotStarveRemainingStudies() {
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
        verifyNoMoreInteractions(qualityService)
    }

    @Test
    fun studyEnumerationFailureDoesNotTriggerAlertDeletion() {
        doThrow(IllegalStateException("synthetic enumeration failure"))
            .`when`(studyManager)
            .getAllStudyIds()

        scheduler.evaluateAllStudies()

        verifyNoMoreInteractions(qualityService)
    }
}
