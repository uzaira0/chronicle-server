package com.openlattice.chronicle.services.twilio

import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.notifications.Notification
import com.openlattice.chronicle.services.notifications.NotificationJobRunner
import com.openlattice.chronicle.services.studies.StudyService
import com.twilio.rest.api.v2010.account.Message
import com.twilio.rest.api.v2010.account.MessageCreator
import com.twilio.type.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLTransientConnectionException
import java.util.UUID

class TwilioPoolTest {
    @Test
    fun `enabled sms uses the job connection for its study phone lookup`() {
        val owner = mock<Connection>()
        val pool = mock<com.zaxxer.hikari.HikariDataSource>()
        whenever(pool.connection).thenThrow(SQLTransientConnectionException("single pool slot is owned by the job"))
        val resolver = object : com.openlattice.chronicle.storage.StorageResolver(
            mock(), com.openlattice.chronicle.configuration.ChronicleStorageConfiguration(),
        ) {
            override fun getPlatformStorage(requiredFlavor: com.geekbeast.configuration.postgres.PostgresFlavor) =
                com.openlattice.chronicle.storage.PinnedPlatformConnection.resolve(pool)
        }
        val hazelcast = mock<com.hazelcast.core.HazelcastInstance>()
        val studies = mock<com.hazelcast.map.IMap<UUID, com.openlattice.chronicle.study.Study>>()
        whenever(hazelcast.getMap<UUID, com.openlattice.chronicle.study.Study>("STUDIES")).thenReturn(studies)
        val studyService = StudyService(
            resolver, mock(), mock(), mock(), mock(), mock(), mock(), mock(), hazelcast, mock(), mock(),
        )
        val update = mock<PreparedStatement>()
        val legacy = mock<PreparedStatement>()
        val guard = mock<PreparedStatement>()
        val phone = mock<PreparedStatement>()
        val legacyRows = mock<java.sql.ResultSet>()
        val guardRows = mock<java.sql.ResultSet>()
        val phoneRows = mock<java.sql.ResultSet>()
        val resolvedStudyId = UUID.randomUUID()
        whenever(owner.createArrayOf(any(), any())).thenReturn(mock())
        whenever(owner.prepareStatement(any())).thenAnswer {
            val sql = it.arguments[0] as String
            when {
                sql.contains("legacy_study_ids") -> legacy
                sql.contains("unnest") -> guard
                sql.contains("study_phone_number") -> phone
                else -> update
            }
        }
        whenever(legacy.executeQuery()).thenReturn(legacyRows)
        whenever(legacyRows.next()).thenReturn(true)
        whenever(legacyRows.getObject("study_id", UUID::class.java)).thenReturn(resolvedStudyId)
        whenever(guard.executeQuery()).thenReturn(guardRows)
        whenever(guardRows.next()).thenReturn(false)
        whenever(phone.executeQuery()).thenReturn(phoneRows)
        whenever(phoneRows.next()).thenReturn(true)
        whenever(phoneRows.getString(1)).thenReturn("+15555550102")
        val notification = Notification(
            UUID.randomUUID(), UUID.randomUUID(), "participant", status = "pending", messageId = "",
            notificationType = NotificationType.ENROLLMENT, deliveryType = DeliveryType.SMS,
            body = "message", destination = "+15555550100",
        )
        val creator = mock<MessageCreator>()
        val message = mock<Message>()
        whenever(creator.setStatusCallback(any<java.net.URI>())).thenReturn(creator)
        whenever(creator.create()).thenReturn(message)
        whenever(message.sid).thenReturn("SM-test-message")
        Mockito.mockStatic(Message::class.java).use { messages ->
            messages.`when`<MessageCreator> {
                Message.creator(any<PhoneNumber>(), any<PhoneNumber>(), any<String>())
            }.thenReturn(creator)
            val sender = TwilioService(
                TwilioConfiguration(
                    enabled = true, sid = "AC00000000000000000000000000000000", token = "test-token",
                    defaultFromPhone = "+15555550101", callbackBaseUrl = "https://localhost",
                ), studyService,
            )
            val runner = object : NotificationJobRunner(sender) {
                fun send(job: ChronicleJob) = runJob(owner, job)
            }
            runner.send(ChronicleJob(
                securablePrincipalId = UUID.randomUUID(), principal = mock(), definition = notification,
            ))
            assertEquals("SM-test-message", notification.messageId)
            Mockito.verify(phone).setObject(1, resolvedStudyId)
            Mockito.verify(pool, Mockito.never()).connection
            Mockito.verify(studies, Mockito.never()).get(any())
            messages.verify {
                Message.creator(PhoneNumber(notification.destination), PhoneNumber("+15555550102"), notification.body)
            }
            Mockito.verify(update).executeUpdate()
            Mockito.verify(owner, Mockito.never()).close()
        }
    }
}
