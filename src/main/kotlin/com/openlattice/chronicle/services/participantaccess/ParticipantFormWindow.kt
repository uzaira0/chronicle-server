package com.openlattice.chronicle.services.participantaccess

import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Participant-local interval; equal endpoints represent one timestamp in a submission.
 * Capabilities carry a calendar date without a zone, so request offsets retain the existing day convention.
 */
public data class ParticipantFormWindow(val start: OffsetDateTime, val endExclusive: OffsetDateTime) {
    internal fun isWithin(day: LocalDate): Boolean {
        if (endExclusive.isBefore(start) || start.toLocalDate() != day) return false
        val last = if (endExclusive.isAfter(start)) endExclusive.minusNanos(1) else start
        return last.toLocalDate() == day
    }
}
