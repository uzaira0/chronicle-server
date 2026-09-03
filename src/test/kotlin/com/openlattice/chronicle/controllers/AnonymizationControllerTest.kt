package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.anonymization.AnonymizationConfig
import com.openlattice.chronicle.services.anonymization.AnonymizationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class AnonymizationControllerTest {

    private val anonymizationService = Mockito.mock(AnonymizationService::class.java)
    private val controller = AnonymizationController(anonymizationService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    // --- Constructor tests ---

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsAnonymizationService() {
        val svc = Mockito.mock(AnonymizationService::class.java)
        val ctrl = AnonymizationController(svc)
        assertNotNull(ctrl)
    }

    // --- getAnonymizationConfig tests ---

    @Test
    fun testGetAnonymizationConfigDelegatesToService() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        val result = controller.getAnonymizationConfig(studyId)
        assertNotNull(result)
        assertEquals(config, result)
        verify(anonymizationService).getConfig(studyId)
    }

    @Test
    fun testGetAnonymizationConfigReturnsCorrectConfig() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        val result = controller.getAnonymizationConfig(studyId)
        assertSame(config, result)
    }

    @Test
    fun testGetAnonymizationConfigPassesStudyId() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        controller.getAnonymizationConfig(studyId)
        verify(anonymizationService).getConfig(studyId)
    }

    @Test
    fun testGetAnonymizationConfigWithDifferentStudyIds() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val config1 = Mockito.mock(AnonymizationConfig::class.java)
        val config2 = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId1)).thenReturn(config1)
        Mockito.`when`(anonymizationService.getConfig(studyId2)).thenReturn(config2)

        assertEquals(config1, controller.getAnonymizationConfig(studyId1))
        assertEquals(config2, controller.getAnonymizationConfig(studyId2))
    }

    @Test(expected = RuntimeException::class)
    fun testGetAnonymizationConfigPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenThrow(RuntimeException("error"))

        controller.getAnonymizationConfig(studyId)
    }

    // --- updateAnonymizationConfig tests ---

    @Test
    fun testUpdateAnonymizationConfigDelegatesToService() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        val result = controller.updateAnonymizationConfig(studyId, config)
        assertNotNull(result)
        assertEquals(updatedConfig, result)
        verify(anonymizationService).updateConfig(studyId, config)
    }

    @Test
    fun testUpdateAnonymizationConfigReturnsUpdatedConfig() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        val result = controller.updateAnonymizationConfig(studyId, config)
        assertSame(updatedConfig, result)
    }

    @Test
    fun testUpdateAnonymizationConfigPassesCorrectArguments() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        controller.updateAnonymizationConfig(studyId, config)
        verify(anonymizationService).updateConfig(studyId, config)
    }

    @Test(expected = RuntimeException::class)
    fun testUpdateAnonymizationConfigPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenThrow(RuntimeException("error"))

        controller.updateAnonymizationConfig(studyId, config)
    }

    @Test
    fun testUpdateAnonymizationConfigWithMultipleStudies() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val config1 = Mockito.mock(AnonymizationConfig::class.java)
        val config2 = Mockito.mock(AnonymizationConfig::class.java)
        val result1 = Mockito.mock(AnonymizationConfig::class.java)
        val result2 = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId1, config1)).thenReturn(result1)
        Mockito.`when`(anonymizationService.updateConfig(studyId2, config2)).thenReturn(result2)

        assertEquals(result1, controller.updateAnonymizationConfig(studyId1, config1))
        assertEquals(result2, controller.updateAnonymizationConfig(studyId2, config2))
    }

    @Test
    fun testUpdateAnonymizationConfigServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        controller.updateAnonymizationConfig(studyId, config)
        verify(anonymizationService, Mockito.times(1)).updateConfig(studyId, config)
    }

    @Test
    fun testGetAnonymizationConfigServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        controller.getAnonymizationConfig(studyId)
        verify(anonymizationService, Mockito.times(1)).getConfig(studyId)
    }

    @Test
    fun testGetAnonymizationConfigNoOtherInteractions() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        controller.getAnonymizationConfig(studyId)
        verify(anonymizationService).getConfig(studyId)
        Mockito.verifyNoMoreInteractions(anonymizationService)
    }

    @Test
    fun testUpdateAnonymizationConfigNoOtherInteractions() {
        val studyId = UUID.randomUUID()
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        controller.updateAnonymizationConfig(studyId, config)
        verify(anonymizationService).updateConfig(studyId, config)
        Mockito.verifyNoMoreInteractions(anonymizationService)
    }

    @Test
    fun testGetAnonymizationConfigWithNilUuid() {
        val studyId = UUID(0, 0)
        val config = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.getConfig(studyId)).thenReturn(config)

        val result = controller.getAnonymizationConfig(studyId)
        assertNotNull(result)
        verify(anonymizationService).getConfig(studyId)
    }

    @Test
    fun testUpdateAnonymizationConfigWithNilUuid() {
        val studyId = UUID(0, 0)
        val config = Mockito.mock(AnonymizationConfig::class.java)
        val updatedConfig = Mockito.mock(AnonymizationConfig::class.java)
        Mockito.`when`(anonymizationService.updateConfig(studyId, config)).thenReturn(updatedConfig)

        val result = controller.updateAnonymizationConfig(studyId, config)
        assertNotNull(result)
        verify(anonymizationService).updateConfig(studyId, config)
    }
}
