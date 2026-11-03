package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.study.StudyLifecycleStatus
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
    private val randomDeviceId = UUID.randomUUID()

    @Test
    fun `empty upload is a storage-free no-op`() {
        assertEquals(emptySet<UUID>(), service.upload(studyId, "participant", randomDeviceId, emptyList()).acceptedIds)
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun `rejects oversized and duplicate batches before opening storage`() {
        val event = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", randomDeviceId, List(UploadDiagnosticsUploadService.MAX_EVENTS + 1) { fixture() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", randomDeviceId, listOf(event, event))
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    private val connection = Mockito.mock(Connection::class.java)
    private val lockStatement = Mockito.mock(PreparedStatement::class.java)
    private val cutoffStatement = Mockito.mock(PreparedStatement::class.java)
    private val stateStatement = Mockito.mock(PreparedStatement::class.java)
    private val upsertStatement = Mockito.mock(PreparedStatement::class.java)

    private fun storage(erasureCutoff: OffsetDateTime?, captureCutoff: OffsetDateTime? = null): ResultSet {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer {
            val sql = it.arguments[0] as String
            when {
                sql.contains("pg_advisory_xact_lock_shared") -> lockStatement
                sql.contains("SELECT max(cutoff)") -> cutoffStatement
                sql == ParticipantCollectionGate.WINDOW_QUERY -> stateStatement
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
        Mockito.`when`(state.next()).thenReturn(true)
        Mockito.`when`(state.getString("lifecycle_status")).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        stubNoClosedCaptureWindows(state)
        Mockito.`when`(state.getString("participation_status")).thenReturn(
            if (captureCutoff == null) ParticipationStatus.ENROLLED.name else ParticipationStatus.PAUSED.name)
        Mockito.`when`(state.getObject("capture_cutoff", OffsetDateTime::class.java)).thenReturn(captureCutoff)
        Mockito.`when`(upsertStatement.executeBatch()).thenReturn(intArrayOf(1))
        Mockito.`when`(upsertStatement.connection).thenReturn(connection)
        stubUploadReceiptTime(connection)
        return state
    }

    @Test
    fun `missing enrollment cannot acknowledge or persist diagnostics`() {
        val state = storage(erasureCutoff = null)
        Mockito.`when`(state.next()).thenReturn(false)
        assertThrows(CollectionUnavailableException::class.java) {
            service.upload(studyId, "participant", randomDeviceId, listOf(fixture()))
        }
        Mockito.verify(upsertStatement, Mockito.never()).executeBatch()
    }

    @Test
    fun `acknowledges idempotent upserts only after commit without issuing retention deletes`() {
        storage(erasureCutoff = null)
        val event = fixture()

        repeat(2) {
            assertEquals(setOf(UUID.fromString(event.id)), service.upload(studyId, "participant", randomDeviceId, listOf(event)).acceptedIds)
        }

        Mockito.verify(connection, Mockito.times(2)).commit()
        Mockito.verify(upsertStatement, Mockito.times(2)).executeBatch()
        val sql = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(connection, Mockito.atLeastOnce()).prepareStatement(sql.capture())
        sql.allValues.forEach { query -> assertEquals(false, query.contains("DELETE FROM", ignoreCase = true)) }
    }

    @Test
    fun `events older than a completed erasure are discarded and never stored again`() {
        storage(erasureCutoff = OffsetDateTime.parse("2026-08-27T00:00:00Z"))
        val erased = fixture()
        val later = fixture(firstOccurredAt = OffsetDateTime.parse("2026-08-27T01:00:00Z"))

        assertEquals(setOf(UUID.fromString(erased.id)),
            service.upload(studyId, "participant", randomDeviceId, listOf(erased)).discardedIds)
        Mockito.verify(upsertStatement, Mockito.never()).executeBatch()

        val receipt = service.upload(studyId, "participant", randomDeviceId, listOf(erased, later))
        assertEquals(setOf(UUID.fromString(later.id)), receipt.acceptedIds)
        assertEquals(setOf(UUID.fromString(erased.id)), receipt.discardedIds)
        Mockito.verify(upsertStatement, Mockito.times(1)).addBatch()
        Mockito.verify(upsertStatement).setString(4, later.id)
        Mockito.verify(upsertStatement, Mockito.never()).setString(4, erased.id)
    }

    /** One clock: a paused phone's queued diagnostics are never judged by their phone stamps. */
    @Test
    fun `stopped diagnostics store every queued event whatever its phone stamp`() {
        val cutoff = OffsetDateTime.parse("2026-08-27T00:00:00Z")
        storage(erasureCutoff = null, captureCutoff = cutoff)
        Mockito.`when`(upsertStatement.executeBatch()).thenReturn(intArrayOf(1, 1, 1))
        val queued = listOf(fixture(), fixture(firstOccurredAt = cutoff), fixture(firstOccurredAt = cutoff.plusHours(1)))

        val receipt = service.upload(studyId, "participant", randomDeviceId, queued)
        assertEquals(queued.map { UUID.fromString(it.id) }.toSet(), receipt.acceptedIds)
        assertEquals(emptySet<UUID>(), receipt.discardedIds)
        Mockito.verify(upsertStatement, Mockito.times(3)).addBatch()
        Mockito.verify(connection).commit()
    }

    @Test
    fun `error type that is not a class name is stored as null`() {
        storage(erasureCutoff = null)

        service.upload(studyId, "participant", randomDeviceId, listOf(fixture(errorType = "https://example.org/patient/123")))
        service.upload(studyId, "participant", randomDeviceId, listOf(fixture(errorType = "java.net.ConnectException")))

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
        lastOccurredAt = firstOccurredAt,
        errorType = errorType,
    )
}
