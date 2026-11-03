package com.openlattice.chronicle.contract

import com.openlattice.chronicle.data.ParticipationStatus
import java.sql.Connection
import java.util.UUID

internal fun seedEnrolledParticipant(connection: Connection, studyId: UUID, participantId: String) {
    connection.prepareStatement("""
        INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
        VALUES (?, ?, ?, ?)
    """.trimIndent()).use {
        it.setObject(1, studyId)
        it.setString(2, participantId)
        it.setObject(3, seedCandidate(connection))
        it.setString(4, ParticipationStatus.ENROLLED.name)
        it.executeUpdate()
    }
}

internal fun seedCandidate(connection: Connection): UUID = UUID.randomUUID().also { candidate ->
    connection.prepareStatement("INSERT INTO candidates (candidate_id) VALUES (?)").use { statement ->
        statement.setObject(1, candidate)
        statement.executeUpdate()
    }
}
