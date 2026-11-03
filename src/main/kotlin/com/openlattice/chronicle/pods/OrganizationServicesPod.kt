package com.openlattice.chronicle.pods

import com.google.common.eventbus.EventBus
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.HazelcastAuthorizationService
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationDependencies
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationTask
import com.openlattice.chronicle.authorization.principals.HazelcastPrincipalService
import com.openlattice.chronicle.authorization.principals.HazelcastPrincipalsMapManager
import com.openlattice.chronicle.authorization.principals.PrincipalsMapManager
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.authorization.reservations.AclKeyReservationService
import com.openlattice.chronicle.configuration.ChronicleConfiguration
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.initializers.OrganizationsInitializationDependencies
import com.openlattice.chronicle.organizations.initializers.OrganizationsInitializationTask
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class OrganizationServicesPod(
    private val storageResolver: StorageResolver,
    private val hazelcast: HazelcastInstance,
) {

    @Bean
    public fun principalsMapManager(): PrincipalsMapManager {
        return HazelcastPrincipalsMapManager(hazelcast)
    }

    @Bean
    public fun aclKeyReservationService(): AclKeyReservationService {
        return AclKeyReservationService(storageResolver)
    }

    @Bean
    public fun authorizationService(
        eventBus: EventBus,
        jwtBlocklist: JwtBlocklist,
        principalsMapManager: PrincipalsMapManager,
    ): AuthorizationManager {
        return HazelcastAuthorizationService(hazelcast, storageResolver, eventBus, principalsMapManager, jwtBlocklist)
    }

    @Bean
    public fun principalsManager(
        principalsMapManager: PrincipalsMapManager,
        aclKeyReservationService: AclKeyReservationService,
        authorizationService: AuthorizationManager,
        auditingManager: AuditingManager,
    ): SecurePrincipalsManager {
        return HazelcastPrincipalService(
            hazelcast,
            aclKeyReservationService,
            authorizationService,
            principalsMapManager,
            auditingManager
        )
    }

    @Bean
    public fun organizationsService(
        idGenerationService: HazelcastIdGenerationService,
        authorizationService: AuthorizationManager,
        auditingManager: AuditingManager,
    ): ChronicleOrganizationService {
        return ChronicleOrganizationService(
            storageResolver,
            authorizationService,
            idGenerationService,
            auditingManager
        )
    }

    @Bean
    public fun authorizationInitializationTask(): AuthorizationInitializationTask {
        return AuthorizationInitializationTask()
    }

    @Bean
    public fun authorizationInitializationTaskDependencies(
        principalsManager: SecurePrincipalsManager,
    ): AuthorizationInitializationDependencies {
        return AuthorizationInitializationDependencies(principalsManager)
    }

    @Bean
    public fun organizationInitTask(): OrganizationsInitializationTask {
        return OrganizationsInitializationTask()
    }

    @Bean
    public fun orgInitTaskDependencies(
        chronicleConfiguration: ChronicleConfiguration,
        principalsManager: SecurePrincipalsManager,
        organizationsService: ChronicleOrganizationService,
    ): OrganizationsInitializationDependencies {
        return OrganizationsInitializationDependencies(
            storageResolver,
            organizationsService,
            principalsManager,
            chronicleConfiguration
        )
    }
}
