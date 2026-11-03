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
package com.openlattice.chronicle.pods

import com.geekbeast.hazelcast.HazelcastClientProvider
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class ChronicleServerServicesPod {

    @Bean
    public fun idGenerationService(
        hazelcastClientProvider: HazelcastClientProvider,
    ): HazelcastIdGenerationService {
        return HazelcastIdGenerationService(hazelcastClientProvider, random = true)
    }

    @Autowired
    public fun init(
        principalsManager: SecurePrincipalsManager,
        hazelcast: HazelcastInstance,
        storageResolver: StorageResolver,
        creatorPrincipalResolver: CreatorPrincipalResolver,
    ) {
        Principals.init(
            principalsManager,
            hazelcast,
            creatorPrincipalResolver,
        )
        storageResolver.setStudyStorage(hazelcast)
    }
}
