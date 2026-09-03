/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */
package com.openlattice.chronicle.pods.servlet

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.ChronicleRoleClaims
import com.openlattice.chronicle.configuration.roleClientIdForClaims
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.security.MfaClaimValidator
import org.slf4j.LoggerFactory
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.services.security.HoneyTokenService
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import com.openlattice.chronicle.filters.JwtBlocklistFilter
import com.openlattice.chronicle.filters.RLSContextFilter
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.rls.RLSContextManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfigurationSource
import com.openlattice.chronicle.configuration.ObservabilityFilter
import com.openlattice.chronicle.observability.ApiMetricsFilter
import com.openlattice.chronicle.security.MetricsAuthenticationFilter
import com.openlattice.chronicle.controllers.ReviewerEnrollmentController
import org.springframework.web.filter.CharacterEncodingFilter
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.web.servlet.handler.HandlerMappingIntrospector
import org.springframework.beans.factory.annotation.Autowired
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import jakarta.servlet.Filter

@Configuration
@EnableMethodSecurity(proxyTargetClass = true)
@EnableWebSecurity(debug = false)
public open class ChronicleServerSecurityPod {

    @Autowired
    private lateinit var chronicleAuthConfiguration: ChronicleAuthConfiguration

    @Autowired
    private lateinit var jwtKeyMaterial: JwtKeyMaterial

    @Value("\${chronicle.security.require-mfa:true}")
    private var requireMfa: Boolean = true

    @Value("\${chronicle.security.approved-mfa-acr-values:}")
    private lateinit var approvedMfaAcrValues: String

    @Value("\${chronicle.security.metrics-username:chronicle-metrics}")
    private lateinit var metricsUsername: String

    @Value("\${chronicle.security.metrics-password:}")
    private lateinit var metricsPassword: String

    @Value("\${chronicle.security.metrics-password-files:}")
    private lateinit var metricsPasswordFiles: String

    @Autowired(required = false)
    private var corsConfigurationSource: CorsConfigurationSource? = null

    @Autowired(required = false)
    private var apiKeyService: ApiKeyService? = null

    @Autowired(required = false)
    private var jwtBlocklist: JwtBlocklist? = null

    @Autowired(required = false)
    private var rlsContextManager: RLSContextManager? = null

    @Autowired(required = false)
    private var honeyTokenService: HoneyTokenService? = null

    @Autowired
    private lateinit var participantFormAccessService: ParticipantFormAccessService

    @Autowired(required = false)
    @Qualifier("mobileApiSignatureFilter")
    private var mobileApiSignatureFilter: Filter? = null

    @Autowired(required = false)
    @Qualifier("corsValidationFilter")
    private var corsValidationFilter: Filter? = null

    @Autowired(required = false)
    @Qualifier("rateLimitFilter")
    private var rateLimitFilter: Filter? = null

    @Bean
    public fun mvcHandlerMappingIntrospector(): HandlerMappingIntrospector {
        return HandlerMappingIntrospector()
    }

    @Bean
    public fun chronicleTokenResolver(): BearerTokenResolver {
        return ChronicleCookieOrBearerTokenResolver()
    }

    @Bean
    public fun chronicleJwtAuthenticationConverter(): JwtAuthenticationConverter {
        val scopeConverter = JwtGrantedAuthoritiesConverter()
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val subjectAuthority = jwt.subject
                ?.takeIf { it.isNotBlank() }
                ?.let { SimpleGrantedAuthority("${PrincipalType.USER.name}|$it") }

            val roleAuthorities = extractChronicleRoles(jwt.claims)
                .filter { it in SystemRole }
                .map { SimpleGrantedAuthority("${PrincipalType.ROLE.name}|$it") }

            (scopeConverter.convert(jwt).orEmpty() + listOfNotNull(subjectAuthority) + roleAuthorities).distinctBy {
                it.authority
            }
        }
        return converter
    }

    @Bean
    public fun jwtDecoder(): JwtDecoder {
        val config = activeJwtClientConfiguration()
            ?: error("Chronicle auth configuration is missing JWT client settings.")
        if (!chronicleAuthConfiguration.allowProductionTestingLogin) {
            return createJwtDecoder(config, jwtKeyMaterial)
        }

        val testingConfig = chronicleAuthConfiguration.configurations.firstOrNull { it.testingTokenIssuer }
            ?: return createJwtDecoder(config, jwtKeyMaterial)

        return createJwtDecoderWithTestingFallback(config, testingConfig, jwtKeyMaterial)
    }

    private fun activeJwtClientConfiguration(): ChronicleJwtClientConfiguration? {
        val oidc = chronicleAuthConfiguration.oidc
        if (oidc.enabled) {
            val configuredOidc = chronicleAuthConfiguration.configurations.firstOrNull {
                !it.testingTokenIssuer && !it.jwkSetUri.isNullOrBlank()
            }
            if (configuredOidc != null) {
                return configuredOidc
            }
            if (oidc.issuer.isNotBlank() && oidc.clientId.isNotBlank() && oidc.jwkSetUri.isNotBlank()) {
                return ChronicleJwtClientConfiguration(
                    audience = oidc.clientId,
                    issuer = oidc.issuer,
                    jwkSetUri = oidc.jwkSetUri,
                    signingAlgorithm = "RS256",
                    testingTokenIssuer = false,
                )
            }
            error("OIDC is enabled, but issuer/clientId/jwkSetUri are not fully configured.")
        }

        return chronicleAuthConfiguration.configurations.firstOrNull { it.testingTokenIssuer }
            ?: chronicleAuthConfiguration.configurations.firstOrNull()
    }

    /**
     * SECURITY: Spring CSRF disabled intentionally — the API is stateless (JWT/cookie) with
     * SameSite=Strict cookies + a custom CSRF cookie (see AuthTokenController). Spring's CSRF
     * token mechanism is incompatible with this pattern. Extracted to a named method (rather than
     * an inline lambda) so the SuppressFBWarnings lands on the synthetic method SpotBugs flags.
     */
    @SuppressFBWarnings(
        value = ["SPRING_CSRF_PROTECTION_DISABLED"],
        justification = "Stateless REST API (SessionCreationPolicy.STATELESS) using Bearer/API-key " +
            "auth; CSRF disabled by design. Cookie-JWT path defended by SameSite=Strict + " +
            "double-submit CSRF cookie.",
    )
    private fun disableCsrf(csrf: CsrfConfigurer<HttpSecurity>) {
        csrf.disable()
    }

    @Bean
    @Throws(Exception::class)
    // reason: linear Spring Security HttpSecurity DSL — the route allowlist and filter ordering
    // are a single security-critical declaration that must not be split or reordered
    @Suppress("LongMethod")
    public fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf(::disableCsrf)
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder())
                    jwt.jwtAuthenticationConverter(chronicleJwtAuthenticationConverter())
                }
                oauth2.bearerTokenResolver(chronicleTokenResolver())
            }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { securityContext -> securityContext.requireExplicitSave(true) }

        // Enable CORS with our custom configuration source
        corsConfigurationSource?.let { corsSource ->
            http.cors { cors -> cors.configurationSource(corsSource) }
        } ?: run {
            http.cors { }
        }

        http.authorizeHttpRequests { auth ->
            auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // The historical /datastore/* servlet alias is intentionally retired. Keep an
                // explicit deny here as defense in depth if a container mapping regresses.
                .requestMatchers("/datastore/**").denyAll()

                // v4 mobile enrollment — bootstrap step that issues the per-device API key.
                // Subsequent v4 uploads fall under /chronicle/** authenticated() and require X-Api-Key.
                .requestMatchers(
                    HttpMethod.GET,
                    "/chronicle/v4/study/*/participant/*/enrollment-preview",
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v4/study/*/participant/*/enroll").permitAll()
                // Reusable Play Console access is accepted only on this exact endpoint by
                // MobileApiSignatureFilter, which converts it to a configured study scope.
                .requestMatchers(HttpMethod.POST, ReviewerEnrollmentController.PATH).permitAll()
                // Legacy iOS enrollment still uses the v3 path with the source device id in the URL.
                // The controller validates the study/participant and derives the stored datasource id.
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/study/*/participant/*/*/enroll").permitAll()

                // v3 study settings — only sensor-related endpoints are public (mobile needs these)
                // T-27: Full settings endpoint (/settings) removed from permitAll — it exposed
                // all study metadata to anyone with a study UUID. Mobile clients use the specific
                // sensor endpoints below instead.
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/sensors").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/type/AndroidSensor").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/type/Sensor").permitAll()
                // DataCollection: the device reads its per-module collection settings as an
                // unauthenticated GET (mirrors AndroidSensor). The AndroidDataCollectionSetting
                // DTO carries no apiKey/secret/participantId/PHI by construction, so — like the
                // sensor types above — it is safe to expose with only a study UUID, consistent
                // with the T-27 decision to publish specific non-sensitive type endpoints only.
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/type/DataCollection").permitAll()
                // Encryption: retain this public, non-sensitive settings read for compatible clients,
                // but the V95 release gate requires enabled=false and rejects encrypted payloads until
                // an OWNER-gated decrypt/export path exists. The StudyEncryptionSetting DTO carries
                // only a public key plus the disabled flag — never a private key or PHI. Clients may
                // discover the disabled setting here; encrypted collection cannot silently engage.
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/settings/type/Encryption").permitAll()

                // Participant verification
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/study/*/participant/*/verify").permitAll()

                // iOS writes intentionally fall through to /chronicle/** authenticated().
                // The client persists the enrollment-issued device key in Keychain and
                // ApiKeyAuthenticationFilter binds it to study, participant, and device.

                // Participant access-code exchange is public but one-time and fail-closed in its service.
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/participant-access/exchange").permitAll()

                // Participant form routes are anonymous only after ParticipantFormAccessFilter
                // verifies a scoped, unexpired HttpOnly capability session. Researcher routes in
                // the same controllers continue to require normal authentication and ACL checks.
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/time-use-diary/*/participant/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/time-use-diary/*/participant/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/time-use-diary/*/settings").permitAll()

                // Survey endpoints (participant-facing, no auth)
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/participant/*/app-usage").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/app-usage").permitAll()
                // Study-scoped survey variant flag (DAILY/HOURLY) — non-sensitive config the
                // participant survey page reads to pick its form; no participant/PHI in the path.
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/app-usage-frequency").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/participant/*/device-usage").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/device-usage").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/survey/*/questionnaire/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/survey/*/participant/*/questionnaire/*").permitAll()

                // Auth cookie management — permitAll() alone is NOT sufficient because the
                // BearerTokenAuthenticationFilter runs first. ChronicleCookieOrBearerTokenResolver
                // must also return null for these paths. See auth-endpoint-routing.md.
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/auth/session").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/auth/oidc/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/chronicle/v3/auth/oidc/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/set-cookie").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/testing-login").permitAll()
                // The dashboard login itself must be reachable before anyone is
                // authenticated; the controller verifies the configured bcrypt password and
                // fails closed when none is set.
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/dashboard-login").permitAll()
                .requestMatchers(HttpMethod.POST, "/v3/auth/dashboard-login").permitAll()
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/auth/oidc/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/v3/auth/oidc/callback").permitAll()

                // Twilio webhook callback
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/notification/status").permitAll()

                // Refresh token endpoint (authenticated via refresh token, not JWT)
                .requestMatchers(HttpMethod.POST, "/chronicle/v3/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/v3/auth/refresh").permitAll()

                // JWKS endpoint (public key publication for RS256 mode)
                .requestMatchers(HttpMethod.GET, "/chronicle/.well-known/jwks.json").permitAll()
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()

                // Liveness and dependency-aware readiness are reached through
                // the existing /chronicle/* servlet mapping. No other internal
                // health endpoints are public.
                .requestMatchers(
                    HttpMethod.GET,
                    "/chronicle/internal/health/live",
                    "/chronicle/internal/health/ready",
                ).permitAll()

                // Metrics are authenticated by MetricsAuthenticationFilter and
                // additionally restricted by edge routing and network policy.
                .requestMatchers("/prometheus/**").permitAll()

                // Everything else requires authentication
                .requestMatchers("/chronicle/**").authenticated()
        }

        val filter = CharacterEncodingFilter()
        filter.encoding = StandardCharsets.UTF_8.toString()
        filter.setForceEncoding(true)
        http.addFilterBefore(filter, CsrfFilter::class.java)

        // Observability: MDC correlation context (requestId, traceId, studyId, participantId)
        // Runs early so all downstream filters and controllers benefit from MDC fields.
        http.addFilterAfter(ObservabilityFilter(), CharacterEncodingFilter::class.java)

        // Observability: API request latency and error metrics (Prometheus)
        http.addFilterAfter(ApiMetricsFilter(), ObservabilityFilter::class.java)

        // Defense-in-depth origin validation before authentication/controller work.
        corsValidationFilter?.let { filter ->
            http.addFilterBefore(filter, BearerTokenAuthenticationFilter::class.java)
        }
        http.addFilterBefore(
            MetricsAuthenticationFilter(
                metricsUsername,
                metricsPassword,
                MetricsAuthenticationFilter.parsePasswordFiles(metricsPasswordFiles),
            ),
            BearerTokenAuthenticationFilter::class.java,
        )

        // Mobile bootstrap endpoints may be permitAll(), so request signing must run
        // before API-key/JWT authentication and before controller deserialization.
        mobileApiSignatureFilter?.let { filter ->
            http.addFilterBefore(filter, BearerTokenAuthenticationFilter::class.java)
        }

        // API abuse protection for both public mobile bootstrap and authenticated API routes.
        rateLimitFilter?.let { filter ->
            http.addFilterBefore(filter, rateLimitFilterAnchor(mobileApiSignatureFilter))
        }

        // H-1/H-2: Register API key authentication filter before JWT auth
        // Includes honey token detection for canary API key alerting.
        apiKeyService?.let { service ->
            http.addFilterBefore(
                ApiKeyAuthenticationFilter(service, honeyTokenService),
                BearerTokenAuthenticationFilter::class.java
            )
        }

        http.addFilterBefore(
            ParticipantFormAccessFilter(participantFormAccessService),
            AnonymousAuthenticationFilter::class.java,
        )

        // JWT blocklist filter: reject revoked tokens after authentication
        if (jwtBlocklist != null) {
            http.addFilterAfter(JwtBlocklistFilter(jwtBlocklist!!), BearerTokenAuthenticationFilter::class.java)
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .info("JwtBlocklistFilter registered — token revocation is ENABLED")
        } else {
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .warn("JwtBlocklist bean not available — token revocation is DISABLED")
        }

        // C-6: Wire RLS context into the filter chain (runs after authentication)
        if (rlsContextManager != null) {
            http.addFilterAfter(
                RLSContextFilter(rlsContextManager!!),
                AnonymousAuthenticationFilter::class.java
            )
        }

        return http.build()
    }

    // reason: boundary catch — JWT decode throws several JwtException subtypes; the fallback must
    // catch any primary-decoder failure to try the testing decoder before surfacing the error
    @Suppress("TooGenericExceptionCaught")
    private fun createJwtDecoderWithTestingFallback(
        primaryAuthConfig: ChronicleJwtClientConfiguration,
        testingAuthConfig: ChronicleJwtClientConfiguration,
        keyMaterial: JwtKeyMaterial,
    ): JwtDecoder {
        val primaryDecoder = createJwtDecoder(primaryAuthConfig, keyMaterial)
        val testingDecoder = createJwtDecoder(testingAuthConfig, keyMaterial)
        return JwtDecoder { token ->
            try {
                primaryDecoder.decode(token)
            } catch (primaryError: Exception) {
                try {
                    testingDecoder.decode(token)
                } catch (testingError: Exception) {
                    LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                        .debug("JWT decode failed for both primary and testing-token validators. primary={}, testing={}",
                            primaryError::class.java.name, testingError::class.java.name)
                    throw testingError
                }
            }
        }
    }

    private fun extractChronicleRoles(claims: Map<String, Any>): List<String> {
        return ChronicleRoleClaims.extract(
            claims,
            chronicleAuthConfiguration.roleClaimNamespace,
            chronicleAuthConfiguration.roleClientIdForClaims(claims),
        )
    }

    private fun createJwtDecoder(
        authConfig: ChronicleJwtClientConfiguration,
        keyMaterial: JwtKeyMaterial,
    ): JwtDecoder {
        val configuredJwks = authConfig.jwkSetUri?.takeIf { it.isNotBlank() }
        val decoder: NimbusJwtDecoder = if (configuredJwks != null) {
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .info("Configuring JwtDecoder with OIDC JWKS ({})", configuredJwks)
            NimbusJwtDecoder.withJwkSetUri(configuredJwks).build()
        } else if (keyMaterial.isRs256()) {
            val rsaPublicKey = requireNotNull(keyMaterial.rsaPublicKey) {
                "RSA public key is required when algorithm is RS256"
            }
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .info("Configuring JwtDecoder with RS256 (kid={})", keyMaterial.keyId)
            NimbusJwtDecoder.withPublicKey(rsaPublicKey).build()
        } else {
            // HS256: use existing symmetric secret from auth configuration
            val secret = if (authConfig.base64EncodedSecret) {
                Base64.getUrlDecoder().decode(authConfig.secret)
            } else {
                authConfig.secret.toByteArray(StandardCharsets.UTF_8)
            }
            val key = SecretKeySpec(secret, "HmacSHA256")
            NimbusJwtDecoder.withSecretKey(key).build()
        }

        if (requireMfa) {
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .info(
                    "MFA enforcement ENABLED — tokens require explicit amr=mfa, " +
                        "pwd plus possession, or an approved acr",
                )
        }

        val validators = buildJwtValidatorChain(
            authConfig.issuer,
            authConfig.audience,
            requireMfa,
            parseApprovedMfaAcrValues(approvedMfaAcrValues),
        )
        decoder.setJwtValidator(validators)
        return decoder
    }
}

internal fun rateLimitFilterAnchor(mobileApiSignatureFilter: Filter?): Class<out Filter> =
    mobileApiSignatureFilter?.javaClass ?: BearerTokenAuthenticationFilter::class.java

/**
 * Builds the JWT validator chain the resource-server decoder applies to every bearer token.
 * Extracted from [ChronicleServerSecurityPod.createJwtDecoder] so the MFA enforcement matrix
 * (require-mfa × amr) is exercised against the exact chain production decodes with — no
 * prod/test drift. See [com.openlattice.chronicle.security.MfaEnforcementMatrixTest].
 *
 * With [requireMfa] true, a token lacking qualifying `amr` or an explicitly
 * approved `acr` value fails validation;
 * `NimbusJwtDecoder` surfaces that as a `JwtValidationException`, which the bearer-token filter
 * renders as HTTP 401. HIPAA §164.312(d) (person/entity authentication — MFA).
 */
internal fun buildJwtValidatorChain(
    issuer: String,
    audience: String,
    requireMfa: Boolean,
    approvedMfaAcrValues: Set<String> = emptySet(),
): OAuth2TokenValidator<Jwt> {
    val validatorList = mutableListOf<OAuth2TokenValidator<Jwt>>(
        JwtTimestampValidator(),
        JwtIssuerValidator(issuer),
        JwtClaimValidator<Any?>(JwtClaimNames.AUD) { aud ->
            when (aud) {
                is String -> audience == aud
                is Collection<*> -> aud.contains(audience)
                else -> false
            }
        },
    )
    if (requireMfa) {
        validatorList.add(MfaClaimValidator(approvedMfaAcrValues))
    }
    return DelegatingOAuth2TokenValidator(validatorList)
}

internal fun parseApprovedMfaAcrValues(configuredValues: String): Set<String> {
    return configuredValues
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}
