package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.pipeline.PipelineRunInfo
import com.openlattice.chronicle.pipeline.PipelineService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class PipelineControllerTest {

    private val pipelineService = Mockito.mock(PipelineService::class.java)
    private val controller = PipelineController(pipelineService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsPipelineService() {
        val svc = Mockito.mock(PipelineService::class.java)
        val ctrl = PipelineController(svc)
        assertNotNull(ctrl)
    }

    // --- triggerPipeline ---

    @Test
    fun testTriggerPipelineDelegatesToService() {
        val studyId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId)).thenReturn(runInfo)

        val result = controller.triggerPipeline(studyId)
        assertNotNull(result)
        assertSame(runInfo, result)
        verify(pipelineService).triggerPipeline(studyId)
    }

    @Test
    fun testTriggerPipelinePassesStudyId() {
        val studyId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId)).thenReturn(runInfo)

        controller.triggerPipeline(studyId)
        verify(pipelineService).triggerPipeline(studyId)
    }

    @Test(expected = RuntimeException::class)
    fun testTriggerPipelinePropagatesException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(pipelineService.triggerPipeline(studyId))
            .thenThrow(RuntimeException("trigger error"))

        controller.triggerPipeline(studyId)
    }

    @Test
    fun testTriggerPipelineForDifferentStudies() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val runInfo1 = Mockito.mock(PipelineRunInfo::class.java)
        val runInfo2 = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId1)).thenReturn(runInfo1)
        Mockito.`when`(pipelineService.triggerPipeline(studyId2)).thenReturn(runInfo2)

        assertSame(runInfo1, controller.triggerPipeline(studyId1))
        assertSame(runInfo2, controller.triggerPipeline(studyId2))
    }

    @Test
    fun testTriggerPipelineServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId)).thenReturn(runInfo)

        controller.triggerPipeline(studyId)
        verify(pipelineService, Mockito.times(1)).triggerPipeline(studyId)
    }

    // --- listPipelineRuns ---

    @Test
    fun testListPipelineRunsDelegatesToService() {
        val studyId = UUID.randomUUID()
        val runs = listOf(Mockito.mock(PipelineRunInfo::class.java))
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(runs)

        val result = controller.listPipelineRuns(studyId)
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(pipelineService).listPipelineRuns(studyId)
    }

    @Test
    fun testListPipelineRunsReturnsEmptyList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(emptyList())

        val result = controller.listPipelineRuns(studyId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testListPipelineRunsReturnsMultipleRuns() {
        val studyId = UUID.randomUUID()
        val runs = listOf(
            Mockito.mock(PipelineRunInfo::class.java),
            Mockito.mock(PipelineRunInfo::class.java),
            Mockito.mock(PipelineRunInfo::class.java)
        )
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(runs)

        val result = controller.listPipelineRuns(studyId)
        assertEquals(3, result.size)
    }

    @Test
    fun testListPipelineRunsReturnsSameList() {
        val studyId = UUID.randomUUID()
        val runs = listOf(Mockito.mock(PipelineRunInfo::class.java))
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(runs)

        val result = controller.listPipelineRuns(studyId)
        assertSame(runs, result)
    }

    @Test(expected = RuntimeException::class)
    fun testListPipelineRunsPropagatesException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(pipelineService.listPipelineRuns(studyId))
            .thenThrow(RuntimeException("list error"))

        controller.listPipelineRuns(studyId)
    }

    @Test
    fun testListPipelineRunsServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(emptyList())

        controller.listPipelineRuns(studyId)
        verify(pipelineService, Mockito.times(1)).listPipelineRuns(studyId)
    }

    // --- getPipelineRun ---

    @Test
    fun testGetPipelineRunDelegatesToService() {
        val studyId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId)).thenReturn(runInfo)

        val result = controller.getPipelineRun(studyId, runId)
        assertNotNull(result)
        assertSame(runInfo, result)
        verify(pipelineService).getPipelineRun(studyId, runId)
    }

    @Test
    fun testGetPipelineRunPassesCorrectArgs() {
        val studyId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId)).thenReturn(runInfo)

        controller.getPipelineRun(studyId, runId)
        verify(pipelineService).getPipelineRun(studyId, runId)
    }

    @Test(expected = RuntimeException::class)
    fun testGetPipelineRunPropagatesException() {
        val studyId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId))
            .thenThrow(RuntimeException("get error"))

        controller.getPipelineRun(studyId, runId)
    }

    @Test
    fun testGetPipelineRunServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId)).thenReturn(runInfo)

        controller.getPipelineRun(studyId, runId)
        verify(pipelineService, Mockito.times(1)).getPipelineRun(studyId, runId)
    }

    @Test
    fun testGetPipelineRunForDifferentRuns() {
        val studyId = UUID.randomUUID()
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val runInfo1 = Mockito.mock(PipelineRunInfo::class.java)
        val runInfo2 = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId1)).thenReturn(runInfo1)
        Mockito.`when`(pipelineService.getPipelineRun(studyId, runId2)).thenReturn(runInfo2)

        assertSame(runInfo1, controller.getPipelineRun(studyId, runId1))
        assertSame(runInfo2, controller.getPipelineRun(studyId, runId2))
    }

    @Test
    fun testNoOtherInteractionsAfterTrigger() {
        val studyId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId)).thenReturn(runInfo)

        controller.triggerPipeline(studyId)
        verify(pipelineService).triggerPipeline(studyId)
        Mockito.verifyNoMoreInteractions(pipelineService)
    }

    @Test
    fun testTriggerAndListUseSameServiceInstance() {
        val studyId = UUID.randomUUID()
        val runInfo = Mockito.mock(PipelineRunInfo::class.java)
        Mockito.`when`(pipelineService.triggerPipeline(studyId)).thenReturn(runInfo)
        Mockito.`when`(pipelineService.listPipelineRuns(studyId)).thenReturn(listOf(runInfo))

        controller.triggerPipeline(studyId)
        controller.listPipelineRuns(studyId)
        verify(pipelineService).triggerPipeline(studyId)
        verify(pipelineService).listPipelineRuns(studyId)
    }
}
