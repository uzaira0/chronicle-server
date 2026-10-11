package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.crypto.EnvelopeCipher
import com.openlattice.chronicle.crypto.PemKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * End-to-end server crypto round-trip (HIPAA-2028 W2): provision a study keypair, seal a
 * payload device-side under the public key, and recover it via [EnvelopeDecryptionService]
 * with the Vault/file-held private key. Proves the key store, key service, and decryption
 * service integrate, that the AAD binds ciphertext to (study, participant), that rotation
 * keeps old keys readable, and that the public setting never carries private material.
 *
 * Uses [FileStudyKeyStore] (the dev/test custody) in a temp dir — no Vault, no DB.
 */
class EnvelopeRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyStore: FileStudyKeyStore
    private lateinit var keyService: StudyEncryptionKeyService
    private lateinit var decryptionService: EnvelopeDecryptionService

    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"

    @Before
    fun setUp() {
        keyStore = FileStudyKeyStore(tempFolder.root.toPath())
        keyService = StudyEncryptionKeyService(keyStore)
        decryptionService = EnvelopeDecryptionService(keyStore)
    }

    private fun seal(setting: com.openlattice.chronicle.study.StudyEncryptionSetting, plaintext: ByteArray) =
        EnvelopeCipher.seal(
            PemKeys.rsaPublicKey(setting.publicKeyPem),
            EnvelopeCipher.decodeMlkemPublicKey(setting.mlkemPublicKey),
            setting.keyId,
            EncryptedPayloadType.SENSOR,
            plaintext,
            EnvelopeCipher.aad(EnvelopeCipher.ENVELOPE_VERSION, studyId, participantId, EncryptedPayloadType.SENSOR),
            sampleCount = 1,
        )

    @Test
    fun provisionProducesAnEnabledPublicOnlySetting() {
        val setting = keyService.provision(studyId)
        assertTrue(setting.enabled)
        assertTrue("must carry an RSA public key", setting.publicKeyPem.contains("PUBLIC KEY"))
        assertTrue("must carry an ML-KEM public key", setting.mlkemPublicKey.isNotBlank())
        assertFalse("must never carry private material", setting.publicKeyPem.contains("PRIVATE"))
        assertTrue("keyId must be set", setting.keyId.isNotBlank())
    }

    @Test
    fun provisionThenSealThenDecryptRecoversPlaintext() {
        val setting = keyService.provision(studyId)
        val plaintext = """[{"sensor":"accelerometer","x":0.5}]""".toByteArray()
        val envelope = seal(setting, plaintext)
        assertArrayEquals(plaintext, decryptionService.decrypt(studyId, participantId, envelope))
    }

    @Test
    fun decryptingUnderADifferentParticipantFailsTheAadBinding() {
        val setting = keyService.provision(studyId)
        val envelope = seal(setting, "secret".toByteArray())
        assertThrows(Exception::class.java) {
            decryptionService.decrypt(studyId, "someone-else", envelope)
        }
    }

    @Test
    fun decryptWithNoProvisionedKeyForTheStudyFails() {
        val setting = keyService.provision(studyId)
        val envelope = seal(setting, "secret".toByteArray())
        assertThrows(IllegalStateException::class.java) {
            decryptionService.decrypt(UUID.randomUUID(), participantId, envelope)
        }
    }

    @Test
    fun reprovisionRotatesKeyIdYetOldCiphertextStillDecrypts() {
        val first = keyService.provision(studyId)
        val oldEnvelope = seal(first, "old".toByteArray())

        val second = keyService.provision(studyId)
        assertNotEquals("re-provision must mint a new keyId", first.keyId, second.keyId)

        // The old envelope references first.keyId, whose private key is still on disk.
        assertArrayEquals("old".toByteArray(), decryptionService.decrypt(studyId, participantId, oldEnvelope))
    }






}
