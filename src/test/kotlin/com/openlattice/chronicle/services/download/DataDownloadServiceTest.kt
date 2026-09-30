package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.converters.PostgresDownloadWrapper
import org.junit.Assert.assertEquals
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

    @Test
    fun `diagnostic exports use retained day and alert-time filters without message columns`() {
        val diagnosticsSql = DataDownloadService.retainedDiagnosticsSql(
            filterParticipants = true,
            filterStart = true,
            filterEnd = true,
        )
        assertTrue(diagnosticsSql.contains("FROM upload_diagnostics"))
        assertTrue(diagnosticsSql.contains("participant_id = ANY(?)"))
        assertTrue(diagnosticsSql.contains("diagnostic_day >= ?::date"))
        assertTrue(diagnosticsSql.contains("diagnostic_day < ?::date"))
        assertTrue(diagnosticsSql.contains("event_id"))
        assertFalse(diagnosticsSql.contains("server_origin"))
        assertFalse(diagnosticsSql.contains("error_message"))

        val alertsSql = DataDownloadService.retainedQualityAlertsSql(
            filterParticipants = true,
            filterStart = true,
            filterEnd = true,
        )
        assertTrue(alertsSql.contains("FROM data_quality_alerts"))
        assertTrue(alertsSql.contains("created_at >= ?"))
        assertTrue(alertsSql.contains("created_at < ?"))
        for (column in listOf("evaluation_start", "evaluation_end", "threshold")) {
            assertTrue("Missing alert export column $column", alertsSql.contains(column))
        }
        assertFalse(alertsSql.contains("message"))
    }

    @Test
    fun `diagnostic upper day honors each supplied offset and non-midnight end`() {
        assertTrue(
            DataDownloadService.diagnosticsExclusiveEndDay(OffsetDateTime.parse("2026-09-08T00:00:00-03:00"))
                .toString() == "2026-09-08",
        )
        assertTrue(
            DataDownloadService.diagnosticsExclusiveEndDay(OffsetDateTime.parse("2026-09-08T10:15:00+09:00"))
                .toString() == "2026-09-09",
        )
    }

    @Test
    fun `diagnostic non-midnight end includes its day and offset midnight excludes it`() {
        val end = java.time.LocalDate.parse("2026-09-06")
            .atStartOfDay(java.time.ZoneId.of("America/Santiago")).toOffsetDateTime()
        assertEquals(OffsetDateTime.parse("2026-09-06T01:00:00-03:00"), end)
        assertEquals(end.toLocalDate().plusDays(1), DataDownloadService.diagnosticsExclusiveEndDay(end))
        val offsetMidnight = end.withOffsetSameInstant(ZoneOffset.ofHours(-4))
        assertEquals(OffsetDateTime.parse("2026-09-06T00:00:00-04:00"), offsetMidnight)
        assertEquals(end.toLocalDate(), DataDownloadService.diagnosticsExclusiveEndDay(offsetMidnight))
        assertEquals(
            java.time.LocalDate.parse("2026-09-07"),
            DataDownloadService.diagnosticsExclusiveEndDay(end.withHour(1).withMinute(1)),
        )
        assertEquals(
            java.time.LocalDate.parse("2026-09-30"),
            DataDownloadService.diagnosticsExclusiveEndDay(OffsetDateTime.parse("2026-09-29T00:30:00Z")),
        )
    }

    @Test
    fun `quality alert export advertises its evaluation fields`() {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storageResolver.getPlatformReadStorage())
            .thenReturn(Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java))
        val rows = DataDownloadService(storageResolver).getParticipantsDataQualityAlertsData(
            UUID.randomUUID(), emptySet(), OffsetDateTime.MIN, OffsetDateTime.MAX,
        ) as PostgresDownloadWrapper
        assertTrue(rows.columnAdvice.containsAll(listOf("evaluation_start", "evaluation_end", "threshold")))
    }
}
