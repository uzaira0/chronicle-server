package com.openlattice.chronicle.services.participantaccess

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal data class EnrollmentAccessCodeQuery(val tokenHash: ByteArray, val studyId: UUID, val participantId: String)

internal data class StoredEnrollmentAttempt(
    val scope: EnrollmentAccessCodeScope,
    val exchangedAt: OffsetDateTime?,
    val receipt: EnrollmentAttemptReceipt?,
)

/** Who a bound enrollment attempt belongs to; its access code row is the binding. */
internal data class BoundEnrollmentAttempt(
    val accessCodeId: UUID,
    val studyId: UUID,
    val participantId: String,
    val randomDeviceId: UUID,
)

/** [cancellable] attempts matched (unacknowledged); [newlyCancelled] of them this call cancelled first. */
internal data class AttemptCancellation(val cancellable: Int, val newlyCancelled: Int)

internal class EnrollmentAttemptRepository(private val connection: Connection) {
    companion object {
        /**
         * The one rule for "this key belongs to a cancelled enrollment attempt", on a key-hash SQL expression. A cancel
         * records the attempt id, and the attempt's binding names the key it proposed. Admin context.
         */
        fun cancelledKeyCondition(keyHash: String): String = """
            EXISTS (
                SELECT 1 FROM cancelled_enrollment_attempts cancelled
                JOIN participant_form_access_codes code ON code.enrollment_attempt_id = cancelled.attempt_id
                WHERE code.form_kind = 'ENROLLMENT' AND code.enrollment_proposed_key_hash = $keyHash
            )
        """
    }

    /**
     * The binding a phone's own cancellation names: its attempt id and the key it proposed. Revoked bindings
     * still match, so the exact replay of a cancellation is answered the same way.
     */
    fun findBoundAttempt(attemptId: UUID, proposedKeyHash: String): BoundEnrollmentAttempt? = connection.prepareStatement(
        """
        SELECT access_code_id, study_id, participant_id, enrollment_random_device_id
        FROM participant_form_access_codes
        WHERE form_kind = ? AND enrollment_attempt_id = ? AND enrollment_proposed_key_hash = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, ParticipantFormKind.ENROLLMENT.name)
        statement.setObject(2, attemptId)
        statement.setString(3, proposedKeyHash)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else BoundEnrollmentAttempt(
                accessCodeId = rows.getObject(1, UUID::class.java),
                studyId = rows.getObject(2, UUID::class.java),
                participantId = rows.getString(3),
                randomDeviceId = rows.getObject(4, UUID::class.java),
            )
        }
    }

    /**
     * The one cancellation of enrollment attempts, for the dashboard and the phone alike. An attempt is
     * cancellable while it has no enrollment acknowledgment, whether or not its key installed. Cancelling revokes
     * its binding and its key and records its id, so it never binds or installs again; it never withdraws anyone.
     * The caller holds each attempt's device lock. Revoked bindings still match, so a replay reads as cancelled.
     */
    fun cancelAttempts(
        studyId: UUID,
        participantId: String,
        accessCodeIds: Set<UUID>,
        now: OffsetDateTime,
    ): AttemptCancellation {
        val cancellable = connection.prepareStatement(
            """
            UPDATE participant_form_access_codes SET revoked_at = COALESCE(revoked_at, ?)
            WHERE study_id = ? AND participant_id = ? AND access_code_id = ANY(?) AND form_kind = ?
              AND enrollment_attempt_id IS NOT NULL AND NOT chronicle_enrollment_acknowledged(access_code_id)
            RETURNING enrollment_attempt_id, enrollment_proposed_key_hash
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, now)
            statement.setObject(2, studyId)
            statement.setString(3, participantId)
            statement.setArray(4, connection.createArrayOf("uuid", accessCodeIds.toTypedArray()))
            statement.setString(5, ParticipantFormKind.ENROLLMENT.name)
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2)) }
            }
        }
        if (cancellable.isEmpty()) return AttemptCancellation(cancellable = 0, newlyCancelled = 0)
        val newlyCancelled = RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
            connection.prepareStatement(
                "UPDATE api_keys SET revoked = true WHERE study_id = ? AND participant_id = ? AND key_hash = ANY(?)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.setArray(3, connection.createArrayOf("text", cancellable.values.toTypedArray()))
                statement.executeUpdate()
            }
            recordCancelled(cancellable.keys)
        }
        return AttemptCancellation(cancellable.size, newlyCancelled)
    }

    /** Records attempt ids as cancelled; returns how many were not recorded before. Admin context. */
    fun recordCancelled(attemptIds: Set<UUID>): Int = connection.prepareStatement(
        "INSERT INTO cancelled_enrollment_attempts (attempt_id) SELECT unnest(?::uuid[]) ON CONFLICT DO NOTHING",
    ).use { statement ->
        statement.setArray(1, connection.createArrayOf("uuid", attemptIds.toTypedArray()))
        statement.executeUpdate()
    }

    /** Whether [keyHash] is the proposed key of a cancelled attempt. Admin context. */
    fun isCancelledKey(keyHash: String): Boolean =
        connection.prepareStatement("SELECT ${cancelledKeyCondition("?")}").use { statement ->
            statement.setString(1, keyHash)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /** Whether a cancel recorded this attempt id. Admin context. */
    fun isCancelled(attemptId: UUID): Boolean =
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM cancelled_enrollment_attempts WHERE attempt_id = ?)")
            .use { statement ->
                statement.setObject(1, attemptId)
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }

    fun revokeOtherEnrollmentReceipts(
        accessCodeId: UUID,
        binding: EnrollmentAttemptBinding,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            UPDATE exchanged_enrollment_access_codes
            SET revoked_at = ?
            WHERE study_id = ? AND participant_id = ?
              AND enrollment_random_device_id = ? AND access_code_id <> ?
              AND enrollment_attempt_id IS NOT NULL AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, now)
            statement.setObject(2, binding.studyId)
            statement.setString(3, binding.participantId)
            statement.setObject(4, binding.randomDeviceId)
            statement.setObject(5, accessCodeId)
            statement.executeUpdate()
        }
    }

    fun loadForRequest(query: EnrollmentAccessCodeQuery, now: OffsetDateTime): EnrollmentAccessCodeScope? {
        val stored = loadAttempt(query) ?: return null
        val receipt = stored.receipt
        return when {
            stored.exchangedAt == null -> stored.scope.takeIf {
                now.isBefore(it.expiresAt) && receipt == null
            }
            receipt != null && now.isBefore(receipt.replayExpiresAt) ->
                stored.scope.copy(committedBinding = receipt.binding)
            else -> null
        }
    }

    fun loadAttempt(query: EnrollmentAccessCodeQuery): StoredEnrollmentAttempt? = connection.prepareStatement(
        """
        SELECT access_code_id, created_at, expires_at, exchanged_at,
               enrollment_attempt_id, enrollment_source_device_hash, enrollment_random_device_id,
               enrollment_manifest_digest, enrollment_request_hash, enrollment_proposed_key_hash,
               enrollment_replay_expires_at, enrollment_settings_version,
               enrollment_disclosure_version, enrollment_enabled_modules, enrollment_required_modules
        FROM participant_form_access_codes
        WHERE token_hash = ? AND study_id = ? AND participant_id = ?
          AND form_kind = ? AND revoked_at IS NULL
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setBytes(1, query.tokenHash)
        statement.setObject(2, query.studyId)
        statement.setString(3, query.participantId)
        statement.setString(4, ParticipantFormKind.ENROLLMENT.name)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            val scope = enrollmentAccessCodeScope(resultSet, query.studyId, query.participantId)
            val attemptId = resultSet.getObject("enrollment_attempt_id", UUID::class.java)
            val receipt = attemptId?.let {
                EnrollmentAttemptReceipt(
                    EnrollmentAttemptBinding(
                        attemptId = it,
                        studyId = query.studyId,
                        participantId = query.participantId,
                        sourceDeviceHash = resultSet.getString("enrollment_source_device_hash"),
                        randomDeviceId = resultSet.getObject("enrollment_random_device_id", UUID::class.java),
                        manifestDigest = resultSet.getString("enrollment_manifest_digest"),
                        requestHash = resultSet.getString("enrollment_request_hash"),
                        proposedApiKeyHash = resultSet.getString("enrollment_proposed_key_hash"),
                        enrollmentSettingsVersion = resultSet.getObject("enrollment_settings_version") as? Int,
                        enrollmentDisclosureVersion = resultSet.getString("enrollment_disclosure_version"),
                        enrollmentEnabledModules = resultSet.getString("enrollment_enabled_modules")
                            ?.let(::decodeCollectionModules),
                        enrollmentRequiredModules = resultSet.getString("enrollment_required_modules")
                            ?.let(::decodeCollectionModules),
                    ),
                    checkNotNull(resultSet.getObject("enrollment_replay_expires_at", OffsetDateTime::class.java))
                        .withOffsetSameInstant(ZoneOffset.UTC),
                )
            }
            StoredEnrollmentAttempt(
                scope,
                resultSet.getObject("exchanged_at", OffsetDateTime::class.java),
                receipt,
            )
        }
    }

    fun bindEnrollmentAttempt(
        accessCodeId: UUID,
        binding: EnrollmentAttemptBinding,
        now: OffsetDateTime,
        replayExpiresAt: OffsetDateTime,
    ): Boolean = connection.prepareStatement(
        """
        UPDATE participant_form_access_codes
        SET exchanged_at = ?, enrollment_attempt_id = ?, enrollment_source_device_hash = ?,
            enrollment_random_device_id = ?, enrollment_manifest_digest = ?, enrollment_request_hash = ?,
            enrollment_proposed_key_hash = ?, enrollment_replay_expires_at = ?,
            enrollment_settings_version = ?, enrollment_disclosure_version = ?,
            enrollment_enabled_modules = ?::jsonb, enrollment_required_modules = ?::jsonb
        WHERE access_code_id = ? AND exchanged_at IS NULL AND revoked_at IS NULL AND expires_at > ?
          AND enrollment_attempt_id IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, now)
        statement.setObject(2, binding.attemptId)
        statement.setString(3, binding.sourceDeviceHash)
        statement.setObject(4, binding.randomDeviceId)
        statement.setString(5, binding.manifestDigest)
        statement.setString(6, binding.requestHash)
        statement.setString(7, binding.proposedApiKeyHash)
        statement.setObject(8, replayExpiresAt)
        statement.setObject(9, binding.enrollmentSettingsVersion)
        statement.setString(10, binding.enrollmentDisclosureVersion)
        statement.setString(11, binding.enrollmentEnabledModules?.let(::encodeCollectionModules))
        statement.setString(12, binding.enrollmentRequiredModules?.let(::encodeCollectionModules))
        statement.setObject(13, accessCodeId)
        statement.setObject(14, now)
        statement.executeUpdate() == 1
    }

    private fun encodeCollectionModules(modules: Set<CollectionModuleId>): String =
        ObjectMappers.getJsonMapper().writeValueAsString(modules.map { it.id }.sorted())

    private fun decodeCollectionModules(json: String): Set<CollectionModuleId> =
        ObjectMappers.getJsonMapper().readValue<List<String>>(json)
            .mapNotNull(CollectionModuleId::fromIdOrNull)
            .toCollection(LinkedHashSet())

    fun load(query: EnrollmentAccessCodeQuery, now: OffsetDateTime): EnrollmentAccessCodeScope? {
        val sql = """
            SELECT access_code_id, created_at, expires_at
            FROM participant_form_access_codes
            WHERE token_hash = ? AND study_id = ? AND participant_id = ?
              AND form_kind = ? AND exchanged_at IS NULL
              AND revoked_at IS NULL AND expires_at > ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            statement.setBytes(1, query.tokenHash)
            statement.setObject(2, query.studyId)
            statement.setString(3, query.participantId)
            statement.setString(4, ParticipantFormKind.ENROLLMENT.name)
            statement.setObject(5, now)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) enrollmentAccessCodeScope(resultSet, query.studyId, query.participantId) else null
            }
        }
    }

    private fun enrollmentAccessCodeScope(
        resultSet: ResultSet,
        studyId: UUID,
        participantId: String,
    ): EnrollmentAccessCodeScope = EnrollmentAccessCodeScope(
        accessCodeId = resultSet.getObject("access_code_id", UUID::class.java),
        studyId = studyId,
        participantId = participantId,
        issuedAt = resultSet.getObject("created_at", OffsetDateTime::class.java)
            .withOffsetSameInstant(ZoneOffset.UTC),
        expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java)
            .withOffsetSameInstant(ZoneOffset.UTC),
    )
}
