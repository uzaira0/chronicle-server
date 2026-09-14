package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.openlattice.chronicle.storage.PostgresColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Module-table and sensor exports must render timestamps in the row's own `timezone`, the
 * way the usage-event export already does, so one workbook does not mix `Z` and `-04:00`.
 */
class DataDownloadServiceTimestampTest {

    private val sampleTimestamp = PostgresColumnDefinition("sample_timestamp", PostgresDatatype.TIMESTAMPTZ)

    @Test
    fun timestampsRenderInTheRowTimezone() {
        val rs = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(rs.getString(PostgresColumns.SENSOR_TIMEZONE.name)).thenReturn("America/Santiago")
        Mockito.`when`(rs.getObject("sample_timestamp", OffsetDateTime::class.java))
            .thenReturn(OffsetDateTime.of(2026, 9, 3, 22, 59, 34, 0, ZoneOffset.UTC))

        val zone = DataDownloadService.rowZone(rs, listOf(sampleTimestamp, PostgresColumns.SENSOR_TIMEZONE))
        val rendered = DataDownloadService.localizedTimestamp(rs, sampleTimestamp, zone)

        assertEquals(ZoneId.of("America/Santiago"), zone)
        // Chile is still on standard time (-04:00) on 2026-09-03; the pilot's usage sheet agrees.
        assertEquals("2026-09-03T18:59:34-04:00", rendered.toString())
    }

    @Test
    fun tablesWithoutTimezoneOrWithBadZoneKeepTheStoredOffset() {
        val rs = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(rs.getString(PostgresColumns.SENSOR_TIMEZONE.name)).thenReturn("Not/AZone")
        val stored = OffsetDateTime.of(2026, 9, 3, 22, 59, 34, 0, ZoneOffset.UTC)
        Mockito.`when`(rs.getObject("sample_timestamp", OffsetDateTime::class.java)).thenReturn(stored)

        assertNull(DataDownloadService.rowZone(rs, listOf(sampleTimestamp)))
        assertNull(DataDownloadService.rowZone(rs, listOf(sampleTimestamp, PostgresColumns.SENSOR_TIMEZONE)))
        assertEquals(stored, DataDownloadService.localizedTimestamp(rs, sampleTimestamp, null))
        Mockito.`when`(rs.getObject("sample_timestamp", OffsetDateTime::class.java)).thenReturn(null)
        assertEquals("", DataDownloadService.localizedTimestamp(rs, sampleTimestamp, null))
    }
}
