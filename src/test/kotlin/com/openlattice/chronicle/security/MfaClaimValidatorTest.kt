package com.openlattice.chronicle.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * HIPAA-2028 W3 — unit coverage for [MfaClaimValidator]: the assurance gate that, when
 * MFA is enforced, requires explicit MFA, two complementary methods, or an approved ACR.
 * Companion to [MfaEnforcementMatrixTest], which proves the same logic end-to-end through the
 * production JWT decoder chain. HIPAA §164.312(d).
 */
class MfaClaimValidatorTest {

    private val validator = MfaClaimValidator()

    private fun jwt(amr: Any?, acr: Any? = null): Jwt {
        val builder = Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .subject("researcher")
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2026-12-31T23:59:59Z"))
        if (amr != null) {
            builder.claim("amr", amr)
        }
        if (acr != null) {
            builder.claim("acr", acr)
        }
        return builder.build()
    }

    @Test
    fun `amr list containing mfa is accepted`() {
        assertFalse(validator.validate(jwt(listOf("mfa"))).hasErrors())
    }

    @Test
    fun `lone otp method is rejected`() {
        assertTrue(validator.validate(jwt(listOf("otp"))).hasErrors())
    }

    @Test
    fun `lone hardware key method is rejected`() {
        assertTrue(validator.validate(jwt(listOf("hwk"))).hasErrors())
    }

    @Test
    fun `amr as a bare accepted string is accepted`() {
        assertFalse(validator.validate(jwt("mfa")).hasErrors())
    }

    @Test
    fun `password plus otp is accepted`() {
        assertFalse(validator.validate(jwt(listOf("pwd", "otp"))).hasErrors())
    }

    @Test
    fun `password plus hardware key is accepted`() {
        assertFalse(validator.validate(jwt(listOf("pwd", "hwk"))).hasErrors())
    }

    @Test
    fun `two possession methods without password are rejected`() {
        assertTrue(validator.validate(jwt(listOf("otp", "hwk"))).hasErrors())
    }

    @Test
    fun `password plus unapproved method is rejected`() {
        assertTrue(validator.validate(jwt(listOf("pwd", "sms"))).hasErrors())
    }

    @Test
    fun `approved acr is accepted without amr`() {
        val acrValidator = MfaClaimValidator(setOf("https://refeds.org/profile/mfa"))
        assertFalse(
            acrValidator.validate(
                jwt(amr = null, acr = "https://refeds.org/profile/mfa")
            ).hasErrors()
        )
    }

    @Test
    fun `unapproved acr is rejected without amr`() {
        val acrValidator = MfaClaimValidator(setOf("https://refeds.org/profile/mfa"))
        assertTrue(acrValidator.validate(jwt(amr = null, acr = "urn:example:password")).hasErrors())
    }

    @Test
    fun `acr matching remains exact`() {
        val acrValidator = MfaClaimValidator(setOf("https://refeds.org/profile/mfa"))
        assertTrue(
            acrValidator.validate(
                jwt(amr = null, acr = "https://refeds.org/profile/MFA")
            ).hasErrors()
        )
    }

    @Test
    fun `missing amr claim is rejected as insufficient_authentication`() {
        val result = validator.validate(jwt(null))
        assertTrue(result.hasErrors())
        assertEquals("insufficient_authentication", result.errors.first().errorCode)
    }

    @Test
    fun `amr with only non-MFA methods is rejected`() {
        val result = validator.validate(jwt(listOf("pwd")))
        assertTrue(result.hasErrors())
        assertEquals("insufficient_authentication", result.errors.first().errorCode)
    }

    @Test
    fun `empty amr list is rejected`() {
        assertTrue(validator.validate(jwt(emptyList<String>())).hasErrors())
    }

    @Test
    fun `amr of an unexpected type is rejected`() {
        assertTrue(validator.validate(jwt(123)).hasErrors())
    }

    @Test
    fun `bare string amr that is not an accepted method is rejected`() {
        assertTrue(validator.validate(jwt("pwd")).hasErrors())
    }
}
