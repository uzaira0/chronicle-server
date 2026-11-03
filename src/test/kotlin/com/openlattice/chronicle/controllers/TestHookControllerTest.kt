package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

class TestHookControllerTest {

    private val uploadService = Mockito.mock(AppDataUploadService::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val backdate = BackdateEnrollmentRequest(UUID.randomUUID(), "participant", 3)

    @Test
    fun disabledTestingModeReturnsNotFoundWithoutFlushing() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = false),
            MockEnvironment(),
            storageResolver,
        )

        val response = controller.flushPipeline()

        assertEquals(404, response.statusCode.value())
        Mockito.verifyNoInteractions(uploadService)
        assertEquals(404, controller.backdateEnrollment(backdate).statusCode.value())
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun enabledTestingModeFlushesPipeline() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = true),
            MockEnvironment(),
            storageResolver,
        )

        val response = controller.flushPipeline()

        assertEquals(200, response.statusCode.value())
        assertEquals("flushed", response.body?.get("status"))
        Mockito.verify(uploadService).drainEventStorage()
    }

    @Test
    fun productionProfileRequiresExplicitOverride() {
        val controller = TestHookController(
            uploadService,
            ChronicleAuthConfiguration(testingLoginEnabled = true),
            MockEnvironment().withProperty("spring.profiles.active", "production").also {
                it.setActiveProfiles("production")
            },
            storageResolver,
        )

        val response = controller.flushPipeline()

        assertEquals(404, response.statusCode.value())
        Mockito.verifyNoInteractions(uploadService)
        assertEquals(404, controller.backdateEnrollment(backdate).statusCode.value())
        Mockito.verifyNoInteractions(storageResolver)
    }
}
