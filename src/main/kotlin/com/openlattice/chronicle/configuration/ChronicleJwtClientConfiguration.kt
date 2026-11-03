package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleJwtClientConfiguration(
    val audience: String,
    val base64EncodedSecret: Boolean = false,
    val issuer: String,
    val jwkSetUri: String? = null,
    val secret: String = "",
    val signingAlgorithm: String = "HS256",
    val testingTokenIssuer: Boolean = true,
    val previousSecret: String = "",
    val previousSecretExpiresAt: Long = 0,
) {
    public fun secretBytes(value: String = secret): ByteArray = if (base64EncodedSecret) {
        Base64.getUrlDecoder().decode(value)
    } else {
        value.toByteArray(StandardCharsets.UTF_8)
    }

    public fun keyId(value: String = secret): String = MessageDigest.getInstance("SHA-256")
        .digest(secretBytes(value)).take(16).joinToString("") { "%02x".format(it) }
}
