package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.UsageEnrollmentFixtures.UPLOAD_ROW_CAP
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pins the input guards on the battery_telemetry ingestion path (the newest
 * collection module). Two guards matter and are asserted with mocked storage:
 *  - an empty batch short-circuits to 0 WITHOUT opening a DB connection, and
 *  - an oversized batch (> UPLOAD_ROW_CAP) is rejected before any storage is touched.
 * The per-row insert + ON CONFLICT idempotency need a real DB and are covered by
 * the Testcontainers e2e suite. Before this test the service had no coverage.
 */
class BatteryTelemetryUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private lateinit var service: BatteryTelemetryUploadService
    private val deviceId = UUID.randomUUID()

    @Before
    fun setUp() {
        service = BatteryTelemetryUploadService(storageResolver)
    }

    @Test
    fun emptyBatchReturnsZeroWithoutTouchingStorage() {
        val result = service.upload(UUID.randomUUID(), "p1", deviceId, emptyList())
        assertEquals("An empty battery batch must be a no-op returning 0", 0, result)
        // No DB connection should be opened for an empty batch.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadRejectsBatchLargerThanTheRowCap() {
        val sample = Mockito.mock(BatterySample::class.java)
        val tooLarge = List(UPLOAD_ROW_CAP + 1) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", deviceId, tooLarge)
            fail("Expected upload of ${tooLarge.size} samples to be rejected (max $UPLOAD_ROW_CAP)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsABatchAtTheRowCap() {
        val sample = Mockito.mock(BatterySample::class.java)
        val atLimit = List(UPLOAD_ROW_CAP) { sample }
        // The size guard passes, so the batch reaches storage; only that storage failure may come back.
        val unavailable = IllegalStateException("synthetic storage failure")
        Mockito.`when`(storageResolver.getPlatformStorage()).thenThrow(unavailable)
        val failure = assertThrows(IllegalStateException::class.java) { service.upload(UUID.randomUUID(), "p1", deviceId, atLimit) }
        assertSame(unavailable, failure)
        Mockito.verify(storageResolver).getPlatformStorage()
    }

    @Test
    fun iosSamplesBindScopeAndIdentityAlongsideNullableLowPowerMode() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val cutoff = Mockito.mock(PreparedStatement::class.java)
        val insert = Mockito.mock(PreparedStatement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenAnswer {
            if ((it.arguments[0] as String).contains("INSERT INTO")) insert else cutoff
        }
        stubOpenCollection(connection)
        stubNoPurgeCutoff(connection)
        val studyId = UUID.randomUUID()
        val timestamp = OffsetDateTime.parse("2026-10-01T12:00:00Z")
        val sample = IosBatterySample(
            UUID.randomUUID().toString(), timestamp, "America/Chicago", 42, BatteryChargingState.CHARGING, false,
            producerVersion = "2026.10.01 (80)",
        )
        val unknown = sample.copy(id = UUID.randomUUID().toString(), lowPowerMode = null, producerVersion = null)

        assertEquals(2, service.uploadIos(studyId, "p1", deviceId, listOf(sample, unknown)))

        Mockito.verify(insert, Mockito.times(2)).setObject(1, studyId)
        Mockito.verify(insert, Mockito.times(2)).setString(2, "p1")
        Mockito.verify(insert).setString(3, sample.id)
        Mockito.verify(insert).setString(3, unknown.id)
        Mockito.verify(insert, Mockito.times(2)).setObject(4, timestamp)
        Mockito.verify(insert, Mockito.times(2)).setString(5, sample.timezone)
        Mockito.verify(insert, Mockito.times(2)).setInt(6, sample.levelPercent)
        Mockito.verify(insert, Mockito.times(2)).setString(7, sample.chargingState.name)
        Mockito.verify(insert).setObject(8, false)
        Mockito.verify(insert).setObject(8, null)
        Mockito.verify(insert, Mockito.times(2)).setObject(9, deviceId)
        Mockito.verify(insert, Mockito.times(2)).setString(10, "Ios")
        Mockito.verify(insert).setString(11, sample.producerVersion)
        Mockito.verify(insert).setString(11, null)
        Mockito.verify(insert, Mockito.times(2)).addBatch()
        Mockito.verify(insert).executeBatch()
    }
}
