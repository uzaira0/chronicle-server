/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import com.hazelcast.core.HazelcastInstance
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.beans.factory.annotation.Autowired
import jakarta.inject.Inject
import jakarta.servlet.Filter
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentScope
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentService
import com.openlattice.chronicle.util.validateParticipantId
import java.util.UUID

/**
 * Configuration class for Mobile API request signing and replay prevention.
 *
 * This configuration retains deployment-wide HMAC-SHA256 request signing only for explicitly
 * controlled legacy/server compatibility clients. Public mobile builds enroll with a selected
 * study server and use their per-device credentials; they must never contain this shared secret.
 * The compatibility layer protects against:
 * - Request tampering (MITM attacks)
 * - Replay attacks (captured requests being re-sent)
 *
 * Configuration is loaded from mobile-security.yaml:
 * ```yaml
 * enabled: false
 * signing-secret: ""
 * previous-signing-secret: ""
 * signing-required: false
 * max-request-age-minutes: 5
 * clock-skew-seconds: 30
 * nonce-ttl-minutes: 10
 * ```
 *
 * IMPORTANT SECURITY NOTES:
 * 1. The signing secret MUST be at least 256 bits (32 bytes) for security
 * 2. Store the secret securely (e.g., a local secret manager or environment variable)
 * 3. Never distribute the secret in a public mobile application
 * 4. Rotate it only across the controlled compatibility clients that still require this layer
 * 5. Public clients use enrollment and per-device keys instead
 *
 * @author uzaira0
 */
@org.springframework.context.annotation.Configuration
public open class MobileApiSecurityConfig {

    internal companion object {
        private val logger = LoggerFactory.getLogger(MobileApiSecurityConfig::class.java)

        /**
         * Minimum recommended secret length in bytes (256 bits).
         */
        private const val MIN_SECRET_LENGTH = 32
    }

    @Inject
    private lateinit var hazelcastInstance: HazelcastInstance

    @Autowired(required = false)
    private var mobileSecurityConfiguration: MobileSecurityConfiguration? = null

    @Autowired
    private lateinit var participantFormAccessService: ParticipantFormAccessService

    @Autowired
    private lateinit var auditService: AuditService

    /**
     * Creates the Mobile API signature filter bean.
     *
     * The filter is ordered at HIGHEST_PRECEDENCE + 10 to run:
     * - After the basic security filters (TRACE blocking, headers, validation)
     * - Before Spring Security authentication
     *
     * This ensures that:
     * 1. Basic request validation has already occurred
     * 2. The signature is verified before authentication
     * 3. Invalid signatures are rejected early
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public fun mobileApiSignatureFilter(): Filter {
        val config = mobileSecurityConfiguration ?: MobileSecurityConfiguration()
        val reviewerScope = config.reviewerEnrollment.validatedScopeOrNull()

        if (!config.enabled) {
            logger.info("Mobile API signature verification is DISABLED")
            return MobileApiSignatureFilter(
                hazelcastInstance = hazelcastInstance,
                signingSecret = "",
                signingRequired = false,
                signatureVerificationEnabled = false,
                internalWebSecret = config.internalWebSecret,
                participantFormAccessService = participantFormAccessService,
                reviewerEnrollmentSecret = config.reviewerEnrollment.secret.takeIf { reviewerScope != null }.orEmpty(),
                reviewerStudyId = reviewerScope?.studyId,
                auditService = auditService,
            )
        }

        if (!config.signingRequired) {
            logger.warn(
                "Mobile API signing is enabled but NOT enforced (signing-required=false). " +
                        "Set signing-required=true in production to enforce HMAC signature verification."
            )
        }

        // Validate secret strength
        val secret = config.signingSecret
        check(secret.isNotBlank()) {
            "Mobile API signing is enabled but signing-secret is blank. " +
                "Configure a secure secret in mobile-security.yaml."
        }
        if (secret.length < MIN_SECRET_LENGTH) {
            logger.warn(
                "Mobile API signing secret is shorter than recommended ({} bytes). " +
                        "Use at least {} bytes for security.",
                secret.length,
                MIN_SECRET_LENGTH
            )
        }

        val previousSecret = config.previousSigningSecret
        check(previousSecret.isBlank() || previousSecret.length >= MIN_SECRET_LENGTH) {
            "Mobile API previous signing secret must be at least $MIN_SECRET_LENGTH bytes."
        }
        check(previousSecret.isBlank() || previousSecret != secret) {
            "Mobile API previous signing secret must differ from the current secret."
        }

        check(!config.signingRequired || config.internalWebSecret.isNotBlank()) {
            "Mobile API signing is enforced but internal-web-secret is blank. " +
                "Configure CHRONICLE_INTERNAL_WEB_SECRET and inject it from the trusted reverse proxy."
        }

        logger.info(
            "Mobile API signature verification is ENABLED. " +
                    "Signing required: {}, Max request age: {} minutes, Clock skew: {} seconds",
            config.signingRequired,
            config.maxRequestAgeMinutes,
            config.clockSkewSeconds
        )

        return MobileApiSignatureFilter(
            hazelcastInstance = hazelcastInstance,
            signingSecret = secret,
            signingRequired = config.signingRequired,
            maxRequestAgeMinutes = config.maxRequestAgeMinutes,
            clockSkewSeconds = config.clockSkewSeconds,
            nonceTtlMinutes = config.nonceTtlMinutes,
            internalWebSecret = config.internalWebSecret,
            previousSigningSecrets = listOfNotNull(previousSecret.takeIf(String::isNotBlank)),
            participantFormAccessService = participantFormAccessService,
            reviewerEnrollmentSecret = config.reviewerEnrollment.secret.takeIf { reviewerScope != null }.orEmpty(),
            reviewerStudyId = reviewerScope?.studyId,
            auditService = auditService,
        )
    }

    @Bean
    public fun enrollmentManifestService(studyService: StudyService): EnrollmentManifestService {
        val config = mobileSecurityConfiguration ?: MobileSecurityConfiguration()
        val publicBaseUrl = config.publicBaseUrl.ifBlank {
            check(!config.enabled) {
                "Mobile enrollment is enabled but public-base-url is blank. " +
                    "Configure the canonical public HTTPS root origin in mobile-security.yaml."
            }
            "https://localhost"
        }
        return EnrollmentManifestService(studyService, participantFormAccessService, publicBaseUrl)
    }

    @Bean
    public fun reviewerEnrollmentService(
        enrollmentManifestService: EnrollmentManifestService,
        enrollmentManager: EnrollmentManager,
        studyService: StudyService,
        studyLifecycleService: StudyLifecycleService,
    ): ReviewerEnrollmentService {
        val config = mobileSecurityConfiguration ?: MobileSecurityConfiguration()
        return ReviewerEnrollmentService(
            config.reviewerEnrollment.validatedScopeOrNull(),
            participantFormAccessService,
            enrollmentManifestService,
            enrollmentManager,
            studyService,
            studyLifecycleService,
            auditService,
        )
    }

}

/**
 * Configuration data class for mobile API security settings.
 *
 * Loaded from mobile-security.yaml configuration file.
 */
@ReloadableConfiguration(uri = "mobile-security.yaml")
public data class MobileSecurityConfiguration(
    /**
     * Whether mobile API signature verification is enabled.
     * When false, all signature-related processing is skipped.
     */
    @param:JsonProperty("enabled")
    val enabled: Boolean = false,

    /**
     * Deployment-wide HMAC key for explicitly controlled legacy/server compatibility clients.
     * It must be at least 256 bits (32 bytes) and must never be distributed in a public mobile
     * application. Public clients use enrollment and per-device keys instead.
     */
    @param:JsonProperty("signing-secret")
    val signingSecret: String = "",

    /**
     * Prior compatibility HMAC key accepted during a bounded controlled-client rotation. Keep
     * blank normally; remove it after every explicitly managed compatibility client has moved to
     * the current key. Public mobile builds must contain neither key.
     */
    @param:JsonProperty("previous-signing-secret")
    val previousSigningSecret: String = "",

    /**
     * Whether request signing is mandatory.
     * - false: Unsigned requests are allowed (for backward compatibility)
     * - true: All requests must be signed (for production enforcement)
     */
    @param:JsonProperty("signing-required")
    val signingRequired: Boolean = false,

    /**
     * Maximum age of requests in minutes.
     * Requests with timestamps older than this are rejected.
     * Default: 5 minutes
     */
    @param:JsonProperty("max-request-age-minutes")
    val maxRequestAgeMinutes: Long = 5,

    /**
     * Allowed clock skew in seconds between client and server.
     * Accounts for time synchronization differences.
     * Default: 30 seconds
     */
    @param:JsonProperty("clock-skew-seconds")
    val clockSkewSeconds: Long = 30,

    /**
     * Time-to-live for nonces in the distributed cache (minutes).
     * Should be at least maxRequestAgeMinutes + clockSkewSeconds to ensure
     * nonces are retained for the entire valid request window.
     * Default: 10 minutes
     */
    @param:JsonProperty("nonce-ttl-minutes")
    val nonceTtlMinutes: Long = 10,

    /**
     * Proxy-injected shared secret that authenticates the internal-web signing
     * bypass (X-Chronicle-Internal-Web). When set, the marker must carry this
     * exact value under constant-time comparison. A blank value disables the
     * bypass; there is no literal marker fallback. The reverse proxy must inject
     * the same secret on the /chronicle/api/web boundary.
     */
    @param:JsonProperty("internal-web-secret")
    val internalWebSecret: String = "",

    /** Canonical public HTTPS root origin embedded in enrollment manifests. */
    @param:JsonProperty("public-base-url")
    val publicBaseUrl: String = "",

    /** Optional Play Console reviewer bootstrap. Disabled unless every scoped value is valid. */
    @param:JsonProperty("reviewer-enrollment")
    val reviewerEnrollment: ReviewerEnrollmentConfiguration = ReviewerEnrollmentConfiguration(),
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("mobile-security.yaml")
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey = key
}

public data class ReviewerEnrollmentConfiguration(
    @param:JsonProperty("enabled")
    val enabled: Boolean = false,
    @param:JsonProperty("secret")
    val secret: String = "",
    @param:JsonProperty("study-id")
    val studyId: String = "",
    @param:JsonProperty("participant-id")
    val participantId: String = "",
) {
    internal fun validatedScopeOrNull(): ReviewerEnrollmentScope? {
        if (!enabled) return null
        check(secret.length in 32..256 && secret.all { it.code in 0x21..0x7e }) {
            "Reviewer enrollment secret must be 32-256 printable ASCII characters without whitespace"
        }
        val parsedStudyId = runCatching { UUID.fromString(studyId) }.getOrNull()
        check(parsedStudyId != null) { "Reviewer enrollment study-id must be a UUID" }
        try {
            validateParticipantId(participantId)
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("Reviewer enrollment participant-id is invalid", exception)
        }
        return ReviewerEnrollmentScope(parsedStudyId, participantId)
    }
}
