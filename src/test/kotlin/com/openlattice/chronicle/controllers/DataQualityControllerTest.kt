package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.quality.DataQualityService
import com.openlattice.chronicle.study.DataQualityDashboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class DataQualityControllerTest {

    private val dataQualityService = Mockito.mock(DataQualityService::class.java)
    private val auditService = Mockito.mock(com.openlattice.chronicle.audit.AuditService::class.java)
    private val controller = DataQualityController(dataQualityService, auditService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsDataQualityService() {
        val svc = Mockito.mock(DataQualityService::class.java)
        val ctrl = DataQualityController(svc, auditService)
        assertNotNull(ctrl)
    }

    @Test
    fun testGetDataQualityDashboardDelegatesToService() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        val result = controller.getDataQualityDashboard(studyId)
        assertNotNull(result)
        verify(dataQualityService).getDataQualityDashboard(studyId)
    }

    @Test
    fun testGetDataQualityDashboardReturnsCorrectDashboard() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        val result = controller.getDataQualityDashboard(studyId)
        assertSame(dashboard, result)
    }

    @Test
    fun testGetDataQualityDashboardPassesStudyId() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        controller.getDataQualityDashboard(studyId)
        verify(dataQualityService).getDataQualityDashboard(studyId)
    }

    @Test(expected = RuntimeException::class)
    fun testGetDataQualityDashboardPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId))
            .thenThrow(RuntimeException("dashboard error"))

        controller.getDataQualityDashboard(studyId)
    }

    @Test
    fun testGetDataQualityDashboardForDifferentStudies() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val dashboard1 = Mockito.mock(DataQualityDashboard::class.java)
        val dashboard2 = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId1)).thenReturn(dashboard1)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId2)).thenReturn(dashboard2)

        assertSame(dashboard1, controller.getDataQualityDashboard(studyId1))
        assertSame(dashboard2, controller.getDataQualityDashboard(studyId2))
    }

    @Test
    fun testGetDataQualityDashboardServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        controller.getDataQualityDashboard(studyId)
        verify(dataQualityService, Mockito.times(1)).getDataQualityDashboard(studyId)
    }

    @Test
    fun testGetDataQualityDashboardNoOtherInteractions() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        controller.getDataQualityDashboard(studyId)
        verify(dataQualityService).getDataQualityDashboard(studyId)
        Mockito.verifyNoMoreInteractions(dataQualityService)
    }

    @Test
    fun testGetDataQualityDashboardWithNilUuid() {
        val studyId = UUID(0, 0)
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        val result = controller.getDataQualityDashboard(studyId)
        assertNotNull(result)
    }

    @Test
    fun testQualityPathConstant() {
        assertEquals("/quality", DataQualityController.QUALITY_PATH)
    }

    @Test
    fun testGetDataQualityDashboardCalledMultipleTimes() {
        val studyId = UUID.randomUUID()
        val dashboard = Mockito.mock(DataQualityDashboard::class.java)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)

        controller.getDataQualityDashboard(studyId)
        controller.getDataQualityDashboard(studyId)
        verify(dataQualityService, Mockito.times(2)).getDataQualityDashboard(studyId)
    }

    @Test(expected = IllegalStateException::class)
    fun testGetDataQualityDashboardPropagatesIllegalStateException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId))
            .thenThrow(IllegalStateException("invalid state"))

        controller.getDataQualityDashboard(studyId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testGetDataQualityDashboardPropagatesIllegalArgumentException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId))
            .thenThrow(IllegalArgumentException("invalid argument"))

        controller.getDataQualityDashboard(studyId)
    }
    @Test
    fun testQualityReadAuditsAccessWithoutParticipantContent() {
        val studyId = UUID.randomUUID()
        val dashboard = DataQualityDashboard(studyId, totalParticipants = 3)
        Mockito.`when`(dataQualityService.getDataQualityDashboard(studyId)).thenReturn(dashboard)
        controller.getDataQualityDashboard(studyId)
        val event = org.mockito.kotlin.argumentCaptor<com.openlattice.chronicle.audit.AuditLogEntry>()
        verify(auditService).log(event.capture())
        assertEquals(com.openlattice.chronicle.audit.AuditAction.PARTICIPANT_DATA_ACCESS, event.firstValue.action)
        assertEquals(studyId, event.firstValue.studyId)
        assertEquals(true, event.firstValue.success)
        assertEquals(true, event.firstValue.accessedPHI)
        assertEquals(mapOf("participantCount" to 3), event.firstValue.additionalData)
    }

}
