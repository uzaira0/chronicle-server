package com.openlattice.chronicle.mapstores.stats

import com.openlattice.chronicle.participants.ParticipantStats
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class ParticipantStatsMapstoreTest {

    @Test
    fun `participant stats persistence remains write behind for rolling compatibility`() {
        val mapstore = ParticipantStatsMapstore(mock())

        assertEquals(5, mapstore.mapStoreConfig.writeDelaySeconds)
    }

    @Test
    fun `confirmed quarantine rejection is acknowledged without a retryable exception`() {
        val fixture = rejectionFixture(
            SQLException(
                """new row violates row-level security policy "deletion_quarantine_participant_stats"""",
                "42501",
            ),
            deletionBlocked = true,
        )

        fixture.mapstore.store(fixture.key, fixture.value)

        verify(fixture.storeStatement).execute()
        verify(fixture.guardStatement).executeQuery()
    }

    @Test
    fun `nested permanent-erasure rejection is acknowledged when the ledger confirms it`() {
        val wrapper = SQLException("batch wrapper", "XX000")
        wrapper.setNextException(
            SQLException(
                "Participant data mutation is blocked by an erasure operation",
                "55000",
            )
        )
        val fixture = rejectionFixture(wrapper, deletionBlocked = true)

        fixture.mapstore.store(fixture.key, fixture.value)

        verify(fixture.storeStatement).execute()
        verify(fixture.guardStatement).executeQuery()
    }

    @Test
    fun `deletion-shaped SQL failure propagates when durable guard does not confirm it`() {
        val fixture = rejectionFixture(
            SQLException(
                """new row violates row-level security policy "deletion_quarantine_participant_stats"""",
                "42501",
            ),
            deletionBlocked = false,
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.mapstore.store(fixture.key, fixture.value)
        }

        verify(fixture.storeStatement).execute()
        verify(fixture.guardStatement).executeQuery()
    }

    @Test
    fun `unrelated SQL failure remains retryable and never consults the deletion guard`() {
        val dataSource = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val key = ParticipantKey(UUID.randomUUID(), "participant-a")
        val value = ParticipantStats(key.studyId, key.participantId)
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.execute()).thenThrow(SQLException("connection lost", "08006"))
        val mapstore = TestParticipantStatsMapstore(dataSource)

        assertThrows(IllegalStateException::class.java) {
            mapstore.store(key, value)
        }

        verify(dataSource, times(1)).connection
        verify(statement).execute()
    }

    @Test
    fun `guard failure never suppresses a deletion-shaped SQL failure`() {
        val dataSource = mock<HikariDataSource>()
        val storeConnection = mock<Connection>()
        val guardConnection = mock<Connection>()
        val storeStatement = mock<PreparedStatement>()
        val key = ParticipantKey(UUID.randomUUID(), "participant-a")
        val value = ParticipantStats(key.studyId, key.participantId)
        whenever(dataSource.connection).thenReturn(storeConnection, guardConnection)
        whenever(storeConnection.prepareStatement(any())).thenReturn(storeStatement)
        whenever(storeStatement.execute()).thenThrow(
            SQLException(
                """new row violates row-level security policy "deletion_quarantine_participant_stats"""",
                "42501",
            )
        )
        whenever(guardConnection.prepareStatement(any())).thenThrow(
            SQLException("guard unavailable", "08006")
        )
        val mapstore = TestParticipantStatsMapstore(dataSource)

        val failure = assertThrows(IllegalStateException::class.java) {
            mapstore.store(key, value)
        }

        assertEquals(1, failure.cause?.suppressed?.size)
        verify(storeStatement).execute()
    }

    @Test
    fun `store all isolates a quarantined key from allowed writes`() {
        val dataSource = mock<HikariDataSource>()
        val batchConnection = mock<Connection>()
        val rejectedConnection = mock<Connection>()
        val guardConnection = mock<Connection>()
        val allowedConnection = mock<Connection>()
        val batchStatement = mock<PreparedStatement>()
        val rejectedStatement = mock<PreparedStatement>()
        val guardStatement = mock<PreparedStatement>()
        val allowedStatement = mock<PreparedStatement>()
        val guardResult = mock<ResultSet>()
        whenever(dataSource.connection).thenReturn(
            batchConnection,
            rejectedConnection,
            guardConnection,
            allowedConnection,
        )
        whenever(batchConnection.prepareStatement(any())).thenReturn(batchStatement)
        whenever(batchStatement.executeBatch()).thenThrow(
            SQLException(
                """new row violates row-level security policy "deletion_quarantine_participant_stats"""",
                "42501",
            )
        )
        whenever(rejectedConnection.prepareStatement(any())).thenReturn(rejectedStatement)
        whenever(rejectedStatement.execute()).thenThrow(
            SQLException(
                """new row violates row-level security policy "deletion_quarantine_participant_stats"""",
                "42501",
            )
        )
        whenever(guardConnection.prepareStatement(any())).thenReturn(guardStatement)
        whenever(guardStatement.executeQuery()).thenReturn(guardResult)
        whenever(guardResult.next()).thenReturn(true)
        whenever(guardResult.getBoolean(1)).thenReturn(true)
        whenever(allowedConnection.prepareStatement(any())).thenReturn(allowedStatement)
        whenever(allowedStatement.execute()).thenReturn(false)
        val rejectedKey = ParticipantKey(UUID.randomUUID(), "quarantined")
        val allowedKey = ParticipantKey(UUID.randomUUID(), "active")
        val mapstore = TestParticipantStatsMapstore(dataSource)

        mapstore.storeAll(
            linkedMapOf(
                rejectedKey to ParticipantStats(rejectedKey.studyId, rejectedKey.participantId),
                allowedKey to ParticipantStats(allowedKey.studyId, allowedKey.participantId),
            )
        )

        verify(batchStatement).executeBatch()
        verify(rejectedStatement).execute()
        verify(guardStatement).executeQuery()
        verify(allowedStatement).execute()
    }

    @Test
    fun `store all propagates an unrelated batch failure without discarding entries`() {
        val dataSource = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeBatch()).thenThrow(SQLException("connection lost", "08006"))
        val key = ParticipantKey(UUID.randomUUID(), "active")
        val mapstore = TestParticipantStatsMapstore(dataSource)

        assertThrows(IllegalStateException::class.java) {
            mapstore.storeAll(
                mapOf(key to ParticipantStats(key.studyId, key.participantId))
            )
        }

        verify(dataSource, times(1)).connection
        verify(statement).executeBatch()
        verify(statement, times(0)).execute()
    }

    private fun rejectionFixture(
        rejection: SQLException,
        deletionBlocked: Boolean,
    ): RejectionFixture {
        val dataSource = mock<HikariDataSource>()
        val storeConnection = mock<Connection>()
        val guardConnection = mock<Connection>()
        val storeStatement = mock<PreparedStatement>()
        val guardStatement = mock<PreparedStatement>()
        val guardResult = mock<ResultSet>()
        whenever(dataSource.connection).thenReturn(storeConnection, guardConnection)
        whenever(storeConnection.prepareStatement(any())).thenReturn(storeStatement)
        whenever(storeStatement.execute()).thenThrow(rejection)
        whenever(guardConnection.prepareStatement(any())).thenReturn(guardStatement)
        whenever(guardStatement.executeQuery()).thenReturn(guardResult)
        whenever(guardResult.next()).thenReturn(true)
        whenever(guardResult.getBoolean(1)).thenReturn(deletionBlocked)
        val key = ParticipantKey(UUID.randomUUID(), "participant-a")

        return RejectionFixture(
            TestParticipantStatsMapstore(dataSource),
            key,
            ParticipantStats(key.studyId, key.participantId),
            storeStatement,
            guardStatement,
        )
    }

    private data class RejectionFixture(
        val mapstore: ParticipantStatsMapstore,
        val key: ParticipantKey,
        val value: ParticipantStats,
        val storeStatement: PreparedStatement,
        val guardStatement: PreparedStatement,
    )

    private class TestParticipantStatsMapstore(
        dataSource: HikariDataSource,
    ) : ParticipantStatsMapstore(dataSource) {
        override fun bind(
            ps: PreparedStatement,
            key: ParticipantKey,
            value: ParticipantStats,
        ) = Unit
    }
}
