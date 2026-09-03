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
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.API_KEYS
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
        private const val PREFIX_LENGTH = 8
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
                (${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_HASH.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name}, ${CREATED_BY.name}, ${EXPIRES_AT.name}, participant_id, device_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private val LIST_API_KEYS_SQL = """
            SELECT ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                   ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                   participant_id, device_id
            FROM ${API_KEYS.name}
            WHERE ${STUDY_ID.name} = ? AND ${REVOKED.name} = false
            ORDER BY ${CREATED_AT.name} DESC
        """.trimIndent()

        private val REVOKE_API_KEY_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${REVOKED.name} = true
            WHERE ${KEY_ID.name} = ? AND ${STUDY_ID.name} = ?
        """.trimIndent()

        // Atomically validate, increment counter, and return key metadata in a single
        // round-trip. Removes the prior LOOKUP + UPDATE_USAGE two-statement pattern.
        private val LOOKUP_AND_TOUCH_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${LAST_USED_AT.name} = now(), ${USAGE_COUNT.name} = ${USAGE_COUNT.name} + 1
            WHERE ${KEY_HASH.name} = ? AND ${REVOKED.name} = false AND ${EXPIRES_AT.name} > now()
            RETURNING ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                      ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                      participant_id, device_id
        """.trimIndent()

        // Revoked mobile credentials remain usable only for an idempotent withdrawal retry.
        private val LOOKUP_WITHDRAWAL_KEY_SQL = """
            SELECT candidate.${KEY_ID.name}, candidate.${STUDY_ID.name}, candidate.${KEY_PREFIX.name},
                   candidate.${NAME.name}, candidate.${SCOPE.name}, candidate.${CREATED_AT.name},
                   candidate.${EXPIRES_AT.name}, candidate.${LAST_USED_AT.name}, candidate.${USAGE_COUNT.name},
                   candidate.participant_id, candidate.device_id
            FROM ${API_KEYS.name} candidate
            LEFT JOIN mobile_withdrawal_requests withdrawal
              ON withdrawal.api_key_id = candidate.${KEY_ID.name}
             AND withdrawal.request_id = ?
             AND withdrawal.${STUDY_ID.name} = candidate.${STUDY_ID.name}
             AND withdrawal.participant_id = candidate.participant_id
             AND withdrawal.device_id = candidate.device_id
            WHERE candidate.${KEY_HASH.name} = ? AND candidate.${EXPIRES_AT.name} > now()
              AND candidate.participant_id IS NOT NULL AND candidate.device_id IS NOT NULL
              AND (
                  candidate.${REVOKED.name} = false
                  OR (
                      withdrawal.request_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM ${API_KEYS.name} active
                          WHERE active.${STUDY_ID.name} = candidate.${STUDY_ID.name}
                            AND active.participant_id = candidate.participant_id
                            AND active.device_id = candidate.device_id
                            AND active.${REVOKED.name} = false
                            AND active.${EXPIRES_AT.name} > now()
                            AND active.${KEY_ID.name} <> candidate.${KEY_ID.name}
                      )
                  )
              )
        """.trimIndent()

        // On re-enrollment, revoke any prior unrevoked mobile key for the same
        // (study, participant, device) tuple before issuing a new one. Prevents
        // unbounded credential accumulation if a device re-enrolls repeatedly.
        private val REVOKE_PRIOR_MOBILE_KEYS_SQL = """
            UPDATE ${API_KEYS.name}
            SET ${REVOKED.name} = true
            WHERE ${STUDY_ID.name} = ? AND participant_id = ? AND device_id = ? AND ${REVOKED.name} = false
        """.trimIndent()

        private const val LOCK_MOBILE_KEY_INSTALL_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"

        private val AUTHORIZED_ENROLLMENT_ATTEMPT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM participant_form_access_codes
                WHERE study_id = ? AND participant_id = ? AND form_kind = 'ENROLLMENT'
                  AND enrollment_attempt_id = ? AND enrollment_device_id = ?
                  AND enrollment_proposed_key_hash = ? AND revoked_at IS NULL
                  AND enrollment_replay_expires_at > now()
            )
        """.trimIndent()

        private val FIND_MOBILE_API_KEY_SQL = """
            SELECT ${KEY_ID.name}, ${STUDY_ID.name}, ${KEY_HASH.name}, ${KEY_PREFIX.name}, ${NAME.name}, ${SCOPE.name},
                   ${CREATED_AT.name}, ${EXPIRES_AT.name}, ${LAST_USED_AT.name}, ${USAGE_COUNT.name},
                   participant_id, device_id
            FROM ${API_KEYS.name}
            WHERE ${STUDY_ID.name} = ? AND participant_id = ? AND device_id = ? AND ${REVOKED.name} = false
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

        internal fun isValidProposedMobileApiKey(rawKey: String): Boolean {
            val match = PROPOSED_MOBILE_KEY_PATTERN.matchEntire(rawKey) ?: return false
            return match.groupValues[1] == match.groupValues[2].take(PREFIX_LENGTH)
        }

        internal fun proposedMobileApiKeyHash(rawKey: String): String? =
            rawKey.takeIf(::isValidProposedMobileApiKey)?.let(::hashKey)
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

    public open fun listApiKeys(studyId: UUID): List<ApiKeyInfo> {
        val keys = mutableListOf<ApiKeyInfo>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(LIST_API_KEYS_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    keys.add(mapApiKeyInfo(rs))
                }
            }
        }
        return keys
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
        // Get the existing key info for name/scope
        val existingKeys = listApiKeys(studyId)
        val existing = existingKeys.find { it.keyId == keyId }
            ?: throw IllegalArgumentException("API key $keyId not found for study $studyId")

        // Revoke old key
        revokeApiKey(studyId, keyId, userId)

        // Create new key with same name/scope
        return createApiKey(
            studyId, userId,
            ApiKeyCreateRequest(
                name = existing.name,
                scope = existing.scope,
                expiresInDays = maxTtlDaysForScope(existing.scope)
            )
        )
    }

    public open fun authenticateApiKey(rawKey: String): ApiKeyInfo? {
        val hash = hashKey(rawKey)
        storageResolver.getPlatformStorage().connection.use { connection ->
            return RLSConnectionCustomizer.withAdminContext(connection) {
                connection.prepareStatement(LOOKUP_AND_TOUCH_SQL).use { ps ->
                    ps.setString(1, hash)
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        mapApiKeyInfo(rs)
                    } else {
                        null
                    }
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
     * Persists the withdrawal intent before any status change, deletion scheduling, or key
     * revocation. One key can own exactly one request id; a changed id or tuple is rejected.
     */
    internal open fun bindWithdrawalIntent(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
        requestId: UUID,
    ): MobileWithdrawalIntent = RLSConnectionCustomizer.withAdminTransactionContext(connection) {
        withdrawalIntentBinder.bind(
            connection,
            studyId,
            participantId,
            deviceId,
            keyId,
            requestId,
        )
    }

    private fun mapApiKeyInfo(rs: ResultSet): ApiKeyInfo {
        val lastUsed = rs.getObject(LAST_USED_AT.name, OffsetDateTime::class.java)
        // Both LOOKUP_AND_TOUCH_SQL and LIST_API_KEYS_SQL select these columns; SQL NULL
        // returns null, exceptions here are real failures and should propagate.
        val participantId = rs.getString("participant_id")
        val deviceId = rs.getObject("device_id", UUID::class.java)
        return ApiKeyInfo(
            keyId = rs.getObject(KEY_ID.name, UUID::class.java),
            studyId = rs.getObject(STUDY_ID.name, UUID::class.java),
            prefix = rs.getString(KEY_PREFIX.name),
            name = rs.getString(NAME.name),
            scope = ApiKeyScope.valueOf(rs.getString(SCOPE.name)),
            createdAt = rs.getObject(CREATED_AT.name, OffsetDateTime::class.java),
            expiresAt = rs.getObject(EXPIRES_AT.name, OffsetDateTime::class.java),
            lastUsedAt = lastUsed,
            usageCount = rs.getLong(USAGE_COUNT.name),
            participantId = participantId,
            deviceId = deviceId
        )
    }

    /**
     * Issue a per-device API key bound to (studyId, participantId, deviceId).
     * Called from the mobile enrollment endpoint. Scope is fixed at WRITE.
     *
     * Revokes any prior unrevoked key for the same tuple in the same transaction
     * so a re-enrolling device can't accumulate live credentials over time.
     * Audit-logged like [createApiKey] so credential issuance leaves a HIPAA trail.
     */
    // reason: security-sensitive credential issuance — single atomic revoke+insert+audit transaction;
    // splitting the auth/crypto path risks the all-in-one-transaction guarantee
    @Suppress("LongMethod")
    public open fun createMobileApiKey(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        name: String = "device-${deviceId.toString().take(8)}",
        expiresInDays: Long = 365
    ): ApiKeyCreateResponse {
        validateMobileKeyRequest(name, expiresInDays)
        val keyId = idGenerationService.getNextId()
        val rawKey = generateRawKey()
        val fullKey = "ck_${rawKey.take(PREFIX_LENGTH)}_$rawKey"
        val prefix = rawKey.take(PREFIX_LENGTH)
        val hash = hashKey(fullKey)
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays)
        var revokedCount = 0

        val aclKey = AclKey(studyId)
        storageResolver.getPlatformStorage().connection.use { connection ->
            RLSConnectionCustomizer.withAdminContext(connection) {
                AuditedTransactionBuilder<Unit>(connection, auditingManager)
                    .transaction { conn ->
                        conn.prepareStatement(REVOKE_PRIOR_MOBILE_KEYS_SQL).use { ps ->
                            ps.setObject(1, studyId)
                            ps.setString(2, participantId)
                            ps.setObject(3, deviceId)
                            revokedCount = ps.executeUpdate()
                        }
                        conn.prepareStatement(INSERT_MOBILE_API_KEY_SQL).use { ps ->
                            ps.setObject(1, keyId)
                            ps.setObject(2, studyId)
                            ps.setString(3, hash)
                            ps.setString(4, prefix)
                            ps.setString(5, name)
                            ps.setString(6, ApiKeyScope.WRITE.name)
                            ps.setString(7, "device:$deviceId")
                            ps.setObject(8, expiresAt)
                            ps.setString(9, participantId)
                            ps.setObject(10, deviceId)
                            ps.executeUpdate()
                        }
                    }
                    .audit {
                        listOf(
                            AuditableEvent(
                                aclKey,
                                eventType = AuditEventType.CREATE_API_KEY,
                                description = "Mobile API key issued: $name",
                                study = studyId,
                                organization = IdConstants.UNINITIALIZED.id,
                                data = mapOf(
                                    "keyId" to keyId.toString(),
                                    "scope" to ApiKeyScope.WRITE.name,
                                    "participantRef" to LogSanitizer.stableFingerprint(participantId, prefix = "participant"),
                                    "deviceRef" to LogSanitizer.stableFingerprint(deviceId.toString(), prefix = "device"),
                                    "priorKeysRevoked" to revokedCount.toString()
                                )
                            )
                        )
                    }
                    .buildAndRun()
            }
        }

        logger.info(
            "Mobile API key {} issued for studyRef {} participantRef {} deviceRef {} (revoked {} prior keys)",
            keyId,
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
            LogSanitizer.stableFingerprint(participantId, prefix = "participant"),
            LogSanitizer.stableFingerprint(deviceId.toString(), prefix = "device"),
            revokedCount
        )
        val info = ApiKeyInfo(
            keyId = keyId,
            studyId = studyId,
            prefix = prefix,
            name = name,
            scope = ApiKeyScope.WRITE,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC),
            expiresAt = expiresAt,
            participantId = participantId,
            deviceId = deviceId
        )
        return ApiKeyCreateResponse(keyId = keyId, rawKey = fullKey, info = info)
    }

    /**
     * Installs client-proposed mobile key material after an invitation has been durably bound.
     * Exact retries return the already-installed credential metadata and echo the caller-held key;
     * the plaintext key is never written to storage or logs.
     */
    public open fun installMobileApiKey(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        enrollmentAttemptId: UUID,
        proposedApiKey: String,
        name: String = "device-${deviceId.toString().take(8)}",
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
                        lockMobileKeyInstallation(conn, deviceId)
                        if (!isAuthorizedEnrollmentAttempt(
                                conn,
                                studyId,
                                participantId,
                                deviceId,
                                enrollmentAttemptId,
                                proposedHash,
                            )
                        ) {
                            throw InvalidEnrollmentAttemptException()
                        }
                        val existing = findMobileApiKey(conn, studyId, participantId, deviceId)
                        if (existing != null && constantTimeHashEquals(existing.hash, proposedHash)) {
                            MobileKeyInstallation(
                                ApiKeyCreateResponse(existing.info.keyId, proposedApiKey, existing.info),
                                created = false,
                                revokedCount = 0,
                            )
                        } else {
                            createProposedMobileApiKey(
                                conn,
                                studyId,
                                participantId,
                                deviceId,
                                proposedApiKey,
                                proposedHash,
                                prefix,
                                name,
                                expiresInDays,
                            )
                        }
                    }
                    .audit { result -> mobileKeyInstallationAudit(result, studyId, participantId, deviceId, name) }
                    .buildAndRun()
            }
        }
        if (installation.created) {
            logger.info(
                "Mobile API key {} installed for studyRef {} participantRef {} deviceRef {} (revoked {} prior keys)",
                installation.response.keyId,
                LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study"),
                LogSanitizer.stableFingerprint(participantId, prefix = "participant"),
                LogSanitizer.stableFingerprint(deviceId.toString(), prefix = "device"),
                installation.revokedCount,
            )
        }
        return installation.response
    }

    private fun lockMobileKeyInstallation(connection: java.sql.Connection, deviceId: UUID) {
        connection.prepareStatement(LOCK_MOBILE_KEY_INSTALL_SQL).use { statement ->
            statement.setObject(1, deviceId)
            statement.execute()
        }
    }

    private fun isAuthorizedEnrollmentAttempt(
        connection: java.sql.Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        enrollmentAttemptId: UUID,
        proposedApiKeyHash: String,
    ): Boolean = connection.prepareStatement(AUTHORIZED_ENROLLMENT_ATTEMPT_SQL).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, enrollmentAttemptId)
        statement.setObject(4, deviceId)
        statement.setString(5, proposedApiKeyHash)
        statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getBoolean(1) }
    }

    private fun findMobileApiKey(
        connection: java.sql.Connection,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
    ): StoredMobileApiKey? = connection.prepareStatement(FIND_MOBILE_API_KEY_SQL).use { statement ->
        statement.setObject(1, studyId)
        statement.setString(2, participantId)
        statement.setObject(3, deviceId)
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
        deviceId: UUID,
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
            statement.setObject(3, deviceId)
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
            statement.setString(7, "device:$deviceId")
            statement.setObject(8, expiresAt)
            statement.setString(9, participantId)
            statement.setObject(10, deviceId)
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
            deviceId = deviceId,
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
        deviceId: UUID,
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
                    "deviceRef" to LogSanitizer.stableFingerprint(deviceId.toString(), prefix = "device"),
                    "priorKeysRevoked" to installation.revokedCount.toString(),
                ),
            ),
        )
    }

    private fun constantTimeHashEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(Charsets.US_ASCII),
        second.toByteArray(Charsets.US_ASCII),
    )

    private fun validateCreateRequest(request: ApiKeyCreateRequest) {
        require(request.name.isNotBlank()) { "API key name is required" }
        require(request.name.length <= MAX_KEY_NAME_LENGTH) { "API key name exceeds maximum length" }
        require(request.expiresInDays >= 1) { "API key expiration must be at least 1 day" }

        val maxTtlDays = maxTtlDaysForScope(request.scope)
        require(request.expiresInDays <= maxTtlDays) {
            "${request.scope.name} API keys must expire within $maxTtlDays days"
        }
    }

    private fun maxTtlDaysForScope(scope: ApiKeyScope): Int {
        return when (scope) {
            ApiKeyScope.ADMIN -> MAX_ADMIN_KEY_TTL_DAYS
            ApiKeyScope.WRITE -> MAX_WRITE_KEY_TTL_DAYS
            ApiKeyScope.READ_ONLY -> MAX_READ_ONLY_KEY_TTL_DAYS
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
