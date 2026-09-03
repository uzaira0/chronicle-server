package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime

/** Resource bounds for legacy synchronous participant-data exports. */
internal object SynchronousExportLimits {
    const val MAX_PARTICIPANTS: Int = 100
    const val MAX_JSON_ROWS: Int = 50_000
    private const val MAX_RANGE_DAYS: Long = 31
    private val MAX_RANGE: Duration = Duration.ofDays(MAX_RANGE_DAYS)

    fun validate(
        participantIds: Set<String>,
        start: OffsetDateTime?,
        end: OffsetDateTime?,
    ): Pair<OffsetDateTime, OffsetDateTime> {
        if (participantIds.isEmpty() || participantIds.size > MAX_PARTICIPANTS) {
            reject(Messages.format("error.export.participantCount", MAX_PARTICIPANTS.toString()))
        }
        if (start == null || end == null) {
            reject(Messages.get("error.export.dateRangeRequired"))
        }
        if (!end.isAfter(start) || Duration.between(start, end) > MAX_RANGE) {
            reject(Messages.format("error.export.dateRangeTooLong", MAX_RANGE_DAYS.toString()))
        }
        return start to end
    }

    private fun reject(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
