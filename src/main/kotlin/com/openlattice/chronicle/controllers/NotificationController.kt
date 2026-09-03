package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.base.OK.Companion.ok
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationApi
import com.openlattice.chronicle.notifications.NotificationApi.Companion.CONTROLLER
import com.openlattice.chronicle.notifications.NotificationApi.Companion.NOTIFICATIONS_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.NOTIFICATION_TYPE
import com.openlattice.chronicle.notifications.NotificationApi.Companion.NOTIFICATION_TYPE_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.PHONE_NUMBERS_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.PHONE_NUMBER
import com.openlattice.chronicle.notifications.NotificationApi.Companion.PHONE_NUMBER_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.notifications.NotificationApi.Companion.PRINCIPAL_ID_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STUDY_ID_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STATUS_PATH
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STUDY_ID
import com.openlattice.chronicle.notifications.NotificationApi.Companion.VERIFICATION_PATH
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
@Validated
@RateLimit(type = RateLimitType.DEFAULT)
public open class NotificationController @Inject constructor(
    public val storageResolver: StorageResolver,
    public val idGenerationService: HazelcastIdGenerationService,
    public val notificationService: NotificationService,
    public val studyService: StudyService,
    private val twilioWebhookSignatureVerifier: TwilioWebhookSignatureVerifier,
    override val auditingManager: AuditingManager,
    override val authorizationManager: AuthorizationManager,
) : NotificationApi, AuthorizingComponent {

    @GetMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + PHONE_NUMBERS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getResearcherPhoneNumber(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
    ): String {
        ensureReadAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        return notificationService.getResearcherPhoneNumber(principalId)
    }

    @PutMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + PHONE_NUMBER_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun setResearcherPhoneNumber(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @PathVariable(PHONE_NUMBER) phoneNumber: String,
    ) {
        ensureWriteAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        notificationService.setResearcherPhoneNumber(principalId, phoneNumber)
    }

    @PostMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + PHONE_NUMBER_PATH + VERIFICATION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun verifyResearcherPhoneNumber(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @PathVariable(PHONE_NUMBER) phoneNumber: String,
        @Valid @RequestBody confirmationCode: String,
    ) {
        ensureWriteAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        notificationService.verifyResearcherPhoneNumber(principalId, phoneNumber)
    }

    @GetMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + PHONE_NUMBER_PATH + VERIFICATION_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun isResearcherPhoneNumberVerified(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @PathVariable(PHONE_NUMBER) phoneNumber: String,
    ): Boolean {
        ensureReadAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        return notificationService.isResearcherPhoneNumberVerified(principalId, phoneNumber)
    }

    @GetMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + NOTIFICATIONS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getResearcherNotificationSettings(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
    ): Map<NotificationType, Set<DeliveryType>> {
        ensureReadAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        return notificationService.getResearcherNotificationSettings(studyId, principalId)
    }

    @PutMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + NOTIFICATIONS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun setResearcherNotificationSettings(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @Valid @RequestBody settings: Map<NotificationType, Set<DeliveryType>>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        notificationService.setResearcherNotificationSettings(studyId, principalId, settings)
        return ok
    }

    @PutMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + NOTIFICATION_TYPE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun setResearcherNotificationSettings(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @PathVariable(NOTIFICATION_TYPE) notificationType: NotificationType,
        @Valid @RequestBody deliveryTypes: Set<DeliveryType>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        val existing = notificationService.getResearcherNotificationSettings(studyId, principalId).toMutableMap()
        existing[notificationType] = deliveryTypes
        notificationService.setResearcherNotificationSettings(studyId, principalId, existing)
        return ok
    }

    @GetMapping(
        path = [STUDY_ID_PATH + PRINCIPAL_ID_PATH + NOTIFICATION_TYPE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getResearcherNotificationSetting(
        @PathVariable(STUDY_ID) studyId: UUID,
        @PathVariable(PRINCIPAL_ID) principalId: String,
        @PathVariable(NOTIFICATION_TYPE) notificationType: NotificationType,
    ): Set<DeliveryType> {
        ensureReadAccess(AclKey(studyId))
        ensureSelfOrAdmin(principalId)
        val settings = notificationService.getResearcherNotificationSettings(studyId, principalId)
        return settings[notificationType] ?: emptySet()
    }

    @RequestMapping(
        path = [STUDY_ID_PATH],
        method = [RequestMethod.POST]
    )
    override fun sendNotifications(
        @PathVariable(value = STUDY_ID) studyId: UUID,
        @Valid @RequestBody @Size(max = 10_000) participantNotificationList: List<ParticipantNotification>,
    ): OK {
        ensureWriteAccess(AclKey(studyId))
        check(studyService.isValidStudy(studyId)) { "Invalid study id specified." }
        //Make sure the calling user has permission to send notifications to all the acl keys.
        notificationService.sendNotifications(studyId, participantNotificationList)
        return ok
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
        notificationService.updateNotificationStatus(messageId, messageStatus)
        return ok

    }

    private fun ensureSelfOrAdmin(principalId: String) {
        val currentPrincipalId = Principals.getCurrentUser().id
        if (principalId != currentPrincipalId && !isAdmin()) {
            throw ForbiddenException("Cannot manage notification settings for another principal.")
        }
    }

}
