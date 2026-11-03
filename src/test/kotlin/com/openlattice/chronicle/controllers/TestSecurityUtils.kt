package com.openlattice.chronicle.controllers

import com.hazelcast.map.IMap
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import java.util.EnumSet
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.SortedPrincipalSet
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import org.mockito.Mockito
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.Optional
import java.util.TreeSet
import java.util.UUID

object TestSecurityUtils {
    fun <T> withServletRequest(request: HttpServletRequest, response: HttpServletResponse? = null, action: () -> T): T {
        val previous = RequestContextHolder.getRequestAttributes()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request, response))
        return try { action() } finally { RequestContextHolder.setRequestAttributes(previous) }
    }

    fun <T> withParticipantFormScope(scope: ParticipantFormAccessScope, action: () -> T): T {
        val request = MockHttpServletRequest().apply {
            setAttribute(ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE, scope)
            setAttribute(ParticipantFormAccessFilter.IDEMPOTENCY_ATTRIBUTE, UUID.randomUUID())
        }
        return withServletRequest(request, action = action)
    }

    fun setupAuthorizedResearcher(authorization: AuthorizationManager) {
        setupSecurityContext()
        Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), eq(EnumSet.of(Permission.READ)))).thenReturn(true)
    }

    @JvmStatic
    @JvmOverloads
    fun setupSecurityContext(subject: String = "test-user", admin: Boolean = true) {
        initPrincipalsIfNeeded(subject, admin)
        authenticate(subject)
    }

    @JvmStatic
    @JvmOverloads
    fun authenticate(subject: String = "test-user") {
        val jwt = Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .subject(subject)
            .expiresAt(Instant.parse("2026-12-31T23:59:59Z"))
            .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build()
        val authToken = JwtAuthenticationToken(jwt)
        authToken.isAuthenticated = true
        val securityContext = SecurityContextHolder.createEmptyContext()
        securityContext.authentication = authToken
        SecurityContextHolder.setContext(securityContext)
    }

    @JvmStatic
    @JvmOverloads
    fun initPrincipalsIfNeeded(subject: String = "test-user", admin: Boolean = true) {
        val principalsMap = mock<IMap<String, SortedPrincipalSet>>()
        val securableMap = mock<IMap<String, SecurablePrincipal>>()
        val hazelcast = mock<HazelcastInstance>()
        Mockito.`when`(hazelcast.getMap<String, SortedPrincipalSet>(HazelcastMap.RESOLVED_PRINCIPAL_TREES.name))
            .thenReturn(principalsMap)
        Mockito.`when`(hazelcast.getMap<String, SecurablePrincipal>(HazelcastMap.SECURABLE_PRINCIPALS.name))
            .thenReturn(securableMap)

        val userPrincipal = Principal(PrincipalType.USER, subject)
        val principalSet = TreeSet<Principal>().apply {
            add(userPrincipal)
            if (admin) add(SystemRole.adminRole)
        }
        Mockito.`when`(principalsMap[subject]).thenReturn(SortedPrincipalSet(principalSet))

        val securableUser = SecurablePrincipal(
            Optional.of(UUID.randomUUID()),
            userPrincipal,
            subject,
            Optional.of("test user")
        )
        Mockito.`when`(securableMap[subject]).thenReturn(securableUser)

        // Principals outside the fixture maps resolve through persisted membership; here: the user alone.
        val resolver = mock<CreatorPrincipalResolver>()
        Mockito.`when`(resolver.resolve(Mockito.anyString())).thenAnswer {
            setOf(Principal(PrincipalType.USER, it.getArgument(0)))
        }
        Principals.init(mock<SecurePrincipalsManager>(), hazelcast, resolver)
    }

    @JvmStatic
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }
}
