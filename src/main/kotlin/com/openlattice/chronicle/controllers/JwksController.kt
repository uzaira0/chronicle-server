package com.openlattice.chronicle.controllers

import com.nimbusds.jose.jwk.RSAKey
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * JWKS (JSON Web Key Set) endpoint for RS256 mode.
 *
 * Publishes the RSA public key in JWK format so that external services
 * and clients can verify JWT signatures without a shared secret.
 *
 * Only active when the JWT algorithm is configured as RS256.
 * Returns 404 in HS256 mode since there is no public key to publish.
 *
 * This endpoint is unauthenticated (public) — see SecurityPod permitAll rules.
 */
@RestController
// DUAL PATH REQUIRED: same reasoning as AuthTokenController — Rhizome maps
// DispatcherServlet to /chronicle/*, so /.well-known/jwks.json needs both forms.
@RequestMapping(value = ["/chronicle/.well-known", "/.well-known"])
public open class JwksController(
    private val jwtKeyMaterial: JwtKeyMaterial,
) {

    @GetMapping(
        path = ["/jwks.json"],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun getJwks(): ResponseEntity<Map<String, Any>> {
        if (!jwtKeyMaterial.isRs256()) {
            return ResponseEntity.notFound().build()
        }

        val rsaPublicKey = jwtKeyMaterial.rsaPublicKey
            ?: return ResponseEntity.notFound().build()

        val jwk = RSAKey.Builder(rsaPublicKey)
            .keyID(jwtKeyMaterial.keyId)
            .build()

        val jwks = mapOf(
            "keys" to listOf(
                jwk.toJSONObject()
            )
        )

        return ResponseEntity.ok(jwks)
    }
}
