package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.ParticipantDataPurgeSummary
import com.openlattice.chronicle.study.ParticipantPurgeRequest
import com.openlattice.chronicle.util.LogSanitizer
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public open class ParticipantPurgeService(
    private val storageResolver: StorageResolver,
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
    private val apiKeyService: ApiKeyService,
    override val auditingManager: AuditingManager,
) : AuditingComponent {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ParticipantPurgeService::class.java)
        private const val TOKEN_VALIDITY_MINUTES = 10L

        private val hmacKey: ByteArray = generateHmacKey()

        /**
         * Derives a random per-process HMAC key. Uses [SecureRandom] because the key is
         * security-relevant (it signs purge confirmation tokens). The single, retained
         * SecureRandom instance is constructed and used exactly once at startup by design.
         */
        @JvmStatic
        @SuppressFBWarnings(
            value = ["DMI_RANDOM_USED_ONLY_ONCE"],
            justification = "Security-relevant HMAC key derived once at startup; SecureRandom is " +
                "the correct primitive and a single one-shot construction here is intentional.",
        )
        private fun generateHmacKey(): ByteArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes
        }

        // Tables intentionally NOT purged:
        // - candidates: keyed by candidate_id, contains no PII (Phase 5 de-identification
        //   replaced names/DOBs with opaque hashes; only candidate_id remains as a key)
        // - study_participants: purge preserves enrollment by design
        // - devices: device enrollment metadata, preserved with enrollment; contains only
        //   server-assigned device_id (UUIDs), not real hardware identifiers (Phase 3.6
        //   removed source_device_id entirely; device_id is a deterministic UUID)
        // - audit / audit_buffer: HIPAA requires 6-year retention of audit logs;
        //   these tables do not contain PII or real device identifiers and must
        //   NOT be purged even on GDPR erasure requests
    }

    public fun previewPurge(studyId: UUID, participantId: String): ParticipantDataPurgeSummary {
        val expiresAt = OffsetDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES)
        val token = generateConfirmationToken(studyId, participantId, expiresAt)

        val summary = storageResolver.getPlatformStorage().connection.use { connection ->
            val counts = ChronicleDataAssetRegistry.participantAssets.associate { asset ->
                asset.tableName to countParticipantRows(connection, asset, studyId, participantId)
            }
            ParticipantDataPurgeSummary(
                studyId = studyId,
                participantId = participantId,
                usageEventsCount = counts.getValue("chronicle_usage_events"),
                usageStatsCount = counts.getValue("chronicle_usage_stats"),
                preprocessedEventsCount = counts.getValue("preprocessed_usage_events"),
                sensorDataCount = counts.getValue("sensor_data"),
                androidSensorDataCount = counts.getValue("android_sensor_data"),
                appUsageSurveyCount = counts.getValue("app_usage_survey"),
                questionnaireSubmissionsCount = counts.getValue("questionnaire_submissions"),
                tudSubmissionsCount = counts.getValue("time_use_diary_submissions"),
                participantStatsCount = counts.getValue("participant_stats"),
                uploadBufferCount = counts.getValue("upload_buffer"),
                assetCounts = ChronicleDataAssetRegistry.participantAssets.associate { asset ->
                    asset.id to counts.getValue(asset.tableName)
                },
                confirmationToken = token,
                tokenExpiresAt = expiresAt,
            )
        }
        recordEvent(
            AuditableEvent(
                aclKey = AclKey(studyId),
                eventType = AuditEventType.PREVIEW_PARTICIPANT_PURGE,
                description = "Preview purge for participant ref " +
                    "${LogSanitizer.stableFingerprint(participantId, "participant")} in study $studyId: " +
                    "${summary.totalRows} legacy-summary rows; all registered assets counted",
                study = studyId,
            )
        )

        logger.info(
            "Purge preview for {} in study {}: {} total rows",
            LogSanitizer.stableFingerprint(participantId, "participant"),
            studyId,
            summary.totalRows,
        )
        return summary
    }

    // reason: HIPAA participant-erasure path — the length is one declarative deletion job per data
    // table (each must be enumerated and audited); restructuring this enumeration risks dropping a
    // table from the erasure set, so the explicit per-table list is kept intact
    @Suppress("LongMethod")
    public fun executePurge(studyId: UUID, request: ParticipantPurgeRequest): Iterable<UUID> {
        return executePurge(
            studyId,
            request,
            idempotencyKey = UUID.randomUUID(),
            mode = DataDeletionMode.COLLECTED_DATA_PURGE,
        )
    }

    @Suppress("LongMethod")
    private fun executePurge(
        studyId: UUID,
        request: ParticipantPurgeRequest,
        idempotencyKey: UUID,
        mode: DataDeletionMode,
    ): Iterable<UUID> {
        val participantId = request.participantId

        // Validate the confirmation token
        require(validateConfirmationToken(request.confirmationToken, studyId, participantId)) {
            "Invalid or expired confirmation token. Please preview the purge again."
        }

        val operationId = dataDeletionOrchestrator.quarantineParticipant(
            studyId = studyId,
            participantId = participantId,
            mode = mode,
            requestedBy = Principals.getCurrentUser().id,
            idempotencyKey = idempotencyKey,
        )
        recordEvent(
            AuditableEvent(
                aclKey = AclKey(operationId),
                eventType = AuditEventType.PURGE_PARTICIPANT_DATA,
                description = "Quarantined participant data for verified erasure after seven days; " +
                    "participant ref ${LogSanitizer.stableFingerprint(participantId, "participant")}",
                study = studyId,
                data = mapOf("operationId" to operationId.toString(), "mode" to mode.name),
            )
        )

        logger.info(
            "Data deletion quarantine initiated for {} in study {}. Operation: {}",
            LogSanitizer.stableFingerprint(participantId, "participant"),
            studyId,
            operationId,
        )
        return listOf(operationId)
    }

    /** Mobile-authenticated withdrawal path; authorization is performed by the API-key filter. */
    public open fun executeSelfWithdrawal(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        keyId: UUID,
        requestId: UUID,
    ): MobileSelfWithdrawalResult {
        val transaction = dataDeletionOrchestrator.quarantineParticipantAtomically(
            studyId = studyId,
            participantId = participantId,
            mode = DataDeletionMode.WITHDRAW_AND_ERASE,
            requestedBy = "device-self-withdrawal",
            idempotencyKey = requestId,
        ) { connection ->
            val intent = apiKeyService.bindWithdrawalIntent(
                connection,
                studyId,
                participantId,
                deviceId,
                keyId,
                requestId,
            )
            ParticipantQuarantineDecision(intent, createOperation = !intent.alreadyWithdrawn)
        }
        transaction.operationId?.let { operationId ->
            recordEvent(
                AuditableEvent(
                    aclKey = AclKey(operationId),
                    eventType = AuditEventType.PURGE_PARTICIPANT_DATA,
                    description = "Quarantined withdrawn participant data for verified erasure after seven days; " +
                        "participant ref ${LogSanitizer.stableFingerprint(participantId, "participant")}",
                    study = studyId,
                    data = mapOf(
                        "operationId" to operationId.toString(),
                        "mode" to DataDeletionMode.WITHDRAW_AND_ERASE.name,
                    ),
                ),
            )
        }
        return MobileSelfWithdrawalResult(
            requestId = transaction.result.requestId,
            alreadyWithdrawn = transaction.result.alreadyWithdrawn,
            deletionOperationId = transaction.operationId,
        )
    }

    private fun countParticipantRows(
        connection: Connection,
        asset: ParticipantDataAsset,
        studyId: UUID,
        participantId: String,
    ): Long {
        val sql = "SELECT COUNT(*) FROM ${asset.tableName} WHERE study_id::text = ? AND participant_id = ?"
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, studyId.toString())
            ps.setString(2, participantId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "No count returned for registered asset ${asset.id}" }
                rs.getLong(1)
            }
        }
    }

    private fun generateConfirmationToken(studyId: UUID, participantId: String, expiresAt: OffsetDateTime): String {
        val payload = "$studyId|$participantId|${expiresAt.toInstant().epochSecond}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(hmacBytes)
        // Token format: base64(hmac)|expiryEpoch
        return "$signature|${expiresAt.toInstant().epochSecond}"
    }

    private fun validateConfirmationToken(token: String, studyId: UUID, participantId: String): Boolean {
        val parts = token.split("|")
        if (parts.size != 2) return false

        val providedSignature = parts[0]
        val expiryEpoch = parts[1].toLongOrNull() ?: return false

        // Check expiry
        if (java.time.Instant.ofEpochSecond(expiryEpoch).isBefore(java.time.Instant.now())) {
            return false
        }

        // Recompute and compare
        val expiresAt = OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(expiryEpoch),
            java.time.ZoneOffset.UTC
        )
        val payload = "$studyId|$participantId|$expiryEpoch"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val expectedSignature = Base64.getEncoder().encodeToString(hmacBytes)

        return java.security.MessageDigest.isEqual(
            providedSignature.toByteArray(Charsets.UTF_8),
            expectedSignature.toByteArray(Charsets.UTF_8)
        )
    }
}

public data class MobileSelfWithdrawalResult(
    val requestId: UUID,
    val alreadyWithdrawn: Boolean,
    val deletionOperationId: UUID?,
)
