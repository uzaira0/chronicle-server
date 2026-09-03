package com.openlattice.chronicle.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.openlattice.chronicle.pods.servlet.buildJwtValidatorChain
import com.openlattice.chronicle.pods.servlet.parseApprovedMfaAcrValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import java.time.Instant
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/**
 * HIPAA-2028 W3 — proves the MFA enforcement matrix end-to-end through the production JWT
 * validator chain ([buildJwtValidatorChain]). Each token is HS256-signed and decoded by a
 * [NimbusJwtDecoder] configured exactly as [com.openlattice.chronicle.pods.servlet.ChronicleServerSecurityPod]
 * configures it, so a rejected token raises the same [JwtValidationException] the bearer-token
 * filter renders as HTTP 401, and an accepted token is one the request proceeds with (200).
 *
 * ```
 *  require-mfa | assurance                    | outcome
 *  ------------|------------------------------|--------
 *  false       | absent or incomplete         | 200
 *  true        | amr=mfa                      | 200
 *  true        | amr=pwd plus otp/hwk         | 200
 *  true        | configured approved acr      | 200
 *  true        | lone otp/hwk or absent       | 401
 * ```
 *
 * HIPAA §164.312(d) (person/entity authentication — MFA).
 */
class MfaEnforcementMatrixTest {

    private companion object {
        // HS256 requires a >= 256-bit (32-byte) secret; this is 42 bytes.
        private const val SECRET = "chronicle-mfa-test-secret-0123456789abcdef"
        private const val ISSUER = "https://idp.test.chronicle"
        private const val AUDIENCE = "chronicle"
    }

    private val key = SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")

    /** Mints an HS256 token with valid iss/aud/exp and optional assurance claims. */
    private fun mint(
        amr: Any?,
        acr: Any? = null,
        issuer: String = ISSUER,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject("researcher")
            .issuer(issuer)
            .audience(AUDIENCE)
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
        if (amr != null) {
            claims.claim("amr", amr)
        }
        if (acr != null) {
            claims.claim("acr", acr)
        }
        val signed = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims.build())
        signed.sign(MACSigner(SECRET.toByteArray()))
        return signed.serialize()
    }

    private fun decoderFor(
        requireMfa: Boolean,
        approvedMfaAcrValues: Set<String> = emptySet(),
    ): NimbusJwtDecoder =
        NimbusJwtDecoder.withSecretKey(key).build().apply {
            setJwtValidator(
                buildJwtValidatorChain(
                    ISSUER,
                    AUDIENCE,
                    requireMfa,
                    approvedMfaAcrValues,
                )
            )
        }

    /** Decodes successfully — the resource server would let the request proceed (HTTP 200). */
    private fun assertAccepted(
        requireMfa: Boolean,
        amr: Any?,
        acr: Any? = null,
        approvedMfaAcrValues: Set<String> = emptySet(),
    ) {
        val jwt = decoderFor(requireMfa, approvedMfaAcrValues).decode(mint(amr, acr))
        assertEquals("researcher", jwt.subject)
    }

    /** Decode throws — the bearer-token filter would reject the request (HTTP 401). */
    private fun assertRejected(
        requireMfa: Boolean,
        amr: Any?,
        acr: Any? = null,
        approvedMfaAcrValues: Set<String> = emptySet(),
    ) {
        assertThrows(JwtValidationException::class.java) {
            decoderFor(requireMfa, approvedMfaAcrValues).decode(mint(amr, acr))
        }
    }

    // require-mfa = false: the amr claim is never enforced -> always accepted.
    @Test
    fun `mfa off with amr present is accepted`() = assertAccepted(requireMfa = false, amr = listOf("mfa"))

    @Test
    fun `mfa off with amr absent is accepted`() = assertAccepted(requireMfa = false, amr = null)

    @Test
    fun `mfa off with wrong amr is accepted`() = assertAccepted(requireMfa = false, amr = listOf("pwd"))

    // require-mfa = true: explicit MFA, a two-factor combination, or approved ACR is required.
    @Test
    fun `mfa on with explicit mfa method is accepted`() =
        assertAccepted(requireMfa = true, amr = listOf("mfa"))

    @Test
    fun `explicit mfa from untrusted issuer is rejected by validator chain`() {
        assertThrows(JwtValidationException::class.java) {
            decoderFor(requireMfa = true).decode(
                mint(
                    amr = listOf("mfa"),
                    issuer = "https://attacker.invalid",
                )
            )
        }
    }

    @Test
    fun `mfa on with password plus otp is accepted`() =
        assertAccepted(requireMfa = true, amr = listOf("pwd", "otp"))

    @Test
    fun `mfa on with lone otp is rejected`() =
        assertRejected(requireMfa = true, amr = listOf("otp"))

    @Test
    fun `mfa on with lone hardware key is rejected`() =
        assertRejected(requireMfa = true, amr = listOf("hwk"))

    @Test
    fun `legacy access token without assurance is rejected by mfa chain`() =
        assertRejected(requireMfa = true, amr = null)

    @Test
    fun `mfa on with wrong amr is rejected`() = assertRejected(requireMfa = true, amr = listOf("pwd"))

    @Test
    fun `mfa on with approved acr is accepted`() {
        val approvedAcr = "https://refeds.org/profile/mfa"
        assertAccepted(
            requireMfa = true,
            amr = null,
            acr = approvedAcr,
            approvedMfaAcrValues = setOf(approvedAcr),
        )
    }

    @Test
    fun `approved acr configuration is comma separated and ignores blanks`() {
        assertEquals(
            setOf("https://refeds.org/profile/mfa", "urn:example:loa:3"),
            parseApprovedMfaAcrValues(
                " https://refeds.org/profile/mfa, ,urn:example:loa:3,https://refeds.org/profile/mfa "
            ),
        )
    }

    @Test
    fun `resource-server filter returns 401 when required amr is absent`() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/study")
        request.addHeader("Authorization", "Bearer ${mint(null)}")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        SecurityContextHolder.clearContext()
        try {
            bearerFilter(requireMfa = true).doFilter(request, response, chain)

            assertEquals(401, response.status)
            assertNull(chain.request)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `resource-server filter invokes application when required amr is present`() {
        val request = MockHttpServletRequest("GET", "/chronicle/v3/study")
        request.addHeader("Authorization", "Bearer ${mint(listOf("pwd", "otp"))}")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        SecurityContextHolder.clearContext()
        try {
            bearerFilter(requireMfa = true).doFilter(request, response, chain)

            assertEquals(200, response.status)
            assertSame(request, chain.request)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun bearerFilter(requireMfa: Boolean): BearerTokenAuthenticationFilter {
        val provider = JwtAuthenticationProvider(decoderFor(requireMfa))
        return BearerTokenAuthenticationFilter(ProviderManager(provider))
    }
}
