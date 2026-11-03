package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedOperation
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.StorageResolver
import java.sql.Connection
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

public class ParticipantProcessingService(
    private val storageResolver: StorageResolver,
    private val auditingManager: AuditingManager,
) {
    /**
     * Blocks processing if the caller saw [expectedRevision] of the participant's status; the block shares
     * `status_revision` with participation status, so a stale tab cannot undo a later change to either.
     */
    public fun block(studyId: UUID, participantId: String, reason: String, expectedRevision: Long) {
        require(reason.isNotBlank()) { "A processing block requires a reason" }
        change(studyId, participantId, reason.trim(), expectedRevision)
    }

    public fun unblock(studyId: UUID, participantId: String, expectedRevision: Long): Unit =
        change(studyId, participantId, null, expectedRevision)

    private fun change(studyId: UUID, participantId: String, reason: String?, expectedRevision: Long) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedOperation(connection, op = {
                DeletionStudyFence.exclusive(it, studyId)
                val current = lockParticipant(it, studyId, participantId)
                // An identical replay of a change that already landed is a no-op, whatever revision it carries.
                val alreadyApplied = current.blocked == (reason != null)
                if (!alreadyApplied && current.revision != expectedRevision) {
                    throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED, Messages.get("error.participant.statusChanged"))
                }
                !alreadyApplied && update(it, studyId, participantId, reason)
            }, auditOp = { changed ->
                if (!changed) emptyList() else listOf(AuditableEvent(
                    aclKey = AclKey(studyId), study = studyId,
                    eventType = if (reason == null) AuditEventType.PARTICIPANT_PROCESSING_UNBLOCK
                        else AuditEventType.PARTICIPANT_PROCESSING_BLOCK,
                    data = mapOf("participantRef" to AuditService.participantReference(participantId)),
                ))
            }, auditingManager = auditingManager).execute()
        }
    }

    private data class LockedProcessingState(val blocked: Boolean, val revision: Long)

    private fun lockParticipant(connection: Connection, studyId: UUID, participantId: String): LockedProcessingState =
        connection.prepareStatement("""
            SELECT processing_blocked_at IS NOT NULL AS blocked, status_revision
            FROM study_participants WHERE study_id = ? AND participant_id = ? FOR UPDATE
        """.trimIndent()).use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get("error.participant.unavailable"))
                LockedProcessingState(rows.getBoolean("blocked"), rows.getLong("status_revision"))
            }
        }

    private fun update(connection: Connection, studyId: UUID, participantId: String, reason: String?): Boolean {
        // An unblock leaves the block behind as a closed capture window (record_capture_cutoff appends it). It runs
        // first, because that trigger pins status_revision on its own update; the update below then advances it.
        if (reason == null) connection.prepareStatement("""
            UPDATE study_participants
            SET closed_capture_windows = tstzmultirange(tstzrange(processing_blocked_at, GREATEST(processing_blocked_at, now())))
            WHERE study_id = ? AND participant_id = ? AND processing_blocked_at IS NOT NULL
        """.trimIndent()).use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeUpdate()
        }
        return connection.prepareStatement("""
            UPDATE study_participants SET processing_blocked_at = CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,
                processing_block_reason = ?, processing_blocked_by = ?, updated_at = now(),
                status_revision = status_revision + 1
            WHERE study_id = ? AND participant_id = ?
              AND (processing_blocked_at IS NULL) = (?::text IS NOT NULL)
        """.trimIndent()).use {
            it.setString(1, reason)
            it.setString(2, reason)
            it.setString(3, if (reason == null) null else Principals.getCurrentUser().id)
            it.setObject(4, studyId)
            it.setString(5, participantId)
            it.setString(6, reason)
            it.executeUpdate() == 1
        }
    }
}
