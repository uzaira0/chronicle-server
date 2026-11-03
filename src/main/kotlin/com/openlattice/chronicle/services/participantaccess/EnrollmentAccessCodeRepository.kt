package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

internal data class EnrollmentDisclosureActivity(
    val hasActiveEnrollment: Boolean,
    val pendingParticipantIds: List<String>,
)

internal data class PendingEnrollmentAttempt(val accessCodeId: UUID, val enrollmentRandomDeviceId: UUID)

internal class LiveInvitation(val accessCodeId: UUID, val tokenHash: ByteArray, val expiresAt: OffsetDateTime)

internal sealed interface ParticipantAccessCodeRevocationScope {
    object All : ParticipantAccessCodeRevocationScope
    object KeepExchangedEnrollment : ParticipantAccessCodeRevocationScope
}

/** Shared enrollment disclosure, cancellation, and deletion queries on the caller's transaction. */
internal class EnrollmentAccessCodeRepository(private val connection: Connection) {
    private companion object {
        val ACTIVE_ENROLLMENT = """
            (EXISTS (SELECT 1 FROM study_participants WHERE study_id = ? AND participation_status = ?)
             OR EXISTS (SELECT 1 FROM devices WHERE study_id = ?))
        """.trimIndent()
    }

    fun hasActiveEnrollment(studyId: UUID): Boolean {
        requireStudy(studyId, StudyLock.READ)
        return connection.prepareStatement("SELECT $ACTIVE_ENROLLMENT").use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, ParticipationStatus.ENROLLED.name)
            statement.setObject(3, studyId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Participant-policy activity query returned no row" }
                rows.getBoolean(1)
            }
        }
    }

    fun lockScope(scope: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))").use { statement ->
            statement.setString(1, scope)
            statement.execute()
        }
    }

    fun pendingParticipantIds(studyId: UUID, limit: Int, offset: Int): List<String> {
        requireStudy(studyId, StudyLock.READ)
        return connection.prepareStatement("""
            SELECT DISTINCT participant_id FROM pending_enrollment_access_codes
            WHERE study_id = ? ORDER BY participant_id LIMIT ? OFFSET ?
        """.trimIndent()).use { statement ->
            statement.setObject(1, studyId)
            statement.setInt(2, com.openlattice.chronicle.util.PaginationDefaults.clampLimit(limit))
            statement.setInt(3, com.openlattice.chronicle.util.PaginationDefaults.clampOffset(offset))
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    fun lockActivity(studyId: UUID): EnrollmentDisclosureActivity {
        check(!connection.autoCommit) { "Enrollment disclosure fencing requires an active transaction" }
        requireStudy(studyId, StudyLock.UPDATE)
        return activity(studyId)
    }

    fun pendingAttempts(studyId: UUID, participantId: String): List<PendingEnrollmentAttempt> =
        connection.prepareStatement(
            """
            SELECT access_code_id, enrollment_random_device_id FROM pending_enrollment_access_codes
            WHERE study_id = ? AND participant_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(PendingEnrollmentAttempt(
                        accessCodeId = rows.getObject(1, UUID::class.java),
                        enrollmentRandomDeviceId = rows.getObject(2, UUID::class.java),
                    ))
                }
            }
        }

    /** The researcher's unexchanged, unrevoked, unexpired enrollment invitation for this participant, if any. */
    fun liveInvitation(studyId: UUID, participantId: String, now: OffsetDateTime): LiveInvitation? =
        connection.prepareStatement(
            """
            SELECT access_code_id, token_hash, expires_at FROM participant_form_access_codes
            WHERE study_id = ? AND participant_id = ? AND form_kind = ? AND issuer_type = ?
              AND resource_id IS NULL AND logical_date IS NULL
              AND exchanged_at IS NULL AND revoked_at IS NULL AND expires_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setString(3, ParticipantFormKind.ENROLLMENT.name)
            statement.setString(4, ParticipantAccessCodeIssuerType.RESEARCHER.name)
            statement.setObject(5, now)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null
                else LiveInvitation(rows.getObject(1, UUID::class.java), rows.getBytes(2),
                    rows.getObject(3, OffsetDateTime::class.java))
            }
        }

    fun revoke(
        studyId: UUID,
        participantId: String,
        revokedAt: OffsetDateTime,
        scope: ParticipantAccessCodeRevocationScope = ParticipantAccessCodeRevocationScope.All,
    ): Int {
        val sql = when (scope) {
            ParticipantAccessCodeRevocationScope.All -> """
                UPDATE participant_form_access_codes SET revoked_at = COALESCE(revoked_at, ?)
                WHERE study_id = ? AND participant_id = ? AND revoked_at IS NULL
            """
            ParticipantAccessCodeRevocationScope.KeepExchangedEnrollment -> """
                UPDATE participant_form_access_codes SET revoked_at = COALESCE(revoked_at, ?)
                WHERE study_id = ? AND participant_id = ? AND revoked_at IS NULL
                  AND access_code_id NOT IN (SELECT access_code_id FROM exchanged_enrollment_access_codes)
            """
        }
        return connection.prepareStatement(sql.trimIndent()).use { statement ->
            statement.setObject(1, revokedAt)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.executeUpdate()
        }
    }

    private enum class StudyLock { READ, UPDATE }

    private fun requireStudy(studyId: UUID, lock: StudyLock) {
        connection.prepareStatement(when (lock) {
            StudyLock.READ -> "SELECT 1 FROM studies WHERE study_id = ?"
            StudyLock.UPDATE -> "SELECT 1 FROM studies WHERE study_id = ? FOR UPDATE"
        }).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw StudyNotFoundException(studyId, Messages.get("error.study.notFound"))
            }
        }
    }

    private fun activity(studyId: UUID): EnrollmentDisclosureActivity = connection.prepareStatement(
        """
        SELECT
            $ACTIVE_ENROLLMENT AS active_enrollment,
            ARRAY (
                SELECT DISTINCT code.participant_id FROM pending_enrollment_access_codes code
                WHERE code.study_id = ?
                ORDER BY code.participant_id
            ) AS pending_enrollment
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, ParticipationStatus.ENROLLED.name)
        statement.setObject(3, studyId)
        statement.setObject(4, studyId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Participant-policy activity query returned no row" }
            EnrollmentDisclosureActivity(
                hasActiveEnrollment = rows.getBoolean(1),
                pendingParticipantIds = (rows.getArray(2)?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
            )
        }
    }
}

/** Enrollment, acknowledgment, and cancellation use the same transaction-scoped evidence lock. */
internal fun lockDeviceEvidence(connection: Connection, randomDeviceId: UUID) =
    EnrollmentAccessCodeRepository(connection).lockScope(randomDeviceId.toString())

/** Binding an attempt and cancelling an attempt the server has not bound serialize on its id, before any device lock. */
internal fun lockEnrollmentAttempt(connection: Connection, attemptId: UUID) =
    EnrollmentAccessCodeRepository(connection).lockScope("enrollment-attempt:$attemptId")
