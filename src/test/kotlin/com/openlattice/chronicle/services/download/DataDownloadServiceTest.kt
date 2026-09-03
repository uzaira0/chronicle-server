package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DataDownloadServiceTest {

    @Test
    fun testEmptyIosSensorSelectionReturnsNoRowsWithoutTouchingStorage() {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        val service = DataDownloadService(storageResolver)
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val rows = service.getParticipantsSensorData(
            UUID.randomUUID(),
            setOf("participant-1"),
            emptySet(),
            now.minusDays(1),
            now,
        )

        assertFalse(rows.iterator().hasNext())
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun `every dedicated Play collection export uses its bounded physical table`() {
        val expectedTables = mapOf(
            ParticipantDataType.SensorAvailability to "android_device_sensor_availability",
            ParticipantDataType.BatteryTelemetry to "battery_telemetry",
            ParticipantDataType.InteractionEvents to "interaction_events",
            ParticipantDataType.AudioActivity to "app_audio_activity",
            ParticipantDataType.AudioContent to "app_audio_content",
            ParticipantDataType.NotificationActivity to "notification_activity",
            ParticipantDataType.SleepEvents to "sleep_events",
            ParticipantDataType.ActivityRecognition to "activity_recognition_events",
            ParticipantDataType.HealthMetrics to "health_metrics",
            ParticipantDataType.ConnectivityState to "connectivity_state_events",
            ParticipantDataType.AppNetworkUsage to "app_network_usage",
            ParticipantDataType.DeviceSettings to "device_settings",
        )

        expectedTables.forEach { (dataType, table) ->
            val sql = DataDownloadService.collectionDataSql(dataType, filterParticipants = true)
            assertTrue("$dataType must read $table", sql.contains("FROM $table"))
            assertTrue("$dataType must stay study scoped", sql.contains("WHERE study_id = ?"))
            assertTrue("$dataType must support participant selection", sql.contains("participant_id = ANY(?)"))
            assertTrue("$dataType must enforce a lower time bound", sql.contains(">= ?"))
            assertTrue("$dataType must enforce an upper time bound", sql.contains("< ?"))
        }
    }
}
