package com.openlattice.chronicle.authorization.principals

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.AclKeySet
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.users.ChronicleUserProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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

    /** A token's identity is its non-blank subject, never the token object's own text. */
    @Test
    fun aTokenWithoutASubjectHasNoIdentity() {
        val context = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val previous = context.authentication
        try {
            for (subject in listOf(null, " ")) {
                context.authentication = jwtAuthentication(subject)
                org.junit.Assert.assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException::class.java) {
                    Principals.getCurrentUser()
                }
            }
            context.authentication = jwtAuthentication("researcher")
            org.junit.Assert.assertEquals("researcher", Principals.getCurrentUser().id)
        } finally { context.authentication = previous }
    }

    private fun jwtAuthentication(subject: String?) = org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token").header("alg", "HS256").claim("scope", "openid")
            .apply { subject?.let(this::subject) }.build()
    )

    @Test
    fun unreservedUsersResolveThroughTheirUserPrincipal() {
        val hz = mock<HazelcastInstance>()
        val map = mock<IMap<AclKey, SecurablePrincipal>>()
        whenever(hz.getMap<AclKey, SecurablePrincipal>(HazelcastMap.PRINCIPALS.name)).thenReturn(map)
        val principal = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "local-user")
        val stored = SecurablePrincipal(java.util.Optional.of(UUID.randomUUID()), principal, "local-user", java.util.Optional.empty())
        whenever(map.values(any<com.hazelcast.query.Predicate<AclKey, SecurablePrincipal>>())).thenReturn(listOf(stored))
        org.junit.Assert.assertEquals(stored, HazelcastPrincipalsMapManager(hz).getSecurablePrincipal(principal.id))
    }

    @Test
    fun emptyCachesReuseStoredAuditIdentityAndResolveCurrentMembership() {
        val manager = mock<SecurePrincipalsManager>()
        val resolver = mock<com.openlattice.chronicle.authorization.CreatorPrincipalResolver>()
        val user = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "stored-user")
        val stored = SecurablePrincipal(java.util.Optional.of(UUID.randomUUID()), user, user.id, java.util.Optional.empty())
        whenever(manager.getSecurablePrincipals(setOf(user))).thenReturn(listOf(stored))
        whenever(resolver.resolve(user.id)).thenReturn(setOf(user))
        val hazelcast = mock<HazelcastInstance>()
        whenever(hazelcast.getMap<String, com.openlattice.chronicle.authorization.SortedPrincipalSet>(
            HazelcastMap.RESOLVED_PRINCIPAL_TREES.name)).thenReturn(mock())
        whenever(hazelcast.getMap<String, SecurablePrincipal>(HazelcastMap.SECURABLE_PRINCIPALS.name)).thenReturn(mock())
        val context = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val oldAuthentication = context.authentication
        try {
            Principals.init(manager, hazelcast, resolver)
            context.authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.id, "unused", emptyList())
            repeat(2) { org.junit.Assert.assertEquals(stored.id, Principals.getCurrentSecurablePrincipal().id) }
            org.junit.Assert.assertEquals(setOf(user), Principals.getUserPrincipals(user.id))
            // H-admin-fallback: a user with no stored role and only unknown or malformed authorities holds no role at all.
            context.authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.id, "unused",
                listOf(org.springframework.security.core.GrantedAuthority { null },
                    org.springframework.security.core.authority.SimpleGrantedAuthority("admin"),
                    org.springframework.security.core.authority.SimpleGrantedAuthority("NOT_A_TYPE|admin")))
            org.junit.Assert.assertEquals(setOf(user), Principals.getCurrentPrincipals())
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
            org.junit.Assert.assertFalse(com.openlattice.chronicle.audit.AuditRequestContext.isAuthenticated())
        } finally {
            TestSecurityUtils.initPrincipalsIfNeeded()
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

    /** A user's stored membership walks user -> role -> a parentless root role and ends there. */
    @Test fun storedMembershipTraversalEndsAtAParentlessRoot() {
        com.openlattice.chronicle.contract.ChronicleContractTestSchema.prodPostgresContainer("root_principal").use { pg ->
            pg.start()
            com.openlattice.chronicle.contract.ChronicleContractTestSchema.waitForQueryReady(pg)
            com.openlattice.chronicle.contract.ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(pg)
            val user = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.USER, "member")
            val role = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.ROLE, "team")
            val root = com.openlattice.chronicle.authorization.Principal(com.openlattice.chronicle.authorization.PrincipalType.ROLE, "root")
            val keys = listOf(user, role, root).associateWith { UUID.randomUUID() }
            pg.createConnection("").use { connection ->
                insertRows(connection, "INSERT INTO principals (acl_key, principal_type, principal_id) VALUES (ARRAY[?::uuid], ?, ?)",
                    keys.map { (principal, key) -> listOf(key.toString(), principal.type.name, principal.id) })
                insertRows(connection,
                    "INSERT INTO principal_trees (acl_key, principal_of_acl_key) VALUES (ARRAY[?::uuid], ARRAY[?::uuid])",
                    listOf(role to user, root to role).map { (parent, child) ->
                        listOf(keys.getValue(parent).toString(), keys.getValue(child).toString())
                    })
            }
            val pool = mock<com.zaxxer.hikari.HikariDataSource>()
            whenever(pool.connection).thenAnswer { pg.createConnection("") }
            val storage = mock<com.openlattice.chronicle.storage.StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            val resolver = com.openlattice.chronicle.authorization.CreatorPrincipalResolver(storage,
                com.openlattice.chronicle.configuration.ChronicleAuthConfiguration())
            org.junit.Assert.assertEquals(setOf(user, role, root), resolver.resolve(user.id))
        }
    }

    private fun insertRows(connection: java.sql.Connection, sql: String, rows: List<List<String>>) =
        connection.prepareStatement(sql).use { statement ->
            for (row in rows) {
                row.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeUpdate()
            }
        }
}
