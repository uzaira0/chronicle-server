package com.openlattice.chronicle.temporal.activities

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.services.notifications.Notification
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ID
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.stereotype.Component
import java.util.UUID

@Component
public open class NotificationActivitiesImpl(
    private val twilioService: TwilioService,
    private val storageResolver: StorageResolver,
    private val auditingManager: AuditingManager,
) : NotificationActivities {

    override fun sendSms(studyId: UUID, participantId: UUID, destination: String, body: String): String {
        val notification = Notification(
            id = UUID.randomUUID(),
            studyId = studyId,
            participantId = participantId.toString(),
            status = "PENDING",
            messageId = "",
            notificationType = NotificationType.OPERATIONAL_CHECKS,
            deliveryType = DeliveryType.SMS,
            body = body,
            destination = destination,
        )
        val result = twilioService.sendNotification(notification)
        return result.messageId
    }

    override fun recordMessageId(notificationId: UUID, messageId: String) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(UPDATE_NOTIFICATION_MESSAGE_ID_SQL).use { ps ->
                ps.setString(1, messageId)
                ps.setObject(2, notificationId)
                ps.executeUpdate()
            }
        }
    }

    override fun auditNotification(
        studyId: UUID,
        participantId: UUID,
        destination: String,
        deliveryType: String,
        notificationType: String,
        principalId: String,
        securablePrincipalId: UUID,
        dropped: Boolean,
    ) {
        val prefix = if (dropped) "DROPPED: " else "Sent "
        val description = "${prefix}${deliveryType} notification of type ${notificationType} to ${destination} (participantId = ${participantId})"
        val event = AuditableEvent(
            AclKey(studyId),
            securablePrincipalId,
            Principal(PrincipalType.USER, principalId),
            AuditEventType.NOTIFICATION_SENT,
            description,
            studyId,
        )
        auditingManager.recordEvents(listOf(event))
    }

    internal companion object {
        private val UPDATE_NOTIFICATION_MESSAGE_ID_SQL = """
            UPDATE ${NOTIFICATIONS.name} SET ${MESSAGE_ID.name} = ? WHERE ${NOTIFICATION_ID.name} = ?
        """
    }
}
