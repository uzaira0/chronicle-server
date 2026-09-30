package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pins the input guard on the iOS SensorKit ingestion path. The jsonb upload-buffer
 * write needs a real DB and is covered by the Testcontainers e2e suite; here we
 * assert with mocked collaborators that an oversized batch is rejected before any
 * storage is touched, and that exactly 10,000 passes the guard. Before this test the
 * service had no coverage.
 */
class SensorDataUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private lateinit var service: SensorDataUploadService

    @Before
    fun setUp() {
        service = SensorDataUploadService(storageResolver, studyService)
    }

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        val sample = Mockito.mock(SensorDataSample::class.java)
        val tooLarge = List(10_001) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), tooLarge)
            fail("Expected upload of ${tooLarge.size} samples to be rejected (max 10,000)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        // Neither collaborator should be touched once the guard trips.
        Mockito.verifyNoInteractions(storageResolver)
        Mockito.verifyNoInteractions(studyService)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandSamplesAtTheBatchBoundary() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        val ids = Mockito.mock(java.sql.Array::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(connection.createArrayOf(Mockito.anyString(), Mockito.any(Array<Any>::class.java))).thenReturn(ids)
        Mockito.`when`(statement.executeQuery()).thenReturn(rows)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        val timestamp = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val sample = SensorDataSample(
            UUID.randomUUID(), timestamp, 0.0,
            """{"totalIncomingCalls":0,"totalOutgoingCalls":0,"totalPhoneDuration":0.0,"totalUniqueContacts":0}""",
            """{"model":"iPhone","name":"test","systemName":"iOS","systemVersion":"26"}""",
            "UTC", SensorType.phoneUsage, timestamp, timestamp,
        )
        val atLimit = List(10_000) { sample }
        assertEquals(10_000, service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), atLimit))
        Mockito.verify(statement).executeUpdate()
    }

    @Test
    fun erasurePredicateFailureNeverAcknowledgesOrQueuesAndRollsBackOwnedTransaction() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val ids = Mockito.mock(java.sql.Array::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.autoCommit).thenReturn(true, false)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(connection.createArrayOf(Mockito.anyString(), Mockito.any(Array<Any>::class.java))).thenReturn(ids)
        Mockito.`when`(statement.executeQuery()).thenThrow(SQLException("simulated erasure lookup outage"))
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        val timestamp = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val sample = SensorDataSample(
            UUID.randomUUID(), timestamp, 0.0,
            """{"totalIncomingCalls":0,"totalOutgoingCalls":0,"totalPhoneDuration":0.0,"totalUniqueContacts":0}""",
            """{"model":"iPhone","name":"test","systemName":"iOS","systemVersion":"26"}""",
            "UTC", SensorType.phoneUsage, timestamp, timestamp,
        )

        assertThrows(SQLException::class.java) {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), listOf(sample))
        }

        Mockito.verify(statement, Mockito.never()).executeUpdate()
        Mockito.verify(connection).rollback()
        Mockito.verify(connection, Mockito.never()).commit()
        Mockito.verify(connection).setAutoCommit(true)
        Mockito.verify(dataSource).connection
    }
}
