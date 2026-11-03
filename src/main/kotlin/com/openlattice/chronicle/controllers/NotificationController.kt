package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.notifications.NotificationApi
import com.openlattice.chronicle.notifications.NotificationApi.Companion.CONTROLLER
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STATUS_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STUDY_ID
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.requireStudyId
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.*
import jakarta.inject.Inject


/**
 * @author Matthew Tamayo-Rios <matthew@openlattice.com>
 * @author Todd Bergman <todd@openlattice.com>
 */

@RestController
@RequestMapping(CONTROLLER)
public open class NotificationController @Inject constructor(
    public val storageResolver: StorageResolver,
    public val idGenerationService: HazelcastIdGenerationService,
    public val notificationService: NotificationService,
    public val studyService: StudyService,
    private val twilioWebhookSignatureVerifier: TwilioWebhookSignatureVerifier,
    override val auditingManager: AuditingManager,
    override val authorizationManager: AuthorizationManager,
) : NotificationApi, AuthorizingComponent {

    

    

    

    

    

    

    

    

    @RequestMapping(
        path = [STUDY_ID_PATH],
        method = [RequestMethod.POST]
    )
    override fun sendNotifications(
        @PathVariable(value = STUDY_ID) studyId: UUID,
        @Valid @RequestBody @Size(max = 10_000) participantNotificationList: List<ParticipantNotification>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        studyService.requireStudyId(studyId)
        throw ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, Messages.get("error.notification.deliveryUnsupported"))
    }

    @RequestMapping(
        path = [STATUS_PATH],
        method = [RequestMethod.POST]
    )
    @ResponseStatus(HttpStatus.OK)
    override fun updateNotificationStatus(
        @RequestParam(value = "MessageSid") messageId: String,
        @RequestParam(value = "MessageStatus") messageStatus: String,
    ): OK {
        if (!twilioWebhookSignatureVerifier.isCurrentRequestValid()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.webhook.twilioSignatureInvalid"))
        }
        RLSRequestContext.withSystemContext {
            notificationService.updateNotificationStatus(messageId, messageStatus)
        }
        return ok

    }

    

}
