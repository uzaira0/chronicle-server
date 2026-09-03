package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Persists envelope-encrypted Android upload batches ([EncryptedEnvelope]) into the
 * blind [ChroniclePostgresTables.ENCRYPTED_PAYLOADS] table (HIPAA-2028 W2).
 *
 * The backend NEVER decrypts here: the base64 envelope fields are decoded straight to
 * opaque BYTEA. `content_hash` = SHA-256(encrypted_key || iv || ciphertext); the
 * `(study_id, participant_id, content_hash)` UNIQUE constraint makes a byte-for-byte
 * re-send (e.g. an HTTP-level retry) idempotent via `ON CONFLICT DO NOTHING`, mirroring
 * [BatteryTelemetryUploadService]'s per-sample dedup.
 */
public open class EncryptedPayloadUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(EncryptedPayloadUploadService::class.java)

        /** AES-GCM nonce length, mirrored from [com.openlattice.chronicle.crypto.EnvelopeCipher]. */
        private const val GCM_NONCE_BYTES = 12

        private val INSERT_ENCRYPTED_PAYLOAD_SQL = """
            INSERT INTO ${ChroniclePostgresTables.ENCRYPTED_PAYLOADS.name} (
                payload_id, study_id, participant_id, device_id, payload_type,
                envelope_version, alg, key_id, encrypted_key, iv, ciphertext,
                sample_count, content_hash, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, content_hash) DO NOTHING
        """.trimIndent()

        internal fun contentHash(encryptedKey: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(encryptedKey)
            digest.update(iv)
            digest.update(ciphertext)
            return digest.digest()
        }
    }

    public fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<EncryptedEnvelope>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Encrypted payload upload batch too large: ${data.size} envelopes (max 10,000)"
        }

        val decoder = Base64.getDecoder()
        StopWatch(
            log = "Writing ${data.size} encrypted payloads for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            persistEnvelopes(decoder, studyId, participantId, deviceId, data)
        }

        return data.size
    }

    private fun persistEnvelopes(
        decoder: Base64.Decoder,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<EncryptedEnvelope>,
    ) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_ENCRYPTED_PAYLOAD_SQL).use { ps ->
                data.forEach { envelope ->
                    bindEnvelope(ps, decoder, studyId, participantId, deviceId, envelope)
                }
                ps.executeBatch()
            }
        }
    }

    private fun bindEnvelope(
        ps: java.sql.PreparedStatement,
        decoder: Base64.Decoder,
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        envelope: EncryptedEnvelope,
    ) {
        // decode() throws IllegalArgumentException on non-base64 → mapped to HTTP 400
        // by GlobalExceptionHandler. Validate the decoded shapes too so a base64-valid
        // but malformed envelope (e.g. a wrong-length IV) is rejected here at ingest
        // (400) rather than silently stored and failing far away at decrypt time.
        val encryptedKey = decoder.decode(envelope.encryptedKey)
        val iv = decoder.decode(envelope.iv)
        val ciphertext = decoder.decode(envelope.ciphertext)
        require(iv.size == GCM_NONCE_BYTES) {
            "Encrypted payload IV must be $GCM_NONCE_BYTES bytes, got ${iv.size}"
        }
        require(encryptedKey.isNotEmpty()) { "Encrypted payload wrapped key is empty" }
        require(ciphertext.isNotEmpty()) { "Encrypted payload ciphertext is empty" }
        ps.setObject(1, UUID.randomUUID())
        ps.setObject(2, studyId)
        ps.setString(3, participantId)
        ps.setObject(4, deviceId)
        ps.setString(5, envelope.payloadType.id)
        ps.setInt(6, envelope.version)
        ps.setString(7, envelope.alg)
        ps.setString(8, envelope.keyId)
        ps.setBytes(9, encryptedKey)
        ps.setBytes(10, iv)
        ps.setBytes(11, ciphertext)
        ps.setInt(12, envelope.sampleCount)
        ps.setBytes(13, contentHash(encryptedKey, iv, ciphertext))
        ps.addBatch()
    }
}
