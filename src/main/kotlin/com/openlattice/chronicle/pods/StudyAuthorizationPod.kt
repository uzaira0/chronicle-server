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
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationInterceptor
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.aop.Advisor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Configuration pod for study-level authorization services and the @RequiresStudyAccess advisor.
 * [AdvisorAutoProxyRegistrar] makes the context's auto-proxy creator apply every Advisor bean.
 *
 * @author uzaira0
 */
@Configuration
@Import(AdvisorAutoProxyRegistrar::class)
public open class StudyAuthorizationPod {

    @Bean
    public fun creatorPrincipalResolver(
        storageResolver: StorageResolver,
        configuration: ChronicleAuthConfiguration,
    ): CreatorPrincipalResolver = CreatorPrincipalResolver(storageResolver, configuration)

    /**
     * Creates the StudyAuthorizationService that provides role-based access control
     * for study operations.
     */
    @Bean
    public fun studyAuthorizationService(
        authorizationManager: AuthorizationManager,
        auditingManager: AuditingManager,
    ): StudyAuthorizationService {
        return StudyAuthorizationService(authorizationManager, auditingManager)
    }

    /**
     * The advisor that intercepts methods annotated with @RequiresStudyAccess and enforces
     * authorization checks. The MVC child context's creator finds it here (ancestors included).
     */
    @Bean
    public fun studyAuthorizationAdvisor(studyAuthorizationService: ObjectProvider<StudyAuthorizationService>): Advisor =
        StudyAuthorizationInterceptor.advisor(studyAuthorizationService::getObject)
}
