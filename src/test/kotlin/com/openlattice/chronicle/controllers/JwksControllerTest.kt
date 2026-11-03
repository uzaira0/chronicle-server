package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.configuration.JwtKeyMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.web.server.ResponseStatusException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

class JwksControllerTest {
    /** The self-host bundle signs with a shared HMAC secret: there is no public key, so the JWKS URL is not found. */
    @Test
    fun hmacDeploymentPublishesNoKeySet() {
        val hs256 = JwtKeyMaterial("HS256", "selfhost", null, null, null)
        assertEquals(404, assertThrows(ResponseStatusException::class.java) { JwksController(hs256).getJwks() }.statusCode.value())
        val rs256WithoutKey = JwtKeyMaterial("RS256", "rotating", null, null, null)
        assertEquals(404, assertThrows(ResponseStatusException::class.java) {
            JwksController(rs256WithoutKey).getJwks()
        }.statusCode.value())
    }

    /** An RS256 deployment publishes only the public half under its key id, never the private exponent. */
    @Test
    fun rsaDeploymentPublishesOnlyThePublicKeyUnderItsKeyId() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val material = JwtKeyMaterial("RS256", "key-2026-10", pair.public as RSAPublicKey, pair.private as RSAPrivateKey, null)

        val response = JwksController(material).getJwks()

        assertEquals(200, response.statusCode.value())
        val key = (response.body!!["keys"] as List<*>).single() as Map<*, *>
        assertEquals("RSA", key["kty"])
        assertEquals("key-2026-10", key["kid"])
        assertEquals(setOf("kty", "kid", "n", "e"), key.keys)
        assertFalse(key.containsKey("d"))
    }
}
