package com.openlattice.chronicle.authorization.annotations

import com.openlattice.chronicle.organizations.OrganizationRole

/**
 * Annotation to require organization-level access on controller methods.
 * When applied, the OrganizationAuthorizationAspect checks that the current
 * user is a member of the target organization with the required minimum role.
 *
 * Role hierarchy: OWNER > ADMIN > RESEARCHER > VIEWER
 * A user with ADMIN access also passes checks requiring RESEARCHER or VIEWER.
 *
 * The organizationId is extracted from the method parameter named "organizationId"
 * (or the name specified in [organizationIdParam]).
 *
 * Usage:
 * ```kotlin
 * @RequiresOrganizationAccess(OrganizationRole.ADMIN)
 * fun addMember(@PathVariable organizationId: UUID, ...): OK { ... }
 *
 * @RequiresOrganizationAccess(OrganizationRole.VIEWER)
 * fun listMembers(@PathVariable organizationId: UUID): List<OrganizationMember> { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class RequiresOrganizationAccess(
    val minRole: OrganizationRole = OrganizationRole.VIEWER,
    val organizationIdParam: String = "organizationId"
)
