package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.study.StudyEncryptionSetting
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.util.UUID

/**
 * Provisions per-study hybrid envelope-encryption keys (HIPAA-2028 W2): generate an RSA-4096
 * keypair AND an ML-KEM-1024 keypair, hand both private keys to the [StudyKeyStore] (Vault in
 * production) in one atomic write, and return the public-only [StudyEncryptionSetting] (both
 * public keys) for the caller to persist into the study's settings so devices can fetch it.
 *
 * This service deliberately does NOT write study settings — the controller owns that,
 * using the audited settings-update transaction. The private key is never returned, never
 * logged, and never written to study settings.
 */
public open class StudyEncryptionKeyService(
    private val keyStore: StudyKeyStore,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyEncryptionKeyService::class.java)
        private const val RSA_KEY_BITS = 4096
    }

    /**
     * Generate and store a fresh keypair for [studyId], returning the enabled public
     * [StudyEncryptionSetting]. A new [keyId] is minted each call, so re-provisioning
     * rotates the key while older private keys remain available to decrypt prior
     * ciphertext.
     */
    public fun provision(studyId: UUID): StudyEncryptionSetting {
        val rsaGenerator = KeyPairGenerator.getInstance("RSA")
        rsaGenerator.initialize(RSA_KEY_BITS)
        val rsaKeyPair = rsaGenerator.generateKeyPair()

        val (mlkemPublic, mlkemPrivate) = EnvelopeCipher.generateMlkemKeyPair()

        val keyId = UUID.randomUUID().toString()

        keyStore.storeKeyMaterial(
            studyId,
            keyId,
            StudyKeyPem.toPkcs8Pem(rsaKeyPair.private),
            EnvelopeCipher.encodeMlkemPrivateKey(mlkemPrivate),
        )
        logger.info("Provisioned hybrid study encryption keys for study {} keyId {}", studyId, keyId)

        return StudyEncryptionSetting(
            enabled = true,
            keyId = keyId,
            algorithm = EnvelopeCipher.DEFAULT_ALG,
            publicKeyPem = StudyKeyPem.toX509Pem(rsaKeyPair.public),
            mlkemPublicKey = EnvelopeCipher.encodeMlkemPublicKey(mlkemPublic),
        )
    }
}
