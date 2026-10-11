package com.openlattice.chronicle.authorization.principals

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.AclKeySet
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.users.ChronicleUserProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class RootPrincipalTest {
    @Test
    fun missingAuthenticationUsesTheUnauthenticatedException() {
        val context = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val previous = context.authentication
        try {
            context.authentication = null
            for (lookup in listOf(Principals::getCurrentUser, Principals::getCurrentSecurablePrincipal, Principals::getCurrentPrincipals)) {
                org.junit.Assert.assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException::class.java) {
                    lookup()
                }
            }
        } finally { context.authentication = previous }
    }

    @Test
    fun unreservedUsersResolveThroughTheirUserPrincipal() {
        val hz = mock<HazelcastInstance>()
        val map = mock<IMap<AclKey, SecurablePrincipal>>()
        whenever(hz.getMap<AclKey, SecurablePrincipal>(HazelcastMap.PRINCIPALS.name)).thenReturn(map)
        val principal = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "local-user")
        val stored = SecurablePrincipal(java.util.Optional.of(UUID.randomUUID()), principal, "local-user", java.util.Optional.empty())
        whenever(map.values(any<com.hazelcast.query.Predicate<AclKey, SecurablePrincipal>>())).thenReturn(listOf(stored))
        val reservations = mock<com.openlattice.chronicle.authorization.reservations.AclKeyReservationService>()
        org.junit.Assert.assertEquals(stored, HazelcastPrincipalsMapManager(hz, reservations).getSecurablePrincipal(principal.id))
        verifyNoInteractions(reservations)
    }

    @Test
    fun emptyCachesReuseStoredAuditIdentityAndResolveCurrentMembership() {
        val manager = mock<SecurePrincipalsManager>()
        val resolver = mock<com.openlattice.chronicle.authorization.CreatorPrincipalResolver>()
        val user = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "stored-user")
        val stored = SecurablePrincipal(java.util.Optional.of(UUID.randomUUID()), user, user.id, java.util.Optional.empty())
        whenever(manager.getSecurablePrincipals(setOf(user))).thenReturn(listOf(stored))
        whenever(resolver.resolve(user.id)).thenReturn(setOf(user))
        val fields = mapOf(
            "principalManager" to manager,
            "principalResolver" to resolver,
            "principals" to mock<IMap<String, com.openlattice.chronicle.authorization.SortedPrincipalSet>>(),
            "securablePrincipals" to mock<IMap<String, SecurablePrincipal>>(),
        ).mapKeys { (name, _) -> Principals::class.java.getDeclaredField(name).apply { isAccessible = true } }
        val previous = fields.mapValues { (field, _) -> field.get(null) }
        val context = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val oldAuthentication = context.authentication
        try {
            fields.forEach { (field, value) -> field.set(null, value) }
            context.authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.id, "unused", emptyList())
            repeat(2) { org.junit.Assert.assertEquals(stored.id, com.openlattice.chronicle.audit.AuditRequestContext.getCurrentUserId()) }
            org.junit.Assert.assertEquals(setOf(user), Principals.getUserPrincipals(user.id))
            context.authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.id, "unused",
                listOf(org.springframework.security.core.GrantedAuthority { null },
                    Principals.fromPrincipal(com.openlattice.chronicle.authorization.SystemRole.adminRole)))
            org.junit.Assert.assertEquals(setOf(user, com.openlattice.chronicle.authorization.SystemRole.adminRole),
                Principals.getCurrentPrincipals())
            whenever(resolver.resolve(user.id)).thenReturn(setOf(user, com.openlattice.chronicle.authorization.SystemRole.adminRole))
            assertTrue(Principals.getCurrentPrincipals().contains(com.openlattice.chronicle.authorization.SystemRole.adminRole))
            whenever(manager.getSecurablePrincipals(setOf(user))).thenReturn(emptyList())
            org.junit.Assert.assertEquals(Principals.getUserId(user.id), Principals.getCurrentSecurablePrincipal().id)
            context.authentication = org.springframework.security.authentication.AnonymousAuthenticationToken("test", "anonymousUser",
                listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")))
            org.junit.Assert.assertNull(com.openlattice.chronicle.audit.AuditRequestContext.getCurrentUserId())
            org.junit.Assert.assertNull(com.openlattice.chronicle.audit.AuditRequestContext.getCurrentUserRole())
        } finally {
            fields.keys.forEach { field -> field.set(null, previous[field]) }
            context.authentication = oldAuthentication
        }
    }
    @Test fun rootPrincipalHasNoParents() {
        val hz = mock<HazelcastInstance>()
        whenever(hz.getMap<AclKey, SecurablePrincipal>(HazelcastMap.PRINCIPALS.name)).thenReturn(mock<IMap<AclKey, SecurablePrincipal>>())
        whenever(hz.getMap<AclKey, AclKeySet>(HazelcastMap.PRINCIPAL_TREES.name)).thenReturn(mock<IMap<AclKey, AclKeySet>>())
        whenever(hz.getMap<String, ChronicleUserProfile>(HazelcastMap.USERS.name)).thenReturn(mock<IMap<String, ChronicleUserProfile>>())
        val service = HazelcastPrincipalService(hz, mock(), mock(), mock(), mock())
        assertTrue(service.getParentPrincipalsOfPrincipal(AclKey(UUID.randomUUID())).isEmpty())
    }
}
