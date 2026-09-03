package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.fasterxml.jackson.module.kotlin.readValue
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pins the input and transaction guards on the Android hardware-sensor ingestion path.
 * Real-Postgres behavior is covered by the fixture contract suite; these mocked tests
 * prove oversized input is rejected before storage, final-table writes commit before
 * acknowledgment, and failures roll back and propagate.
 */
class AndroidSensorDataUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private lateinit var service: AndroidSensorDataUploadService
    private val expectedValueCounts = mapOf(
        AndroidSensorType.accelerometer to 3,
        AndroidSensorType.gyroscope to 3,
        AndroidSensorType.magnetometer to 3,
        AndroidSensorType.gravity to 3,
        AndroidSensorType.linearAcceleration to 3,
        AndroidSensorType.rotationVector to 4,
        AndroidSensorType.stepCounter to 1,
        AndroidSensorType.light to 1,
        AndroidSensorType.proximity to 1,
        AndroidSensorType.significantMotion to 1,
        AndroidSensorType.tiltDetector to 1,
        AndroidSensorType.screenOrientation to 1,
        AndroidSensorType.samsungGripWifi to 16,
        AndroidSensorType.samsungMotion to 4,
    )

    @Before
    fun setUp() {
        service = AndroidSensorDataUploadService(storageResolver)
    }

    @Test
    fun everyKnownAndroidSensorTypeHasAnExplicitBackendPayloadContract() {
        val knownSensors = AndroidSensorType.values().toSet()

        assertEquals(
            "Update backend expectedValueCounts when AndroidSensorType changes.",
            knownSensors,
            expectedValueCounts.keys,
        )

        AndroidSensorType.values().forEach { sensor ->
            val values = valuesFor(sensor)
            val sample = sample(sensor, values = values)

            assertEquals("$sensor values width", expectedValueCounts.getValue(sensor), sample.values.size)
            assertNullableFloat("$sensor x", values.getOrNull(0), sample.x)
            assertNullableFloat("$sensor y", values.getOrNull(1), sample.y)
            assertNullableFloat("$sensor z", values.getOrNull(2), sample.z)
            assertNullableFloat("$sensor w", values.getOrNull(3), sample.w)
            if (sensor == AndroidSensorType.significantMotion) {
                assertNull("$sensor trigger samples do not require accuracy", sample.accuracy)
            } else {
                assertEquals("$sensor accuracy", 3, sample.accuracy)
            }
            assertFalse("$sensor timezone must be populated", sample.timezone.isBlank())
        }
    }

    @Test
    fun backendUploadJsonPreservesEveryKnownSensorPayloadExactly() {
        val original = AndroidSensorType.values().mapIndexed { index, sensor ->
            sample(
                sensor = sensor,
                id = UUID.nameUUIDFromBytes(sensor.name.toByteArray()),
                timestamp = OffsetDateTime.parse("2026-06-07T13:05:00-05:00").plusSeconds(index.toLong()),
                values = valuesFor(sensor),
            )
        }

        val json = AndroidSensorDataUploadService.mapper.writeValueAsString(original)
        val decoded: List<AndroidSensorSample> = AndroidSensorDataUploadService.mapper.readValue(json)

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (expected, actual) ->
            assertEquals("${expected.sensor} id", expected.id, actual.id)
            assertEquals("${expected.sensor} sensor", expected.sensor, actual.sensor)
            assertEquals("${expected.sensor} timestamp", expected.timestamp, actual.timestamp)
            assertEquals("${expected.sensor} timezone", expected.timezone, actual.timezone)
            assertEquals("${expected.sensor} values", expected.values, actual.values)
            assertNullableFloat("${expected.sensor} x", expected.x, actual.x)
            assertNullableFloat("${expected.sensor} y", expected.y, actual.y)
            assertNullableFloat("${expected.sensor} z", expected.z, actual.z)
            assertNullableFloat("${expected.sensor} w", expected.w, actual.w)
            assertEquals("${expected.sensor} accuracy", expected.accuracy, actual.accuracy)
        }
    }

    @Test
    fun uploadRejectsBatchLargerThanTenThousand() {
        val sample = Mockito.mock(AndroidSensorSample::class.java)
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
        // The guard runs before any storage access — storage must never be touched.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadRejectsOversizedSensorValueVectorBeforeStorageAccess() {
        val oversized = sample(
            sensor = AndroidSensorType.samsungGripWifi,
            values = List(17) { it.toFloat() },
        )

        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), listOf(oversized))
            fail("Expected a sensor value vector wider than 16 to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("too many values") == true)
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsExactlyTenThousandSamplesAtTheBatchBoundary() {
        val sample = Mockito.mock(AndroidSensorSample::class.java)
        val atLimit = List(10_000) { sample }
        try {
            service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), atLimit)
        } catch (e: IllegalArgumentException) {
            fail("A batch of exactly 10,000 samples must pass the size guard, was rejected: ${e.message}")
        } catch (_: Exception) {
            // Expected: storage is mocked, so the call fails after the size guard passes.
        }
    }

    @Test
    fun uploadCommitsFinalTableBatchBeforeAcknowledgingSamples() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.autoCommit).thenReturn(true)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeBatch()).thenReturn(intArrayOf(1))
        val sample = sample(AndroidSensorType.accelerometer)

        assertEquals(1, service.upload(UUID.randomUUID(), "p1", UUID.randomUUID(), listOf(sample)))

        Mockito.verify(connection).prepareStatement(Mockito.contains("INSERT INTO android_sensor_data"))
        Mockito.verify(connection).setAutoCommit(false)
        Mockito.verify(connection).commit()
        Mockito.verify(connection, Mockito.never()).rollback()
        Mockito.verify(connection).setAutoCommit(true)
    }

    @Test
    fun uploadRollsBackAndDoesNotAcknowledgeWhenFinalTableBatchFails() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.autoCommit).thenReturn(true)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeBatch()).thenThrow(SQLException("simulated insert failure"))

        try {
            service.upload(
                UUID.randomUUID(),
                "p1",
                UUID.randomUUID(),
                listOf(sample(AndroidSensorType.accelerometer)),
            )
            fail("Expected failed final-table insert to propagate")
        } catch (expected: SQLException) {
            assertEquals("simulated insert failure", expected.message)
        }

        Mockito.verify(connection).rollback()
        Mockito.verify(connection, Mockito.never()).commit()
        Mockito.verify(connection).setAutoCommit(true)
    }

    private fun valuesFor(sensor: AndroidSensorType): List<Float> {
        val width = expectedValueCounts.getValue(sensor)
        return List(width) { offset ->
            sensor.ordinal * 100.0f + offset + 0.25f
        }
    }

    private fun sample(
        sensor: AndroidSensorType,
        id: UUID = UUID.randomUUID(),
        timestamp: OffsetDateTime = OffsetDateTime.parse("2026-06-07T13:05:00-05:00"),
        values: List<Float> = valuesFor(sensor),
    ): AndroidSensorSample {
        return AndroidSensorSample(
            id = id,
            sensor = sensor,
            timestamp = timestamp,
            timezone = "America/Chicago",
            x = values.getOrNull(0),
            y = values.getOrNull(1),
            z = values.getOrNull(2),
            w = values.getOrNull(3),
            accuracy = if (sensor == AndroidSensorType.significantMotion) null else 3,
            values = values,
        )
    }

    private fun assertNullableFloat(label: String, expected: Float?, actual: Float?) {
        if (expected == null) {
            assertNull(label, actual)
        } else {
            assertNotNull(label, actual)
            assertEquals(label, expected, actual!!, 0.0001f)
        }
    }
}
