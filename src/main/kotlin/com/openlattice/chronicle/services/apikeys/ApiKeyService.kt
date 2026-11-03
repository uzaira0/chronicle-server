package com.openlattice.chronicle.services.apikeys

import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyCreateResponse
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.services.participantaccess.AttemptCancellation
import com.openlattice.chronicle.services.participantaccess.BoundEnrollmentAttempt
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptRepository
import com.openlattice.chronicle.services.participantaccess.lockEnrollmentAttempt
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.API_KEYS
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.ParticipantErasureFence
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.CREATED_BY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.EXPIRES_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_HASH
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.KEY_PREFIX
import com.openlattice.chronicle.storage.PostgresColumns.Companion.LAST_USED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.NAME
import com.openlattice.chronicle.storage.PostgresColumns.Companion.REVOKED
import com.openlattice.chronicle.storage.PostgresColumns.Companion.SCOPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.USAGE_COUNT
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class ApiKeyService(
    private val storageResolver: StorageResolver,
    private val idGenerationService: HazelcastIdGenerationService,
    override val auditingManager: AuditingManager
) : AuditingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ApiKeyService::class.java)
        private val secureRandom = SecureRandom()
        private const val KEY_LENGTH = 32
        internal const val PREFIX_LENGTH = 8
        private const val MAX_KEY_NAME_LENGTH = 255
        private const val MAX_READ_ONLY_KEY_TTL_DAYS = 365
        private const val MAX_WRITE_KEY_TTL_DAYS = 90
        private const val MAX_ADMIN_KEY_TTL_DAYS = 30
        private const val MAX_MOBILE_KEY_TTL_DAYS = 365
        private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val PROPOSED_MOBILE_KEY_PATTERN = Regex("^ck_([0-9A-Za-z]{8})_([0-9A-Za-z]{32})$")

        private val INSERT_API_KEY_SQL = """
            INSERT INTO ${API_KEYS.name}
                (${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_HASH.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name}, ${CREATED_BY.name}, ${EXPIRES_AT.name})
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val INSERT_MOBILE_API_KEY_SQL = """
            INSERT INTO ${API_KEYS.name}
                (${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_HASH.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name}, ${CREATED_BY.name}, ${EXPIRES_AT.name}, participant_id, random_device_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val SELECT_API_KEYS_SQL = """
            SELECT ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                   ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                   participant_id, random_device_id
            FROM ${API_KEYS.name}
            WHERE ${STUDY_ID.name} = ? AND ${REVOKED.name} = false
        """.trimIndent()
        private val LIST_API_KEYS_SQL = "$SELECT_API_KEYS_SQL ORDER BY ${CREATED_AT.name} DESC, ${KEY_ID.name} LIMIT ? OFFSET ?"
        private val LOCK_API_KEY_FOR_ROTATION_SQL = "$SELECT_API_KEYS_SQL AND ${KEY_ID.name} = ? FOR UPDATE"

        private val REVOKE_API_KEY_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${REVOKED.name} = true
            WHERE ${KEY_ID.name} = ? AND ${STUDY_ID.name} = ?
        """.trimIndent()

        // A live key: authentication admits it, and the pre-authentication limiter does not count it.
        private val LIVE_KEY_PREDICATE = "${KEY_HASH.name} = ? AND ${REVOKED.name} = false AND ${EXPIRES_AT.name} > now()"

        private val FIND_LIVE_KEY_SQL = """
            SELECT ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                   ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                   participant_id, random_device_id
            FROM ${API_KEYS.name} WHERE $LIVE_KEY_PREDICATE
        """.trimIndent()

        // Atomically validate, increment counter, and return key metadata in a single
        // round-trip. Removes the prior LOOKUP + UPDATE_USAGE two-statement pattern.
        // A device-bound mobile key slides its expiry on every successful use while its enrollment is
        // live: nothing on the device renews it, so a fixed expiry silently stopped collection on day
        // 366 of a long study. The key slides only while the participant is ENROLLED/PAUSED and no
        // participant or study erasure (quarantined, held, failed, erasing or completed) covers it;
        // both are explicit predicates, read in the deletion-worker context, so neither depends on
        // RLS (a superuser pool bypasses it before RLSContextFilter runs). A collected-data purge
        // keeps the enrollment, so its key keeps sliding. Other keys keep their fixed expiry.
        private val LOOKUP_AND_TOUCH_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${LAST_USED_AT.name} = now(), ${USAGE_COUNT.name} = ${USAGE_COUNT.name} + 1,
                ${EXPIRES_AT.name} = CASE
                    WHEN participant_id IS NOT NULL AND random_device_id IS NOT NULL AND EXISTS (
                        SELECT 1 FROM study_participants sp
                        WHERE sp.study_id = ${API_KEYS.name}.${STUDY_ID.name}
                          AND sp.participant_id = ${API_KEYS.name}.participant_id
                          AND sp.participation_status IN ('ENROLLED', 'PAUSED')
                    ) AND NOT chronicle_has_participant_erasure(
                        ${API_KEYS.name}.${STUDY_ID.name}, ${API_KEYS.name}.participant_id
                    )
                        THEN now() + interval '$MAX_MOBILE_KEY_TTL_DAYS days'
                    ELSE ${EXPIRES_AT.name}
                END
            WHERE $LIVE_KEY_PREDICATE
            RETURNING ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                      ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                      participant_id, random_device_id, ${CREATED_BY.name}
        """.trimIndent()

        // Revoked mobile credentials remain usable only for an idempotent withdrawal retry. A key
        // deleted by erasure reaches the withdrawal route through its tombstone instead.
        private val LOOKUP_WITHDRAWAL_KEY_SQL = """
            SELECT candidate.${KEY_ID.name}, candidate.${STUDY_ID.name}, candidate.${KEY_PREFIX.name},
                   candidate.${NAME.name}, candidate.${SCOPE.name}, candidate.${CREATED_AT.name},
                   candidate.${EXPIRES_AT.name}, candidate.${LAST_USED_AT.name}, candidate.${USAGE_COUNT.name},
                   candidate.participant_id, candidate.random_device_id
            FROM ${API_KEYS.name} candidate
            LEFT JOIN mobile_withdrawal_requests withdrawal
              ON withdrawal.api_key_id = candidate.${KEY_ID.name}
             AND withdrawal.request_id = ?
             AND withdrawal.${STUDY_ID.name} = candidate.${STUDY_ID.name}
             AND withdrawal.participant_id = candidate.participant_id
             AND withdrawal.random_device_id = candidate.random_device_id
            WHERE candidate.${KEY_HASH.name} = ? AND candidate.${EXPIRES_AT.name} > now()
              AND candidate.participant_id IS NOT NULL AND candidate.random_device_id IS NOT NULL
              AND (
                  candidate.${REVOKED.name} = false
                  OR (
                      withdrawal.request_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM ${API_KEYS.name} active
                          WHERE active.${STUDY_ID.name} = candidate.${STUDY_ID.name}
                            AND active.participant_id = candidate.participant_id
                            AND active.random_device_id = candidate.random_device_id
                            AND active.${REVOKED.name} = false
                            AND active.${EXPIRES_AT.name} > now()
                            AND active.${KEY_ID.name} <> candidate.${KEY_ID.name}
                      )
                  )
              )
        """.trimIndent()

        // Erasure deletes a device key's api_keys row and keeps only its hash here. A matching
        // unexpired tombstone reaches the status read and the withdrawal acknowledgment, nothing else.
        // It never answers for a hash an api_keys row (live or revoked) still owns: a credential that
        // shared its material with an erased key before V115 stays an ordinary credential.
        private const val EXPIRE_ERASED_KEY_TOMBSTONES_SQL =
            "DELETE FROM erased_device_key_tombstones WHERE expires_at <= now()"

        internal fun expireErasedDeviceKeyTombstones(connection: java.sql.Connection): Int =
            connection.createStatement().use { it.executeUpdate(EXPIRE_ERASED_KEY_TOMBSTONES_SQL) }
        private val LOOKUP_ERASED_KEY_TOMBSTONE_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM erased_device_key_tombstones tombstone
                WHERE tombstone.key_hash = ? AND tombstone.expires_at > now()
                  AND NOT EXISTS (
                      SELECT 1 FROM ${API_KEYS.name} owner WHERE owner.${KEY_HASH.name} = tombstone.key_hash
                  )
            )
        """.trimIndent()

        // On re-enrollment, revoke any prior unrevoked mobile key for the same
        // (study, participant, device) tuple before issuing a new one. Prevents
        // unbounded credential accumulation if a device re-enrolls repeatedly.
        private val REVOKE_PRIOR_MOBILE_KEYS_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${REVOKED.name} = true
            WHERE ${STUDY_ID.name} = ? AND participant_id = ? AND random_device_id = ? AND ${REVOKED.name} = false
        """.trimIndent()

        // Key material an erasure tombstoned, or any api_keys row (live or revoked) already holds, is never
        // installed again, so a live key never shares its hash with a tombstone. One statement reads both
        // tables, and erasure inserts the tombstone and deletes the row in one transaction, so a concurrent
        // erasure shows this check either the row or the tombstone.
        private val USED_KEY_MATERIAL_SQL = """
            SELECT EXISTS (SELECT 1 FROM erased_device_key_tombstones WHERE key_hash = ?)
                OR EXISTS (SELECT 1 FROM ${API_KEYS.name} WHERE ${KEY_HASH.name} = ?)
        """.trimIndent()

        private const val LOCK_MOBILE_KEY_INSTALL_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"

        // Serializes ownership of one key hash across devices: installation holds it from the used-material
        // check to the insert, and erasure takes the same lock (in hash order) before tombstoning.
        internal const val LOCK_KEY_MATERIAL_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-key-material:' || ?, 0))"

        private val AUTHORIZED_ENROLLMENT_ATTEMPT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM participant_form_access_codes
                WHERE study_id = ? AND participant_id = ? AND form_kind = 'ENROLLMENT'
                  AND enrollment_attempt_id = ? AND enrollment_random_device_id = ?
                  AND enrollment_proposed_key_hash = ? AND revoked_at IS NULL
                  AND enrollment_replay_expires_at > now()
            )
        """.trimIndent()

        private val FIND_MOBILE_API_KEY_SQL = """
            SELECT ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_HASH.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                   ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                   participant_id, random_device_id
            FROM ${API_KEYS.name}
            WHERE ${STUDY_ID.name} = ? AND participant_id = ? AND random_device_id = ? AND ${REVOKED.name} = false
            LIMIT 1
        """.trimIndent()

        private fun generateRawKey(): String {
            val bytes = ByteArray(KEY_LENGTH)
            secureRandom.nextBytes(bytes)
            val sb = StringBuilder(KEY_LENGTH)
            for (b in bytes) {
                sb.append(BASE62[(b.toInt() and 0xFF) % BASE62.length])
            }
            return sb.toString()
        }

        private fun hashKey(rawKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(rawKey.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeHashEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
            first.toByteArray(Charsets.US_ASCII),
            second.toByteArray(Charsets.US_ASCII),
        )

        internal fun isValidProposedMobileApiKey(rawKey: String): Boolean {
            val match = PROPOSED_MOBILE_KEY_PATTERN.matchEntire(rawKey) ?: return false
            return match.groupValues[1] == match.groupValues[2].take(PREFIX_LENGTH)
        }

        internal fun proposedMobileApiKeyHash(rawKey: String): String? =
            rawKey.takeIf(::isValidProposedMobileApiKey)?.let(::hashKey)

        private fun maxTtlDaysForScope(scope: ApiKeyScope): Int = when (scope) {
            ApiKeyScope.ADMIN -> MAX_ADMIN_KEY_TTL_DAYS
            ApiKeyScope.WRITE -> MAX_WRITE_KEY_TTL_DAYS
            ApiKeyScope.READ_ONLY -> MAX_READ_ONLY_KEY_TTL_DAYS
        }
    }

    private val withdrawalIntentBinder = MobileWithdrawalIntentBinder(auditingManager)

    public open fun createApiKey(studyId: UUID, userId: String, request: ApiKeyCreateRequest): ApiKeyCreateResponse {
        validateCreateRequest(request)
        val keyId = idGenerationService.getNextId()
        val rawKey = generateRawKey()
        val fullKey = "ck_${rawKey.take(PREFIX_LENGTH)}_$rawKey"
        val prefix = rawKey.take(PREFIX_LENGTH)
        val hash = hashKey(fullKey)
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(request.expiresInDays.toLong())

        val aclKey = AclKey(studyId)
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    conn.prepareStatement(INSERT_API_KEY_SQL).use { ps ->
                        ps.setObject(1, keyId)
                        ps.setObject(2, studyId)
                        ps.setString(3, hash)
                        ps.setString(4, prefix)
                        ps.setString(5, request.name)
                        ps.setString(6, request.scope.name)
                        ps.setString(7, userId)
                        ps.setObject(8, expiresAt)
                        ps.executeUpdate()
                    }
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            aclKey,
                            eventType = AuditEventType.CREATE_API_KEY,
                            description = "API key created: ${request.name}",
                            study = studyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = mapOf("keyId" to keyId.toString(), "scope" to request.scope.name)
                        )
                    )
                }
                .buildAndRun()
        }

        logger.info(
            "API key {} created for studyRef {} by userRef {}",
            keyId,
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
            LogSanitizer.stableFingerprint(userId, prefix = "user")
        )
        val info = ApiKeyInfo(
            keyId = keyId,
            studyId = studyId,
            prefix = prefix,
            name = request.name,
            scope = request.scope,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC),
            expiresAt = expiresAt
        )
        return ApiKeyCreateResponse(keyId = keyId, rawKey = fullKey, info = info)
    }

    public open fun listApiKeys(studyId: UUID, limit: Int = 100, offset: Int = 0): List<ApiKeyInfo> {
        val keys = mutableListOf<ApiKeyInfo>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_API_KEYS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setInt(2, com.openlattice.chronicle.util.PaginationDefaults.clampLimit(limit))
                ps.setInt(3, com.openlattice.chronicle.util.PaginationDefaults.clampOffset(offset))
                val rs = ps.executeQuery()
                while (rs.next()) {
                    keys.add(mapApiKeyInfo(rs))
                }
            }
        }
        return keys
    }

    public open fun getApiKey(studyId: UUID, keyId: UUID): ApiKeyInfo? =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("$SELECT_API_KEYS_SQL AND ${KEY_ID.name} = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.setObject(2, keyId)
                statement.executeQuery().use(::firstApiKeyInfo)
            }
        }

    public open fun revokeApiKey(studyId: UUID, keyId: UUID, userId: String) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction { conn ->
                    conn.prepareStatement(REVOKE_API_KEY_SQL).use { ps ->
                        ps.setObject(1, keyId)
                        ps.setObject(2, studyId)
                        val updated = ps.executeUpdate()
                        check(updated > 0) { "API key $keyId not found for study $studyId" }
                    }
                }
                .audit {
                    listOf(
                        AuditableEvent(
                            AclKey(studyId),
                            eventType = AuditEventType.REVOKE_API_KEY,
                            description = "API key revoked: $keyId",
                            study = studyId,
                            organization = IdConstants.UNINITIALIZED.id,
                            data = mapOf("keyId" to keyId.toString())
                        )
                    )
                }
                .buildAndRun()
        }
        logger.info(
            "API key {} revoked for studyRef {} by userRef {}",
            keyId,
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
            LogSanitizer.stableFingerprint(userId, prefix = "user")
        )
    }

    public open fun rotateApiKey(studyId: UUID, keyId: UUID, userId: String): ApiKeyCreateResponse {
        val storage = storageResolver.getPlatformStorage()
        return storage.connection.use { connection ->
            AuditedTransactionBuilder<ApiKeyCreateResponse>(connection, auditingManager)
                .transaction { conn ->
                    PinnedPlatformConnection.pinning(storage, conn) {
                        val existing = conn.prepareStatement(LOCK_API_KEY_FOR_ROTATION_SQL).use { statement ->
                            statement.setObject(1, studyId)
                            statement.setObject(2, keyId)
                            statement.executeQuery().use { rows ->
                                require(rows.next()) { "API key $keyId not found for study $studyId" }
                                require(rows.getString("participant_id") == null && rows.getObject("random_device_id", UUID::class.java) == null) {
                                    "Mobile API keys must be replaced through enrollment"
                                }
                                mapApiKeyInfo(rows)
                            }
                        }
                        revokeApiKey(studyId, keyId, userId)
                        createApiKey(studyId, userId, ApiKeyCreateRequest(
                            name = existing.name,
                            scope = existing.scope,
                            expiresInDays = maxTtlDaysForScope(existing.scope),
                        ))
                    }
                }
                // The existing helpers stage both audit events in the pinned transaction.
                .audit { emptyList() }
                .buildAndRun()
        }
    }

    public open fun authenticateApiKey(rawKey: String): ApiKeyInfo? {
        val hash = hashKey(rawKey)
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                // The sliding predicate reads the enrollment row itself, past the deletion-quarantine
                // RLS policy, so a collected-data purge does not freeze a live participant's key.
                RLSConnectionCustomizer.setDeletionWorkerContext(connection, transactionLocal = false)
                connection.prepareStatement(LOOKUP_AND_TOUCH_SQL).use { ps ->
                    ps.setString(1, hash)
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        mapApiKeyInfo(rs).copy(createdBy = rs.getString(CREATED_BY.name))
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * True when [authenticateApiKey] would admit [rawKey]: same hash, same live-key predicate. Read-only: no usage,
     * expiry or audit writes, so the pre-authentication limiter can ask it before authentication runs.
     */
    public open fun isLiveApiKey(rawKey: String): Boolean = findLiveApiKey(rawKey) != null

    /** The read-only live-credential check, returning the live key or null. */
    internal open fun findLiveApiKey(rawKey: String): ApiKeyInfo? {
        val hash = hashKey(rawKey)
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                connection.prepareStatement(FIND_LIVE_KEY_SQL).use { ps ->
                    ps.setString(1, hash)
                    ps.executeQuery().use { rs -> if (rs.next()) mapApiKeyInfo(rs) else null }
                }
            }
        }
    }

    /**
     * True when erasure deleted this device key within its lifetime. The caller admits it only to the
     * participation-status read and the withdrawal acknowledgment. Expired tombstones are deleted here.
     */
    public open fun isErasedDeviceKey(rawKey: String): Boolean {
        val hash = hashKey(rawKey)
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                expireErasedDeviceKeyTombstones(connection)
                connection.prepareStatement(LOOKUP_ERASED_KEY_TOMBSTONE_SQL).use { ps ->
                    ps.setString(1, hash)
                    ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            }
        }
    }

    /**
     * Authenticates an active key for a first withdrawal, or a revoked key only when the
     * caller repeats the exact request id already bound to that credential before revocation.
     */
    public open fun authenticateWithdrawalApiKey(rawKey: String, requestId: UUID): ApiKeyInfo? {
        val hash = hashKey(rawKey)
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                connection.prepareStatement(LOOKUP_WITHDRAWAL_KEY_SQL).use { ps ->
                    ps.setObject(1, requestId)
                    ps.setString(2, hash)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) mapApiKeyInfo(rs) else null
                    }
                }
            }
        }
    }

    /**
     * A phone cancelling its enrollment attempt: the attempt id, the key it proposed and its source device must name
     * one binding exactly. Whether the key installed does not matter; possession of the attempt's key material is
     * the proof, and the cancellation never withdraws. An attempt with no binding is recorded as cancelled under the
     * attempt lock, so the enroll request still in flight for it never binds.
     */
    internal open fun authenticatePendingEnrollmentAttempt(
        rawKey: String,
        attemptId: UUID,
        sourceRandomDeviceId: String?,
    ): BoundEnrollmentAttempt? {
        val hash = proposedMobileApiKeyHash(rawKey) ?: return null
        return storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                AuditedTransactionBuilder<BoundEnrollmentAttempt?>(connection, auditingManager)
                    .transaction { conn ->
                        lockEnrollmentAttempt(conn, attemptId)
                        val attempts = EnrollmentAttemptRepository(conn)
                        val bound = attempts.findBoundAttempt(attemptId, hash)
                        if (bound == null && !isUsedKeyMaterial(conn, hash)) attempts.recordCancelled(setOf(attemptId))
                        bound?.takeIf {
                            !sourceRandomDeviceId.isNullOrBlank() && it.randomDeviceId ==
                                RandomDeviceIdUtils.deriveRandomDeviceId(it.studyId, it.participantId, sourceRandomDeviceId)
                        }
                    }
                    // Recording an attempt id that was never bound touches no participant, key or invitation.
                    .audit { emptyList() }
                    .buildAndRun()
            }
        }
    }

    /** Cancels the bound attempt under the device's key-installation lock, so installation commits before or never. */
    internal open fun cancelPendingEnrollmentAttempt(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        attemptId: UUID,
        proposedKeyHash: String,
    ): PendingAttemptCancellation = storageResolver.getPlatformStorage().connection.use { connection ->
        RLSConnectionCustomizer.withAdminContext(connection) {
            AuditedTransactionBuilder<PendingAttemptCancellation>(connection, auditingManager)
                .transaction { conn ->
                    DeletionStudyFence.shared(conn, studyId)
                    lockMobileKeyInstallation(conn, randomDeviceId)
                    val attempts = EnrollmentAttemptRepository(conn)
                    val attempt = attempts.findBoundAttempt(attemptId, proposedKeyHash)?.takeIf {
                        it.studyId == studyId && it.participantId == participantId && it.randomDeviceId == randomDeviceId
                    }
                    if (attempt == null) PendingAttemptCancellation.UNKNOWN
                    else attempts.cancelAttempts(studyId, participantId, setOf(attempt.accessCodeId), OffsetDateTime.now())
                        .let { PendingAttemptCancellation.of(it) }
                }
                .audit { outcome ->
                    if (outcome != PendingAttemptCancellation.CANCELLED) emptyList() else listOf(AuditableEvent(
                        aclKey = AclKey(studyId),
                        study = studyId,
                        eventType = AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE,
                        description = "Participant cancelled an enrollment attempt",
                        data = mapOf(
                            "participantRef" to LogSanitizer.stableFingerprint(participantId, "participant"),
                            "deviceRef" to LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device"),
                        ),
                    ))
                }
                .buildAndRun()
        }
    }

    /** True when [rawKey] is the proposed key of a cancelled enrollment attempt: the phone is told so, not a bare 401. */
    public open fun isCancelledEnrollmentKey(rawKey: String): Boolean {
        val hash = proposedMobileApiKeyHash(rawKey) ?: return false
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                EnrollmentAttemptRepository(connection).isCancelledKey(hash)
            }
        }
    }

    /**
     * Persists the withdrawal intent before any status change, deletion scheduling, or key
     * revocation. One key can own exactly one request id; a changed id or tuple is rejected.
     */
    internal open fun bindWithdrawalIntent(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        keyId: UUID,
        requestId: UUID,
    ): MobileWithdrawalIntent = RLSConnectionCustomizer.withAdminTransactionContext(connection) {
        withdrawalIntentBinder.bind(
            connection,
            studyId,
            participantId,
            randomDeviceId,
            keyId,
            requestId,
        )
    }

    /**
     * Installs client-proposed mobile key material after an invitation has been durably bound.
     * Exact retries return the already-installed credential metadata and echo the caller-held key;
     * the plaintext key is never written to storage or logs.
     */
    public open fun installMobileApiKey(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        enrollmentAttemptId: UUID,
        proposedApiKey: String,
        name: String = "device-${randomDeviceId.toString().take(8)}",
        expiresInDays: Long = 365,
    ): ApiKeyCreateResponse {
        validateMobileKeyRequest(name, expiresInDays)
        val proposedHash = requireNotNull(proposedMobileApiKeyHash(proposedApiKey)) {
            "Proposed mobile API key has an invalid format"
        }
        val prefix = proposedApiKey.substringAfter("ck_").substringBefore('_')
        val installation = storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                AuditedTransactionBuilder<MobileKeyInstallation>(connection, auditingManager)
                    .transaction { conn ->
                        DeletionStudyFence.shared(conn, studyId)
                        ParticipantErasureFence.requireReusable(conn, studyId, participantId)
                        lockMobileKeyInstallation(conn, randomDeviceId)
                        if (!isAuthorizedEnrollmentAttempt(
                                conn, studyId, participantId, randomDeviceId, enrollmentAttemptId, proposedHash,
                            )) {
                            throw InvalidEnrollmentAttemptException()
                        }
                        val existing = findMobileApiKey(conn, studyId, participantId, randomDeviceId)
                        if (existing != null && constantTimeHashEquals(existing.hash, proposedHash)) {
                            MobileKeyInstallation(
                                ApiKeyCreateResponse(existing.info.keyId, proposedApiKey, existing.info),
                                created = false,
                                revokedCount = 0,
                            )
                        } else {
                            lockKeyMaterial(conn, proposedHash)
                            if (isUsedKeyMaterial(conn, proposedHash)) throw InvalidEnrollmentAttemptException()
                            createProposedMobileApiKey(
                                conn,
                                studyId,
                                participantId,
                                randomDeviceId,
                                proposedApiKey,
                                proposedHash,
                                prefix,
                                name,
                                expiresInDays,
                            )
                        }
                    }
                    .audit { result -> mobileKeyInstallationAudit(result, studyId, participantId, randomDeviceId, name) }
                    .buildAndRun()
            }
        }
        if (installation.created) {
            logger.info(
                "Mobile API key {} installed for studyRef {} participantRef {} deviceRef {} (revoked {} prior keys)",
                installation.response.keyId,
                LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
                LogSanitizer.stableFingerprint(participantId, prefix = "participant"),
                LogSanitizer.stableFingerprint(randomDeviceId.toString(), prefix = "device"),
                installation.revokedCount,
            )
        }
        return installation.response
    }

    private fun lockMobileKeyInstallation(connection: java.sql.Connection, randomDeviceId: UUID) {
        connection.prepareStatement(LOCK_MOBILE_KEY_INSTALL_SQL).use { statement ->
            statement.setObject(1, randomDeviceId)
            statement.execute()
        }
    }

    private fun lockKeyMaterial(connection: java.sql.Connection, keyHash: String) {
        connection.prepareStatement(LOCK_KEY_MATERIAL_SQL).use { statement ->
            statement.setString(1, keyHash)
            statement.execute()
        }
    }

    private fun isAuthorizedEnrollmentAttempt(
        connection: java.sql.Connection,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        enrollmentAttemptId: UUID,
        proposedApiKeyHash: String,
    ): Boolean = connection.prepareStatement(AUTHORIZED_ENROLLMENT_ATTEMPT_SQL).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, enrollmentAttemptId)
        statement.setObject(4, randomDeviceId)
        statement.setString(5, proposedApiKeyHash)
        statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
    }

    private fun isUsedKeyMaterial(connection: java.sql.Connection, keyHash: String): Boolean =
        connection.prepareStatement(USED_KEY_MATERIAL_SQL).use { statement ->
            statement.setString(1, keyHash)
            statement.setString(2, keyHash)
            statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
        }

    private fun findMobileApiKey(
        connection: java.sql.Connection,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
    ): StoredMobileApiKey? = connection.prepareStatement(FIND_MOBILE_API_KEY_SQL).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, randomDeviceId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) {
                StoredMobileApiKey(resultSet.getString(KEY_HASH.name), mapApiKeyInfo(resultSet))
            } else {
                null
            }
        }
    }

    @Suppress("LongParameterList")
    private fun createProposedMobileApiKey(
        connection: java.sql.Connection,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        proposedApiKey: String,
        proposedHash: String,
        prefix: String,
        name: String,
        expiresInDays: Long,
    ): MobileKeyInstallation {
        var revokedCount = 0
        connection.prepareStatement(REVOKE_PRIOR_MOBILE_KEYS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.setObject(3, randomDeviceId)
            revokedCount = statement.executeUpdate()
        }
        val keyId = idGenerationService.getNextId()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expiresAt = now.plusDays(expiresInDays)
        connection.prepareStatement(INSERT_MOBILE_API_KEY_SQL).use { statement ->
            statement.setObject(1, keyId)
            statement.setObject(2, studyId)
            statement.setString(3, proposedHash)
            statement.setString(4, prefix)
            statement.setString(5, name)
            statement.setString(6, ApiKeyScope.WRITE.name)
            statement.setString(7, "device:$randomDeviceId")
            statement.setObject(8, expiresAt)
            statement.setString(9, participantId)
            statement.setObject(10, randomDeviceId)
            check(statement.executeUpdate() == 1) { "Mobile API key installation failed" }
        }
        val info = ApiKeyInfo(
            keyId = keyId,
            studyId = studyId,
            prefix = prefix,
            name = name,
            scope = ApiKeyScope.WRITE,
            createdAt = now,
            expiresAt = expiresAt,
            participantId = participantId,
            randomDeviceId = randomDeviceId,
        )
        return MobileKeyInstallation(
            ApiKeyCreateResponse(keyId, proposedApiKey, info),
            created = true,
            revokedCount = revokedCount,
        )
    }

    private fun mobileKeyInstallationAudit(
        installation: MobileKeyInstallation,
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        name: String,
    ): List<AuditableEvent> {
        if (!installation.created) return emptyList()
        return listOf(
            AuditableEvent(
                AclKey(studyId),
                eventType = AuditEventType.CREATE_API_KEY,
                description = "Mobile API key installed: $name",
                study = studyId,
                organization = IdConstants.UNINITIALIZED.id,
                data = mapOf(
                    "keyId" to installation.response.keyId.toString(),
                    "scope" to ApiKeyScope.WRITE.name,
                    "participantRef" to LogSanitizer.stableFingerprint(participantId, prefix = "participant"),
                    "deviceRef" to LogSanitizer.stableFingerprint(randomDeviceId.toString(), prefix = "device"),
                    "priorKeysRevoked" to installation.revokedCount.toString(),
                ),
            ),
        )
    }

    private fun validateCreateRequest(request: ApiKeyCreateRequest) {
        require(request.name.isNotBlank()) { "API key name is required" }
        require(request.name.length <= MAX_KEY_NAME_LENGTH) { "API key name exceeds maximum length" }
        require(request.expiresInDays >= 1) { "API key expiration must be at least 1 day" }

        val maxTtlDays = maxTtlDaysForScope(request.scope)
        require(request.expiresInDays <= maxTtlDays) {
            "${request.scope.name} API keys must expire within $maxTtlDays days"
        }
    }

    private fun validateMobileKeyRequest(name: String, expiresInDays: Long) {
        require(name.isNotBlank()) { "Mobile API key name is required" }
        require(name.length <= MAX_KEY_NAME_LENGTH) { "Mobile API key name exceeds maximum length" }
        require(expiresInDays in 1..MAX_MOBILE_KEY_TTL_DAYS) {
            "Mobile API keys must expire within $MAX_MOBILE_KEY_TTL_DAYS days"
        }
    }

    private data class StoredMobileApiKey(val hash: String, val info: ApiKeyInfo)

    private data class MobileKeyInstallation(
        val response: ApiKeyCreateResponse,
        val created: Boolean,
        val revokedCount: Int,
    )
}

/** Shared row projection for scoped lookup, authentication and paginated metadata reads. */
private fun mapApiKeyInfo(rs: ResultSet): ApiKeyInfo = ApiKeyInfo(
    keyId = rs.getObject(KEY_ID.name, UUID::class.java),
    studyId = rs.getObject(STUDY_ID.name, UUID::class.java),
    prefix = rs.getString(KEY_PREFIX.name),
    name = rs.getString(NAME.name),
    scope = ApiKeyScope.valueOf(rs.getString(SCOPE.name)),
    createdAt = rs.getObject(CREATED_AT.name, OffsetDateTime::class.java),
    expiresAt = rs.getObject(EXPIRES_AT.name, OffsetDateTime::class.java),
    lastUsedAt = rs.getObject(LAST_USED_AT.name, OffsetDateTime::class.java),
    usageCount = rs.getLong(USAGE_COUNT.name),
    participantId = rs.getString("participant_id"),
    randomDeviceId = rs.getObject("random_device_id", UUID::class.java),
)

private fun firstApiKeyInfo(rows: ResultSet): ApiKeyInfo? = if (rows.next()) mapApiKeyInfo(rows) else null

public data class MobileWithdrawalIntent(
    val requestId: UUID,
    val alreadyWithdrawn: Boolean,
)

internal object MobileWithdrawalRequestIds {
    private val canonicalUuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )

    fun parse(value: String?): UUID? = value
        ?.takeIf(canonicalUuid::matches)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}

internal class InvalidWithdrawalRequestException : RuntimeException("Invalid withdrawal request")

internal class InvalidEnrollmentAttemptException : RuntimeException("Invalid enrollment attempt")

/**
 * A phone's cancel of its attempt: no such binding, this call cancelled it, an earlier call did (an exact replay), or
 * the attempt completed its enrollment acknowledgment, after which only withdrawal ends the enrollment.
 */
internal enum class PendingAttemptCancellation {
    UNKNOWN, CANCELLED, ALREADY_CANCELLED, NOT_CANCELLABLE;

    companion object {
        fun of(cancellation: AttemptCancellation): PendingAttemptCancellation = when {
            cancellation.cancellable == 0 -> NOT_CANCELLABLE
            cancellation.newlyCancelled > 0 -> CANCELLED
            else -> ALREADY_CANCELLED
        }
    }
}
