package com.openlattice.chronicle.controllers

import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.SortedPrincipalSet
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.principals.Principals
import org.mockito.Mockito
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.Optional
import java.util.TreeSet
import java.util.UUID

object TestSecurityUtils {
    @JvmStatic
    @JvmOverloads
    fun setupSecurityContext(subject: String = "test-user", admin: Boolean = true) {
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
        initPrincipalsIfNeeded(subject, admin)
    }

    // The companion's lateinit vars compile to static fields on the OUTER Principals class
    // (see `javap -p Principals.class`), not the Companion inner class. Inject mock IMaps so
    // unit tests don't need a real Hazelcast cluster.
    @JvmStatic
    @JvmOverloads
    fun initPrincipalsIfNeeded(subject: String = "test-user", admin: Boolean = true) {
        @Suppress("UNCHECKED_CAST")
        val principalsMap = injectStaticMock(Principals::class.java, "principals") {
            Mockito.mock(IMap::class.java)
        } as IMap<String, SortedPrincipalSet>
        @Suppress("UNCHECKED_CAST")
        val securableMap = injectStaticMock(Principals::class.java, "securablePrincipals") {
            Mockito.mock(IMap::class.java)
        } as IMap<String, SecurablePrincipal>

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
    }

    private fun injectStaticMock(targetClass: Class<*>, fieldName: String, factory: () -> Any): Any {
        val field = targetClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val existing = field.get(null)
        if (existing != null && Mockito.mockingDetails(existing).isMock) {
            Mockito.reset(existing)
            return existing
        }
        val created = factory()
        field.set(null, created)
        return created
    }

    @JvmStatic
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }
}
