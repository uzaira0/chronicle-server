package com.openlattice.chronicle.storage

import com.openlattice.chronicle.i18n.Messages
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.util.UUID

/** Completed withdrawal fences identify a permanent subject, never a reusable enrollment generation. */
internal object ParticipantErasureFence {
    fun requireReusable(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement("""
            SELECT chronicle_has_participant_erasure(?::uuid, ?::text) AS erased
        """.trimIndent()).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Participant erasure state is unavailable" }
                if (rows.getBoolean("erased")) throw ResponseStatusException(HttpStatus.CONFLICT,
                    Messages.get("error.participantId.erased"))
            }
        }
    }
}
