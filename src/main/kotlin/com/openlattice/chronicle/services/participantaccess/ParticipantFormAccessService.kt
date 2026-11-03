package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.services.participantaccess.ParticipantSessionSecrets.generateToken
import com.openlattice.chronicle.services.participantaccess.ParticipantSessionSecrets.isPlausibleToken
import com.openlattice.chronicle.services.participantaccess.ParticipantSessionSecrets.sha256
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.ParticipantCollectionGate
import com.openlattice.chronicle.storage.ParticipantErasureFence
import com.openlattice.chronicle.storage.ParticipantProcessingBlockedException
import com.openlattice.chronicle.storage.ParticipantProcessingGate
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SqlFailures
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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
) {
    /** A researcher's enrollment invitation: its code derives from its id, so the link already sent can be shown again. */
    val isEnrollmentInvitation: Boolean
        get() = formKind == ParticipantFormKind.ENROLLMENT && issuerType == ParticipantAccessCodeIssuerType.RESEARCHER
}

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
        window: ParticipantFormWindow? = null,
    ): Boolean {
        if (logicalDate != null && window != null && !window.isWithin(logicalDate)) return false
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
    val committedBinding: EnrollmentAttemptBinding? = null,
)

/** Hash-only identity of one public enrollment request. No client credential is retained in plaintext. */
public data class EnrollmentAttemptBinding(
    val attemptId: UUID,
    val studyId: UUID,
    val participantId: String,
    val sourceDeviceHash: String,
    val randomDeviceId: UUID,
    val manifestDigest: String,
    val requestHash: String,
    val proposedApiKeyHash: String,
    /** Replay requires complete consent evidence; incomplete bindings never authorize a retry. */
    val enrollmentSettingsVersion: Int?,
    val enrollmentDisclosureVersion: String?,
    val enrollmentEnabledModules: Set<CollectionModuleId>?,
    val enrollmentRequiredModules: Set<CollectionModuleId>?,
) {
    internal fun securelyMatches(other: EnrollmentAttemptBinding): Boolean =
        sameIdentity(other) && sameHashes(other) && sameEnrollmentTerms(other)

    private fun sameIdentity(other: EnrollmentAttemptBinding): Boolean =
        attemptId == other.attemptId && studyId == other.studyId &&
            participantId == other.participantId && randomDeviceId == other.randomDeviceId

    private fun sameHashes(other: EnrollmentAttemptBinding): Boolean =
        constantTimeAsciiEquals(sourceDeviceHash, other.sourceDeviceHash) &&
            constantTimeAsciiEquals(manifestDigest, other.manifestDigest) &&
            constantTimeAsciiEquals(requestHash, other.requestHash) &&
            constantTimeAsciiEquals(proposedApiKeyHash, other.proposedApiKeyHash)

    /** Every enrollment term must be recorded on this binding and equal on the candidate. */
    private fun sameEnrollmentTerms(other: EnrollmentAttemptBinding): Boolean =
        enrollmentSettingsVersion != null && enrollmentSettingsVersion == other.enrollmentSettingsVersion &&
            enrollmentDisclosureVersion != null && enrollmentDisclosureVersion == other.enrollmentDisclosureVersion &&
            enrollmentEnabledModules != null && enrollmentEnabledModules == other.enrollmentEnabledModules &&
            enrollmentRequiredModules != null && enrollmentRequiredModules == other.enrollmentRequiredModules
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
    auditingManager: AuditingManager,
    sessionSecret: ByteArray,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessionSecret = sessionSecret.copyOf().also {
        require(it.size >= 32) { "Participant sessions require the deployment's internal web secret" }
    }
    private val pendingEnrollments = PendingEnrollmentService(storageResolver, auditingManager, clock)
    private val sessionAdmission = ParticipantSessionAdmission(storageResolver, clock)

    public fun getPendingEnrollments(studyId: UUID, limit: Int = 100, offset: Int = 0): List<String> =
        pendingEnrollments.getPendingEnrollments(studyId, limit, offset)

    public fun getSettingLocks(studyId: UUID): List<StudySettingType> = pendingEnrollments.getSettingLocks(studyId)

    public fun cancelPendingEnrollment(studyId: UUID, participantId: String): Int =
        pendingEnrollments.cancelPendingEnrollment(studyId, participantId)

    internal companion object {
        private val DEFAULT_ACCESS_CODE_LIFETIME: Duration = Duration.ofDays(7)
        private val MAX_ACCESS_CODE_LIFETIME: Duration = Duration.ofDays(30)
        private val SESSION_LIFETIME: Duration = Duration.ofHours(24)
        internal val IDLE_LIFETIME: Duration = Duration.ofMinutes(30)
        internal val FORM_EXCHANGE_REPLAY_WINDOW: Duration = Duration.ofMinutes(5)
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
            connection.withTransaction {
                DeletionStudyFence.shared(connection, commands.map { it.studyId })
                lockAccessCodeIssuanceScopes(connection, commands.filter { shouldReplaceCode(it, replacePrior) })
                val responses = commands.map { command ->
                    ParticipantErasureFence.requireReusable(connection, command.studyId, command.participantId)
                    // Enrollment codes precede consent; every other form follows its admission rule.
                    if (command.formKind != ParticipantFormKind.ENROLLMENT &&
                        !ParticipantCollectionGate(connection).admitsForm(command.formKind, command.studyId, command.participantId)) {
                        throw CollectionUnavailableException(CollectionUnavailableException.Scope.PARTICIPANT)
                    }
                    createAccessCode(connection, command, now, replacePrior)
                }
                connection.commit()
                responses
            }
        }
    }

    private fun shouldReplaceCode(command: ParticipantAccessCodeCommand, replacePrior: Boolean): Boolean =
        replacePrior || command.issuerType == ParticipantAccessCodeIssuerType.DEVICE ||
            command.issuerType == ParticipantAccessCodeIssuerType.RESEARCHER

    private data class IssuanceScope(
        val studyId: UUID,
        val participantId: String,
        val formKind: ParticipantFormKind,
        val resourceId: UUID?,
        val issuerType: ParticipantAccessCodeIssuerType,
        val issuedBy: String?,
    ) {
        fun lockKey(): String = "$studyId|$participantId|${formKind.name}|$resourceId|${issuerType.name}|$issuedBy"
    }

    private fun accessCodeRevocationScope(command: ParticipantAccessCodeCommand): IssuanceScope = IssuanceScope(
        studyId = command.studyId,
        participantId = command.participantId,
        formKind = command.formKind,
        resourceId = command.resourceId,
        issuerType = command.issuerType,
        issuedBy = command.issuedBy.take(200).takeUnless { command.issuerType == ParticipantAccessCodeIssuerType.RESEARCHER },
    )

    private fun lockAccessCodeIssuanceScopes(
        connection: Connection,
        commands: List<ParticipantAccessCodeCommand>,
    ) {
        commands.map { accessCodeRevocationScope(it).lockKey() }.sorted().forEach { key ->
            EnrollmentAccessCodeRepository(connection).lockScope(key)
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
        // The server's survey days decide which app-usage days are answerable; a code is never bound to one.
        require(command.formKind != ParticipantFormKind.APP_USAGE || command.logicalDate == null) {
            "APP_USAGE access codes are not bound to a day"
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
        val accessCodeId = UUID.randomUUID()
        val rawToken = if (!command.isEnrollmentInvitation) generateToken()
        else ParticipantSessionSecrets.enrollmentInvitationCode(accessCodeId, sessionSecret)

        if (shouldReplaceCode(command, replacePrior)) {
            connection.prepareStatement(
                """
                UPDATE participant_form_access_codes
                SET revoked_at = ?
                WHERE study_id = ? AND participant_id = ? AND form_kind = ?
                  AND resource_id IS NOT DISTINCT FROM ?
                  AND logical_date IS NOT DISTINCT FROM ?
                  AND issuer_type = ? AND (? OR issued_by = ?)
                  AND exchanged_at IS NULL AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                // One live code per form and day: a diary link for yesterday never revokes today's.
                statement.setObject(1, now)
                statement.setObject(2, command.studyId)
                statement.setString(3, command.participantId)
                statement.setString(4, command.formKind.name)
                statement.setObject(5, command.resourceId)
                statement.setObject(6, command.logicalDate)
                statement.setString(7, command.issuerType.name)
                statement.setBoolean(8, command.issuerType == ParticipantAccessCodeIssuerType.RESEARCHER)
                statement.setString(9, command.issuedBy.take(200))
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
            statement.setBytes(2, sha256(rawToken))
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

    /** Whether a cancel (dashboard or phone) ended this enrollment attempt; its enroll is answered as cancelled. */
    public fun isCancelledEnrollmentAttempt(attemptId: UUID): Boolean =
        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) { EnrollmentAttemptRepository(connection).isCancelled(attemptId) }
        }

    /**
     * The researcher's live enrollment invitation for this participant, the link already sent: unexchanged, unrevoked
     * and unexpired. Null when there is none, or when it was issued under another deployment secret.
     */
    public fun liveEnrollmentInvitation(studyId: UUID, participantId: String): ParticipantFormAccessCodeResponse? {
        val live = storageResolver.getPlatformStorage().connection.use { connection ->
            EnrollmentAccessCodeRepository(connection).liveInvitation(studyId, participantId, OffsetDateTime.now(clock))
        } ?: return null
        val code = ParticipantSessionSecrets.enrollmentInvitationCode(live.accessCodeId, sessionSecret)
        if (!MessageDigest.isEqual(sha256(code), live.tokenHash)) return null
        return ParticipantFormAccessCodeResponse(code, live.expiresAt.withOffsetSameInstant(ZoneOffset.UTC),
            ParticipantFormKind.ENROLLMENT)
    }

    @Suppress("TooGenericExceptionCaught")
    public fun exchangeAccessCode(rawAccessCode: String, attemptId: UUID = UUID.randomUUID()): ExchangedParticipantFormSession? {
        if (!isPlausibleToken(rawAccessCode)) return null
        val tokenHash = sha256(rawAccessCode)
        return storageResolver.withParticipantCredentialStorage(studyId = null) { connection ->
            connection.withTransaction {
                val sessions = ParticipantFormSessionRepository(connection)
                val studyId = sessions.findStudy(tokenHash) ?: run { connection.rollback(); return@withTransaction null }
                DeletionStudyFence.shared(connection, studyId)
                val accessCode = sessions.loadExchangeableAccessCode(tokenHash) ?: run {
                    connection.rollback()
                    return@withTransaction null
                }
                if (accessCode.studyId != studyId) { connection.rollback(); return@withTransaction null }
                val rawSessionToken = ParticipantSessionSecrets.sessionToken(rawAccessCode, attemptId, sessionSecret)
                val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                val replayExpiry = if (accessCode.exchangedAt == null) null else {
                    if (!now.isBefore(accessCode.exchangedAt.plus(FORM_EXCHANGE_REPLAY_WINDOW))) {
                        connection.rollback()
                        return@withTransaction null
                    }
                    sessions.replayExpiry(accessCode.accessCodeId, sha256(rawSessionToken)) ?: run {
                        connection.rollback()
                        return@withTransaction null
                    }
                }
                // Only a live, unused capability may reveal an unavailable participation scope.
                ParticipantProcessingGate.requireAvailable(connection, studyId, accessCode.participantId)
                if (!ParticipantCollectionGate(connection).admitsForm(accessCode.formKind, studyId, accessCode.participantId)) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.form.accessInactive"))
                }
                val session = createSession(sessions, accessCode, now, rawSessionToken, replayExpiry)
                if (session == null) connection.rollback() else connection.commit()
                session
            }
        }
    }

    public fun recoverSession(rawSessionToken: String): ParticipantFormSessionResponse? {
        val scope = resolveSession(rawSessionToken, null, requireCsrf = false) ?: return null
        return ParticipantFormSessionResponse(
            csrfToken = ParticipantSessionSecrets.csrfToken(rawSessionToken),
            studyId = scope.studyId,
            participantId = scope.participantId,
            formKind = scope.formKind,
            resourceId = scope.resourceId,
            logicalDate = scope.logicalDate,
            expiresAt = scope.absoluteExpiresAt,
        )
    }

    private fun createSession(
        sessions: ParticipantFormSessionRepository,
        accessCode: StoredAccessCode,
        now: OffsetDateTime,
        rawSessionToken: String,
        replayExpiry: OffsetDateTime?,
    ): ExchangedParticipantFormSession? {
        val sessionHash = sha256(rawSessionToken)
        val absoluteExpiry = replayExpiry ?: minOf(accessCode.expiresAt, now.plus(SESSION_LIFETIME))
        val idleExpiry = minOf(absoluteExpiry, now.plus(IDLE_LIFETIME))
        val csrfToken = ParticipantSessionSecrets.csrfToken(rawSessionToken)
        val sessionId = UUID.randomUUID()

        val reserved = replayExpiry != null || sessions.reserve(accessCode.accessCodeId, now)
        // Losing the reservation means a concurrent request exchanged this same code
        // between the load above and this UPDATE. That is exactly the "already used"
        // outcome loadExchangeableAccessCode reports by returning null, and the caller
        // turns into a 401 -- so answer it the same way. Throwing here made it a 500,
        // which surfaced as a browser sending the exchange twice for one page load and
        // getting an unexplained server error on the second.
        if (!reserved) return null
        if (replayExpiry == null) sessions.insert(ParticipantFormSessionRecord(
            sessionId = sessionId,
            sessionHash = sessionHash,
            accessCodeId = accessCode.accessCodeId,
            studyId = accessCode.studyId,
            participantId = accessCode.participantId,
            formKind = accessCode.formKind,
            resourceId = accessCode.resourceId,
            logicalDate = accessCode.logicalDate,
            csrfHash = sha256(csrfToken),
            idleExpiresAt = idleExpiry,
            absoluteExpiresAt = absoluteExpiry,
            lastSeenAt = now,
        ))
        return ExchangedParticipantFormSession(
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
    }

    /** Resolves an unexpired enrollment invitation without consuming it. */
    public fun resolveEnrollmentAccessCode(
        rawAccessCode: String,
        studyId: UUID,
        participantId: String,
    ): EnrollmentAccessCodeScope? {
        if (!isPlausibleToken(rawAccessCode) || participantId.isBlank()) return null
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.withParticipantCredentialStorage(studyId = studyId) { connection ->
            EnrollmentAttemptRepository(connection).load(
                EnrollmentAccessCodeQuery(sha256(rawAccessCode), studyId, participantId), now,
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
        return storageResolver.withParticipantCredentialStorage(studyId = studyId) { connection ->
            EnrollmentAttemptRepository(connection).loadForRequest(
                EnrollmentAccessCodeQuery(sha256(rawAccessCode), studyId, participantId), now,
            )
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
        return storageResolver.withParticipantCredentialStorage(studyId = studyId) { connection ->
            try {
                connection.withTransaction {
                    val authorized = authorizeLockedEnrollmentAttempt(
                        connection,
                        EnrollmentAccessCodeQuery(sha256(rawAccessCode), studyId, participantId),
                        binding,
                        now,
                        predicate,
                    )
                    if (authorized) connection.commit() else connection.rollback()
                    authorized
                }
            } catch (exception: SQLException) {
                if (exception.sqlState == UNIQUE_VIOLATION_SQL_STATE) false else throw SqlFailures.sanitize(exception)
            }
        }
    }

    private fun authorizeLockedEnrollmentAttempt(
        connection: Connection,
        query: EnrollmentAccessCodeQuery,
        binding: EnrollmentAttemptBinding,
        now: OffsetDateTime,
        predicate: (Connection, EnrollmentAccessCodeScope) -> Boolean,
    ): Boolean {
        DeletionStudyFence.shared(connection, query.studyId)
        lockEnrollmentAttempt(connection, binding.attemptId)
        lockDeviceEvidence(connection, binding.randomDeviceId)
        val attempts = EnrollmentAttemptRepository(connection)
        val stored = attempts.loadAttempt(query) ?: return false
        return when {
            stored.exchangedAt != null -> stored.receipt?.accepts(binding, now) == true
            !now.isBefore(stored.scope.expiresAt) || stored.receipt != null -> false
            // A phone cancelled this attempt before the server bound it; its delayed request never binds.
            RLSConnectionCustomizer.withRestoredAdminTransactionContext(connection) {
                attempts.isCancelled(binding.attemptId)
            } -> false
            !predicate(connection, stored.scope) -> false
            else -> {
                attempts.revokeOtherEnrollmentReceipts(stored.scope.accessCodeId, binding, now)
                attempts.bindEnrollmentAttempt(
                    stored.scope.accessCodeId,
                    binding,
                    now,
                    now.plus(ENROLLMENT_REPLAY_LIFETIME),
                )
            }
        }
    }

    /**
     * Resolves the form session a request acts as. The host-wide cookie holds only the browser's
     * newest session, so a second form tab replaces it; the per-tab CSRF token then selects that
     * tab's own session among the same participant's live sessions. The cookie still has to be a
     * live (unrevoked, unexpired) session of that participant, and a mutation still needs a CSRF
     * token whose hash a live session holds. The selected session alone carries the idle timeout:
     * the cookie only binds the browser, and its own tab may have closed.
     */
    public fun resolveSession(
        rawSessionToken: String,
        mutationCsrfToken: String?,
        requireCsrf: Boolean,
    ): ParticipantFormAccessScope? = sessionAdmission.admitted(rawSessionToken, mutationCsrfToken, requireCsrf, touch = true)

    /**
     * Whether [resolveSession] would admit these credentials now, decided by the same rules but read-only: the
     * session's idle clock is not extended and nothing is written, so a caller that checks before the participant
     * filter resolves does not refresh the session twice. A participant whose processing is blocked is not admitted.
     * Database failures propagate as [SQLException], allowing the limiter to count an unverified credential.
     */
    public fun isAdmittedSession(rawSessionToken: String, mutationCsrfToken: String?, requireCsrf: Boolean): Boolean = try {
        sessionAdmission.admitted(rawSessionToken, mutationCsrfToken, requireCsrf, touch = false) != null
    } catch (_: ParticipantProcessingBlockedException) {
        false
    }
}

/** Token-only lookups precede identity establishment and must opt in to database privilege. */
internal fun <T> StorageResolver.withParticipantCredentialStorage(studyId: UUID?, block: (Connection) -> T): T {
    val context = RLSConnectionContext("participant-credential", setOfNotNull(studyId), isAdmin = studyId == null)
    return RLSRequestContext.withContext(context) {
        getPlatformStorage().connection.use(block)
    }
}

/** The one owner of form-session admission rules, for both the participant filter and the pre-authentication limiter. */
internal class ParticipantSessionAdmission(private val storageResolver: StorageResolver, private val clock: Clock) {
    /** The admitted scope; [touch] refreshes its idle clock, otherwise rollback releases the read's locks before returning. */
    fun admitted(
        rawSessionToken: String,
        mutationCsrfToken: String?,
        requireCsrf: Boolean,
        touch: Boolean,
    ): ParticipantFormAccessScope? {
        if (!isPlausibleToken(rawSessionToken) || (requireCsrf && !isPlausibleToken(mutationCsrfToken))) return null
        val csrfHash = mutationCsrfToken?.takeIf(::isPlausibleToken)?.let(::sha256)
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return storageResolver.withParticipantCredentialStorage(studyId = null) { connection ->
            connection.withTransaction(rollbackOnSuccess = !touch) {
                val sessions = ParticipantFormSessionRepository(connection)
                val cookieSession = sessions.cookieSession(sha256(rawSessionToken), now)
                val session = when {
                    cookieSession == null -> null
                    csrfHash == null || MessageDigest.isEqual(cookieSession.csrfHash, csrfHash) ->
                        cookieSession.takeIf { it.idleExpiresAt.isAfter(now) }
                    else -> sessions.tabSession(cookieSession.scope, csrfHash, now)
                        ?: cookieSession.takeIf { !requireCsrf && it.idleExpiresAt.isAfter(now) }
                }
                val activeSession = session?.takeIf {
                    ParticipantProcessingGate.requireAvailable(connection, it.scope.studyId, it.scope.participantId)
                    ParticipantCollectionGate(connection).admitsForm(it.scope.formKind, it.scope.studyId, it.scope.participantId)
                }
                if (touch) {
                    activeSession?.let {
                        sessions.refresh(
                            it, now, minOf(it.scope.absoluteExpiresAt, now.plus(ParticipantFormAccessService.IDLE_LIFETIME)),
                        )
                    }
                    connection.commit()
                }
                activeSession?.scope
            }
        }
    }
}

internal class PendingEnrollmentService(
    private val storageResolver: StorageResolver,
    private val auditingManager: AuditingManager,
    private val clock: Clock,
) {
    public fun getPendingEnrollments(studyId: UUID, limit: Int, offset: Int): List<String> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            EnrollmentAccessCodeRepository(connection).pendingParticipantIds(studyId, limit, offset)
        }

    public fun getSettingLocks(studyId: UUID): List<StudySettingType> =
        storageResolver.getPlatformStorage().connection.use { connection ->
            if (EnrollmentAccessCodeRepository(connection).hasActiveEnrollment(studyId))
                StudySettingType.entries.filter { it.lockedAfterEnrollment } else emptyList()
        }

    public fun cancelPendingEnrollment(studyId: UUID, participantId: String): Int =
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Int>(connection, auditingManager)
                .transaction {
                    DeletionStudyFence.shared(it, studyId)
                    val codes = EnrollmentAccessCodeRepository(it)
                    val pending = codes.pendingAttempts(studyId, participantId)
                    // Recheck after the device locks, preserving acknowledgments that won the race.
                    pending.map { attempt -> attempt.enrollmentRandomDeviceId }.distinct().sortedBy(UUID::toString)
                        .forEach { randomDeviceId -> lockDeviceEvidence(it, randomDeviceId) }
                    if (pending.isEmpty()) 0 else EnrollmentAttemptRepository(it).cancelAttempts(
                        studyId = studyId,
                        participantId = participantId,
                        accessCodeIds = pending.mapTo(mutableSetOf()) { attempt -> attempt.accessCodeId },
                        now = OffsetDateTime.now(clock),
                    ).cancellable
                }
                .audit { revoked -> listOf(AuditableEvent(
                    aclKey = AclKey(studyId),
                    study = studyId,
                    eventType = AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE,
                    data = mapOf(
                        "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                        "formKind" to ParticipantFormKind.ENROLLMENT.name,
                        "revokedCount" to revoked,
                    ),
                )) }
                .buildAndRun()
        }

}
