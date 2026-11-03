/*
 * Copyright (C) 2019. OpenLattice, Inc.
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
 *
 */

package com.openlattice.chronicle.organizations.initializers

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.rls.RLSRequestContext

import com.geekbeast.tasks.HazelcastInitializationTask
import com.geekbeast.tasks.Task
import com.google.common.base.Stopwatch
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationTask
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationTask.Companion.GLOBAL_ADMIN_ROLE
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.util.ensureVanilla
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger(OrganizationsInitializationTask::class.java)

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class OrganizationsInitializationTask : HazelcastInitializationTask<OrganizationsInitializationDependencies> {
    override fun initialize(dependencies: OrganizationsInitializationDependencies): Unit = runCatching {
        RLSRequestContext.withSystemContext {
            logger.info("Running bootstrap process for organizations.")
            val sw = Stopwatch.createStarted()
            val organizationService = dependencies.organizationService
            val globalOrg = organizationService.maybeGetOrganization(IdConstants.SYSTEM_ORGANIZATION.id)

            if (globalOrg.isPresent) {
                val org = globalOrg.get()

                logger.info(
                    "Expected id = {}, Actual id = {}",
                    IdConstants.SYSTEM_ORGANIZATION.id,
                    org.id
                )
                require(IdConstants.SYSTEM_ORGANIZATION.id == org.id) {
                    "Mismatch in expected global org id and read global org id"
                }
            } else {
                val org = createGlobalOrg()
                val storage = dependencies.storageResolver.getDefaultPlatformStorage()
                val flavor = storage.flavor
                val hds = storage.dataSource
                ensureVanilla(flavor)
                hds.connection.use { connection ->
                    connection.withTransaction {
                        //Note: Consider auditing this
                        organizationService.createOrganization(connection, GLOBAL_ADMIN_ROLE.principal, org)
                        connection.commit()
                    }
                }
            }

            logger.info("Bootstrapping for organizations took {} ms", sw.elapsed(TimeUnit.MILLISECONDS))
        }
    }.onFailure { failure ->
        ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.ORGANIZATION_BOOTSTRAP)
        logger.error("Unable to bootstrap organizations.", failure)
    }.getOrThrow()

    override fun getInitialDelay(): Long {
        return 0
    }

    override fun getTimeUnit(): TimeUnit {
        return TimeUnit.MILLISECONDS
    }

    override fun after(): Set<Class<out HazelcastInitializationTask<*>>> {
        return setOf(AuthorizationInitializationTask::class.java)
    }

    override fun getName(): String {
        return Task.ORGANIZATION_INITIALIZATION.name
    }

    override fun getDependenciesClass(): Class<out OrganizationsInitializationDependencies> {
        return OrganizationsInitializationDependencies::class.java
    }

    internal companion object {
        private fun createGlobalOrg(): Organization {
            val id = IdConstants.SYSTEM_ORGANIZATION.id
            val title = "Chronicle System Organization"
            return Organization(
                id,
                title,
                "Built in organization for managing system roles and permissions."
            )
        }
    }
}
