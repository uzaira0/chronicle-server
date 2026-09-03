package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.UUID

class StudyLifecycleControllerTest {

    private val lifecycleService = Mockito.mock(StudyLifecycleService::class.java)
    private val controller = StudyLifecycleController(lifecycleService)

    // archiveStudy and unarchiveStudy call Principals.getCurrentUser().id internally,
    // so they cannot be tested without a real security context.
    // Covered by integration tests.

    @Test
    fun testGetStudyLifecycleStatusReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val expected = StudyLifecycleStatus.ACTIVE
        Mockito.`when`(lifecycleService.getLifecycleStatus(studyId)).thenReturn(expected)

        val result = controller.getStudyLifecycleStatus(studyId)
        assertEquals(expected, result)
    }

    @Test
    fun testGetStudyLifecycleStatusForArchivedStudy() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(lifecycleService.getLifecycleStatus(studyId)).thenReturn(StudyLifecycleStatus.ARCHIVED)

        val result = controller.getStudyLifecycleStatus(studyId)
        assertEquals(StudyLifecycleStatus.ARCHIVED, result)
    }

    @Test
    fun testGetStudyDataSummaryDelegatesToService() {
        val studyId = UUID.randomUUID()
        controller.getStudyDataSummary(studyId)
        verify(lifecycleService).getStudyDataSummary(studyId)
    }
}
