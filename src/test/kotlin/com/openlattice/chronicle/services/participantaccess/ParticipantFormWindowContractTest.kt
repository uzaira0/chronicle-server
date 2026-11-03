package com.openlattice.chronicle.services.participantaccess

import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class ParticipantFormWindowContractTest {
    @Test
    fun santiagoWindowsKeepTheirAuthorizedDayAcrossBothClockChanges() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/participant-form-windows.json"))
        resource.use { stream ->
            ObjectMappers.newJsonMapper().readTree(stream).forEach { fixture ->
                val day = LocalDate.parse(fixture["day"].asText())
                val zone = ZoneId.of(fixture["zone"].asText())
                val start = OffsetDateTime.parse(fixture["start"].asText())
                val end = OffsetDateTime.parse(fixture["end"].asText())
                assertEquals(day.atStartOfDay(zone).toOffsetDateTime(), start)
                assertEquals(day.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), end)
                assertEquals(fixture["hours"].asLong(), Duration.between(start, end).toHours())
                val window = ParticipantFormWindow(start, end)
                assertTrue(window.isWithin(day))
                assertFalse(window.isWithin(day.minusDays(1)))
                assertFalse(window.isWithin(day.plusDays(1)))
            }
        }
    }
}
