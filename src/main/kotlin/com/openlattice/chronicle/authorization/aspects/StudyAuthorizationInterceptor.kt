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
package com.openlattice.chronicle.authorization.aspects

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.annotations.StudyId
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.slf4j.LoggerFactory
import org.springframework.aop.Advisor
import org.springframework.aop.support.AopUtils
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.core.DefaultParameterNameDiscoverer
import java.lang.reflect.Method
import java.util.UUID

/**
 * Intercepts methods annotated with @RequiresStudyAccess and enforces study-level authorization
 * before the method runs. It is plain Spring AOP (spring-aop, Apache-2.0): the AspectJ aspect it
 * replaces needed aspectjweaver, whose EPL-2.0 license carries no GPL secondary-license designation
 * and so may not ship in this GPL-3.0 server.
 *
 * The study ID is taken from:
 * 1. A parameter annotated with @StudyId
 * 2. The parameter named by @RequiresStudyAccess.studyIdParam ("studyId" by default)
 *
 * A method with neither fails closed; a denied check throws before the method runs.
 *
 * @author uzaira0
 */
public class StudyAuthorizationInterceptor(
    private val studyAuthorizationService: () -> StudyAuthorizationService,
) : MethodInterceptor {

    public companion object {
        private val logger = LoggerFactory.getLogger(StudyAuthorizationInterceptor::class.java)
        private val parameterNames = DefaultParameterNameDiscoverer()

        /** Runs before other advice, as the aspect's @Order(1) did. */
        public const val ORDER: Int = 1

        /**
         * The advisor that applies the interceptor to every method annotated with @RequiresStudyAccess.
         * The service is resolved on first use, so creating the advisor (which every auto-proxy creator
         * does early) does not instantiate the authorization beans before they can be proxied.
         */
        public fun advisor(studyAuthorizationService: () -> StudyAuthorizationService): Advisor =
            DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(RequiresStudyAccess::class.java),
                StudyAuthorizationInterceptor(studyAuthorizationService),
            ).apply { order = ORDER }
    }

    override fun invoke(invocation: MethodInvocation): Any? {
        val target = invocation.getThis()
        val method = if (target == null) invocation.method else AopUtils.getMostSpecificMethod(invocation.method, target.javaClass)
        val requiresAccess = checkNotNull(method.getAnnotation(RequiresStudyAccess::class.java)) {
            "@RequiresStudyAccess advice reached ${method.name} without the annotation"
        }
        val studyId = extractStudyId(method, invocation.arguments, requiresAccess)

        if (studyId == null) {
            logger.error(
                "Could not extract study ID from method {} - check @RequiresStudyAccess configuration",
                method.name
            )
            error("Study ID not found in method parameters. " +
                    "Ensure the method has a parameter named '${requiresAccess.studyIdParam}' " +
                    "or a parameter annotated with @StudyId.")
        }

        logger.debug(
            "Checking authorization for study {} with permission {} on method {}",
            studyId,
            requiresAccess.permission,
            method.name
        )

        // Check authorization - this will throw ForbiddenException if denied
        studyAuthorizationService().requirePermission(studyId, requiresAccess.permission)

        // If we get here, authorization passed - proceed with the method
        return invocation.proceed()
    }

    private fun extractStudyId(method: Method, args: Array<Any?>, requiresAccess: RequiresStudyAccess): UUID? {
        // First, try to find a parameter annotated with @StudyId
        val parameters = method.parameters
        for (i in parameters.indices) {
            if (parameters[i].isAnnotationPresent(StudyId::class.java)) {
                return args[i] as? UUID
            }
        }

        // Next, try to find a parameter with the name specified in the annotation
        val names = parameterNames.getParameterNames(method) ?: return null
        val index = names.indexOf(requiresAccess.studyIdParam)
        return if (index >= 0) args[index] as? UUID else null
    }
}
