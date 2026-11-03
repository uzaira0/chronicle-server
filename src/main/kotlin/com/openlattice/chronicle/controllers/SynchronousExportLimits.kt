package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.data.FileType
import com.openlattice.chronicle.study.ParticipantDataType
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat

import com.openlattice.chronicle.generated.SynchronousExportBounds
import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

internal data class SynchronousExportWindow(val start: OffsetDateTime, val end: OffsetDateTime)

/** Request checks for synchronous participant-data exports; results stream, they are never capped. */
internal object SynchronousExportLimits {
    fun validate(
        participantIds: Set<String>,
        start: OffsetDateTime?,
        end: OffsetDateTime?,
    ): SynchronousExportWindow {
        if (participantIds.isEmpty()) {
            reject(Messages.get("error.export.participantRequired"))
        }
        val lower = start ?: OffsetDateTime.MIN
        val upper = end ?: OffsetDateTime.MAX
        if (!upper.isAfter(lower)) {
            reject(Messages.get("error.export.dateRangeInvalid"))
        }
        return SynchronousExportWindow(lower, upper)
    }

    private fun reject(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}

/** Query parameters for synchronous participant downloads. */
public data class SynchronousExportRequest(
    val dataType: ParticipantDataType,
    val participantId: Set<String>,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val startDate: OffsetDateTime? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val endDate: OffsetDateTime? = null,
    val responseType: FileType = FileType.csv,
    @field:Size(max = SynchronousExportBounds.SYNC_EXPORT_MAX_FILENAME_CHARS)
    val fileName: String? = null,
    val sensorTypes: Set<String>? = null,
)
