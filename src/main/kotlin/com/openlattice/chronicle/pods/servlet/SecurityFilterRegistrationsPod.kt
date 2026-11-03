package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.ObservabilityFilter
import com.openlattice.chronicle.configuration.PreAuthenticationRateLimitFilter
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ConcurrentEditFilter
import com.openlattice.chronicle.filters.CreateReceiptFilter
import com.openlattice.chronicle.filters.JwtBlocklistFilter
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.filters.RLSContextFilter
import com.openlattice.chronicle.observability.ApiMetricsFilter
import com.openlattice.chronicle.security.MetricsAuthenticationFilter
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSContextManager
import jakarta.servlet.Filter
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.web.filter.CharacterEncodingFilter

// nosemgrep: chronicle-hardcoded-password-kotlin -- Configuration property name; Spring injects the credential through @Value.
internal const val METRICS_PASSWORD_PROPERTY = "chronicle.security.metrics-password"

@Configuration
public open class SecurityFilterRegistrationsPod(
    private val storageResolver: StorageResolver,
    private val studyAuthorizationService: StudyAuthorizationService,
    private val mobileSecurityConfiguration: MobileSecurityConfiguration,
) {
    @Bean
    public fun metricsAuthenticationFilter(
        @Value("\${chronicle.security.metrics-username:chronicle-metrics}") username: String,
        @Value("\${${METRICS_PASSWORD_PROPERTY}:}") password: String,
        @Value("\${chronicle.security.metrics-password-files:}") passwordFiles: String,
    ): MetricsAuthenticationFilter = MetricsAuthenticationFilter(
        username, password, MetricsAuthenticationFilter.parsePasswordFiles(passwordFiles),
    )

    @Bean
    @Order(1)
    public fun requestFilterRegistration(
        metricsAuthenticationFilter: MetricsAuthenticationFilter,
        preAuthenticationRateLimitFilter: PreAuthenticationRateLimitFilter? = null,
        @Qualifier("mobileApiSignatureFilter") mobileApiSignatureFilter: Filter? = null,
        @Qualifier("corsValidationFilter") corsValidationFilter: Filter? = null,
        @Qualifier("rateLimitFilter") rateLimitFilter: Filter? = null,
    ): SecurityFilterRegistration = SecurityFilterRegistration { http ->
        // Observability: MDC correlation context (requestId, traceId, studyId, participantId)
        // Runs early so all downstream filters and controllers benefit from MDC fields.
        http.addFilterAfter(ObservabilityFilter(), CharacterEncodingFilter::class.java)

        // Observability: API request latency and error metrics (Prometheus)
        http.addFilterAfter(ApiMetricsFilter(), ObservabilityFilter::class.java)

        // Defense-in-depth origin validation before authentication/controller work.
        corsValidationFilter?.let { filter ->
            http.addFilterBefore(filter, BearerTokenAuthenticationFilter::class.java)
        }
        http.addFilterBefore(metricsAuthenticationFilter, BearerTokenAuthenticationFilter::class.java)
        preAuthenticationRateLimitFilter?.let { filter ->
            http.addFilterBefore(filter, MetricsAuthenticationFilter::class.java)
        }

        // Mobile bootstrap endpoints may be permitAll(), so request signing must run
        // before API-key/JWT authentication and before controller deserialization.
        mobileApiSignatureFilter?.let { filter ->
            http.addFilterBefore(filter, BearerTokenAuthenticationFilter::class.java)
        }

        // Validated API-key/JWT identities must be available before bucketing phones behind SNAT.
        rateLimitFilter?.let { filter ->
            http.addFilterBefore(filter, rateLimitFilterAnchor(mobileApiSignatureFilter))
        }
    }

    @Bean
    @Order(2)
    public fun apiKeyFilterRegistration(
        creatorPrincipalResolver: CreatorPrincipalResolver,
        apiKeyService: ApiKeyService? = null,
        authorizationManager: AuthorizationManager? = null,
    ): SecurityFilterRegistration = SecurityFilterRegistration { http ->
        // Register API key authentication filter before JWT auth
        // Without the authorization manager a key's creator cannot be checked, so keys are not admitted at all.
        if (apiKeyService != null && authorizationManager != null) {
            http.addFilterBefore(
                ApiKeyAuthenticationFilter(apiKeyService, authorizationManager, creatorPrincipalResolver::resolve),
                BearerTokenAuthenticationFilter::class.java
            )
        }
    }

    @Bean
    @Order(3)
    public fun sessionFilterRegistration(
        participantFormAccessService: ParticipantFormAccessService,
        chronicleAuthConfiguration: ChronicleAuthConfiguration,
        jwtBlocklist: JwtBlocklist? = null,
        rlsContextManager: RLSContextManager? = null,
    ): SecurityFilterRegistration = SecurityFilterRegistration { http ->
        http.addFilterBefore(
            ParticipantFormAccessFilter(participantFormAccessService),
            AnonymousAuthenticationFilter::class.java,
        )

        // JWT blocklist filter: reject revoked tokens after authentication
        if (jwtBlocklist != null) {
            http.addFilterAfter(
                JwtBlocklistFilter(jwtBlocklist, Duration.ofMinutes(chronicleAuthConfiguration.sessionIdleMinutes)),
                BearerTokenAuthenticationFilter::class.java,
            )
            logger.info("JwtBlocklistFilter registered — token revocation is ENABLED")
        } else {
            logger.warn("JwtBlocklist bean not available — token revocation is DISABLED")
        }

        // Wire RLS context into the filter chain (runs after authentication)
        if (rlsContextManager != null) {
            http.addFilterAfter(
                RLSContextFilter(rlsContextManager),
                AnonymousAuthenticationFilter::class.java
            )
        }

        http.addFilterAfter(ConcurrentEditFilter(storageResolver, studyAuthorizationService),
            AuthorizationFilter::class.java)
        http.addFilterAfter(CreateReceiptFilter(storageResolver, studyAuthorizationService) { mobileSecurityConfiguration.internalWebSecret },
            ConcurrentEditFilter::class.java)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SecurityFilterRegistrationsPod::class.java)
    }
}

public fun interface SecurityFilterRegistration {
    public fun register(http: HttpSecurity)
}
