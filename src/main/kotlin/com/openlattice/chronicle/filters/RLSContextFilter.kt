package com.openlattice.chronicle.filters

import com.openlattice.chronicle.storage.rls.RLSContextManager
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Spring Security filter that sets RLS context on the database connection
 * after JWT authentication has completed.
 *
 * This filter runs AFTER the OAuth2 resource server filter so that
 * SecurityContextHolder contains the authenticated principal. It sets
 * the PostgreSQL session variables (app.current_user_id, app.authorized_studies,
 * app.is_admin) that RLS policies reference.
 *
 * C-6: Wires the existing RLSContextManager into the request lifecycle.
 */
public open class RLSContextFilter(
    private val rlsContextManager: RLSContextManager
) : OncePerRequestFilter() {

    internal companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(RLSContextFilter::class.java)

        // Paths that don't need RLS context (unauthenticated endpoints)
        private val SKIP_PATHS = setOf(
            "/prometheus",
            "/health",
            "/internal",
            "/chronicle/internal/health/live",
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return SKIP_PATHS.any { path.startsWith(it) }
    }

    // reason: boundary catch — RLS context build must fail closed (403) on any failure type; must not leak any failure past this security filter
    @Suppress("TooGenericExceptionCaught")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authentication = SecurityContextHolder.getContext().authentication

        val authenticated = authentication != null &&
                authentication.isAuthenticated &&
                authentication !is AnonymousAuthenticationToken

        if (authenticated) {
            try {
                RLSRequestContext.set(rlsContextManager.getCurrentUserContext())
            } catch (e: Exception) {
                log.warn("Failed to build RLS context for request: {}", e.message, e)
                response.sendError(HttpServletResponse.SC_FORBIDDEN)
                return
            }
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            RLSRequestContext.clear()
        }
    }
}
