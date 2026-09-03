package com.openlattice.chronicle.configuration

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Centralized JWT key material configuration.
 *
 * Loads signing/verification keys based on the configured algorithm (HS256 or RS256).
 * All components that need key material (decoder, token issuer, JWKS endpoint,
 * testing token service) inject this single bean rather than reading properties independently.
 *
 * Configuration properties:
 * - chronicle.security.jwt.algorithm: HS256 (default) or RS256
 * - chronicle.security.jwt.public-key-path: PEM file path (RS256 only)
 * - chronicle.security.jwt.private-key-path: PEM file path (RS256 only)
 * - chronicle.security.jwt.key-id: kid header value (auto-generated if absent)
 */
@Configuration
public open class JwtKeyConfig {

    internal companion object {
        private val logger = LoggerFactory.getLogger(JwtKeyConfig::class.java)
    }

    @Value("\${chronicle.security.jwt.algorithm:HS256}")
    private lateinit var algorithm: String

    @Value("\${chronicle.security.jwt.public-key-path:}")
    private var publicKeyPath: String = ""

    @Value("\${chronicle.security.jwt.private-key-path:}")
    private var privateKeyPath: String = ""

    @Value("\${chronicle.security.jwt.key-id:}")
    private var keyId: String = ""

    @Bean
    public fun jwtKeyMaterial(): JwtKeyMaterial {
        val effectiveAlgorithm = algorithm.uppercase().trim()
        val effectiveKeyId = keyId.ifBlank { UUID.randomUUID().toString() }

        return when (effectiveAlgorithm) {
            "RS256" -> {
                require(publicKeyPath.isNotBlank()) {
                    "chronicle.security.jwt.public-key-path is required when algorithm is RS256"
                }
                require(privateKeyPath.isNotBlank()) {
                    "chronicle.security.jwt.private-key-path is required when algorithm is RS256"
                }

                val publicKey = loadRsaPublicKey(publicKeyPath)
                val privateKey = loadRsaPrivateKey(privateKeyPath)

                logger.info("JWT key material loaded: algorithm=RS256, kid={}", effectiveKeyId)
                JwtKeyMaterial(
                    algorithm = "RS256",
                    keyId = effectiveKeyId,
                    rsaPublicKey = publicKey,
                    rsaPrivateKey = privateKey,
                    hmacSecret = null,
                )
            }
            "HS256" -> {
                logger.info("JWT key material loaded: algorithm=HS256, kid={}", effectiveKeyId)
                // HS256 key material comes from ChronicleAuthConfiguration (existing config)
                JwtKeyMaterial(
                    algorithm = "HS256",
                    keyId = effectiveKeyId,
                    rsaPublicKey = null,
                    rsaPrivateKey = null,
                    hmacSecret = null,  // populated from existing auth config at decoder-build time
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported JWT algorithm: $effectiveAlgorithm. Supported: HS256, RS256"
            )
        }
    }

    private fun loadRsaPublicKey(path: String): RSAPublicKey {
        val pem = File(path).readText(StandardCharsets.UTF_8)
        val encoded = parsePem(pem, "PUBLIC KEY")
        val spec = X509EncodedKeySpec(encoded)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePublic(spec) as RSAPublicKey
    }

    private fun loadRsaPrivateKey(path: String): RSAPrivateKey {
        val pem = File(path).readText(StandardCharsets.UTF_8)
        val encoded = parsePem(pem, "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(encoded)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(spec) as RSAPrivateKey
    }

    private fun parsePem(pem: String, label: String): ByteArray {
        val stripped = pem
            .replace("-----BEGIN $label-----", "")
            .replace("-----END $label-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(stripped)
    }
}

/**
 * Immutable holder for JWT key material.
 */
public data class JwtKeyMaterial(
    val algorithm: String,
    val keyId: String,
    val rsaPublicKey: RSAPublicKey?,
    val rsaPrivateKey: RSAPrivateKey?,
    val hmacSecret: ByteArray?,
) {
    public fun isRs256(): Boolean = algorithm == "RS256"
    public fun isHs256(): Boolean = algorithm == "HS256"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JwtKeyMaterial) return false
        return algorithm == other.algorithm && keyId == other.keyId
    }

    override fun hashCode(): Int = 31 * algorithm.hashCode() + keyId.hashCode()
}
