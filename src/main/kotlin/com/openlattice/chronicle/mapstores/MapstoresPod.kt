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
package com.openlattice.chronicle.mapstores

import com.geekbeast.postgres.PostgresTableManager
import com.geekbeast.postgres.PostgresPod
import org.jdbi.v3.core.Jdbi
import com.geekbeast.rhizome.mapstores.SelfRegisteringMapStore
import java.util.UUID
import com.geekbeast.rhizome.jobs.DistributableJob
import com.geekbeast.rhizome.jobs.PostgresJobsMapStore
import com.google.common.eventbus.EventBus
import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.openlattice.chronicle.authorization.AceKey
import com.openlattice.chronicle.authorization.AceValue
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.mapstores.SecurableObjectTypeMapstore
import com.openlattice.chronicle.authorization.mapstores.UserMapstore
import com.openlattice.chronicle.authorization.principals.PrincipalMapstore
import com.openlattice.chronicle.ids.mapstores.IdGenerationMapstore
import com.openlattice.chronicle.ids.mapstores.LongIdsMapstore
import com.openlattice.chronicle.mapstores.apps.FilteredAppsMapstore
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore
import com.openlattice.chronicle.mapstores.authorization.PrincipalTreesMapstore
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.mapstores.ids.Range
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.mapstores.storage.StudyLimitsMapstore
import com.openlattice.chronicle.mapstores.storage.StudyMapstore
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSDataSources
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import jakarta.inject.Inject

@Configuration
@Import(PostgresPod::class)
public open class MapstoresPod {
    // reason: DI-injected dependency kept to preserve Spring bean-initialization ordering
    // (PostgresTableManager must be constructed even though this pod does not read it directly)
    @Suppress("UnusedPrivateProperty")
    @Inject
    private lateinit var ptMgr: PostgresTableManager

    @Inject
    private lateinit var eventBus: EventBus

    @Inject
    private lateinit var storageResolver: StorageResolver

    // reason: DI-injected dependency kept to preserve Spring bean-initialization ordering
    @Suppress("UnusedPrivateProperty")
    @Inject
    private lateinit var jdbi: Jdbi

    @Bean
    public fun studyLimitsMapstore(): StudyLimitsMapstore {
        return StudyLimitsMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun jobsMapstore(): SelfRegisteringMapStore<UUID, DistributableJob<*>> {
        return PostgresJobsMapStore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun studyMapstore(): StudyMapstore {
        return StudyMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun permissionMapstore(): SelfRegisteringMapStore<AceKey, AceValue> {
        return PermissionMapstore(storageResolver.getPlatformStorage(), eventBus)
    }

    @Bean
    public fun filteredAppsMapstore(): SelfRegisteringMapStore<UUID, KotlinDelegatedStringSet> {
        return FilteredAppsMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun securableObjectTypeMapstore(): SelfRegisteringMapStore<AclKey, SecurableObjectType> {
        return SecurableObjectTypeMapstore(storageResolver.getPlatformStorage())
    }

    //    @Bean
    //    public SelfRegisteringMapStore<String, UUID> aclKeysMapstore() {
    //        return new AclKeysMapstore( storageResolver.getPlatformStorage() );
    //    }
    @Bean
    public fun principalsMapstore(): SelfRegisteringMapStore<AclKey, SecurablePrincipal> {
        return PrincipalMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun longIdsMapstore(): SelfRegisteringMapStore<String, Long> {
        return LongIdsMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun userMapstore(): SelfRegisteringMapStore<String, ChronicleUserProfile> {
        return UserMapstore(storageResolver.getPlatformStorage())
    }

    //
    //    @Bean
    //    public SelfRegisteringMapStore<UUID, Organization> organizationsMapstore() {
    //        return new OrganizationsMapstore( storageResolver.getPlatformStorage() );
    //    }
    @Bean
    public fun idGenerationMapstore(): SelfRegisteringMapStore<Long, Range> {
        return IdGenerationMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun principalTreesMapstore(): PrincipalTreesMapstore {
        return PrincipalTreesMapstore(storageResolver.getPlatformStorage())
    }

    @Bean
    public fun participantStatsMapstore(): ParticipantStatsMapstore {
        // Hazelcast persists this map on write-behind worker threads, after the request
        // ThreadLocal has been cleared. Give only this trusted internal store an explicit
        // system RLS context; normal request datasources remain study-scoped and fail closed.
        return ParticipantStatsMapstore(
            RLSDataSources.wrapWithSystemContext(storageResolver.getPlatformStorage())
        )
    }

    //    @Bean
    //    public SecurablePrincipalsMapLoader securablePrincipalsMapLoader() {
    //        return new SecurablePrincipalsMapLoader();
    //    }
    //
    //    @Bean
    //    public ResolvedPrincipalTreesMapLoader resolvedPrincipalTreesMapLoader() {
    //        return new ResolvedPrincipalTreesMapLoader();
    //    }
}
