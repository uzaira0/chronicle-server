package com.openlattice.chronicle.services.notifications

import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationStatus
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.twilio.TwilioService
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationJobRunnerFailureTest {
    @Test
    fun unsupportedEmailPersistsFailureCancelsJobAndNeverAuditsSent() {
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        val notification = Notification(id = UUID.randomUUID(), studyId = UUID.randomUUID(), participantId = "participant",
            status = "queued", messageId = "", notificationType = NotificationType.ENROLLMENT,
            deliveryType = DeliveryType.EMAIL, body = "message", destination = "researcher@example.org")
        val job = ChronicleJob(securablePrincipalId = UUID.randomUUID(), principal = Principal(PrincipalType.USER, "test"),
            definition = notification)
        val events = NotificationJobRunner(mock()).run(connection, job)
        assertEquals(JobStatus.CANCELED, job.status)
        assertTrue(events.isEmpty())
        verify(statement).setString(2, NotificationStatus.failed.name)
        verify(statement).executeUpdate()
    }
    @Test
    fun failedSmsPersistsFailureWithoutSuccessAuditOrJobCompletion() {
        val twilio = mock<TwilioService>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val notification = Notification(
            id = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            participantId = "participant",
            status = "queued",
            messageId = "",
            notificationType = NotificationType.ENROLLMENT,
            deliveryType = DeliveryType.SMS,
            body = "message",
            destination = "+15555550100",
        )
        whenever(twilio.sendNotification(notification, connection)).thenAnswer {
            notification.status = NotificationStatus.failed.name
            notification
        }
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        val job = ChronicleJob(
            securablePrincipalId = UUID.randomUUID(),
            principal = Principal(PrincipalType.USER, "notification-test"),
            definition = notification,
        )

        val events = NotificationJobRunner(twilio).run(connection, job)

        assertEquals(JobStatus.CANCELED, job.status)
        assertTrue(events.isEmpty())
        val sql = argumentCaptor<String>()
        verify(connection).prepareStatement(sql.capture())
        assertTrue(sql.firstValue.contains("status"))
        assertTrue(sql.firstValue.contains("updated_at"))
        verify(statement).setString(1, "")
        verify(statement).setString(2, NotificationStatus.failed.name)
        verify(statement).setObject(3, notification.updatedAt)
        verify(statement).setObject(4, notification.id)
        verify(statement).executeUpdate()
    }
}
