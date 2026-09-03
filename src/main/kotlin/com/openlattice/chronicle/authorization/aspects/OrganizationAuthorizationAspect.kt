package com.openlattice.chronicle.authorization.aspects

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.authorization.annotations.RequiresOrganizationAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.organizations.OrganizationRole
import com.openlattice.chronicle.services.organizations.OrganizationMemberService
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.*

/**
 * Aspect that intercepts methods annotated with @RequiresOrganizationAccess and
 * enforces organization-level authorization before allowing the method to proceed.
 *
 * Role hierarchy (ordinal comparison): OWNER(0) > ADMIN(1) > RESEARCHER(2) > VIEWER(3)
 * A user with ADMIN role passes checks requiring RESEARCHER or VIEWER.
 */
@Aspect
@Component
@Order(1)
public open class OrganizationAuthorizationAspect(
    private val memberService: OrganizationMemberService
) {

    public companion object {
        private val logger = LoggerFactory.getLogger(OrganizationAuthorizationAspect::class.java)

        /** Role hierarchy — lower ordinal = more privileged. */
        private fun hasMinimumRole(userRole: OrganizationRole, requiredMinRole: OrganizationRole): Boolean {
            return userRole.ordinal <= requiredMinRole.ordinal
        }
    }

    @Around("@annotation(requiresAccess)")
    public fun checkOrganizationAuthorization(
        joinPoint: ProceedingJoinPoint,
        requiresAccess: RequiresOrganizationAccess
    ): Any? {
        val organizationId = extractOrganizationId(joinPoint, requiresAccess)

        if (organizationId == null) {
            logger.error(
                "Could not extract organization ID from method {} — check @RequiresOrganizationAccess configuration",
                joinPoint.signature.toShortString()
            )
            throw IllegalStateException(
                "Organization ID not found in method parameters. " +
                        "Ensure the method has a parameter named '${requiresAccess.organizationIdParam}'."
            )
        }

        val userId = Principals.getCurrentUser().id
        val userRole = memberService.getMemberRole(organizationId, userId)

        if (userRole == null || !hasMinimumRole(userRole, requiresAccess.minRole)) {
            logger.warn(
                "Authorization denied for user {} on organization {} — required: {}, has: {}",
                userId, organizationId, requiresAccess.minRole, userRole ?: "NONE"
            )
            throw ForbiddenException(
                "Insufficient permissions on organization $organizationId. Required: ${requiresAccess.minRole}"
            )
        }

        return joinPoint.proceed()
    }

    private fun extractOrganizationId(
        joinPoint: ProceedingJoinPoint,
        requiresAccess: RequiresOrganizationAccess
    ): UUID? {
        val signature = joinPoint.signature as MethodSignature
        val paramNames = signature.parameterNames
        val args = joinPoint.args
        val targetParam = requiresAccess.organizationIdParam

        for (i in paramNames.indices) {
            if (paramNames[i] == targetParam) {
                return args[i] as? UUID
            }
        }
        return null
    }
}
