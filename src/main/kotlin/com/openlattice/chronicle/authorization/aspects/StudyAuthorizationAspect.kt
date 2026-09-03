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
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.*

/**
 * Aspect that intercepts methods annotated with @RequiresStudyAccess and
 * enforces study-level authorization before allowing the method to proceed.
 *
 * This aspect automatically:
 * 1. Extracts the study ID from method parameters
 * 2. Checks if the current user has the required permission
 * 3. Throws ForbiddenException if access is denied
 * 4. Logs authorization failures to the audit log
 *
 * The study ID is extracted from:
 * 1. A parameter annotated with @StudyId
 * 2. A parameter named "studyId" (default)
 * 3. The parameter name specified in @RequiresStudyAccess.studyIdParam
 *
 * @author uzaira0
 */
@Aspect
@Component
@Order(1) // Run before other aspects
public open class StudyAuthorizationAspect(
    private val studyAuthorizationService: StudyAuthorizationService
) {

    public companion object {
        private val logger = LoggerFactory.getLogger(StudyAuthorizationAspect::class.java)
    }

    /**
     * Intercepts methods annotated with @RequiresStudyAccess and checks authorization.
     *
     * @param joinPoint The join point representing the intercepted method.
     * @param requiresAccess The @RequiresStudyAccess annotation on the method.
     * @return The result of the method if authorized.
     * @throws ForbiddenException if authorization fails.
     */
    @Around("@annotation(requiresAccess)")
    public fun checkStudyAuthorization(
        joinPoint: ProceedingJoinPoint,
        requiresAccess: RequiresStudyAccess
    ): Any? {
        val studyId = extractStudyId(joinPoint, requiresAccess)

        if (studyId == null) {
            logger.error(
                "Could not extract study ID from method {} - check @RequiresStudyAccess configuration",
                joinPoint.signature.toShortString()
            )
            throw IllegalStateException(
                "Study ID not found in method parameters. " +
                        "Ensure the method has a parameter named '${requiresAccess.studyIdParam}' " +
                        "or a parameter annotated with @StudyId."
            )
        }

        logger.debug(
            "Checking authorization for study {} with permission {} on method {}",
            studyId,
            requiresAccess.permission,
            joinPoint.signature.toShortString()
        )

        // Check authorization - this will throw ForbiddenException if denied
        studyAuthorizationService.requirePermission(studyId, requiresAccess.permission)

        // If we get here, authorization passed - proceed with the method
        return joinPoint.proceed()
    }

    /**
     * Extracts the study ID from the method parameters.
     *
     * @param joinPoint The join point representing the intercepted method.
     * @param requiresAccess The @RequiresStudyAccess annotation.
     * @return The study ID, or null if not found.
     */
    private fun extractStudyId(
        joinPoint: ProceedingJoinPoint,
        requiresAccess: RequiresStudyAccess
    ): UUID? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val parameters = method.parameters
        val args = joinPoint.args

        // First, try to find a parameter annotated with @StudyId
        for (i in parameters.indices) {
            val parameter = parameters[i]
            if (parameter.isAnnotationPresent(StudyId::class.java)) {
                return args[i] as? UUID
            }
        }

        // Next, try to find a parameter with the name specified in the annotation
        val paramNames = signature.parameterNames
        val targetParamName = requiresAccess.studyIdParam

        for (i in paramNames.indices) {
            if (paramNames[i] == targetParamName) {
                return args[i] as? UUID
            }
        }

        // If nothing found, return null
        return null
    }
}
