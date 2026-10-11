package com.openlattice.chronicle.storage

import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Locks the collection decision until the caller's write commits. */
internal object ParticipantCollectionGate {
    private data class State(val lifecycle: String?, val participation: String?, val cutoff: OffsetDateTime?) {
        val restricted: Boolean get() = participation == ParticipationStatus.PAUSED.name ||
            participation == ParticipationStatus.COLLECTION_COMPLETED.name
    }

    fun isActive(connection: Connection, studyId: UUID, participantId: String? = null,
                 latestCapturedAt: Instant? = null, allowQueuedCaptures: Boolean = false): Boolean {
        val state = loadLockedState(connection, studyId, participantId) ?: return false
        return state.lifecycle == StudyLifecycleStatus.ACTIVE.name &&
            (participantId == null || state.participation == ParticipationStatus.ENROLLED.name ||
                (state.restricted && state.cutoff != null && (allowQueuedCaptures ||
                    latestCapturedAt?.isBefore(state.cutoff.toInstant()) == true)))
    }

    /**
     * A successful batch acknowledges dropped rows too; unknown capture times never prove pre-stop capture.
     * A stop with no recorded cutoff cannot classify any row, so the batch is refused (held on the device)
     * rather than acknowledged as discarded.
     */
    fun <T> retainQueuedCaptures(connection: Connection, studyId: UUID, participantId: String,
                                data: List<T>, capturedAt: (T) -> OffsetDateTime?): List<T> {
        val state = loadLockedState(connection, studyId, participantId)
        if (state?.restricted != true) return data
        val cutoff = state.cutoff
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.notEnrolled"))
        return data.filter { sample -> capturedAt(sample)?.isBefore(cutoff) == true }
    }

    private fun loadLockedState(connection: Connection, studyId: UUID, participantId: String?): State? {
        DeletionStudyFence.shared(connection, studyId)
        return connection.prepareStatement("""
            SELECT lifecycle_status, participant.participation_status, participant.capture_cutoff
            FROM studies LEFT JOIN LATERAL (
                SELECT participation_status, capture_cutoff FROM study_participants
                WHERE study_id = studies.study_id AND participant_id = ? FOR SHARE
            ) participant ON true
            WHERE study_id = ? FOR SHARE OF studies
        """.trimIndent()).use { statement ->
            statement.setString(1, participantId)
            statement.setObject(2, studyId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else State(rows.getString("lifecycle_status"),
                    rows.getString("participation_status"), rows.getObject("capture_cutoff", OffsetDateTime::class.java))
            }
        }
    }

    fun requireActive(connection: Connection, studyId: UUID, participantId: String? = null) {
        if (!isActive(connection, studyId, participantId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.enrollment.notEnrolled"))
        }
    }
}
