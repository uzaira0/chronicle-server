package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.MobileReviewerAuthenticationToken
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.EnumSet
import java.util.UUID

/**
 * Resolves the Row-Level Security (RLS) context of the current request's credential.
 *
 * [RLSConnectionSql] writes it into the PostgreSQL session variables the RLS policies read:
 * - app.current_user_id: The authenticated user's principal ID
 * - app.authorized_studies: Comma-separated list of study UUIDs the user can access
 * - app.authorized_organizations: Comma-separated list of organization UUIDs the user can access
 * - app.is_admin: Boolean flag for admin bypass
 * - app.is_deletion_worker: Trusted internal scope, never derived from an authenticated subject or claim
 * - app.receipt_owner_kind: Receipt-only credential domain derived once from the actual authentication type
 *
 * @author uzaira0
 */
public open class RLSContextManager(
    private val authorizationManager: AuthorizationManager,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(RLSContextManager::class.java)
    }

    public fun getCurrentUserContext(): RLSConnectionContext {
        val authentication = SecurityContextHolder.getContext().authentication
        val receiptKind = when (authentication) {
            is JwtAuthenticationToken -> ReceiptOwnerKind.USER.takeIf { authentication.isAuthenticated }
            is ApiKeyAuthenticationToken -> ReceiptOwnerKind.API_KEY.takeIf {
                authentication.isAuthenticated && authentication.restriction == null &&
                    authentication.participantId == null && authentication.randomDeviceId == null
            }
            else -> null
        }
        mobileStudyContext(authentication, receiptKind)?.let { return it }

        val principal = Principals.getCurrentUser()
        val principals = Principals.getCurrentPrincipals()
        val isAdmin = principals.any { it == Principals.getAdminRole() }
        return RLSConnectionContext(
            principalId = principal.id,
            authorizedStudyIds = getAuthorizedIds(principals, SecurableObjectType.Study),
            isAdmin = isAdmin,
            authorizedOrganizationIds = getAuthorizedIds(principals, SecurableObjectType.Organization),
            receiptOwnerKind = receiptKind,
        )
    }

    /** Mobile credentials are deliberately study-scoped and never inherit organization/admin access. */
    private fun mobileStudyContext(authentication: Authentication?, receiptKind: ReceiptOwnerKind?): RLSConnectionContext? {
        val scopedAuthentication = authentication ?: return null
        val studyId = when (scopedAuthentication) {
            is ApiKeyAuthenticationToken -> scopedAuthentication.studyId
            is MobileApiHmacAuthenticationToken -> scopedAuthentication.studyId
            is MobileEnrollmentAuthenticationToken -> scopedAuthentication.studyId
            is MobileReviewerAuthenticationToken -> scopedAuthentication.studyId
            else -> null
        } ?: return null
        return RLSConnectionContext(
            principalId = scopedAuthentication.principal.toString(),
            authorizedStudyIds = setOf(studyId),
            isAdmin = false,
            receiptOwnerKind = receiptKind,
        )
    }

    /**
     * Gets the IDs of securable objects of [type] the given principals can READ
     * for the corresponding study or organization session setting.
     */
    // reason: boundary catch — any authorization-lookup failure must fail closed via RLSContextException
    @Suppress("TooGenericExceptionCaught")
    private fun getAuthorizedIds(principals: Set<Principal>, type: SecurableObjectType): Set<UUID> {
        return try {
            authorizationManager.getAuthorizedObjectsOfType(
                principals,
                type,
                EnumSet.of(Permission.READ)
            ).map { aclKey -> aclKey.first() }
                .toList()
                .toSet()
        } catch (e: Exception) {
            logger.error("Failed to get authorized {} ids for principals; failing closed", type, e)
            throw RLSContextException("Failed to resolve authorized ${type.name.lowercase()} ids", e)
        }
    }

}

/**
 * Exception thrown when RLS context operations fail.
 */
public open class RLSContextException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
