package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.ArgumentCaptor
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class UploadDiagnosticsUploadServiceTest {
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val service = UploadDiagnosticsUploadService(storageResolver)
    private val studyId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()

    @Test
    fun `empty upload is a storage-free no-op`() {
        assertEquals(emptyList<String>(), service.upload(studyId, "participant", deviceId, emptyList()))
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun `rejects oversized and duplicate batches before opening storage`() {
        val event = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", deviceId, List(501) { fixture() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", deviceId, listOf(event, event))
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    private val connection = Mockito.mock(Connection::class.java)
    private val lockStatement = Mockito.mock(PreparedStatement::class.java)
    private val cutoffStatement = Mockito.mock(PreparedStatement::class.java)
    private val stateStatement = Mockito.mock(PreparedStatement::class.java)
    private val upsertStatement = Mockito.mock(PreparedStatement::class.java)

    private fun storage(erasureCutoff: OffsetDateTime?, captureCutoff: OffsetDateTime? = null) {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer {
            val sql = it.arguments[0] as String
            when {
                sql.contains("pg_advisory_xact_lock_shared") -> lockStatement
                sql.contains("SELECT max(cutoff)") -> cutoffStatement
                sql.contains("LEFT JOIN LATERAL") -> stateStatement
                else -> upsertStatement
            }
        }
        val lockResultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(lockStatement.executeQuery()).thenReturn(lockResultSet)
        Mockito.`when`(lockResultSet.next()).thenReturn(true)
        Mockito.`when`(cutoffStatement.executeQuery()).thenReturn(resultSet)
        Mockito.`when`(resultSet.next()).thenReturn(true)
        Mockito.`when`(resultSet.getObject(1, OffsetDateTime::class.java)).thenReturn(erasureCutoff)
        val state = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(stateStatement.executeQuery()).thenReturn(state)
        Mockito.`when`(state.next()).thenReturn(captureCutoff != null)
        Mockito.`when`(state.getString("participation_status")).thenReturn("PAUSED")
        Mockito.`when`(state.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(captureCutoff)
        Mockito.`when`(upsertStatement.executeBatch()).thenReturn(intArrayOf(1))
    }

    @Test
    fun `acknowledges idempotent upserts only after commit without issuing retention deletes`() {
        storage(erasureCutoff = null)
        val event = fixture()

        repeat(2) {
            assertEquals(listOf(event.id), service.upload(studyId, "participant", deviceId, listOf(event)))
        }

        Mockito.verify(connection, Mockito.times(2)).commit()
        Mockito.verify(upsertStatement, Mockito.times(2)).executeBatch()
        val sql = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(connection, Mockito.atLeastOnce()).prepareStatement(sql.capture())
        sql.allValues.forEach { query -> assertEquals(false, query.contains("DELETE FROM", ignoreCase = true)) }
    }

    @Test
    fun `events older than a completed erasure are acknowledged but never stored again`() {
        storage(erasureCutoff = OffsetDateTime.parse("2026-08-27T00:00:00Z"))
        val erased = fixture()
        val later = fixture(firstOccurredAt = OffsetDateTime.parse("2026-08-27T01:00:00Z"))

        assertEquals(listOf(erased.id), service.upload(studyId, "participant", deviceId, listOf(erased)))
        Mockito.verify(upsertStatement, Mockito.never()).executeBatch()

        assertEquals(listOf(erased.id, later.id), service.upload(studyId, "participant", deviceId, listOf(erased, later)))
        Mockito.verify(upsertStatement, Mockito.times(1)).addBatch()
        Mockito.verify(upsertStatement).setString(4, later.id)
        Mockito.verify(upsertStatement, Mockito.never()).setString(4, erased.id)
    }

    @Test
    fun `stopped diagnostics acknowledge every id but persist only pre-stop events`() {
        val cutoff = OffsetDateTime.parse("2026-08-27T00:00:00Z")
        storage(erasureCutoff = null, captureCutoff = cutoff)
        val before = fixture()
        val atStop = fixture(firstOccurredAt = cutoff.minusSeconds(1))
        val later = fixture(firstOccurredAt = cutoff)

        assertEquals(listOf(before.id, atStop.id, later.id),
            service.upload(studyId, "participant", deviceId, listOf(before, atStop, later)))
        Mockito.verify(upsertStatement).addBatch()
        Mockito.verify(upsertStatement).setString(4, before.id)
        Mockito.verify(upsertStatement, Mockito.never()).setString(4, atStop.id)
        Mockito.verify(upsertStatement, Mockito.never()).setString(4, later.id)
        Mockito.verify(connection).commit()
    }

    @Test
    fun `error type that is not a class name is stored as null`() {
        storage(erasureCutoff = null)

        service.upload(studyId, "participant", deviceId, listOf(fixture(errorType = "https://example.org/patient/123")))
        service.upload(studyId, "participant", deviceId, listOf(fixture(errorType = "java.net.ConnectException")))

        Mockito.verify(upsertStatement).setString(12, null)
        Mockito.verify(upsertStatement).setString(12, "java.net.ConnectException")
    }

    private fun fixture(
        id: String = UUID.randomUUID().toString(),
        firstOccurredAt: OffsetDateTime = OffsetDateTime.parse("2026-08-26T12:00:00Z"),
        errorType: String = "ConnectException",
    ): AndroidUploadDiagnosticEvent = AndroidUploadDiagnosticEvent(
        id = id,
        day = LocalDate.parse("2026-08-26"),
        moduleFamily = "USAGE_LIFECYCLE",
        issueCode = "CONNECTION_FAILURE",
        count = 1,
        firstOccurredAt = firstOccurredAt,
        lastOccurredAt = firstOccurredAt.plusSeconds(1),
        errorType = errorType,
    )
}
