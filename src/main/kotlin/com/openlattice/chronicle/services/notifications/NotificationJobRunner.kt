package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationStatus
import com.openlattice.chronicle.util.LogSanitizer
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.services.jobs.AbstractChronicleJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATIONS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.MESSAGE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NOTIFICATION_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STATUS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPDATED_AT
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class NotificationJobRunner(
    private val twilioService: TwilioService,
) : AbstractChronicleJobRunner<Notification>() {
    internal companion object {
        private val UPDATE_NOTIFICATION_DELIVERY_SQL = """
            UPDATE ${NOTIFICATIONS.name}
            SET ${MESSAGE_ID.name} = ?, ${STATUS.name} = ?, ${UPDATED_AT.name} = ?
            WHERE ${NOTIFICATION_ID.name} = ?
        """
        private val logger = LoggerFactory.getLogger(NotificationJobRunner::class.java)
    }

    override fun accepts(): Class<Notification> = Notification::class.java

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {

        val notification = job.definition as Notification
        when (notification.deliveryType) {
            DeliveryType.SMS -> {
                val result = twilioService.sendNotification(notification, connection)
                result.updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
                if (result.status == NotificationStatus.failed.name) {
                    updateNotificationDelivery(connection, result)
                    job.status = JobStatus.CANCELED
                    return emptyList()
                }
                // Keep Twilio's status; its delivery callback advances it from there.
                updateNotificationDelivery(connection, result)
            }
            DeliveryType.EMAIL -> {
                logger.warn("Email delivery is not supported. Dropping EMAIL notification for {} in study {}.",
                    LogSanitizer.stableFingerprint(notification.participantId, "participant"), notification.studyId)
                notification.status = NotificationStatus.failed.name
                notification.updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
                updateNotificationDelivery(connection, notification)
                job.status = JobStatus.CANCELED
                return emptyList()
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

    private fun updateNotificationDelivery(connection: Connection, notification: Notification) {
        connection.prepareStatement(UPDATE_NOTIFICATION_DELIVERY_SQL).use { ps ->
            ps.setString(1, notification.messageId)
            ps.setString(2, notification.status)
            ps.setObject(3, notification.updatedAt)
            ps.setObject(4, notification.id)
            ps.executeUpdate()
        }
    }
}
