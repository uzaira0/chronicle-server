package com.openlattice.chronicle.services.apikeys

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REVOKED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import java.sql.Connection
import java.util.UUID

/**
 * Serializes and durably binds mobile withdrawal ownership before credential revocation.
 * Kept separate from general API-key lifecycle operations so this transaction boundary is explicit.
 */
internal class MobileWithdrawalIntentBinder(
    override val auditingManager: AuditingManager,
) : AuditingComponent {
    internal companion object {
        private const val LOCK_MOBILE_WITHDRAWAL_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text || ':' || ?, 0))"

        private const val LOCK_WITHDRAWAL_KEY_SQL = """
            SELECT revoked
            FROM api_keys
            WHERE key_id = ? AND study_id = ?
              AND participant_id = ? AND device_id = ?
            FOR UPDATE
        """

        private const val LOAD_WITHDRAWAL_INTENT_SQL = """
            SELECT request_id, study_id, participant_id, device_id, already_withdrawn
            FROM mobile_withdrawal_requests
            WHERE api_key_id = ?
        """

        private const val INSERT_WITHDRAWAL_INTENT_SQL = """
            INSERT INTO mobile_withdrawal_requests (
                request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn
            ) VALUES (?, ?, ?, ?, ?, ?)
        """

        private const val LOCK_PARTICIPATION_STATUS_SQL = """
            SELECT participation_status
            FROM study_participants
            WHERE study_id = ? AND participant_id = ?
            FOR UPDATE
        """

        private const val CLAIM_PARTICIPANT_WITHDRAWAL_SQL = """
            UPDATE study_participants
            SET participation_status = 'NOT_ENROLLED'
            WHERE study_id = ? AND participant_id = ? AND participation_status = 'ENROLLED'
        """

        private const val REVOKE_WITHDRAWAL_KEY_SQL = """
            UPDATE api_keys
            SET revoked = true
            WHERE key_id = ? AND study_id = ? AND participant_id = ? AND device_id = ?
        """
    }

    fun bind(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
        requestId: UUID,
    ): MobileWithdrawalIntent {
        lockParticipant(connection, studyId, participantId)
        val binding = resolveIntent(connection, studyId, participantId, deviceId, keyId, requestId)
        revokeWithdrawalKey(connection, studyId, participantId, deviceId, keyId)
        recordEvents(withdrawalAudit(binding, studyId, participantId, keyId))
        return MobileWithdrawalIntent(binding.intent.requestId, binding.intent.alreadyWithdrawn)
    }

    private fun resolveIntent(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
        requestId: UUID,
    ): WithdrawalBindingResult {
        val revoked = requireWithdrawalKey(connection, studyId, participantId, deviceId, keyId)
        resolveExistingIntent(connection, keyId, requestId, studyId, participantId, deviceId)?.let { return it }
        if (revoked) invalidWithdrawal()
        val alreadyWithdrawn = classifyParticipation(connection, studyId, participantId)
        val intent = insertIntent(
            connection,
            WithdrawalIntentRecord(requestId, studyId, participantId, deviceId, alreadyWithdrawn),
            keyId,
        )
        if (!alreadyWithdrawn) claimParticipantWithdrawal(connection, studyId, participantId)
        return WithdrawalBindingResult(intent, statusChanged = !alreadyWithdrawn)
    }

    private fun resolveExistingIntent(
        connection: Connection,
        keyId: UUID,
        requestId: UUID,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
    ): WithdrawalBindingResult? = loadIntent(connection, keyId)?.let { existing ->
        val exact = existing.takeIf {
            it.requestId == requestId &&
                it.studyId == studyId &&
                it.participantId == participantId &&
                it.deviceId == deviceId
        } ?: invalidWithdrawal()
        WithdrawalBindingResult(exact, statusChanged = false)
    }

    private fun lockParticipant(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement(LOCK_MOBILE_WITHDRAWAL_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.execute()
        }
    }

    private fun classifyParticipation(connection: Connection, studyId: UUID, participantId: String): Boolean =
        when (lockParticipationStatus(connection, studyId, participantId)) {
            ParticipationStatus.ENROLLED -> false
            ParticipationStatus.NOT_ENROLLED -> true
            else -> invalidWithdrawal()
        }

    private fun lockParticipationStatus(
        connection: Connection,
        studyId: UUID,
        participantId: String,
    ): ParticipationStatus = connection.prepareStatement(LOCK_PARTICIPATION_STATUS_SQL).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) invalidWithdrawal()
            runCatching { ParticipationStatus.valueOf(resultSet.getString("participation_status")) }
                .getOrElse { invalidWithdrawal() }
        }
    }

    private fun claimParticipantWithdrawal(connection: Connection, studyId: UUID, participantId: String) {
        connection.prepareStatement(CLAIM_PARTICIPANT_WITHDRAWAL_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            if (statement.executeUpdate() != 1) invalidWithdrawal()
        }
    }

    private fun withdrawalAudit(
        result: WithdrawalBindingResult,
        studyId: UUID,
        participantId: String,
        keyId: UUID,
    ): List<AuditableEvent> = buildList {
        if (result.statusChanged) add(
            AuditableEvent(
                aclKey = AclKey(studyId),
                eventType = AuditEventType.UPDATE_PARTICIPATION_STATUS,
                description = "Set participation status of participant $participantId in study $studyId to NOT_ENROLLED",
            ),
        )
        add(
            AuditableEvent(
                aclKey = AclKey(studyId),
                eventType = AuditEventType.REVOKE_API_KEY,
                description = "Revoked mobile API key $keyId during self-withdrawal",
                study = studyId,
                data = mapOf("keyId" to keyId.toString()),
            ),
        )
    }

    private fun revokeWithdrawalKey(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
    ) {
        connection.prepareStatement(REVOKE_WITHDRAWAL_KEY_SQL).use { statement ->
            statement.setObject(1, keyId)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.setObject(4, deviceId)
            check(statement.executeUpdate() == 1) { "Withdrawal credential disappeared during revocation" }
        }
    }

    private fun requireWithdrawalKey(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
    ): Boolean = connection.prepareStatement(LOCK_WITHDRAWAL_KEY_SQL).use { statement ->
        statement.setObject(1, keyId)
        statement.setObject(2, studyId)
        statement.setString(3, participantId)
        statement.setObject(4, deviceId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.getBoolean(REVOKED.name) else invalidWithdrawal()
        }
    }

    private fun loadIntent(connection: Connection, keyId: UUID): WithdrawalIntentRecord? =
        connection.prepareStatement(LOAD_WITHDRAWAL_INTENT_SQL).use { statement ->
            statement.setObject(1, keyId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    WithdrawalIntentRecord(
                        requestId = resultSet.getObject("request_id", UUID::class.java),
                        studyId = resultSet.getObject(STUDY_ID.name, UUID::class.java),
                        participantId = resultSet.getString("participant_id"),
                        deviceId = resultSet.getObject("device_id", UUID::class.java),
                        alreadyWithdrawn = resultSet.getBoolean("already_withdrawn"),
                    )
                } else {
                    null
                }
            }
        }

    private fun insertIntent(
        connection: Connection,
        intent: WithdrawalIntentRecord,
        keyId: UUID,
    ): WithdrawalIntentRecord {
        connection.prepareStatement(INSERT_WITHDRAWAL_INTENT_SQL).use { statement ->
            statement.setObject(1, intent.requestId)
            statement.setObject(2, keyId)
            statement.setObject(3, intent.studyId)
            statement.setString(4, intent.participantId)
            statement.setObject(5, intent.deviceId)
            statement.setBoolean(6, intent.alreadyWithdrawn)
            check(statement.executeUpdate() == 1) { "Withdrawal intent was not persisted" }
        }
        return intent
    }

    private fun invalidWithdrawal(): Nothing = throw InvalidWithdrawalRequestException()
}

private data class WithdrawalIntentRecord(
    val requestId: UUID,
    val studyId: UUID,
    val participantId: String,
    val deviceId: UUID,
    val alreadyWithdrawn: Boolean,
)

private data class WithdrawalBindingResult(
    val intent: WithdrawalIntentRecord,
    val statusChanged: Boolean,
)
