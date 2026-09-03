package com.openlattice.chronicle.services.twilio

import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.services.notifications.Notification
import com.openlattice.chronicle.notifications.NotificationApi.Companion.BASE
import com.openlattice.chronicle.notifications.NotificationApi.Companion.STATUS_PATH
import com.openlattice.chronicle.notifications.NotificationStatus
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.util.LogSanitizer
import com.twilio.Twilio
import com.twilio.exception.ApiException
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import org.apache.commons.lang3.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.util.*

/**
 * @author Matthew Tamayo-Rios <matthew@openlattice.com>
 * @author Todd Bergman <todd@openlattice.com>
 */

@Service
public open class TwilioService(
    twilioConfiguration: TwilioConfiguration,
    private val studyService: StudyService,
) : TwilioManager {
    public companion object {
        protected val logger: Logger = LoggerFactory.getLogger(TwilioService::class.java)
    }

    private val enabled: Boolean = twilioConfiguration.enabled
    protected val callbackURL: String = "${twilioConfiguration.callbackBaseUrl}$BASE$STATUS_PATH"
    protected val defaultPhoneNumber: PhoneNumber? = twilioConfiguration.defaultFromPhone
        .takeIf { enabled }
        ?.let(::PhoneNumber)
    //"https://api.openlattice.com$BASE$STATUS_PATH"
    //protected val testingCallbackURL :String = "http://da67-75-26-16-62.ngrok.io$BASE$STATUS_PATH"


    init {
        if (enabled) {
            Twilio.init(twilioConfiguration.sid, twilioConfiguration.token)
        }
    }

    public fun sendNotification(notification: Notification): Notification {
        val participantRef = LogSanitizer.stableFingerprint(notification.participantId, "participant")
        if (!enabled) {
            logger.warn("Twilio is disabled; notification for {} was not sent", participantRef)
            notification.status = NotificationStatus.failed.name
            return notification
        }
        try {
            val message = Message
                .creator(PhoneNumber(notification.destination), getStudyPhoneNumber(notification.studyId), notification.body)
                .setStatusCallback(URI.create(callbackURL))
                .create()
            logger.info(
                "message sent to {} for study {}",
                participantRef,
                notification.studyId,
            )
            notification.messageId = message.sid
        } catch (e: ApiException) {
            logger.error(
                (
                    "Unable to send message of type ${notification.notificationType} " +
                        "to $participantRef " +
                        "in study ${notification.studyId}"
                    ).trimIndent(),
                e
            )
            notification.status = NotificationStatus.failed.name
        }
        return notification
    }

    override fun sendNotifications(notifications: List<Notification>): List<Notification> {
        return notifications.map { notification -> sendNotification(notification) }
    }

    override fun getStudyPhoneNumber(studyId: UUID): PhoneNumber {
        val phoneNumber = studyService.getStudyPhoneNumber(studyId)

        //Default phone number is only for one way communication.
        return if (StringUtils.isBlank(phoneNumber)) {
            checkNotNull(defaultPhoneNumber) { "Twilio is disabled" }
        } else {
            PhoneNumber(phoneNumber)
        }
    }
}
