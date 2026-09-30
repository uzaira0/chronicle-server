package com.openlattice.chronicle.storage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.reservations.AclKeyReservationService
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.notifications.Notification
import com.openlattice.chronicle.services.notifications.NotificationJobRunner
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTask
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLTransientConnectionException
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PoolBorrowRegressionTest {
    private class SingleSlot {
        val dataSource = mock<HikariDataSource>()
        val connection = mock<Connection>()
        private val owned = AtomicBoolean()
        init {
            whenever(dataSource.connection).thenAnswer {
                if (!owned.compareAndSet(false, true)) throw SQLTransientConnectionException("single pool slot is owned")
                connection
            }
            Mockito.doAnswer { owned.set(false); null }.`when`(connection).close()
            whenever(connection.createArrayOf(any(), any())).thenReturn(mock())
        }
    }

    @Test
    fun `a pin never substitutes an unrelated datasource`() {
        val first = mock<HikariDataSource>()
        val other = mock<HikariDataSource>()
        PinnedPlatformConnection.pinning(first, mock()) {
            assertSame(other, PinnedPlatformConnection.resolve(other))
        }
    }

    @Test
    fun `identical platform and event pool passes topology without borrowing twice`() {
        val slot = SingleSlot()
        val manager = mock<DataSourceManager>()
        whenever(manager.getDataSource(any())).thenReturn(slot.dataSource)
        whenever(manager.getFlavor(any())).thenReturn(PostgresFlavor.VANILLA)
        val resolver = StorageResolver(manager, ChronicleStorageConfiguration())
        resolver.requireDefaultDeletionStorageColocated()
        Mockito.verify(slot.dataSource, Mockito.never()).connection
    }

    @Test
    fun `acl name conflict lookup reuses the insert connection`() {
        val slot = SingleSlot()
        val resolver = mock<StorageResolver>()
        whenever(resolver.getPlatformStorage()).thenReturn(slot.dataSource)
        val insert = mock<PreparedStatement>()
        val select = mock<PreparedStatement>()
        val insertedRows = mock<ResultSet>()
        val foundRows = mock<ResultSet>()
        val existing = UUID.randomUUID()
        whenever(slot.connection.prepareStatement(any())).thenAnswer {
            if ((it.arguments[0] as String).contains("INSERT INTO")) insert else select
        }
        whenever(insert.executeQuery()).thenReturn(insertedRows)
        whenever(insertedRows.next()).thenReturn(false)
        whenever(select.executeQuery()).thenReturn(foundRows)
        whenever(foundRows.next()).thenReturn(true)
        whenever(foundRows.getObject(PostgresColumns.SECURABLE_OBJECT_ID.name, UUID::class.java)).thenReturn(existing)
        val study = Study(UUID.randomUUID(), "reserved study", contact = "owner")
        assertEquals(existing, AclKeyReservationService(resolver).registerSecurableObject(study))
        assertEquals(existing, study.id)
        Mockito.verify(slot.dataSource, Mockito.times(1)).connection
        Mockito.verify(insertedRows).close()
        Mockito.verify(foundRows).close()
    }

    @Test
    fun `event writer joins an aliased platform transaction`() {
        val slot = SingleSlot()
        val prepared = mock<PreparedStatement>()
        val statement = mock<Statement>()
        val permitted = mock<java.sql.ResultSet>()
        whenever(slot.connection.prepareStatement(any())).thenReturn(prepared)
        whenever(slot.connection.createStatement()).thenReturn(statement)
        whenever(prepared.executeUpdate()).thenReturn(1)
        whenever(prepared.executeQuery()).thenReturn(permitted)
        whenever(permitted.next()).thenReturn(false)
        whenever(permitted.getInt(1)).thenReturn(1)
        val method = MoveToEventStorageTask::class.java.getDeclaredMethod(
            "writeToEventStorage", HikariDataSource::class.java, List::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val entry = UsageEventQueueEntry(UUID.randomUUID(), "participant", emptyMap(), OffsetDateTime.now())
        slot.dataSource.connection.use { owner ->
            PinnedPlatformConnection.pinning(slot.dataSource, owner) {
                assertEquals(1, method.invoke(MoveToEventStorageTask(), slot.dataSource, listOf(entry), false))
            }
            Mockito.verify(owner, Mockito.never()).close()
            Mockito.verify(owner, Mockito.never()).commit()
        }
        Mockito.verify(slot.dataSource, Mockito.times(1)).connection
    }

    @Test
    fun `sms job passes its owned connection to the sender`() {
        val slot = SingleSlot()
        val prepared = mock<PreparedStatement>()
        whenever(slot.connection.prepareStatement(any())).thenReturn(prepared)
        val notification = Notification(
            UUID.randomUUID(), UUID.randomUUID(), "participant", status = "pending", messageId = "",
            notificationType = NotificationType.ENROLLMENT, deliveryType = DeliveryType.SMS,
            body = "message", destination = "+15555550100",
        )
        val sender = Mockito.mock(TwilioService::class.java) { invocation ->
            if (invocation.method.name.startsWith("sendNotification")) {
                if (invocation.arguments.size == 1) slot.dataSource.connection.use { }
                else assertSame(slot.connection, invocation.arguments[1])
                notification
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val job = ChronicleJob(
            securablePrincipalId = UUID.randomUUID(), principal = mock<Principal>(), definition = notification,
        )
        slot.dataSource.connection.use { owner ->
            val runner = object : NotificationJobRunner(sender) {
                fun send(owned: Connection, ownedJob: ChronicleJob) = runJob(owned, ownedJob)
            }
            assertTrue(runner.send(owner, job).isNotEmpty())
            Mockito.verify(prepared).executeUpdate()
        }
        Mockito.verify(slot.dataSource, Mockito.times(1)).connection
    }
}
