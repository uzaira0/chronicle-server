package com.openlattice.chronicle.security

import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * JWT validator that enforces MFA (multi-factor authentication) by inspecting
 * the `amr` (Authentication Methods Reference) and `acr` (Authentication
 * Context Class Reference) claims.
 *
 * A signed token satisfies this validator when it has one of:
 * - an explicit `amr=mfa` assertion from the trusted issuer;
 * - both `amr=pwd` and a possession method (`otp` or `hwk`);
 * - an `acr` value explicitly approved by configuration.
 *
 * A lone `otp` or `hwk` value identifies only one factor and is not sufficient.
 *
 * Enforcement is configured via `chronicle.security.require-mfa` (secure
 * default: true). Approved assurance contexts are configured via
 * `chronicle.security.approved-mfa-acr-values` (secure default: empty).
 *
 * HIPAA §164.312(d) — MFA strengthens authentication controls.
 */
public class MfaClaimValidator(
    private val approvedAcrValues: Set<String> = emptySet(),
) : OAuth2TokenValidator<Jwt> {

    internal companion object {
        private val logger = LoggerFactory.getLogger(MfaClaimValidator::class.java)
        private const val EXPLICIT_MFA_METHOD = "mfa"
        private const val AMR_PWD_VALUE = "pwd"
        private val POSSESSION_METHODS = setOf("otp", "hwk")
        private val MFA_MISSING_ERROR = OAuth2Error(
            "insufficient_authentication",
            "Multi-factor authentication is required. The token must contain an explicit " +
                "'amr=mfa', a password plus possession-factor method, or an approved 'acr' value.",
            null
        )
    }

    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val acrClaim = token.claims["acr"] as? String
        if (acrClaim != null && acrClaim in approvedAcrValues) {
            return OAuth2TokenValidatorResult.success()
        }

        val amrClaim = token.claims["amr"]
        val amrValues: Set<String> = when (amrClaim) {
            is Collection<*> -> amrClaim.filterIsInstance<String>().toSet()
            is String -> setOf(amrClaim)
            null -> {
                logger.warn(
                    "MFA required but token has neither qualifying 'amr' nor approved 'acr' for subjectRef={}",
                    LogSanitizer.stableFingerprint(token.subject.orEmpty(), prefix = "subject"),
                )
                return OAuth2TokenValidatorResult.failure(MFA_MISSING_ERROR)
            }
            else -> {
                logger.warn(
                    "MFA required but 'amr' claim has unexpected type={} for subjectRef={}",
                    amrClaim.javaClass.simpleName,
                    LogSanitizer.stableFingerprint(token.subject.orEmpty(), prefix = "subject"),
                )
                return OAuth2TokenValidatorResult.failure(MFA_MISSING_ERROR)
            }
        }

        val hasExplicitMfa = EXPLICIT_MFA_METHOD in amrValues
        val hasPasswordAndPossession =
            AMR_PWD_VALUE in amrValues && amrValues.any(POSSESSION_METHODS::contains)
        if (!hasExplicitMfa && !hasPasswordAndPossession) {
            logger.warn(
                "MFA required but 'amr' claim {} is not explicit MFA or password plus possession for subjectRef={}",
                amrValues,
                LogSanitizer.stableFingerprint(token.subject.orEmpty(), prefix = "subject"),
            )
            return OAuth2TokenValidatorResult.failure(MFA_MISSING_ERROR)
        }

        return OAuth2TokenValidatorResult.success()
    }
}
