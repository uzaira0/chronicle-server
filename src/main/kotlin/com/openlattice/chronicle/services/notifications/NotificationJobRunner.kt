package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.services.jobs.AbstractChronicleJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ID
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class NotificationJobRunner(
    private val twilioService: TwilioService,
) : AbstractChronicleJobRunner<Notification>() {
    internal companion object {
        private val UPDATE_NOTIFICATION_MESSAGE_ID_SQL = """
            UPDATE ${NOTIFICATIONS.name} SET ${MESSAGE_ID.name} = ? WHERE ${NOTIFICATION_ID.name} = ?
        """
        private val logger = LoggerFactory.getLogger(NotificationJobRunner::class.java)
    }

    override fun accepts(): Class<Notification> = Notification::class.java

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {

        val notification = job.definition as Notification
        when (notification.deliveryType) {
            DeliveryType.SMS -> updateWithMessageId(connection, twilioService.sendNotification(notification))
            DeliveryType.EMAIL -> {
                logger.warn("Email delivery is not supported. Dropping EMAIL notification for participant {} in study {}.",
                    notification.participantId, notification.studyId)
                val droppedDescription =
                    "DROPPED: ${notification.deliveryType} notification of type " +
                        "${notification.notificationType} to ${notification.destination} — " +
                        "email delivery not supported (participantId = ${notification.participantId})"
                return listOf(
                    AuditableEvent(
                        AclKey(notification.studyId),
                        job.securablePrincipalId,
                        job.principal,
                        AuditEventType.NOTIFICATION_SENT,
                        droppedDescription,
                        notification.studyId
                    )
                )
            }
        }

        val sentDescription =
            "Sent ${notification.deliveryType} notification of type " +
                "${notification.notificationType} to ${notification.destination} " +
                "(participantId = ${notification.participantId})"
        return listOf(
            AuditableEvent(
                AclKey(notification.studyId),
                job.securablePrincipalId,
                job.principal,
                AuditEventType.NOTIFICATION_SENT,
                sentDescription,
                notification.studyId
            )
        )
    }

    private fun updateWithMessageId(connection: Connection, notification: Notification) {
        connection.prepareStatement(UPDATE_NOTIFICATION_MESSAGE_ID_SQL).use { ps ->
            ps.setString(1, notification.messageId)
            ps.setObject(2, notification.id)
            ps.executeUpdate()
        }
    }
}
