package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EnvelopeCipher
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
 */
public open class EnvelopeDecryptionService(
    private val keyStore: StudyKeyStore,
) {

    /** Open a single [envelope] for ([studyId], [participantId]). Both private keys are required. */
    public fun decrypt(studyId: UUID, participantId: String, envelope: EncryptedEnvelope): ByteArray {
        val rsaPrivateKey = keyStore.privateKey(studyId, envelope.keyId)
            ?: error("No RSA private key available for study $studyId keyId ${envelope.keyId}")
        val mlkemPrivateKey = keyStore.mlkemPrivateKey(studyId, envelope.keyId)
            ?: error("No ML-KEM private key available for study $studyId keyId ${envelope.keyId}")
        val aad = EnvelopeCipher.aad(envelope.version, studyId, participantId, envelope.payloadType)
        return EnvelopeCipher.open(rsaPrivateKey, mlkemPrivateKey, envelope, aad)
    }

}
