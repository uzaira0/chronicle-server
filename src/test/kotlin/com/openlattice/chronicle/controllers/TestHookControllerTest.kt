package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.services.upload.AppDataUploadService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

class TestHookControllerTest {

    private val uploadService = Mockito.mock(AppDataUploadService::class.java)
    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val request = FlushPipelineRequest(studyId, participantId)

    @Test
    fun disabledTestingModeReturnsNotFoundWithoutFlushing() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = false),
            MockEnvironment(),
        )

        val response = controller.flushPipeline(request)

        assertEquals(404, response.statusCode.value())
        Mockito.verifyNoInteractions(uploadService)
    }

    @Test
    fun enabledTestingModeFlushesPipeline() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = true),
            MockEnvironment(),
        )

        val response = controller.flushPipeline(request)

        assertEquals(200, response.statusCode.value())
        assertEquals("flushed", response.body?.get("status"))
        Mockito.verify(uploadService).moveToEventStorage(studyId, participantId)
    }

    @Test
    fun productionProfileRequiresExplicitOverride() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = true),
            MockEnvironment().withProperty("spring.profiles.active", "production").also {
                it.setActiveProfiles("production")
            },
        )

        val response = controller.flushPipeline(request)

        assertEquals(404, response.statusCode.value())
        Mockito.verifyNoInteractions(uploadService)
    }
}
