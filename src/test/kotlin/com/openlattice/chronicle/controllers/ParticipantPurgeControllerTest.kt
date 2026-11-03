package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.study.ParticipantDataPurgeSummary
import com.openlattice.chronicle.study.ParticipantPurgeRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class ParticipantPurgeControllerTest {

    private val purgeService = Mockito.mock(ParticipantPurgeService::class.java)
    private val controller = ParticipantPurgeController(purgeService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    

    @Test
    fun testControllerAcceptsPurgeService() {
        val svc = Mockito.mock(ParticipantPurgeService::class.java)
        val ctrl = ParticipantPurgeController(svc)
        assertNotNull(ctrl)
    }

    // --- previewParticipantPurge ---

    @Test
    fun testPreviewParticipantPurgeDelegatesToService() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val summary = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        Mockito.`when`(purgeService.previewPurge(studyId, participantId)).thenReturn(summary)

        val result = controller.previewParticipantPurge(studyId, participantId)
        assertNotNull(result)
        assertSame(summary, result)
        verify(purgeService).previewPurge(studyId, participantId)
    }

    @Test
    fun testPreviewParticipantPurgePassesCorrectArgs() {
        val studyId = UUID.randomUUID()
        val participantId = "p-456"
        val summary = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        Mockito.`when`(purgeService.previewPurge(studyId, participantId)).thenReturn(summary)

        controller.previewParticipantPurge(studyId, participantId)
        verify(purgeService).previewPurge(studyId, participantId)
    }

    @Test
    fun testPreviewParticipantPurgePropagatesException() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        Mockito.`when`(purgeService.previewPurge(studyId, participantId))
            .thenThrow(RuntimeException("preview error"))

        assertThrows(RuntimeException::class.java) {
            controller.previewParticipantPurge(studyId, participantId)
        }
    }

    @Test
    fun testPreviewParticipantPurgeForDifferentParticipants() {
        val studyId = UUID.randomUUID()
        val summary1 = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        val summary2 = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        Mockito.`when`(purgeService.previewPurge(studyId, "p-1")).thenReturn(summary1)
        Mockito.`when`(purgeService.previewPurge(studyId, "p-2")).thenReturn(summary2)

        assertSame(summary1, controller.previewParticipantPurge(studyId, "p-1"))
        assertSame(summary2, controller.previewParticipantPurge(studyId, "p-2"))
    }

    @Test
    fun testPreviewParticipantPurgeServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val summary = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        Mockito.`when`(purgeService.previewPurge(studyId, participantId)).thenReturn(summary)

        controller.previewParticipantPurge(studyId, participantId)
        verify(purgeService, Mockito.times(1)).previewPurge(studyId, participantId)
    }

    // --- executeParticipantPurge ---

    @Test
    fun testExecuteParticipantPurgeDelegatesToService() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        val purgedIds = listOf(UUID.randomUUID())
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(purgedIds)

        val result = controller.executeParticipantPurge(studyId, request)
        assertNotNull(result)
        verify(purgeService).executePurge(studyId, request)
    }

    @Test
    fun testExecuteParticipantPurgeReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        val purgedIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(purgedIds)

        val result = controller.executeParticipantPurge(studyId, request)
        assertEquals(2, result.count())
    }

    @Test
    fun testExecuteParticipantPurgeReturnsEmptyWhenNoPurge() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(emptyList())

        val result = controller.executeParticipantPurge(studyId, request)
        assertEquals(0, result.count())
    }

    @Test
    fun testExecuteParticipantPurgePropagatesException() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        Mockito.`when`(purgeService.executePurge(studyId, request))
            .thenThrow(RuntimeException("purge error"))

        assertThrows(RuntimeException::class.java) {
            controller.executeParticipantPurge(studyId, request)
        }
    }

    @Test
    fun testExecuteParticipantPurgePassesCorrectArgs() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(emptyList())

        controller.executeParticipantPurge(studyId, request)
        verify(purgeService).executePurge(studyId, request)
    }

    @Test
    fun testExecuteParticipantPurgeServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(emptyList())

        controller.executeParticipantPurge(studyId, request)
        verify(purgeService, Mockito.times(1)).executePurge(studyId, request)
    }

    @Test
    fun testNoOtherInteractionsAfterPreview() {
        val studyId = UUID.randomUUID()
        val participantId = "p-123"
        val summary = Mockito.mock(ParticipantDataPurgeSummary::class.java)
        Mockito.`when`(purgeService.previewPurge(studyId, participantId)).thenReturn(summary)

        controller.previewParticipantPurge(studyId, participantId)
        verify(purgeService).previewPurge(studyId, participantId)
        Mockito.verifyNoMoreInteractions(purgeService)
    }

    @Test
    fun testNoOtherInteractionsAfterExecute() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ParticipantPurgeRequest::class.java)
        Mockito.`when`(purgeService.executePurge(studyId, request)).thenReturn(emptyList())

        controller.executeParticipantPurge(studyId, request)
        verify(purgeService).executePurge(studyId, request)
        Mockito.verifyNoMoreInteractions(purgeService)
    }
}
