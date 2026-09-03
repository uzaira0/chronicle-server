package com.openlattice.chronicle.services.participantaccess

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.storage.StorageResolver
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

public enum class ParticipantAccessCodeIssuerType {
    DEVICE,
    RESEARCHER,
}

/** Application command; deliberately independent of Spring and JDBC. */
public data class ParticipantAccessCodeCommand(
    val studyId: UUID,
    val participantId: String,
    val formKind: ParticipantFormKind,
    val resourceId: UUID?,
    val logicalDate: LocalDate?,
    val requestedExpiresAt: OffsetDateTime?,
    val issuerType: ParticipantAccessCodeIssuerType,
    val issuedBy: String,
)

/** Request scope produced only after a stored, unexpired capability session is verified. */
public data class ParticipantFormAccessScope(
    val accessCodeId: UUID,
    val studyId: UUID,
    val participantId: String,
    val formKind: ParticipantFormKind,
    val resourceId: UUID?,
    val logicalDate: LocalDate?,
    val absoluteExpiresAt: OffsetDateTime,
) {
    public fun permits(
        requiredKind: ParticipantFormKind,
        requiredStudyId: UUID,
        requiredParticipantId: String?,
        requiredResourceId: UUID?,
    ): Boolean {
        if (studyId != requiredStudyId || (formKind != ParticipantFormKind.PORTAL && formKind != requiredKind)) {
            return false
        }
        if (requiredParticipantId != null && participantId != requiredParticipantId) {
            return false
        }
        return requiredResourceId == null || formKind == ParticipantFormKind.PORTAL || resourceId == requiredResourceId
    }
}

public data class ExchangedParticipantFormSession(
    val rawSessionToken: String,
    val response: ParticipantFormSessionResponse,
)

/** Immutable enrollment-invitation facts used to make the preview manifest reproducible. */
public data class EnrollmentAccessCodeScope(
    val accessCodeId: UUID,
    val studyId: UUID,
    val participantId: String,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
)

/** Hash-only identity of one public enrollment request. No client credential is retained in plaintext. */
public data class EnrollmentAttemptBinding(
    val attemptId: UUID,
    val studyId: UUID,
    val participantId: String,
    val sourceDeviceHash: String,
    val deviceId: UUID,
    val manifestDigest: String,
    val requestHash: String,
    val proposedApiKeyHash: String,
    /** Authoritative consent evidence stamped with new V91 receipts; null marks a legacy V89 receipt. */
    val enrollmentSettingsVersion: Int? = null,
    val enrollmentDisclosureVersion: String? = null,
    val enrollmentEnabledModules: Set<CollectionModuleId>? = null,
    val enrollmentRequiredModules: Set<CollectionModuleId>? = null,
) {
    internal fun securelyMatches(other: EnrollmentAttemptBinding): Boolean =
        attemptId == other.attemptId &&
            studyId == other.studyId &&
            participantId == other.participantId &&
            deviceId == other.deviceId &&
            constantTimeAsciiEquals(sourceDeviceHash, other.sourceDeviceHash) &&
            constantTimeAsciiEquals(manifestDigest, other.manifestDigest) &&
            constantTimeAsciiEquals(requestHash, other.requestHash) &&
            constantTimeAsciiEquals(proposedApiKeyHash, other.proposedApiKeyHash) &&
            legacyOrEqual(enrollmentSettingsVersion, other.enrollmentSettingsVersion) &&
            legacyOrEqual(enrollmentDisclosureVersion, other.enrollmentDisclosureVersion) &&
            legacyOrEqual(enrollmentEnabledModules, other.enrollmentEnabledModules) &&
            legacyOrEqual(enrollmentRequiredModules, other.enrollmentRequiredModules)

    private fun <T> legacyOrEqual(stored: T?, candidate: T?): Boolean = stored == null || stored == candidate
}

/** A consumed invitation may authorize only its exact request binding, and only for a bounded recovery window. */
public data class EnrollmentAttemptReceipt(
    val binding: EnrollmentAttemptBinding,
    val replayExpiresAt: OffsetDateTime,
) {
    public fun accepts(candidate: EnrollmentAttemptBinding, now: OffsetDateTime): Boolean =
        now.isBefore(replayExpiresAt) && binding.securelyMatches(candidate)
}

private fun constantTimeAsciiEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
    first.toByteArray(Charsets.US_ASCII),
    second.toByteArray(Charsets.US_ASCII),
)

/**
 * Persistence adapter for participant one-time access codes and sessions.
 *
 * Raw access codes, sessions, and mutation tokens are never persisted. This service is deliberately
 * transport-neutral so it can move behind an Axum adapter without changing the SQL or wire model.
 */
public class ParticipantFormAccessService(
    private val storageResolver: StorageResolver,
    private val clock: Clock = Clock.systemUTC(),
) {
    internal companion object {
        private val secureRandom = SecureRandom()
        private const val TOKEN_BYTES = 48
        private const val MAX_TOKEN_CHARS = 256
        private val DEFAULT_ACCESS_CODE_LIFETIME: Duration = Duration.ofDays(7)
        private val MAX_ACCESS_CODE_LIFETIME: Duration = Duration.ofDays(30)
        private val SESSION_LIFETIME: Duration = Duration.ofHours(24)
        private val IDLE_LIFETIME: Duration = Duration.ofMinutes(30)
        private val ENROLLMENT_REPLAY_LIFETIME: Duration = Duration.ofHours(24)
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }

    public fun createAccessCode(
        studyId: UUID,
        participantId: String,
        formKind: ParticipantFormKind,
        resourceId: UUID?,
        logicalDate: LocalDate?,
        requestedExpiresAt: OffsetDateTime?,
        issuerType: ParticipantAccessCodeIssuerType,
        issuedBy: String,
    ): ParticipantFormAccessCodeResponse = createAccessCodes(
        listOf(
            ParticipantAccessCodeCommand(
                studyId,
                participantId,
                formKind,
                resourceId,
                logicalDate,
                requestedExpiresAt,
                issuerType,
                issuedBy,
            ),
        ),
    ).single()

    /**
     * Issues one code after revoking any still-live code from the same explicit issuer
     * scope. Used by reusable bootstrap credentials so repeated requests rotate the
     * resulting one-time capability instead of accumulating invitations.
     */
    public fun createReplacingAccessCode(
        studyId: UUID,
        participantId: String,
        formKind: ParticipantFormKind,
        resourceId: UUID?,
        logicalDate: LocalDate?,
        requestedExpiresAt: OffsetDateTime?,
        issuerType: ParticipantAccessCodeIssuerType,
        issuedBy: String,
    ): ParticipantFormAccessCodeResponse = createAccessCodesInternal(
        listOf(
            ParticipantAccessCodeCommand(
                studyId,
                participantId,
                formKind,
                resourceId,
                logicalDate,
                requestedExpiresAt,
                issuerType,
                issuedBy,
            ),
        ),
        replacePrior = true,
    ).single()

    /**
     * Issues a complete reminder/access set atomically. If any insert fails, prior device codes
     * remain valid and no partial manifest can strand already-scheduled reminders.
     */
    public fun createAccessCodes(
        commands: List<ParticipantAccessCodeCommand>,
    ): List<ParticipantFormAccessCodeResponse> = createAccessCodesInternal(commands, replacePrior = false)

    private fun createAccessCodesInternal(
        commands: List<ParticipantAccessCodeCommand>,
        replacePrior: Boolean,
    ): List<ParticipantFormAccessCodeResponse> {
        if (commands.isEmpty()) return emptyList()
        commands.forEach(::validateAccessCodeCommand)
        require(commands.distinctBy(::accessCodeRevocationScope).size == commands.size) {
            "A participant access-code batch cannot contain duplicate issuance targets"
        }
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                if (replacePrior) {
                    lockAccessCodeIssuanceScopes(connection, commands)
                }
                val responses = commands.map { command ->
                    createAccessCode(connection, command, now, replacePrior)
                }
                connection.commit()
                responses
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    private fun accessCodeRevocationScope(command: ParticipantAccessCodeCommand): List<Any?> = listOf(
        command.studyId,
        command.participantId,
        command.formKind,
        command.resourceId,
        command.issuerType,
        command.issuedBy.take(200),
    )

    private fun accessCodeIssuanceLockKey(command: ParticipantAccessCodeCommand): String =
        accessCodeRevocationScope(command).joinToString(separator = "|") { it?.toString() ?: "-" }

    private fun lockAccessCodeIssuanceScopes(
        connection: Connection,
        commands: List<ParticipantAccessCodeCommand>,
    ) {
        commands.sortedBy(::accessCodeIssuanceLockKey).forEach { command ->
            lockAccessCodeIssuanceScope(connection, command)
        }
    }

    private fun lockAccessCodeIssuanceScope(connection: Connection, command: ParticipantAccessCodeCommand) {
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
        ).use { statement ->
            statement.setString(1, accessCodeIssuanceLockKey(command))
            statement.execute()
        }
    }

    private fun validateAccessCodeCommand(command: ParticipantAccessCodeCommand) {
        require(command.participantId.isNotBlank()) { "participantId is required" }
        require(command.issuedBy.isNotBlank()) { "issuedBy is required" }
        require(command.formKind != ParticipantFormKind.QUESTIONNAIRE || command.resourceId != null) {
            "QUESTIONNAIRE access codes require resourceId"
        }
        require(command.formKind == ParticipantFormKind.QUESTIONNAIRE || command.resourceId == null) {
            "resourceId is only valid for QUESTIONNAIRE access codes"
        }
    }

    private fun createAccessCode(
        connection: Connection,
        command: ParticipantAccessCodeCommand,
        now: OffsetDateTime,
        replacePrior: Boolean,
    ): ParticipantFormAccessCodeResponse {
        val expiresAt = command.requestedExpiresAt ?: now.plus(DEFAULT_ACCESS_CODE_LIFETIME)
        require(expiresAt.isAfter(now)) { "Access code expiry must be in the future" }
        require(!expiresAt.isAfter(now.plus(MAX_ACCESS_CODE_LIFETIME))) { "Access code expiry exceeds 30 days" }
        val rawToken = generateToken()
        val tokenHash = sha256(rawToken)
        val accessCodeId = UUID.randomUUID()

        if (replacePrior || command.issuerType == ParticipantAccessCodeIssuerType.DEVICE) {
            connection.prepareStatement(
                """
                UPDATE participant_form_access_codes
                SET revoked_at = ?
                WHERE study_id = ? AND participant_id = ? AND form_kind = ?
                  AND resource_id IS NOT DISTINCT FROM ?
                  AND issuer_type = ? AND issued_by = ?
                  AND exchanged_at IS NULL AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, now)
                statement.setObject(2, command.studyId)
                statement.setString(3, command.participantId)
                statement.setString(4, command.formKind.name)
                statement.setObject(5, command.resourceId)
                statement.setString(6, command.issuerType.name)
                statement.setString(7, command.issuedBy.take(200))
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO participant_form_access_codes
                (access_code_id, token_hash, study_id, participant_id, form_kind, resource_id,
                 logical_date, issuer_type, issued_by, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, accessCodeId)
            statement.setBytes(2, tokenHash)
            statement.setObject(3, command.studyId)
            statement.setString(4, command.participantId)
            statement.setString(5, command.formKind.name)
            statement.setObject(6, command.resourceId)
            statement.setObject(7, command.logicalDate)
            statement.setString(8, command.issuerType.name)
            statement.setString(9, command.issuedBy.take(200))
            statement.setObject(10, expiresAt)
            check(statement.executeUpdate() == 1) { "Participant access code was not persisted" }
        }
        return ParticipantFormAccessCodeResponse(
            rawToken,
            expiresAt,
            command.formKind,
            command.resourceId,
            command.logicalDate,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    public fun exchangeAccessCode(rawAccessCode: String): ExchangedParticipantFormSession? {
        if (!isPlausibleToken(rawAccessCode)) return null
        val tokenHash = sha256(rawAccessCode)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.autoCommit = false
            try {
                val accessCode = loadExchangeableAccessCode(connection, tokenHash) ?: run {
                    connection.rollback()
                    return@use null
                }
                val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                val absoluteExpiry = minOf(accessCode.expiresAt, now.plus(SESSION_LIFETIME))
                val idleExpiry = minOf(absoluteExpiry, now.plus(IDLE_LIFETIME))
                val rawSessionToken = generateToken()
                val csrfToken = generateToken()
                val sessionId = UUID.randomUUID()

                val reserved = connection.prepareStatement(
                    "UPDATE participant_form_access_codes SET exchanged_at = ? WHERE access_code_id = ? AND exchanged_at IS NULL"
                ).use { statement ->
                    statement.setObject(1, now)
                    statement.setObject(2, accessCode.accessCodeId)
                    statement.executeUpdate() == 1
                }
                // Losing the reservation means a concurrent request exchanged this same code
                // between the load above and this UPDATE. That is exactly the "already used"
                // outcome loadExchangeableAccessCode reports by returning null, and the caller
                // turns into a 401 -- so answer it the same way. Throwing here made it a 500,
                // which surfaced as a browser sending the exchange twice for one page load and
                // getting an unexplained server error on the second.
                if (!reserved) {
                    connection.rollback()
                    return@use null
                }
                connection.prepareStatement(
                    """
                    INSERT INTO participant_form_sessions
                        (session_id, session_hash, access_code_id, study_id, participant_id, form_kind,
                         resource_id, logical_date, csrf_hash, idle_expires_at, absolute_expires_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.setBytes(2, sha256(rawSessionToken))
                    statement.setObject(3, accessCode.accessCodeId)
                    statement.setObject(4, accessCode.studyId)
                    statement.setString(5, accessCode.participantId)
                    statement.setString(6, accessCode.formKind.name)
                    statement.setObject(7, accessCode.resourceId)
                    statement.setObject(8, accessCode.logicalDate)
                    statement.setBytes(9, sha256(csrfToken))
                    statement.setObject(10, idleExpiry)
                    statement.setObject(11, absoluteExpiry)
                    statement.setObject(12, now)
                    check(statement.executeUpdate() == 1) { "Participant session was not persisted" }
                }
                connection.commit()
                ExchangedParticipantFormSession(
                    rawSessionToken = rawSessionToken,
                    response = ParticipantFormSessionResponse(
                        csrfToken = csrfToken,
                        studyId = accessCode.studyId,
                        participantId = accessCode.participantId,
                        formKind = accessCode.formKind,
                        resourceId = accessCode.resourceId,
                        logicalDate = accessCode.logicalDate,
                        expiresAt = absoluteExpiry,
                    ),
                )
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** Resolves an unexpired enrollment invitation without consuming it. */
    public fun resolveEnrollmentAccessCode(
        rawAccessCode: String,
        studyId: UUID,
        participantId: String,
    ): EnrollmentAccessCodeScope? {
        if (!isPlausibleToken(rawAccessCode) || participantId.isBlank()) return null
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            loadEnrollmentAccessCode(
                connection,
                sha256(rawAccessCode),
                studyId,
                participantId,
                now,
            )
        }
    }

    /**
     * Resolves an invitation for the v4 enrollment filter. A live unconsumed code or a consumed
     * code inside its exact-attempt recovery window may enter the controller; the controller then
     * verifies every durable binding field before doing any device or credential work.
     */
    public fun resolveEnrollmentAccessCodeForRequest(
        rawAccessCode: String,
        studyId: UUID,
        participantId: String,
    ): EnrollmentAccessCodeScope? {
        if (!isPlausibleToken(rawAccessCode) || participantId.isBlank()) return null
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            loadEnrollmentAccessCodeForRequest(connection, sha256(rawAccessCode), studyId, participantId, now)
        }
    }

    /**
     * Atomically consumes and binds a live invitation, or accepts an exact replay of the binding.
     * The disclosure predicate runs while both the invitation row and authoritative study row are
     * locked, so the first binding cannot cross a concurrent policy/settings update.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun authorizeEnrollmentAttempt(
        rawAccessCode: String,
        studyId: UUID,
        participantId: String,
        binding: EnrollmentAttemptBinding,
        predicate: (Connection, EnrollmentAccessCodeScope) -> Boolean,
    ): Boolean {
        if (!isPlausibleToken(rawAccessCode) || participantId.isBlank()) return false
        if (binding.studyId != studyId || binding.participantId != participantId) return false
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val authorized = authorizeLockedEnrollmentAttempt(
                    connection,
                    sha256(rawAccessCode),
                    studyId,
                    participantId,
                    binding,
                    now,
                    predicate,
                )
                if (authorized) connection.commit() else connection.rollback()
                authorized
            } catch (exception: SQLException) {
                connection.rollback()
                if (exception.sqlState == UNIQUE_VIOLATION_SQL_STATE) false else throw exception
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    private fun authorizeLockedEnrollmentAttempt(
        connection: Connection,
        tokenHash: ByteArray,
        studyId: UUID,
        participantId: String,
        binding: EnrollmentAttemptBinding,
        now: OffsetDateTime,
        predicate: (Connection, EnrollmentAccessCodeScope) -> Boolean,
    ): Boolean {
        lockEnrollmentDevice(connection, binding.deviceId)
        val stored = loadEnrollmentAttempt(connection, tokenHash, studyId, participantId) ?: return false
        return when {
            stored.exchangedAt != null -> stored.receipt?.accepts(binding, now) == true
            !now.isBefore(stored.scope.expiresAt) || stored.receipt != null -> false
            !predicate(connection, stored.scope) -> false
            else -> {
                revokeOtherEnrollmentReceipts(connection, stored.scope.accessCodeId, binding, now)
                bindEnrollmentAttempt(
                    connection,
                    stored.scope.accessCodeId,
                    binding,
                    now,
                    now.plus(ENROLLMENT_REPLAY_LIFETIME),
                )
            }
        }
    }

    /**
     * Atomically consumes a one-time code scoped to one participant enrollment.
     * Enrollment codes never become browser form sessions and cannot be replayed.
     */
    public fun consumeEnrollmentAccessCode(
        rawAccessCode: String,
        studyId: UUID,
        participantId: String,
    ): Boolean {
        if (!isPlausibleToken(rawAccessCode) || participantId.isBlank()) return false
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE participant_form_access_codes
                SET exchanged_at = ?
                WHERE token_hash = ? AND study_id = ? AND participant_id = ?
                  AND form_kind = 'ENROLLMENT' AND exchanged_at IS NULL
                  AND revoked_at IS NULL AND expires_at > ?
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, now)
                statement.setBytes(2, sha256(rawAccessCode))
                statement.setObject(3, studyId)
                statement.setString(4, participantId)
                statement.setObject(5, now)
                statement.executeUpdate() == 1
            }
        }
    }

    public fun resolveSession(
        rawSessionToken: String,
        mutationCsrfToken: String?,
        requireCsrf: Boolean,
    ): ParticipantFormAccessScope? {
        if (!isPlausibleToken(rawSessionToken) || (requireCsrf && !isPlausibleToken(mutationCsrfToken))) return null
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT session_id, access_code_id, study_id, participant_id, form_kind, resource_id,
                       logical_date, csrf_hash, absolute_expires_at
                FROM participant_form_sessions
                WHERE session_hash = ? AND revoked_at IS NULL
                  AND idle_expires_at > ? AND absolute_expires_at > ?
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, sha256(rawSessionToken))
                statement.setObject(2, now)
                statement.setObject(3, now)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return@use null
                    val expectedCsrfHash = resultSet.getBytes("csrf_hash")
                    if (requireCsrf && !MessageDigest.isEqual(expectedCsrfHash, sha256(mutationCsrfToken!!))) {
                        return@use null
                    }
                    val absoluteExpiry = resultSet.getObject("absolute_expires_at", OffsetDateTime::class.java)
                    val refreshedIdleExpiry = minOf(absoluteExpiry, now.plus(IDLE_LIFETIME))
                    val sessionId = resultSet.getObject("session_id", UUID::class.java)
                    connection.prepareStatement(
                        "UPDATE participant_form_sessions SET last_seen_at = ?, idle_expires_at = ? WHERE session_id = ?"
                    ).use { update ->
                        update.setObject(1, now)
                        update.setObject(2, refreshedIdleExpiry)
                        update.setObject(3, sessionId)
                        check(update.executeUpdate() == 1) { "Participant session refresh failed" }
                    }
                    ParticipantFormAccessScope(
                        accessCodeId = resultSet.getObject("access_code_id", UUID::class.java),
                        studyId = resultSet.getObject("study_id", UUID::class.java),
                        participantId = resultSet.getString("participant_id"),
                        formKind = ParticipantFormKind.valueOf(resultSet.getString("form_kind")),
                        resourceId = resultSet.getObject("resource_id", UUID::class.java),
                        logicalDate = resultSet.getObject("logical_date", LocalDate::class.java),
                        absoluteExpiresAt = absoluteExpiry,
                    )
                }
            }
        }
    }

    private fun loadExchangeableAccessCode(connection: Connection, tokenHash: ByteArray): StoredAccessCode? {
        return connection.prepareStatement(
            """
            SELECT access_code_id, study_id, participant_id, form_kind, resource_id, logical_date, expires_at
            FROM participant_form_access_codes
            WHERE token_hash = ? AND form_kind <> 'ENROLLMENT'
              AND exchanged_at IS NULL AND revoked_at IS NULL AND expires_at > now()
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setBytes(1, tokenHash)
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
    )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun isPlausibleToken(value: String?): Boolean =
        value != null && value.length in 32..MAX_TOKEN_CHARS && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private data class StoredAccessCode(
        val accessCodeId: UUID,
        val studyId: UUID,
        val participantId: String,
        val formKind: ParticipantFormKind,
        val resourceId: UUID?,
        val logicalDate: LocalDate?,
        val expiresAt: OffsetDateTime,
    )

}

private fun lockEnrollmentDevice(connection: Connection, deviceId: UUID) {
    connection.prepareStatement(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
    ).use { statement ->
        statement.setObject(1, deviceId)
        statement.execute()
    }
}

private fun revokeOtherEnrollmentReceipts(
    connection: Connection,
    accessCodeId: UUID,
    binding: EnrollmentAttemptBinding,
    now: OffsetDateTime,
) {
    connection.prepareStatement(
        """
        UPDATE participant_form_access_codes
        SET revoked_at = ?
        WHERE study_id = ? AND participant_id = ? AND form_kind = 'ENROLLMENT'
          AND enrollment_device_id = ? AND access_code_id <> ?
          AND enrollment_attempt_id IS NOT NULL AND revoked_at IS NULL
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, now)
        statement.setObject(2, binding.studyId)
        statement.setString(3, binding.participantId)
        statement.setObject(4, binding.deviceId)
        statement.setObject(5, accessCodeId)
        statement.executeUpdate()
    }
}

private fun loadEnrollmentAccessCodeForRequest(
    connection: Connection,
    tokenHash: ByteArray,
    studyId: UUID,
    participantId: String,
    now: OffsetDateTime,
): EnrollmentAccessCodeScope? = connection.prepareStatement(
    """
    SELECT access_code_id, created_at, expires_at
    FROM participant_form_access_codes
    WHERE token_hash = ? AND study_id = ? AND participant_id = ?
      AND form_kind = 'ENROLLMENT' AND revoked_at IS NULL
      AND (
          (exchanged_at IS NULL AND expires_at > ?)
          OR (enrollment_attempt_id IS NOT NULL AND enrollment_replay_expires_at > ?)
      )
    """.trimIndent(),
).use { statement ->
    statement.setBytes(1, tokenHash)
    statement.setObject(2, studyId)
    statement.setString(3, participantId)
    statement.setObject(4, now)
    statement.setObject(5, now)
    statement.executeQuery().use { resultSet ->
        if (resultSet.next()) enrollmentAccessCodeScope(resultSet, studyId, participantId) else null
    }
}

private fun loadEnrollmentAttempt(
    connection: Connection,
    tokenHash: ByteArray,
    studyId: UUID,
    participantId: String,
): StoredEnrollmentAttempt? = connection.prepareStatement(
    """
    SELECT access_code_id, created_at, expires_at, exchanged_at,
           enrollment_attempt_id, enrollment_source_device_hash, enrollment_device_id,
           enrollment_manifest_digest, enrollment_request_hash, enrollment_proposed_key_hash,
           enrollment_replay_expires_at, enrollment_settings_version,
           enrollment_disclosure_version, enrollment_enabled_modules, enrollment_required_modules
    FROM participant_form_access_codes
    WHERE token_hash = ? AND study_id = ? AND participant_id = ?
      AND form_kind = 'ENROLLMENT' AND revoked_at IS NULL
    FOR UPDATE
    """.trimIndent(),
).use { statement ->
    statement.setBytes(1, tokenHash)
    statement.setObject(2, studyId)
    statement.setString(3, participantId)
    statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) return@use null
        val scope = enrollmentAccessCodeScope(resultSet, studyId, participantId)
        val attemptId = resultSet.getObject("enrollment_attempt_id", UUID::class.java)
        val receipt = attemptId?.let {
            EnrollmentAttemptReceipt(
                EnrollmentAttemptBinding(
                    attemptId = it,
                    studyId = studyId,
                    participantId = participantId,
                    sourceDeviceHash = resultSet.getString("enrollment_source_device_hash"),
                    deviceId = resultSet.getObject("enrollment_device_id", UUID::class.java),
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

private fun bindEnrollmentAttempt(
    connection: Connection,
    accessCodeId: UUID,
    binding: EnrollmentAttemptBinding,
    now: OffsetDateTime,
    replayExpiresAt: OffsetDateTime,
): Boolean = connection.prepareStatement(
    """
    UPDATE participant_form_access_codes
    SET exchanged_at = ?, enrollment_attempt_id = ?, enrollment_source_device_hash = ?,
        enrollment_device_id = ?, enrollment_manifest_digest = ?, enrollment_request_hash = ?,
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
    statement.setObject(4, binding.deviceId)
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

private fun loadEnrollmentAccessCode(
    connection: Connection,
    tokenHash: ByteArray,
    studyId: UUID,
    participantId: String,
    now: OffsetDateTime,
): EnrollmentAccessCodeScope? {
    val sql = """
        SELECT access_code_id, created_at, expires_at
        FROM participant_form_access_codes
        WHERE token_hash = ? AND study_id = ? AND participant_id = ?
          AND form_kind = 'ENROLLMENT' AND exchanged_at IS NULL
          AND revoked_at IS NULL AND expires_at > ?
    """.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
        statement.setBytes(1, tokenHash)
        statement.setObject(2, studyId)
        statement.setString(3, participantId)
        statement.setObject(4, now)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) enrollmentAccessCodeScope(resultSet, studyId, participantId) else null
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

private data class StoredEnrollmentAttempt(
    val scope: EnrollmentAccessCodeScope,
    val exchangedAt: OffsetDateTime?,
    val receipt: EnrollmentAttemptReceipt?,
)
