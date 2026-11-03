package com.openlattice.chronicle.pods

import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.configuration.CorsValidationFilterConfig
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.configuration.MobileApiSecurityConfig
import com.openlattice.chronicle.configuration.ParameterPollutionConfig
import com.openlattice.chronicle.configuration.RateLimitConfig
import com.openlattice.chronicle.configuration.SecurityHardeningConfig
import com.openlattice.chronicle.services.security.EncryptionHealthService
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(
    JacksonSecurityConfig::class,
    SecurityHardeningConfig::class,
    ParameterPollutionConfig::class,
    CorsValidationFilterConfig::class,
    MobileApiSecurityConfig::class,
    RateLimitConfig::class,
)
public open class SecurityServicesPod(
    private val storageResolver: StorageResolver,
) {

    @Bean
    public fun auditingManager(): AuditingManager {
        return PostgresAuditingManager(storageResolver)
    }

    @Bean
    public fun auditService(auditingManager: AuditingManager): AuditService = AuditService(auditingManager)

    @Bean
    public fun encryptionHealthService(): EncryptionHealthService {
        return EncryptionHealthService(storageResolver)
    }
}
