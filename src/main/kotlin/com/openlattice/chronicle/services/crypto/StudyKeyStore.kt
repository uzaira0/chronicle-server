package com.openlattice.chronicle.services.crypto

import com.openlattice.chronicle.configuration.VaultSecretProvider
import com.openlattice.chronicle.crypto.EnvelopeCipher
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Custody for per-study **private** keys used to unwrap hybrid envelope-encrypted upload
 * batches at authorized export time (HIPAA-2028 W2). Each study key is a *pair*: an RSA-4096
 * private key (classical half) and an ML-KEM-1024 private key (post-quantum half); both are
 * required to recover any payload (see [EnvelopeCipher]).
 *
 * Neither private key ever touches the device, the `studies.settings` JSON, or the
 * `encrypted_payloads` ciphertext rows — only the matching public keys are ever distributed.
 * Two custody backends mirror how the codebase already handles asymmetric secrets: Vault
 * (KV v2, the production custody the design calls for) and a file directory (the JWT-key style
 * fallback used for dev/test where Vault is disabled).
 */
public interface StudyKeyStore {
    /** The RSA private key for ([studyId], [keyId]), or null if none is stored. */
    public fun privateKey(studyId: UUID, keyId: String): RSAPrivateKey?

    /** The ML-KEM-1024 private key for ([studyId], [keyId]), or null if none is stored. */
    public fun mlkemPrivateKey(studyId: UUID, keyId: String): MLKEMPrivateKeyParameters?

    /**
     * Persist both private keys for ([studyId], [keyId]) atomically: [rsaPrivateKeyPem] (PKCS#8
     * PEM) and [mlkemPrivateKeyB64] (base64 raw FIPS 203). A single write so a rotation never
     * leaves a half-provisioned key that could decrypt one half but not the other.
     */
    public fun storeKeyMaterial(
        studyId: UUID,
        keyId: String,
        rsaPrivateKeyPem: String,
        mlkemPrivateKeyB64: String,
    )
}

/**
 * Vault-backed custody. Each key lives at its own KV v2 path
 * `encryption/study/{studyId}/{keyId}`, with both private keys stored as distinct fields of the
 * same secret, so rotating to a new keyId never clobbers a prior key still needed to decrypt
 * older ciphertext.
 */
public open class VaultStudyKeyStore(
    private val vault: VaultSecretProvider,
) : StudyKeyStore {

    internal companion object {
        private const val RSA_FIELD = "private-key"
        private const val MLKEM_FIELD = "mlkem-private-key"
        private fun path(studyId: UUID, keyId: String) = "encryption/study/$studyId/$keyId"
    }

    override fun privateKey(studyId: UUID, keyId: String): RSAPrivateKey? =
        vault.getSecret(path(studyId, keyId), RSA_FIELD)?.let(StudyKeyPem::parsePkcs8)

    override fun mlkemPrivateKey(studyId: UUID, keyId: String): MLKEMPrivateKeyParameters? =
        vault.getSecret(path(studyId, keyId), MLKEM_FIELD)?.let(EnvelopeCipher::decodeMlkemPrivateKey)

    override fun storeKeyMaterial(
        studyId: UUID,
        keyId: String,
        rsaPrivateKeyPem: String,
        mlkemPrivateKeyB64: String,
    ) {
        check(
            vault.putSecret(
                path(studyId, keyId),
                mapOf(RSA_FIELD to rsaPrivateKeyPem, MLKEM_FIELD to mlkemPrivateKeyB64),
            ),
        ) {
            "Failed to persist study private keys to Vault for study $studyId keyId $keyId"
        }
    }
}

/**
 * File-directory custody (dev/test, or a secret-mounted volume). Mirrors the JWT key file
 * pattern: the RSA private key in `{studyId}-{keyId}.pem` and the ML-KEM private key in
 * `{studyId}-{keyId}.mlkem`. The directory is created on first write; a missing key is absent.
 */
public open class FileStudyKeyStore(
    private val directory: Path,
) : StudyKeyStore {

    internal companion object {
        private val logger = LoggerFactory.getLogger(FileStudyKeyStore::class.java)
    }

    private fun rsaFile(studyId: UUID, keyId: String): Path =
        directory.resolve("$studyId-$keyId.pem")

    private fun mlkemFile(studyId: UUID, keyId: String): Path =
        directory.resolve("$studyId-$keyId.mlkem")

    override fun privateKey(studyId: UUID, keyId: String): RSAPrivateKey? {
        val file = rsaFile(studyId, keyId)
        if (!Files.exists(file)) return null
        return StudyKeyPem.parsePkcs8(Files.readString(file))
    }

    override fun mlkemPrivateKey(studyId: UUID, keyId: String): MLKEMPrivateKeyParameters? {
        val file = mlkemFile(studyId, keyId)
        if (!Files.exists(file)) return null
        return EnvelopeCipher.decodeMlkemPrivateKey(Files.readString(file))
    }

    override fun storeKeyMaterial(
        studyId: UUID,
        keyId: String,
        rsaPrivateKeyPem: String,
        mlkemPrivateKeyB64: String,
    ) {
        Files.createDirectories(directory)
        restrictToOwner(directory, "rwx------")
        // Private key material: owner-read/write only (0600), never group/world readable.
        writeRestricted(rsaFile(studyId, keyId), rsaPrivateKeyPem)
        writeRestricted(mlkemFile(studyId, keyId), mlkemPrivateKeyB64)
        logger.info("Stored study private keys on disk for study {} keyId {}", studyId, keyId)
    }

    private fun writeRestricted(file: Path, content: String) {
        Files.writeString(file, content)
        restrictToOwner(file, "rw-------")
    }

    /**
     * Restrict [path] to the owner. Uses POSIX permissions where the filesystem supports them
     * (the production/Linux case); otherwise falls back to the owner-only `java.io.File` flags so
     * a non-POSIX FS can never leave key material group/world readable.
     */
    private fun restrictToOwner(path: Path, posixMode: String) {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixMode))
        } else {
            path.toFile().apply {
                setReadable(false, false); setReadable(true, true)
                setWritable(false, false); setWritable(true, true)
                setExecutable(false, false)
                if (posixMode.startsWith("rwx")) setExecutable(true, true)
            }
        }
    }
}

/**
 * PEM (de)serialization for study RSA keys. Server-only — the device uses
 * [com.openlattice.chronicle.crypto.PemKeys] for the public key.
 */
public object StudyKeyPem {

    public fun parsePkcs8(pem: String): RSAPrivateKey {
        val der = Base64.getMimeDecoder().decode(strip(pem, "PRIVATE KEY"))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
    }

    public fun toPkcs8Pem(key: PrivateKey): String = wrap(key.encoded, "PRIVATE KEY")

    public fun toX509Pem(key: PublicKey): String = wrap(key.encoded, "PUBLIC KEY")

    private fun wrap(der: ByteArray, label: String): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    private fun strip(pem: String, label: String): String = pem
        .replace("-----BEGIN $label-----", "")
        .replace("-----END $label-----", "")
        .replace(Regex("\\s"), "")
}
