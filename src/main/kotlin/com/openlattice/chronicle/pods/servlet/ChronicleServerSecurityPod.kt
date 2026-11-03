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

import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.ChronicleRoleClaims
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.configuration.RequestHardeningFilters
import com.openlattice.chronicle.configuration.boundedRestTemplate
import com.openlattice.chronicle.configuration.roleClientIdForClaims
import com.openlattice.chronicle.filters.ChronicleCookieOrBearerTokenResolver
import com.openlattice.chronicle.filters.NativeExportDownload
import com.openlattice.chronicle.pods.REQUIRE_MFA_PROPERTY
import com.openlattice.chronicle.security.MfaClaimValidator
import com.nimbusds.jose.JWSAlgorithm
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import jakarta.servlet.Filter
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.filter.CharacterEncodingFilter

@Configuration
@Import(SecurityFilterRegistrationsPod::class)
@EnableMethodSecurity(proxyTargetClass = true)
@EnableWebSecurity(debug = false)
public open class ChronicleServerSecurityPod(
    private val chronicleAuthConfiguration: ChronicleAuthConfiguration,
    private val jwtKeyMaterial: JwtKeyMaterial,
    private val requestHardeningFilters: RequestHardeningFilters,
    @Value("\${${REQUIRE_MFA_PROPERTY}:true}") private val requireMfa: Boolean,
    @Value("\${chronicle.security.approved-mfa-acr-values:}") private val approvedMfaAcrValues: String,
) {
    init {
        // The built-in password login mints tokens with no MFA claim. With MFA required and no
        // identity provider, the dashboard would sign in and then get 401 on every call.
        require(!requireMfa || chronicleAuthConfiguration.dashboardPasswordHash.isNullOrBlank() ||
            chronicleAuthConfiguration.oidc.enabled) {
            "$REQUIRE_MFA_PROPERTY (REQUIRE_MFA) is true, but the only dashboard login is the built-in " +
                "password, whose sessions carry no MFA claim. Set REQUIRE_MFA=false for the internal password login."
        }
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
    public fun securityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        chronicleJwtAuthenticationConverter: JwtAuthenticationConverter,
        chronicleTokenResolver: BearerTokenResolver,
        filterRegistrations: List<SecurityFilterRegistration>,
    ): SecurityFilterChain {
        val bearerEntryPoint = BearerTokenAuthenticationEntryPoint()
        val entryPoint = AuthenticationEntryPoint { request, response, exception ->
            if (!NativeExportDownload.redirectForExpiredForm(request, response)) {
                bearerEntryPoint.commence(request, response, exception)
            }
        }
        http
            .csrf(::disableCsrf)
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                    jwt.jwtAuthenticationConverter(chronicleJwtAuthenticationConverter)
                }
                oauth2.bearerTokenResolver(chronicleTokenResolver)
                oauth2.authenticationEntryPoint(entryPoint)
            }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { securityContext -> securityContext.requireExplicitSave(true) }

        // No Spring CORS headers: the dashboard is same-origin; origin validation lives in the CORS validation filter.
        http.cors { it.configurationSource(CorsConfigurationSource { null }) }

        configureChronicleAuthorization(http)

        addRequestFilters(http, requestHardeningFilters.ordered)
        filterRegistrations.forEach { it.register(http) }
        return http.build()
    }

    private fun addRequestFilters(http: HttpSecurity, hardeningFilters: List<Filter>) {
        val filter = CharacterEncodingFilter()
        filter.encoding = StandardCharsets.UTF_8.toString()
        filter.setForceEncoding(true)
        http.addFilterBefore(filter, CsrfFilter::class.java)

        hardeningFilters.forEach { hardening ->
            http.addFilterBefore(hardening, CsrfFilter::class.java)
        }
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
                .info("Configuring JwtDecoder with OIDC JWKS")
            NimbusJwtDecoder.withJwkSetUri(configuredJwks).restOperations(boundedRestTemplate()).build()
        } else if (keyMaterial.isRs256()) {
            val rsaPublicKey = requireNotNull(keyMaterial.rsaPublicKey) {
                "RSA public key is required when algorithm is RS256"
            }
            LoggerFactory.getLogger(ChronicleServerSecurityPod::class.java)
                .info("Configuring JwtDecoder with RS256 (kid={})", keyMaterial.keyId)
            NimbusJwtDecoder.withPublicKey(rsaPublicKey).build()
        } else {
            val algorithm = requireNotNull(MacAlgorithm.from(authConfig.signingAlgorithm)) {
                "Signing algorithm must be HS256, HS384 or HS512"
            }
            val keyAlgorithm = "HmacSHA${algorithm.name.removePrefix("HS")}"
            val key = SecretKeySpec(authConfig.secretBytes(), keyAlgorithm)
            val previous = authConfig.previousSecret.takeIf { it.isNotEmpty() }
                ?.let { SecretKeySpec(authConfig.secretBytes(it), keyAlgorithm) }
            val currentId = authConfig.keyId()
            val previousId = previous?.let { authConfig.keyId(authConfig.previousSecret) }
            NimbusJwtDecoder.withSecretKey(key).macAlgorithm(algorithm).jwtProcessorCustomizer { processor ->
                processor.setJWSKeySelector { header, _ ->
                    buildList {
                        if (header.algorithm == JWSAlgorithm.parse(algorithm.name)) {
                            if (header.keyID == null || header.keyID == currentId) add(key)
                            // Legacy tokens have no kid; try both keys only during the configured overlap.
                            val matchesPreviousKey = header.keyID == null || header.keyID == previousId
                            if (previous != null && Instant.now().epochSecond < authConfig.previousSecretExpiresAt &&
                                matchesPreviousKey) add(previous)
                        }
                    }
                }
            }.build()
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

internal fun rateLimitFilterAnchor(@Suppress("UNUSED_PARAMETER") mobileApiSignatureFilter: Filter?): Class<out Filter> =
    AnonymousAuthenticationFilter::class.java

/**
 * Builds the JWT validator chain the resource-server decoder applies to every bearer token.
 * Extracted from [ChronicleServerSecurityPod.createJwtDecoder] so the MFA enforcement matrix
 * (require-mfa × amr) is exercised against the exact chain production decodes with — no
 * prod/test drift. See [com.openlattice.chronicle.security.MfaEnforcementMatrixTest].
 *
 * With [requireMfa] true, a token lacking qualifying `amr` or an explicitly
 * approved `acr` value fails validation;
 * `NimbusJwtDecoder` surfaces that as a `JwtValidationException`, which the bearer-token filter
 * renders as HTTP 401.
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
        JwtClaimValidator<Any?>(JwtClaimNames.SUB) { it is String && it.isNotBlank() },
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
