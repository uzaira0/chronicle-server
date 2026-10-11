package com.openlattice.chronicle.authorization

import com.google.common.eventbus.EventBus
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.principals.PrincipalsMapManager
import com.openlattice.chronicle.services.auth.RefreshTokenService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.UUID

class AclSessionRevocationTest {
    private val user = Principal(PrincipalType.USER, "affected")
    private val key = AclKey(UUID.randomUUID())
    private val aces = mock<IMap<AceKey, AceValue>>()
    private val revoked = mock<JwtBlocklist>()
    private val refresh = mock<RefreshTokenService>()
    private val service = run {
        val hz = mock<HazelcastInstance>()
        whenever(hz.getMap<AceKey, AceValue>("PERMISSIONS")).thenReturn(aces)
        whenever(hz.getMap<AclKey, SecurableObjectType>("SECURABLE_OBJECT_TYPES")).thenReturn(mock<IMap<AclKey, SecurableObjectType>>())
        val principals = mock<PrincipalsMapManager>()
        whenever(principals.getAclKeyByPrincipal(setOf(user))).thenReturn(mapOf(user to AclKey(UUID.randomUUID())))
        HazelcastAuthorizationService(hz, mock<StorageResolver>(), EventBus(), principals, revoked, refresh)
    }

    private fun currently(vararg permissions: Permission) {
        whenever(aces[AceKey(key, user)]).thenReturn(
            AceValue(EnumSet.copyOf(permissions.toList()), SecurableObjectType.Study, OffsetDateTime.MAX))
    }

    private fun assertRevoked(expected: Boolean) {
        val times = if (expected) times(1) else never()
        verify(revoked, times).revokePrincipalBefore(eq("affected"), any())
        verify(refresh, times).revokeAllForUser("affected")
        verify(revoked, never()).revokePrincipalBefore(eq("unrelated"), any())
    }

    // Creating a study grants its creator OWNER; ending the session there would log the researcher out.
    @Test fun grantKeepsSessions() {
        service.addPermission(key, user, EnumSet.of(Permission.READ), SecurableObjectType.Study, OffsetDateTime.MAX)
        assertRevoked(false)
    }

    @Test fun removalRevokesAffectedSessions() {
        service.removePermission(key, user, EnumSet.of(Permission.READ))
        assertRevoked(true)
    }

    @Test fun replacementThatDropsAPermissionRevokes() {
        currently(Permission.OWNER, Permission.READ, Permission.WRITE)
        service.setPermission(key, user, EnumSet.of(Permission.OWNER, Permission.READ), OffsetDateTime.MAX)
        assertRevoked(true)
    }

    @Test fun replacementThatOnlyAddsKeepsSessions() {
        currently(Permission.OWNER)
        service.setPermission(key, user, EnumSet.of(Permission.OWNER, Permission.READ), OffsetDateTime.MAX)
        assertRevoked(false)
    }
}
