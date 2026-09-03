package com.openlattice.chronicle.filters

import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSContextManager
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/**
 * Unit tests for [RLSContextFilter] exercising shouldNotFilter path matching,
 * the authenticated / unauthenticated branches, the fail-closed (403) error path,
 * and the always-clear-context finally block. Mockito mocks the RLSContextManager
 * so no Postgres / Hazelcast is required (runs in PIT's minion).
 */
class RLSContextFilterMutationTest {

    private val rlsContextManager: RLSContextManager = mock()
    private val filter = RLSContextFilter(rlsContextManager)

    private val sampleContext = RLSConnectionContext(
        principalId = "user-1",
        authorizedStudyIds = setOf(UUID.randomUUID()),
        isAdmin = false
    )

    @After
    fun cleanup() {
        SecurityContextHolder.clearContext()
        RLSRequestContext.clear()
    }

    private fun authenticate() {
        // The 3-arg constructor marks the token authenticated=true (trusted).
        val auth = UsernamePasswordAuthenticationToken(
            "user-1", "creds", listOf(SimpleGrantedAuthority("USER|user-1"))
        )
        SecurityContextHolder.getContext().authentication = auth
    }

    // ---- shouldNotFilter (driven through doFilter; the method is protected) ----

    @Test
    fun `skip paths bypass doFilterInternal even when authenticated`() {
        // shouldNotFilter==true -> doFilterInternal is never invoked, so RLS is never consulted,
        // and the chain still proceeds.
        whenever(rlsContextManager.getCurrentUserContext()).doReturn(sampleContext)
        authenticate()
        for (path in listOf(
            "/prometheus",
            "/health",
            "/internal",
            "/health/live",
            "/prometheus/metrics",
            "/chronicle/internal/health/live",
        )) {
            val request = MockHttpServletRequest("GET", path)
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            assertNotNull("skip path $path should pass through chain", chain.request)
            assertEquals("skip path $path should not 403", 200, response.status)
        }
        verify(rlsContextManager, never()).getCurrentUserContext()
    }

    @Test
    fun `non-skip API path is filtered (RLS consulted) when authenticated`() {
        whenever(rlsContextManager.getCurrentUserContext()).doReturn(sampleContext)
        authenticate()
        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        verify(rlsContextManager, times(1)).getCurrentUserContext()
    }

    @Test
    fun `path that only contains a skip token mid-string is still filtered`() {
        // "/api/health" does not START with any skip prefix, so doFilterInternal runs.
        whenever(rlsContextManager.getCurrentUserContext()).doReturn(sampleContext)
        authenticate()
        val request = MockHttpServletRequest("GET", "/api/health")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        verify(rlsContextManager, times(1)).getCurrentUserContext()
    }

    // ---- doFilterInternal: authenticated happy path ----

    @Test
    fun `authenticated request sets context, proceeds, and clears context`() {
        whenever(rlsContextManager.getCurrentUserContext()).doReturn(sampleContext)
        authenticate()

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        // RLS context was looked up exactly once
        verify(rlsContextManager, times(1)).getCurrentUserContext()
        // chain proceeded
        assertNotNull("chain should proceed for authenticated request", chain.request)
        assertEquals(200, response.status)
        // finally-block always clears the thread-local
        assertNull("RLS request context must be cleared after the request", RLSRequestContext.current())
    }

    // ---- doFilterInternal: unauthenticated branch ----

    @Test
    fun `no authentication skips RLS lookup but still proceeds`() {
        SecurityContextHolder.clearContext()

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(rlsContextManager, never()).getCurrentUserContext()
        assertNotNull("unauthenticated request should still proceed", chain.request)
        assertEquals(200, response.status)
        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `anonymous authentication is treated as unauthenticated`() {
        val anon = AnonymousAuthenticationToken(
            "key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        )
        SecurityContextHolder.getContext().authentication = anon

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(rlsContextManager, never()).getCurrentUserContext()
        assertNotNull(chain.request)
        assertEquals(200, response.status)
    }

    @Test
    fun `not-authenticated token is treated as unauthenticated`() {
        val auth = UsernamePasswordAuthenticationToken("user-1", "creds")
        auth.isAuthenticated = false
        SecurityContextHolder.getContext().authentication = auth

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(rlsContextManager, never()).getCurrentUserContext()
        assertEquals(200, response.status)
    }

    // ---- doFilterInternal: fail-closed error path ----

    @Test
    fun `RLS context build failure fails closed with 403 and does not proceed`() {
        whenever(rlsContextManager.getCurrentUserContext()).doThrow(RuntimeException("boom"))
        authenticate()

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
        assertNull("chain must NOT proceed when RLS context build fails", chain.request)
        assertNull("context cleared even on the failure path", RLSRequestContext.current())
    }

    @Test
    fun `context is cleared even when downstream chain throws`() {
        whenever(rlsContextManager.getCurrentUserContext()).doReturn(sampleContext)
        authenticate()

        val request = MockHttpServletRequest("GET", "/chronicle/api/web/studies")
        val response = MockHttpServletResponse()
        val throwingChain: FilterChain = mock()
        whenever(throwingChain.doFilter(any(), any())).doThrow(RuntimeException("downstream"))

        var thrown = false
        try {
            filter.doFilter(request, response, throwingChain)
        } catch (expected: RuntimeException) {
            thrown = true
        }

        assertEquals("downstream exception must propagate", true, thrown)
        assertNull("finally must clear context even when chain throws", RLSRequestContext.current())
    }
}
