package com.openlattice.chronicle.storage

import com.openlattice.chronicle.study.ParticipantProcessingApi
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.util.UUID

/** Refuses participant work while a researcher has blocked processing for that participant. */
internal object ParticipantProcessingGate {
    fun requireAvailable(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement("SELECT chronicle_participant_processing_allowed(?, ?)").use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeQuery().use { rows ->
                check(rows.next()) { "Participant processing state is unavailable" }
                if (!rows.getBoolean(1)) throw ParticipantProcessingBlockedException()
            }
        }
    }
}

internal class ParticipantProcessingBlockedException : ResponseStatusException(
    HttpStatus.FORBIDDEN, ParticipantProcessingApi.PROCESSING_BLOCKED_REASON,
)
