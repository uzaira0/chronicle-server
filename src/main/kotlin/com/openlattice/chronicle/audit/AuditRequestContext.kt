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

package com.openlattice.chronicle.audit

import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID
import jakarta.servlet.http.HttpServletRequest

/**
 * Utility class for extracting audit-relevant information from the current request context.
 * This is used by controllers to populate audit log entries with request details.
 */
// reason: cohesive namespace of request-context accessors; splitting would scatter related helpers
@Suppress("TooManyFunctions")
public object AuditRequestContext {

    private val logger = LoggerFactory.getLogger(AuditRequestContext::class.java)

    /**
     * Gets the current HTTP request, if available.
     */
    public fun getCurrentRequest(): HttpServletRequest? {
        val requestAttributes = RequestContextHolder.getRequestAttributes()
        return (requestAttributes as? ServletRequestAttributes)?.request
    }

    /**
     * Gets the client IP address from the current request.
     * Handles proxied requests only when the direct peer is trusted.
     */
    public fun getClientIpAddress(): String {
        val request = getCurrentRequest() ?: return "0.0.0.0"
        return ClientIpResolver.resolve(request)
    }

    /**
     * Gets the User-Agent header from the current request.
     */
    public fun getUserAgent(): String? {
        return getCurrentRequest()?.getHeader("User-Agent")
    }

    /**
     * Gets the request path from the current request.
     */
    public fun getRequestPath(): String? {
        return getCurrentRequest()?.requestURI?.let { LogSanitizer.sanitizeRequestPath(it) }
    }

    /**
     * Gets the HTTP method from the current request.
     */
    public fun getRequestMethod(): String? {
        return getCurrentRequest()?.method
    }

    /**
     * Gets the current authenticated user's ID, if available.
     */
    // reason: boundary catch — absence of an authenticated principal must yield a null id
    @Suppress("TooGenericExceptionCaught")
    public fun getCurrentUserId(): UUID? {
        return try {
            Principals.getCurrentSecurablePrincipal().id
        } catch (e: Exception) {
            logger.debug("No current securable principal available for user id", e)
            null
        }
    }

    /**
     * Gets the current authenticated user's role/type, if available.
     */
    // reason: boundary catch — absence of an authenticated principal must yield a null role
    @Suppress("TooGenericExceptionCaught")
    public fun getCurrentUserRole(): String? {
        return try {
            Principals.getCurrentSecurablePrincipal().principal.type.name
        } catch (e: Exception) {
            logger.debug("No current securable principal available for user role", e)
            null
        }
    }

    /**
     * Gets the current authenticated user's principal ID (auth0 id), if available.
     */
    // reason: boundary catch — absence of an authenticated user must yield a null principal id
    @Suppress("TooGenericExceptionCaught")
    public fun getCurrentUserPrincipalId(): String? {
        return try {
            Principals.getCurrentUser().id
        } catch (e: Exception) {
            logger.debug("No current user available for principal id", e)
            null
        }
    }

    /**
     * Checks if the current user is authenticated.
     */
    // reason: boundary catch — any failure resolving the principal means not authenticated
    @Suppress("TooGenericExceptionCaught")
    public fun isAuthenticated(): Boolean {
        return try {
            Principals.getCurrentSecurablePrincipal()
            true
        } catch (e: Exception) {
            logger.debug("No current securable principal available; treating as unauthenticated", e)
            false
        }
    }

    /**
     * Creates a pre-populated AuditLogEntryBuilder with common request context fields.
     */
    public fun createAuditBuilder(): AuditLogEntryBuilder {
        return AuditLogEntryBuilder()
            .ipAddress(getClientIpAddress())
            .userAgent(getUserAgent())
            .requestPath(getRequestPath())
            .requestMethod(getRequestMethod())
            .userId(getCurrentUserId())
            .userRole(getCurrentUserRole())
    }

    /**
     * Extension function to easily add request context to an existing builder.
     */
    internal fun AuditLogEntryBuilder.withRequestContext(): AuditLogEntryBuilder {
        return this
            .ipAddress(getClientIpAddress())
            .userAgent(getUserAgent())
            .requestPath(getRequestPath())
            .requestMethod(getRequestMethod())
            .userId(getCurrentUserId())
            .userRole(getCurrentUserRole())
    }
}

/**
 * Extension function for easy audit logging from controllers.
 * Creates an audit entry pre-populated with request context.
 */
internal fun AuditService.logWithContext(block: AuditLogEntryBuilder.() -> Unit) {
    val builder = AuditRequestContext.createAuditBuilder()
    builder.apply(block)
    this.log(builder.build())
}
