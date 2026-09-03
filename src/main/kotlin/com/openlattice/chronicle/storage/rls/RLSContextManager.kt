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
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.sql.Connection
import java.util.EnumSet
import java.util.UUID

/**
 * Manages Row-Level Security (RLS) context for database connections.
 *
 * This class is responsible for setting the PostgreSQL session variables that
 * the RLS policies use to determine which rows a user can access.
 *
 * Session Variables Set:
 * - app.current_user_id: The authenticated user's principal ID
 * - app.authorized_studies: Comma-separated list of study UUIDs the user can access
 * - app.is_admin: Boolean flag for admin bypass
 * - app.authorized_orgs: Comma-separated list of organization UUIDs the user can access
 *   (consumed by the V57 organization_members/organization_quotas policies)
 *
 * @author uzaira0
 */
public open class RLSContextManager(
    private val authorizationManager: AuthorizationManager,
    private val hikariDataSource: HikariDataSource? = null
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(RLSContextManager::class.java)

        // Session-scoped settings survive JDBC autocommit boundaries; the RLS-aware
        // connection wrapper clears them before returning the connection to Hikari.
        private const val SET_RLS_CONTEXT_SQL = """
            SELECT
                set_config('app.current_user_id', ?, false),
                set_config('app.authorized_studies', ?, false),
                set_config('app.is_admin', ?, false),
                set_config('app.authorized_orgs', ?, false)
        """

        private const val CLEAR_RLS_CONTEXT_SQL = """
            SELECT
                set_config('app.current_user_id', '', false),
                set_config('app.authorized_studies', '', false),
                set_config('app.is_admin', 'false', false),
                set_config('app.authorized_orgs', '', false)
        """
    }

    /**
     * Sets the RLS context for the current user on the given connection.
     *
     * This method looks up the user's authorized studies from the authorization
     * system and sets the appropriate session variables.
     *
     * @param connection The database connection to configure
     */
    public fun setCurrentUserContext(connection: Connection) {
        val context = getCurrentUserContext()
        setContext(connection, context)
    }

    public fun getCurrentUserContext(): RLSConnectionContext {
        val authentication = SecurityContextHolder.getContext().authentication
        mobileStudyContext(authentication)?.let { return it }

        val principal = Principals.getCurrentUser()
        val principals = Principals.getCurrentPrincipals()
        val isAdmin = principals.any { it == Principals.getAdminRole() }
        return RLSConnectionContext(
            principalId = principal.id,
            authorizedStudyIds = getAuthorizedIds(principals, SecurableObjectType.Study),
            isAdmin = isAdmin,
            authorizedOrganizationIds = getAuthorizedIds(principals, SecurableObjectType.Organization),
        )
    }

    /** Mobile credentials are deliberately study-scoped and never inherit organization/admin access. */
    private fun mobileStudyContext(authentication: Authentication?): RLSConnectionContext? {
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
        )
    }

    /**
     * Sets the RLS context for a specific principal on the given connection.
     *
     * @param connection The database connection to configure
     * @param principal The principal whose context to set
     * @param principals All principals (including roles) for the user
     * @param isAdmin Whether the user has admin privileges
     */
    public fun setContext(
        connection: Connection,
        principal: Principal,
        principals: Set<Principal>,
        isAdmin: Boolean
    ) {
        setContext(
            connection,
            RLSConnectionContext(
                principalId = principal.id,
                authorizedStudyIds = getAuthorizedIds(principals, SecurableObjectType.Study),
                isAdmin = isAdmin,
                authorizedOrganizationIds = getAuthorizedIds(principals, SecurableObjectType.Organization),
            )
        )
    }

    // reason: boundary catch — any failure setting the RLS session must surface as RLSContextException (fail-closed)
    @Suppress("TooGenericExceptionCaught")
    public fun setContext(connection: Connection, context: RLSConnectionContext) {
        try {
            connection.prepareStatement(SET_RLS_CONTEXT_SQL).use { stmt ->
                stmt.setString(1, context.principalId)
                stmt.setString(2, context.authorizedStudiesCsv)
                stmt.setString(3, context.isAdmin.toString())
                stmt.setString(4, context.authorizedOrgsCsv)
                stmt.execute()
            }

            logger.debug(
                "Set RLS context: user={}, studies={}, orgs={}, isAdmin={}",
                context.principalId,
                context.authorizedStudyIds.size,
                context.authorizedOrganizationIds.size,
                context.isAdmin
            )
        } catch (e: Exception) {
            logger.error("Failed to set RLS context for principal: ${context.principalId}", e)
            throw RLSContextException("Failed to set RLS context", e)
        }
    }

    /**
     * Sets an admin context that bypasses RLS policies.
     *
     * This should only be used for system operations like migrations,
     * maintenance tasks, and background jobs that need full database access.
     *
     * @param connection The database connection to configure
     * @param systemUserId An identifier for the system operation
     */
    // reason: boundary catch — any failure setting admin RLS context must surface as RLSContextException
    @Suppress("TooGenericExceptionCaught")
    public fun setAdminContext(connection: Connection, systemUserId: String = "system") {
        try {
            connection.prepareStatement(SET_RLS_CONTEXT_SQL).use { stmt ->
                stmt.setString(1, systemUserId)
                stmt.setString(2, "") // Empty studies list - admin bypass doesn't need it
                stmt.setString(3, "true")
                stmt.setString(4, "") // Empty orgs list - admin bypass doesn't need it
                stmt.execute()
            }

            logger.debug("Set admin RLS context for system operation: {}", systemUserId)
        } catch (e: Exception) {
            logger.error("Failed to set admin RLS context", e)
            throw RLSContextException("Failed to set admin RLS context", e)
        }
    }

    /**
     * Clears the RLS context from the connection.
     *
     * This should be called when returning connections to a pool to ensure
     * the next user doesn't inherit the previous user's context.
     *
     * @param connection The database connection to clear
     */
    // reason: boundary catch — clearing RLS context must evict the connection on any failure to prevent context leak
    @Suppress("TooGenericExceptionCaught")
    public fun clearContext(connection: Connection) {
        try {
            connection.prepareStatement(CLEAR_RLS_CONTEXT_SQL).use { stmt ->
                stmt.execute()
            }
            logger.debug("Cleared RLS context")
        } catch (e: Exception) {
            logger.error("Failed to clear RLS context — evicting connection from pool to prevent context leak", e)
            // Evict this connection from the pool so the next request
            // doesn't inherit the previous user's RLS session variables.
            try {
                hikariDataSource?.evictConnection(connection)
                    ?: logger.warn("No HikariDataSource available to evict connection; closing connection directly")
                if (hikariDataSource == null) {
                    connection.close()
                }
            } catch (evictEx: Exception) {
                logger.error("Failed to evict/close connection after RLS context clear failure", evictEx)
            }
            throw RLSContextException("Failed to clear RLS context — connection evicted", e)
        }
    }

    /**
     * Gets the IDs of securable objects of [type] the given principals can READ.
     * Used for both studies (app.authorized_studies) and organizations
     * (app.authorized_orgs — the V57 org RLS policies match against it).
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

    /**
     * Executes a block of code with the current user's RLS context applied.
     *
     * @param connection The database connection to use
     * @param block The code block to execute
     * @return The result of the block
     */
    // reason: boundary catch — must capture any block/cleanup failure to guarantee context is always cleared
    @Suppress("TooGenericExceptionCaught")
    public fun <T> withUserContext(connection: Connection, block: () -> T): T {
        setCurrentUserContext(connection)
        var blockResult: T? = null
        var blockException: Throwable? = null
        try {
            blockResult = block()
        } catch (e: Throwable) {
            blockException = e
        } finally {
            try {
                clearContext(connection)
            } catch (clearEx: Exception) {
                if (blockException != null) {
                    blockException.addSuppressed(clearEx)
                } else {
                    blockException = clearEx
                }
            }
        }
        if (blockException != null) throw blockException
        @Suppress("UNCHECKED_CAST")
        return blockResult as T
    }

    /**
     * Executes a block of code with admin RLS context (bypass).
     *
     * @param connection The database connection to use
     * @param systemUserId An identifier for the system operation
     * @param block The code block to execute
     * @return The result of the block
     */
    // reason: boundary catch — must capture any block/cleanup failure to guarantee admin context is always cleared
    @Suppress("TooGenericExceptionCaught")
    public fun <T> withAdminContext(
        connection: Connection,
        systemUserId: String = "system",
        block: () -> T
    ): T {
        setAdminContext(connection, systemUserId)
        var blockResult: T? = null
        var blockException: Throwable? = null
        try {
            blockResult = block()
        } catch (e: Throwable) {
            blockException = e
        } finally {
            try {
                clearContext(connection)
            } catch (clearEx: Exception) {
                if (blockException != null) {
                    blockException.addSuppressed(clearEx)
                } else {
                    blockException = clearEx
                }
            }
        }
        if (blockException != null) throw blockException
        @Suppress("UNCHECKED_CAST")
        return blockResult as T
    }
}

/**
 * Exception thrown when RLS context operations fail.
 */
public open class RLSContextException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
