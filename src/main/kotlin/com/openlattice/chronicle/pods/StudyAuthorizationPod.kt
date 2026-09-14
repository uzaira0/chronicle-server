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
package com.openlattice.chronicle.pods

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.aspects.OrganizationAuthorizationAspect
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationAspect
import com.openlattice.chronicle.services.organizations.OrganizationMemberService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import jakarta.inject.Inject

/**
 * Configuration pod for study-level authorization services and AOP aspects.
 * Enables AspectJ auto-proxying and configures the study authorization infrastructure.
 *
 * @author uzaira0
 */
@Configuration
@EnableAspectJAutoProxy
public open class StudyAuthorizationPod {

    @Inject
    private lateinit var authorizationManager: AuthorizationManager

    @Inject
    private lateinit var auditingManager: AuditingManager

    /**
     * Creates the StudyAuthorizationService that provides role-based access control
     * for study operations.
     */
    @Bean
    public fun studyAuthorizationService(): StudyAuthorizationService {
        return StudyAuthorizationService(authorizationManager, auditingManager)
    }

    /**
     * Creates the AOP aspect that intercepts methods annotated with @RequiresStudyAccess
     * and enforces authorization checks.
     */
    @Bean
    public fun studyAuthorizationAspect(): StudyAuthorizationAspect {
        return StudyAuthorizationAspect(studyAuthorizationService())
    }

    /**
     * Creates the AOP aspect that enforces @RequiresOrganizationAccess.
     *
     * The aspect's own @Aspect/@Component stereotype never took effect: the MVC pod scans only the
     * controller packages, so nothing registered the bean and every organization-scoped endpoint
     * ran unauthorized. It must be declared here alongside the study aspect.
     */
    @Bean
    public fun organizationAuthorizationAspect(
        organizationMemberService: OrganizationMemberService,
    ): OrganizationAuthorizationAspect {
        return OrganizationAuthorizationAspect(organizationMemberService)
    }
}
