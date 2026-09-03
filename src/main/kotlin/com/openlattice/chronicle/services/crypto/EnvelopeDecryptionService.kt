package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.OWNER_PERMISSION
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import java.util.Base64
import java.util.UUID

/**
 * Recovers plaintext from envelope-encrypted upload batches at authorized export time
 * (HIPAA-2028 W2). This is the ONLY place the study private key is used; it is fetched
 * from the [StudyKeyStore] (Vault in production) and never leaves the server process.
 *
 * The AAD binds each ciphertext to its [studyId] + participantId, which are supplied by the
 * caller (request path / query args), NOT taken from the row — so a ciphertext relocated to a
 * different study or participant fails to open. (The version + payloadType in the AAD come from
 * the stored row, but [EnvelopeCipher.open] pins version to the supported constant, and any
 * post-storage tamper of the payload_type column changes the AAD and fails the GCM tag.)
 *
 * [decryptStored] enforces authorization itself: it requires an active request-scoped RLS context
 * (so the read runs as the non-superuser chronicle_app role under RLS) AND requires the caller to
 * hold OWNER on the study — a strictly stronger bar than the READ that mere RLS visibility implies,
 * because recovering every participant's plaintext is an export-grade operation. The
 * encrypted_payloads RLS policy is the DB-level backstop for the row-level isolation.
 */
public open class EnvelopeDecryptionService(
    private val storageResolver: StorageResolver,
    private val keyStore: StudyKeyStore,
    private val authorizationManager: AuthorizationManager,
) {

    internal companion object {
        private val SELECT_ENCRYPTED_PAYLOADS_SQL = """
            SELECT envelope_version, alg, key_id, payload_type, encrypted_key, iv, ciphertext, sample_count
            FROM ${ChroniclePostgresTables.ENCRYPTED_PAYLOADS.name}
            WHERE study_id = ? AND participant_id = ?
            ORDER BY uploaded_at
        """.trimIndent()
    }

    /** Open a single [envelope] for ([studyId], [participantId]). Both private keys are required. */
    public fun decrypt(studyId: UUID, participantId: String, envelope: EncryptedEnvelope): ByteArray {
        val rsaPrivateKey = keyStore.privateKey(studyId, envelope.keyId)
            ?: error("No RSA private key available for study $studyId keyId ${envelope.keyId}")
        val mlkemPrivateKey = keyStore.mlkemPrivateKey(studyId, envelope.keyId)
            ?: error("No ML-KEM private key available for study $studyId keyId ${envelope.keyId}")
        val aad = EnvelopeCipher.aad(envelope.version, studyId, participantId, envelope.payloadType)
        return EnvelopeCipher.open(rsaPrivateKey, mlkemPrivateKey, envelope, aad)
    }

    /**
     * Read and decrypt every stored payload for ([studyId], [participantId]).
     *
     * Reads PHI ciphertext over the platform datasource, which enforces row-level study isolation
     * ONLY when a request-scoped [RLSRequestContext] is active (it drops the connection to the
     * non-superuser `chronicle_app` role on borrow). This refuses to run without one — otherwise
     * the read would bypass RLS entirely — and requires the caller to hold OWNER on the study
     * before any ciphertext is read. OWNER (not the READ that RLS visibility implies) is the right
     * bar: recovering every participant's plaintext is an export-grade operation, mirroring the
     * OWNER gate on key provisioning. The RLS policy on `encrypted_payloads` is the DB-level
     * backstop.
     */
    // reason: crypto/RLS export path — the superuser-session guard and the row-decrypt loop are
    // nested inside JDBC use{} scopes that must not be split; restructuring this auth-gated read
    // risks the RLS/OWNER enforcement invariants
    @Suppress("NestedBlockDepth")
    public fun decryptStored(studyId: UUID, participantId: String): List<DecryptedPayload> {
        val ctx = RLSRequestContext.current()
            ?: throw IllegalStateException(
                "decryptStored must run within an RLS request context; refusing to read encrypted " +
                    "PHI over an unscoped (RLS-bypassing) connection."
            )
        if (!ctx.isAdmin &&
            !authorizationManager.checkIfHasPermissions(
                AclKey(studyId), Principals.getCurrentPrincipals(), OWNER_PERMISSION,
            )
        ) {
            throw SecurityException(
                "Not authorized to decrypt encrypted payloads for study $studyId; OWNER permission required",
            )
        }
        val encoder = Base64.getEncoder()
        val results = mutableListOf<DecryptedPayload>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            // Belt-and-suspenders: the context check above proves a request scope is present, but the
            // actual RLS role-drop (SET ROLE chronicle_app) is gated on RLSDataSources.appRole. Verify
            // the borrowed connection is NOT a superuser session, so a misconfiguration (appRole unset,
            // or the app role absent) can't silently read PHI with row-level isolation bypassed.
            connection.createStatement().use { st ->
                st.executeQuery("SELECT current_setting('is_superuser')").use { rs ->
                    check(rs.next() && !rs.getString(1).equals("on", ignoreCase = true)) {
                        "decryptStored reached a superuser connection; the RLS role-drop did not engage, " +
                            "so the read would bypass row-level study isolation."
                    }
                }
            }
            connection.prepareStatement(SELECT_ENCRYPTED_PAYLOADS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, participantId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val envelope = EncryptedEnvelope(
                            version = rs.getInt("envelope_version"),
                            alg = rs.getString("alg"),
                            keyId = rs.getString("key_id"),
                            payloadType = EncryptedPayloadType.fromId(rs.getString("payload_type")),
                            encryptedKey = encoder.encodeToString(rs.getBytes("encrypted_key")),
                            iv = encoder.encodeToString(rs.getBytes("iv")),
                            ciphertext = encoder.encodeToString(rs.getBytes("ciphertext")),
                            sampleCount = rs.getInt("sample_count"),
                        )
                        results += DecryptedPayload(
                            payloadType = envelope.payloadType,
                            sampleCount = envelope.sampleCount,
                            plaintext = decrypt(studyId, participantId, envelope),
                        )
                    }
                }
            }
        }
        return results
    }
}

/** A decrypted payload: the recovered plaintext bytes plus their non-sensitive metadata. */
public class DecryptedPayload(
    public val payloadType: EncryptedPayloadType,
    public val sampleCount: Int,
    public val plaintext: ByteArray,
)
