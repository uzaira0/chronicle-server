package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.webhooks.WebhookRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import java.util.UUID

class WebhookControllerTest {

    private val webhookService = Mockito.mock(WebhookService::class.java)
    private val controller = WebhookController(webhookService)

    @Test
    fun testListWebhooksReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val expected = emptyList<WebhookRegistration>()
        Mockito.`when`(webhookService.listWebhooks(studyId)).thenReturn(expected)

        val result = controller.listWebhooks(studyId)

        assertNotNull(result)
        assertEquals(0, result.size)
    }

    @Test
    fun testDeleteWebhookDelegatesToService() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()
        controller.deleteWebhook(studyId, webhookId)
        verify(webhookService).deleteWebhook(studyId, webhookId)
    }

    @Test
    fun testTestWebhookDelegatesToService() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()
        controller.testWebhook(studyId, webhookId)
        verify(webhookService).testWebhook(studyId, webhookId)
    }

    @Test
    fun testUnavailableNestedWebhookMapsToGeneric404() {
        val result = controller.handleWebhookNotFound()

        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals(HttpStatus.NOT_FOUND.value(), result.body?.status)
        assertEquals("The requested resource was not found", result.body?.message)
    }
}
