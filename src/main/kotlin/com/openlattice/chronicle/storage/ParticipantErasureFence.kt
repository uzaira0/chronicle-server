package com.openlattice.chronicle.storage

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.util.UUID

/** Completed withdrawal fences identify a permanent subject, never a reusable enrollment generation. */
internal object ParticipantErasureFence {
    fun requireReusable(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement("""
            SELECT EXISTS (
                SELECT 1 FROM data_deletion_operations erasure
                WHERE erasure.study_id = ? AND erasure.status NOT IN ('PREVIEW', 'CANCELLED')
                  AND (erasure.mode = 'STUDY_ERASURE' OR
                    (erasure.mode = 'WITHDRAW_AND_ERASE' AND erasure.participant_block_token = md5(? || ':' || ?)))
            ) AS erased
        """.trimIndent()).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, studyId.toString())
            statement.setString(3, participantId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Participant erasure state is unavailable" }
                if (rows.getBoolean("erased")) throw ResponseStatusException(HttpStatus.CONFLICT,
                    "This participant identifier has been erased and cannot be reused; choose a new identifier")
            }
        }
    }
}
