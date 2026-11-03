package com.openlattice.chronicle.pods.servlet

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.controllers.ReviewerEnrollmentController
import jakarta.servlet.FilterChain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

class SecurityRouteAuthorizationTest : ChronicleServerTests() {
    @Test fun everyPublishedRouteDeclaresItsActualAuthorization() {
        val routes = com.geekbeast.mappers.mappers.ObjectMappers.getJsonMapper()
            .readTree(java.io.File("src/test/resources/route-inventory.json"))
        routes.forEach { route ->
            val method = route["method"].asText()
            val path = route["path"].asText().replace(Regex("\\{[^}]+}"), "route-fixture")
            val decision = route["auth"]?.asText()
            org.junit.Assert.assertTrue("$method $path has no auth contract", decision in setOf("PUBLIC", "AUTHENTICATED", "DENY"))
            assertDecisions(method, path, anonymousAllowed = decision == "PUBLIC", authenticatedAllowed = decision != "DENY")
        }
    }
    @Test fun publicRoutesAllowAnonymousAndAuthenticatedRequests() {
        val routes = mapOf(
            "OPTIONS" to listOf("/datastore/private", "/chronicle/private"),
            "GET" to listOf(
                "/chronicle/v4/study/s/participant/p/enrollment-preview",
                "/chronicle/v3/study/s/settings/type/DataCollection",
                "/chronicle/v3/time-use-diary/s/participant/p", "/chronicle/v3/time-use-diary/s/settings",
                "/chronicle/v3/survey/s/participant/p/app-usage", "/chronicle/v3/survey/s/app-usage-frequency",
                "/chronicle/v3/survey/s/participant/p/app-usage/days",
                "/chronicle/v3/survey/s/participant/p/device-usage", "/chronicle/v3/survey/s/questionnaire/q",
                "/chronicle/v3/auth/session", "/chronicle/v3/auth/oidc/login", "/chronicle/v3/auth/oidc/callback",
                "/v3/auth/oidc/login", "/v3/auth/oidc/callback",
                "/chronicle/.well-known/jwks.json", "/.well-known/jwks.json",
                "/chronicle/internal/health/live", "/chronicle/internal/health/ready", "/prometheus/metrics",
            ),
            "POST" to listOf(
                "/chronicle/v4/study/s/participant/p/enroll", ReviewerEnrollmentController.PATH,
                "/chronicle/v3/participant-access/exchange",
                "/chronicle/v3/time-use-diary/s/participant/p", "/chronicle/v3/survey/s/participant/p/app-usage",
                "/chronicle/v3/survey/s/participant/p/device-usage", "/chronicle/v3/survey/s/participant/p/questionnaire/q",
                "/chronicle/v3/auth/set-cookie", "/chronicle/v3/auth/testing-login",
                "/chronicle/v3/auth/dashboard-login", "/v3/auth/dashboard-login", "/chronicle/v3/auth/logout",
                "/chronicle/v3/notification/status",
            ),
        )
        routes.forEach { (method, paths) -> paths.forEach { assertDecisions(method, it, anonymousAllowed = true) } }
    }

    @Test fun protectedRoutesAndRetiredAliasKeepTheirDecisions() {
        assertDecisions("GET", "/datastore/private", anonymousAllowed = false, authenticatedAllowed = false)
        assertDecisions("POST", "/datastore/private", anonymousAllowed = false, authenticatedAllowed = false)
        for (path in listOf("/chronicle/v3/studies", "/chronicle/v3/study/s/settings",
            "/chronicle/v3/study/s/settings/type/Private", "/chronicle/internal/health/private")) {
            assertDecisions("GET", path, anonymousAllowed = false)
        }
        assertDecisions("POST", "/chronicle/v3/study/s/settings/type/DataCollection", anonymousAllowed = false)
        assertDecisions("GET", "/chronicle/v3/participant-access/exchange", anonymousAllowed = false)
        assertDecisions("POST", "/chronicle/v3/admin/test-only/backdate-enrollment", anonymousAllowed = false)
    }

    @Test fun retiredMobilePrefixesDenyAuthenticatedAndAnonymousRequests() {
        for (prefix in listOf("/chronicle/v1", "/chronicle/v2", "/chronicle/study")) {
            for (suffix in listOf("", "/", "/upload")) {
                assertDecisions("POST", prefix + suffix, anonymousAllowed = false, authenticatedAllowed = false)
            }
        }
    }

    private fun assertDecisions(
        method: String,
        path: String,
        anonymousAllowed: Boolean,
        authenticatedAllowed: Boolean = true,
    ) {
        val filter = testServer.context.getBean(SecurityFilterChain::class.java).filters
            .filterIsInstance<AuthorizationFilter>().single()
        try {
            for (authenticated in listOf(false, true)) {
                SecurityContextHolder.getContext().authentication = if (authenticated) {
                    UsernamePasswordAuthenticationToken.authenticated("route-fixture", null, emptyList())
                } else AnonymousAuthenticationToken("route-fixture", "anonymous", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
                var allowed = false
                try {
                    filter.doFilter(MockHttpServletRequest(method, path), MockHttpServletResponse(), FilterChain { _, _ -> allowed = true })
                } catch (_: AccessDeniedException) {
                    allowed = false
                }
                assertEquals("$method $path authenticated=$authenticated", if (authenticated) authenticatedAllowed else anonymousAllowed, allowed)
            }
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
