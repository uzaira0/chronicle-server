package com.openlattice.chronicle.services.download

import com.zaxxer.hikari.HikariDataSource
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.StudyDisclosureGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DataDownloadServiceTest {

    @Test
    fun `usage download scope preserves study bindings participant selection and streaming timeout`() {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        Mockito.`when`(connection.transactionIsolation).thenReturn(Connection.TRANSACTION_READ_COMMITTED)
        Mockito.`when`(connection.createStatement()).thenReturn(Mockito.mock(Statement::class.java))
        val statement = Mockito.mock(PreparedStatement::class.java)
        val participantArray = Mockito.mock(java.sql.Array::class.java)
        Mockito.`when`(statement.connection).thenReturn(connection)
        Mockito.`when`(connection.createArrayOf(Mockito.anyString(), Mockito.any())).thenReturn(participantArray)
        val studyId = UUID.randomUUID()
        val start = OffsetDateTime.parse("2026-09-01T00:00:00Z")
        val end = start.plusDays(1)

        assertEquals(1, DataDownloadService.bindDownloadScope(statement, studyId, emptySet(), eventStorage = true))
        Mockito.verify(statement).setString(1, studyId.toString())
        Mockito.verify(statement, Mockito.never()).setQueryTimeout(Mockito.anyInt())
        Mockito.verify(statement, Mockito.never()).setArray(Mockito.anyInt(), Mockito.any())

        Mockito.clearInvocations(statement)
        assertEquals(2, DataDownloadService.bindDownloadScope(statement, studyId, setOf("participant-1")))
        Mockito.verify(statement).setObject(1, studyId)
        Mockito.verify(statement).setArray(2, participantArray)

        Mockito.clearInvocations(statement)
        Mockito.`when`(storageResolver.getPlatformReadStorage()).thenReturn(dataSource)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeQuery()).thenReturn(Mockito.mock(ResultSet::class.java))
        val generation = Mockito.mock(PreparedStatement::class.java)
        val generationRows = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(connection.prepareStatement(Mockito.contains(StudyDisclosureGeneration.COLUMN))).thenReturn(generation)
        Mockito.`when`(generation.executeQuery()).thenReturn(generationRows)
        Mockito.`when`(generationRows.next()).thenReturn(true)
        Mockito.`when`(generationRows.getObject(1, UUID::class.java)).thenReturn(studyId)
        val rows = DataDownloadService(storageResolver).getParticipantsAppUsageSurveyData(
            studyId, setOf("participant-1"), start, end, ZoneOffset.UTC,
        )
        assertFalse(rows.iterator().hasNext())
        Mockito.verify(connection.createStatement()).execute("SET LOCAL statement_timeout = 0")
        Mockito.verify(statement).setObject(1, studyId)
        Mockito.verify(statement).setArray(2, participantArray)
        Mockito.verify(statement).setObject(3, start.toLocalDate())
        Mockito.verify(statement).setObject(4, end.toLocalDate())

        val service = DataDownloadService(storageResolver)
        Mockito.clearInvocations(statement)
        assertFalse(service.getParticipantsUploadDiagnosticsData(studyId, emptySet(), start, end.plusHours(1)).iterator().hasNext())
        Mockito.verify(statement).setObject(2, start.toLocalDate())
        Mockito.verify(statement).setObject(3, end.toLocalDate().plusDays(1))

        Mockito.clearInvocations(statement)
        assertFalse(service.getParticipantsUploadDiagnosticsData(studyId, emptySet(), OffsetDateTime.MIN, OffsetDateTime.MAX).iterator().hasNext())
        Mockito.verify(statement).setObject(1, studyId)
        Mockito.verify(statement, Mockito.never()).setObject(Mockito.eq(2), Mockito.any())
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

    
}
