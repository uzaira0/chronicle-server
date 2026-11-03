package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSFixtureContext
import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** The real store/export keeps capture metadata, including honest absence, when uploads are replayed. */
class BatteryProvenanceDbTest : ServerSweepTestFixture() {
    @get:Rule val fixtureContext = RLSFixtureContext()

    @Test fun malformedProducerVersionsCannotEnterThroughDirectDatabaseWrites() {
        val study = UUID.randomUUID()
        val participant = "battery-provenance-boundary"
        seed(study, participant)
        pool("battery-provenance-boundary").use { pool ->
            pool.connection.use { assertProducerVersionBoundaries(it, study, participant) }
        }
    }

    @Test fun sourceAndCaptureVersionSurviveStorageExportAndConflictingReplay() {
        val study = UUID.randomUUID()
        val participant = "battery-provenance"
        seed(study, participant)
        pool("battery-provenance").use { pool ->
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            val upload = BatteryTelemetryUploadService(storage)
            val captured = OffsetDateTime.parse("2026-10-01T12:00:00Z")
            val androidDevice = UUID.randomUUID()
            val iosDevice = UUID.randomUUID()
            val android = BatterySample(UUID.randomUUID().toString(), captured, "UTC", 42,
                BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 220, 4000, BatteryHealth.GOOD,
                producerVersion = "2026.10.01 (80)")
            val ios = IosBatterySample(UUID.randomUUID().toString(), captured, "UTC", 42,
                BatteryChargingState.DISCHARGING, producerVersion = "2026.10.01 (41)")
            val legacyWire = android.copy(id = UUID.randomUUID().toString(), producerVersion = null)
            upload.upload(study, participant, androidDevice, listOf(android, legacyWire))
            upload.uploadIos(study, participant, iosDevice, listOf(ios))
            // A delayed retry cannot relabel the original capture with a newer build or another device.
            upload.upload(study, participant, UUID.randomUUID(), listOf(android.copy(producerVersion = "2026.11.01 (81)")))
            upload.uploadIos(study, participant, UUID.randomUUID(), listOf(ios.copy(producerVersion = "2026.11.01 (42)")))
            upload.uploadIos(study, participant, UUID.randomUUID(), listOf(ios.copy(id = android.id)))
            upload.upload(study, participant, UUID.randomUUID(), listOf(android.copy(id = ios.id)))

            pool.connection.use { assertStoredProvenance(it, study, participant, android, androidDevice) }
            val exported = DataDownloadService(storage).getParticipantsCollectionData(study, setOf(participant),
                ParticipantDataType.BatteryTelemetry, captured.minusDays(1), captured.plusDays(1)).toList()
                .associateBy { it.getValue("sample_id").toString() }
            val expected = mapOf(
                android.id to mapOf("random_device_id" to androidDevice.toString(), "device_type" to "Android",
                    "producer_version" to android.producerVersion),
                ios.id to mapOf("random_device_id" to iosDevice.toString(), "device_type" to "Ios",
                    "producer_version" to ios.producerVersion),
                legacyWire.id to mapOf("random_device_id" to androidDevice.toString(), "device_type" to "Android",
                    "producer_version" to ""),
                "historic" to mapOf("random_device_id" to "", "device_type" to "", "producer_version" to ""),
            )
            assertEquals(expected, exported.mapValues { (_, row) -> row.filterKeys { it in expected.getValue("historic") } })
        }
    }

    private fun assertProducerVersionBoundaries(connection: Connection, study: UUID, participant: String) {
        connection.prepareStatement("INSERT INTO battery_telemetry (study_id,participant_id,sample_id," +
            "sample_timestamp,timezone,level_percent,charging_state,producer_version) " +
            "VALUES (?,?,?,now(),'UTC',42,'UNKNOWN',?)").use { statement ->
            statement.setObject(1, study)
            statement.setString(2, participant)
            val whiteSpace = (0x0009..0x000D).toList() + listOf(0x0020, 0x0085, 0x00A0, 0x1680) +
                (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
            assertEquals(25, whiteSpace.size)
            val malformed = listOf("", " \t\r\n") + whiteSpace.map { it.toChar().toString() } +
                listOf("v".repeat(65), "\uD801\uDC00".repeat(65))
            for (invalid in malformed) {
                statement.setString(3, UUID.randomUUID().toString())
                statement.setString(4, invalid)
                assertThrows(SQLException::class.java) { statement.executeUpdate() }
            }
            for (valid in listOf("\u001C", "\u200B", "\uFEFF", "line\nversion",
                "v".repeat(64), "\uD801\uDC00".repeat(64))) {
                statement.setString(3, UUID.randomUUID().toString())
                statement.setString(4, valid)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun assertStoredProvenance(connection: Connection, study: UUID, participant: String,
                                      android: BatterySample, androidDevice: UUID) {
        connection.prepareStatement("SELECT random_device_id, device_type, producer_version, uploaded_at " +
            "FROM battery_telemetry WHERE study_id=? AND sample_id=?").use { statement ->
            statement.setObject(1, study)
            statement.setString(2, android.id)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals(androidDevice, rows.getObject(1, UUID::class.java))
                assertEquals("Android", rows.getString(2))
                assertEquals(android.producerVersion, rows.getString(3))
                assertTrue(rows.getObject(4) != null)
            }
        }
        // Rows retained from older databases have no recoverable source metadata.
        connection.prepareStatement("INSERT INTO battery_telemetry (study_id,participant_id,sample_id," +
            "sample_timestamp,timezone,level_percent,charging_state) VALUES (?,?,'historic',?,'UTC',42,'UNKNOWN')").use {
            it.setObject(1, study); it.setString(2, participant); it.setObject(3, android.timestamp); it.executeUpdate()
        }
        connection.prepareStatement("SELECT random_device_id,device_type,producer_version FROM battery_telemetry " +
            "WHERE study_id=? AND sample_id='historic'").use {
            it.setObject(1, study)
            it.executeQuery().use { rows ->
                assertTrue(rows.next())
                for (column in 1..3) assertNull(rows.getObject(column))
            }
        }
    }
}
