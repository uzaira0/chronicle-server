// reason: intentional package layout — declared package (com.openlattice.users) differs from the
// directory by design; renaming the package would break all importers of these JWT utils
@file:Suppress("InvalidPackageDeclaration")

package com.openlattice.users

import com.auth0.jwt.algorithms.Algorithm
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import java.security.InvalidAlgorithmParameterException

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */

internal fun parseAlgorithm(aac: ChronicleJwtClientConfiguration): Algorithm {
    val algorithm: (String) -> Algorithm = when (aac.signingAlgorithm) {
        "HS256" -> Algorithm::HMAC256
        "HS384" -> Algorithm::HMAC384
        "HS512" -> Algorithm::HMAC512
        else -> throw InvalidAlgorithmParameterException("Algorithm ${aac.signingAlgorithm} not recognized.")
    }
    return algorithm(aac.secret)
}

/**
 * Returns the auth0 [Algorithm] for token signing, supporting both HS256 and RS256.
 *
 * When [JwtKeyMaterial] specifies RS256, the RSA key pair is used directly and the
 * YAML secret is ignored. Otherwise falls back to the existing HMAC-based flow.
 */
internal fun parseAlgorithm(aac: ChronicleJwtClientConfiguration, keyMaterial: JwtKeyMaterial): Algorithm {
    if (keyMaterial.isRs256()) {
        val publicKey = requireNotNull(keyMaterial.rsaPublicKey) { "RS256 requires an RSA public key" }
        val privateKey = requireNotNull(keyMaterial.rsaPrivateKey) { "RS256 requires an RSA private key" }
        return Algorithm.RSA256(publicKey, privateKey)
    }
    return parseAlgorithm(aac)
}
