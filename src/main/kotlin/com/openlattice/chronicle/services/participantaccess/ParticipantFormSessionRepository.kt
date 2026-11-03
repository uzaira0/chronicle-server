package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import java.sql.Connection
import java.sql.ResultSet
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal data class StoredAccessCode(
    val accessCodeId: UUID,
    val studyId: UUID,
    val participantId: String,
    val formKind: ParticipantFormKind,
    val resourceId: UUID?,
    val logicalDate: LocalDate?,
    val expiresAt: OffsetDateTime,
    val exchangedAt: OffsetDateTime?,
)

internal data class ParticipantFormSessionRecord(
    val sessionId: UUID,
    val sessionHash: ByteArray,
    val accessCodeId: UUID,
    val studyId: UUID,
    val participantId: String,
    val formKind: ParticipantFormKind,
    val resourceId: UUID?,
    val logicalDate: LocalDate?,
    val csrfHash: ByteArray,
    val idleExpiresAt: OffsetDateTime,
    val absoluteExpiresAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
)

internal data class StoredParticipantSession(
    val sessionId: UUID,
    val csrfHash: ByteArray,
    val idleExpiresAt: OffsetDateTime,
    val scope: ParticipantFormAccessScope,
)

internal class ParticipantFormSessionRepository(private val connection: Connection) {
    fun loadExchangeableAccessCode(tokenHash: ByteArray): StoredAccessCode? {
        return connection.prepareStatement(
            """
            SELECT access_code_id, study_id, participant_id, form_kind, resource_id, logical_date, expires_at, exchanged_at
            FROM participant_form_access_codes
            WHERE token_hash = ? AND form_kind <> ?
              AND revoked_at IS NULL AND expires_at > now()
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setBytes(1, tokenHash)
            statement.setString(2, ParticipantFormKind.ENROLLMENT.name)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toStoredAccessCode() else null
            }
        }
    }

    private fun ResultSet.toStoredAccessCode(): StoredAccessCode = StoredAccessCode(
        accessCodeId = getObject("access_code_id", UUID::class.java),
        studyId = getObject("study_id", UUID::class.java),
        participantId = getString("participant_id"),
        formKind = ParticipantFormKind.valueOf(getString("form_kind")),
        resourceId = getObject("resource_id", UUID::class.java),
        logicalDate = getObject("logical_date", LocalDate::class.java),
        expiresAt = getObject("expires_at", OffsetDateTime::class.java),
        exchangedAt = getObject("exchanged_at", OffsetDateTime::class.java),
    )

    fun findStudy(tokenHash: ByteArray): UUID? = connection.prepareStatement(
        "SELECT study_id FROM participant_form_access_codes WHERE token_hash = ?",
    ).use { statement ->
        statement.setBytes(1, tokenHash)
        statement.executeQuery().use { result ->
            if (result.next()) result.getObject(1, UUID::class.java) else null
        }
    }

    fun replayExpiry(accessCodeId: UUID, sessionHash: ByteArray): OffsetDateTime? = connection.prepareStatement(
        """
        SELECT absolute_expires_at FROM participant_form_sessions
        WHERE access_code_id = ? AND session_hash = ? AND revoked_at IS NULL
          AND idle_expires_at > now() AND absolute_expires_at > now()
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, accessCodeId)
        statement.setBytes(2, sessionHash)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, OffsetDateTime::class.java) else null }
    }

    fun reserve(accessCodeId: UUID, now: OffsetDateTime): Boolean = connection.prepareStatement(
        "UPDATE participant_form_access_codes SET exchanged_at = ? WHERE access_code_id = ? AND exchanged_at IS NULL"
    ).use { statement ->
        statement.setObject(1, now)
        statement.setObject(2, accessCodeId)
        statement.executeUpdate() == 1
    }

    fun insert(record: ParticipantFormSessionRecord) {
        connection.prepareStatement(
            """
            INSERT INTO participant_form_sessions
                (session_id, session_hash, access_code_id, study_id, participant_id, form_kind,
                 resource_id, logical_date, csrf_hash, idle_expires_at, absolute_expires_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, record.sessionId)
            statement.setBytes(2, record.sessionHash)
            statement.setObject(3, record.accessCodeId)
            statement.setObject(4, record.studyId)
            statement.setString(5, record.participantId)
            statement.setString(6, record.formKind.name)
            statement.setObject(7, record.resourceId)
            statement.setObject(8, record.logicalDate)
            statement.setBytes(9, record.csrfHash)
            statement.setObject(10, record.idleExpiresAt)
            statement.setObject(11, record.absoluteExpiresAt)
            statement.setObject(12, record.lastSeenAt)
            check(statement.executeUpdate() == 1) { "Participant session was not persisted" }
        }
    }
    fun cookieSession(sessionHash: ByteArray, now: OffsetDateTime): StoredParticipantSession? = loadSession(
        connection, "session_hash = ? AND revoked_at IS NULL AND absolute_expires_at > ?",
    ) { statement -> statement.setBytes(1, sessionHash); statement.setObject(2, now) }

    fun tabSession(scope: ParticipantFormAccessScope, csrfHash: ByteArray, now: OffsetDateTime): StoredParticipantSession? =
        loadSession(connection,
            """
            study_id = ? AND participant_id = ? AND csrf_hash = ? AND revoked_at IS NULL
              AND idle_expires_at > ? AND absolute_expires_at > ?
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, scope.studyId)
            statement.setString(2, scope.participantId)
            statement.setBytes(3, csrfHash)
            statement.setObject(4, now)
            statement.setObject(5, now)
        }

    fun refresh(session: StoredParticipantSession, now: OffsetDateTime, idleExpiresAt: OffsetDateTime) {
        connection.prepareStatement(
            "UPDATE participant_form_sessions SET last_seen_at = ?, idle_expires_at = ? WHERE session_id = ?",
        ).use { update ->
            update.setObject(1, now)
            update.setObject(2, idleExpiresAt)
            update.setObject(3, session.sessionId)
            check(update.executeUpdate() == 1) { "Participant session refresh failed" }
        }
    }

    private fun loadSession(
        connection: Connection,
        predicate: String,
        bind: (PreparedStatement) -> Unit,
    ): StoredParticipantSession? = connection.prepareStatement(
        """
        SELECT session_id, access_code_id, study_id, participant_id, form_kind, resource_id,
               logical_date, csrf_hash, idle_expires_at, absolute_expires_at
        FROM participant_form_sessions
        WHERE $predicate
        """.trimIndent()
    ).use { statement ->
        bind(statement)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            StoredParticipantSession(
                sessionId = resultSet.getObject("session_id", UUID::class.java),
                csrfHash = resultSet.getBytes("csrf_hash"),
                idleExpiresAt = resultSet.getObject("idle_expires_at", OffsetDateTime::class.java),
                scope = ParticipantFormAccessScope(
                    accessCodeId = resultSet.getObject("access_code_id", UUID::class.java),
                    studyId = resultSet.getObject("study_id", UUID::class.java),
                    participantId = resultSet.getString("participant_id"),
                    formKind = ParticipantFormKind.valueOf(resultSet.getString("form_kind")),
                    resourceId = resultSet.getObject("resource_id", UUID::class.java),
                    logicalDate = resultSet.getObject("logical_date", LocalDate::class.java),
                    absoluteExpiresAt = resultSet.getObject("absolute_expires_at", OffsetDateTime::class.java),
                ),
            )
        }
    }
}
