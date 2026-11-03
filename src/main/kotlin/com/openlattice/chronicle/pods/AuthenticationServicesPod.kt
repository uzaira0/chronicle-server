package com.openlattice.chronicle.pods

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.JwtKeyConfig
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.directory.ConfiguredUserDirectoryService
import com.openlattice.chronicle.directory.UserDirectoryService
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.users.ConfiguredUserListingService
import com.openlattice.chronicle.users.UserListingService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

internal const val REQUIRE_MFA_PROPERTY = "chronicle.security.require-mfa"
private const val ACCESS_TOKEN_EXPIRY_PROPERTY = "\${chronicle.security.jwt.access-token-expiry-minutes:15}"

@Configuration
@Import(JwtKeyConfig::class)
public open class AuthenticationServicesPod(
    private val storageResolver: StorageResolver,
    private val hazelcast: HazelcastInstance,
    private val jwtKeyMaterial: JwtKeyMaterial,
) {

    @Bean
    public fun jwtBlocklist(): JwtBlocklist {
        return JwtBlocklist(hazelcast, storageResolver)
    }

    @Bean
    public fun userListingService(
        chronicleAuthConfiguration: ChronicleAuthConfiguration,
        @Value(ACCESS_TOKEN_EXPIRY_PROPERTY) accessTokenExpiryMinutes: Long,
    ): UserListingService {
        return ConfiguredUserListingService(
            chronicleAuthConfiguration,
            jwtKeyMaterial,
            accessTokenExpiryMinutes,
        )
    }

    @Bean
    public fun userDirectoryService(
        chronicleAuthConfiguration: ChronicleAuthConfiguration,
    ): UserDirectoryService {
        return ConfiguredUserDirectoryService(chronicleAuthConfiguration)
    }

    @Bean
    public fun apiKeyService(
        idGenerationService: HazelcastIdGenerationService,
        auditingManager: AuditingManager,
    ): ApiKeyService {
        return ApiKeyService(
            storageResolver,
            idGenerationService,
            auditingManager
        )
    }
}
