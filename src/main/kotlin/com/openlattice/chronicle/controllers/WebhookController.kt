package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.services.webhooks.WebhookNotFoundException
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.webhooks.WebhookApi
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookDeliveryInfo
import com.openlattice.chronicle.webhooks.WebhookRegistration
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class WebhookController(
    private val webhookService: WebhookService,
    private val auditService: AuditService,
) : WebhookApi {

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + WebhookApi.WEBHOOKS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun createWebhook(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid request: WebhookCreateRequest
    ): WebhookRegistration {
        val userId = Principals.getCurrentUser().id
        return webhookService.createWebhook(studyId, userId, request).also {
            auditWebhook(AuditAction.CREATE, it)
        }
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + WebhookApi.WEBHOOKS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun listWebhooks(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): List<WebhookRegistration> {
        return webhookService.listWebhooks(studyId)
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @DeleteMapping(
        path = [StudyApi.STUDY_ID_PATH + WebhookApi.WEBHOOKS_PATH + WebhookApi.WEBHOOK_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun deleteWebhook(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(WebhookApi.WEBHOOK_ID) webhookId: UUID
    ): OK {
        val registration = registration(studyId, webhookId)
        webhookService.deleteWebhook(studyId, webhookId)
        auditWebhook(AuditAction.DELETE, registration)
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + WebhookApi.WEBHOOKS_PATH + WebhookApi.WEBHOOK_ID_PATH + WebhookApi.DELIVERIES_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getDeliveries(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(WebhookApi.WEBHOOK_ID) webhookId: UUID
    ): List<WebhookDeliveryInfo> {
        return webhookService.getDeliveries(studyId, webhookId)
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + WebhookApi.WEBHOOKS_PATH + WebhookApi.WEBHOOK_ID_PATH + WebhookApi.TEST_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun testWebhook(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(WebhookApi.WEBHOOK_ID) webhookId: UUID
    ): OK {
        val registration = registration(studyId, webhookId)
        webhookService.testWebhook(studyId, webhookId)
        auditWebhook(AuditAction.CONFIGURATION_CHANGE, registration)
        return OK.ok
    }

    @ExceptionHandler(WebhookNotFoundException::class)
    public fun handleWebhookNotFound(): ResponseEntity<ApiError> {
        return ResponseEntity(ApiError.notFound(), HttpStatus.NOT_FOUND)
    }

    private fun registration(studyId: UUID, webhookId: UUID): WebhookRegistration =
        webhookService.listWebhooks(studyId).firstOrNull { it.webhookId == webhookId } ?: throw WebhookNotFoundException()

    private fun auditWebhook(action: AuditAction, registration: WebhookRegistration) {
        auditService.logWithContext {
            action(action)
            resourceType("Webhook")
            resourceId(registration.webhookId)
            studyId(registration.studyId)
            success(true)
            additionalData(mapOf("destinationHost" to WebhookService.parseDeliveryUrl(registration.url).host))
        }
    }
}
