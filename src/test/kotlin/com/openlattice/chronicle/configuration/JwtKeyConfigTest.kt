package com.openlattice.chronicle.configuration

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.NestedExceptionUtils
import org.springframework.core.env.MapPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** The key material the backend boots with, from the same `chronicle.security.jwt.*` properties an operator sets. */
class JwtKeyConfigTest {
    private val directory: Path = Files.createTempDirectory(Path.of(checkNotNull(System.getenv("TMPDIR"))), "jwt-keys-")

    @After
    fun cleanUp() = directory.toFile().deleteRecursively().let { }

    private fun material(properties: Map<String, String>): JwtKeyMaterial =
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("operator", properties))
            context.register(JwtKeyConfig::class.java)
            context.refresh()
            context.getBean(JwtKeyMaterial::class.java)
        }

    private fun startupFailure(properties: Map<String, String>): Throwable =
        NestedExceptionUtils.getMostSpecificCause(runCatching { material(properties) }.exceptionOrNull()!!)

    private fun pem(label: String, der: ByteArray): Path = directory.resolve("${label.replace(' ', '-')}.pem").also {
        Files.writeString(it, "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n")
    }

    @Test
    fun defaultIsHs256WithAFreshKeyIdUnlessOneIsConfigured() {
        val first = material(emptyMap())
        val second = material(emptyMap())
        assertTrue(first.isHs256() && !first.isRs256())
        assertNull(first.rsaPublicKey)
        assertNotEquals("each boot without a key-id gets its own", first.keyId, second.keyId)
        assertEquals("kid-cl", material(mapOf("chronicle.security.jwt.key-id" to "kid-cl")).keyId)
    }

    @Test
    fun rs256LoadsThePemPairItWasGivenAndTokensSignedWithItVerify() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val loaded = material(mapOf(
            "chronicle.security.jwt.algorithm" to " rs256 ",
            "chronicle.security.jwt.public-key-path" to pem("PUBLIC KEY", pair.public.encoded).toString(),
            "chronicle.security.jwt.private-key-path" to pem("PRIVATE KEY", pair.private.encoded).toString(),
            "chronicle.security.jwt.key-id" to "kid-rs",
        ))

        assertTrue(loaded.isRs256())
        assertEquals("kid-rs", loaded.keyId)
        assertEquals(pair.public, loaded.rsaPublicKey)
        val token = JWT.create().withSubject("researcher").sign(Algorithm.RSA256(null, loaded.rsaPrivateKey))
        val verified = JWT.require(Algorithm.RSA256(pair.public as RSAPublicKey, null as RSAPrivateKey?)).build().verify(token)
        assertEquals("researcher", verified.subject)
    }

    @Test
    fun misconfiguredKeyMaterialStopsStartupWithTheSettingToFix() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicPem = pem("PUBLIC KEY", pair.public.encoded).toString()
        val rs256 = mapOf("chronicle.security.jwt.algorithm" to "RS256")

        assertTrue(startupFailure(rs256).message!!.contains("public-key-path is required"))
        assertTrue(startupFailure(rs256 + ("chronicle.security.jwt.public-key-path" to publicPem))
            .message!!.contains("private-key-path is required"))
        val unsupported = startupFailure(mapOf("chronicle.security.jwt.algorithm" to "ES256"))
        assertEquals("Unsupported JWT algorithm: ES256. Supported: HS256, RS256", unsupported.message)
        // The public key mounted where the private key belongs is not silently accepted.
        val swapped = startupFailure(rs256 + mapOf(
            "chronicle.security.jwt.public-key-path" to publicPem,
            "chronicle.security.jwt.private-key-path" to publicPem,
        ))
        assertTrue(swapped.toString(), swapped is java.security.GeneralSecurityException || swapped is IllegalArgumentException)
        val missing = startupFailure(rs256 + mapOf(
            "chronicle.security.jwt.public-key-path" to directory.resolve("absent.pem").toString(),
            "chronicle.security.jwt.private-key-path" to publicPem,
        ))
        assertTrue(missing.toString(), missing is java.io.IOException)
    }
}
