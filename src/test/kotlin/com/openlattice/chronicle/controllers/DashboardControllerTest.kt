package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.dashboard.StudyEvent
import com.openlattice.chronicle.dashboard.StudyRealtimeStats
import com.openlattice.chronicle.services.dashboard.DashboardService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.UUID

class DashboardControllerTest {

    private val dashboardService = Mockito.mock(DashboardService::class.java)
    private val auditService = Mockito.mock(com.openlattice.chronicle.audit.AuditService::class.java)
    private val controller = DashboardController(dashboardService, auditService)

    @Test
    fun testGetStatsReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val expected = StudyRealtimeStats(studyId = studyId, totalParticipants = 42, activeParticipants24h = 10)
        Mockito.`when`(dashboardService.getStats(studyId)).thenReturn(expected)

        val result = controller.getStats(studyId)

        assertEquals(expected.studyId, result.studyId)
        assertEquals(expected.totalParticipants, result.totalParticipants)
        assertEquals(expected.activeParticipants24h, result.activeParticipants24h)
    }

    @Test
    fun testGetRecentEventsReturnsEmptyList() {
        val studyId = UUID.randomUUID()
        val events = emptyList<StudyEvent>()
        Mockito.`when`(dashboardService.getRecentEvents(studyId, 100, null)).thenReturn(events)

        val result = controller.getRecentEvents(studyId, 100, null)

        assertNotNull(result)
        assertEquals(0, result.size)
        val audit = org.mockito.kotlin.argumentCaptor<com.openlattice.chronicle.audit.AuditLogEntry>()
        verify(auditService).log(audit.capture())
        assertEquals(com.openlattice.chronicle.audit.AuditAction.PARTICIPANT_DATA_ACCESS, audit.firstValue.action)
        assertEquals(true, audit.firstValue.accessedPHI)
        assertEquals(studyId, audit.firstValue.studyId)
    }

    @Test
    fun testGetRecentEventsPassesLimitAndSince() {
        val studyId = UUID.randomUUID()
        val since = "2024-01-01T00:00:00Z"
        controller.getRecentEvents(studyId, 50, since)
        verify(dashboardService).getRecentEvents(kEq(studyId), kEq(50), kAny())
    }
}
