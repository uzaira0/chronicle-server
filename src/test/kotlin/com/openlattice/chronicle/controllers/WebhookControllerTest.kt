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
    private val auditService = Mockito.mock(com.openlattice.chronicle.audit.AuditService::class.java)
    private val controller = WebhookController(webhookService, auditService)

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
        stubRegistration(studyId, webhookId)
        controller.deleteWebhook(studyId, webhookId)
        verify(webhookService).deleteWebhook(studyId, webhookId)
        assertAudit(com.openlattice.chronicle.audit.AuditAction.DELETE, studyId)
    }

    @Test
    fun testTestWebhookDelegatesToService() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()
        stubRegistration(studyId, webhookId)
        controller.testWebhook(studyId, webhookId)
        verify(webhookService).testWebhook(studyId, webhookId)
        assertAudit(com.openlattice.chronicle.audit.AuditAction.CONFIGURATION_CHANGE, studyId)
    }

    @Test
    fun creatingWebhookAuditsTheDestinationHost() {
        TestSecurityUtils.setupSecurityContext()
        try {
            val registration = stubRegistration(UUID.randomUUID(), UUID.randomUUID())
            val request = com.openlattice.chronicle.webhooks.WebhookCreateRequest(url = registration.url,
                secret = "test-secret", eventTypes = emptySet())
            org.mockito.kotlin.whenever(webhookService.createWebhook(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()))
                .thenReturn(registration)
            controller.createWebhook(registration.studyId, request)
            assertAudit(com.openlattice.chronicle.audit.AuditAction.CREATE, registration.studyId)
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
    }

    private fun stubRegistration(studyId: UUID, webhookId: UUID): WebhookRegistration {
        val registration = WebhookRegistration(webhookId = webhookId, studyId = studyId, url = "https://example.org/path?private=value")
        Mockito.`when`(webhookService.listWebhooks(studyId)).thenReturn(listOf(registration))
        return registration
    }

    private fun assertAudit(action: com.openlattice.chronicle.audit.AuditAction, studyId: UUID) {
        val audit = org.mockito.kotlin.argumentCaptor<com.openlattice.chronicle.audit.AuditLogEntry>()
        verify(auditService).log(audit.capture())
        assertEquals(action, audit.firstValue.action)
        assertEquals(studyId, audit.firstValue.studyId)
        assertEquals("example.org", audit.firstValue.additionalData?.get("destinationHost"))
    }

    @Test
    fun testUnavailableNestedWebhookMapsToGeneric404() {
        val result = controller.handleWebhookNotFound()

        assertEquals(HttpStatus.NOT_FOUND, result.statusCode)
        assertEquals(HttpStatus.NOT_FOUND.value(), result.body?.status)
        assertEquals("The requested resource was not found", result.body?.message)
    }
}
