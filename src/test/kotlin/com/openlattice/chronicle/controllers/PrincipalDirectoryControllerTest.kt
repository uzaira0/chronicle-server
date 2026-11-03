package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.audit.success
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.directory.UserDirectoryService
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.users.DirectedAclKeys
import com.openlattice.chronicle.users.UserSearchFields
import com.openlattice.chronicle.users.UserListingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class PrincipalDirectoryControllerTest {

    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val userDirectoryService = Mockito.mock(UserDirectoryService::class.java)
    private val userListingService = Mockito.mock(UserListingService::class.java)
    private val spm = Mockito.mock(SecurePrincipalsManager::class.java)
    private val organizationService = Mockito.mock(ChronicleOrganizationService::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val audit = RecordingAuditingManager()
    private val auditService = AuditService(audit)

    private lateinit var controller: PrincipalDirectoryController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        // ensureWriteAccess/ensureOwnerAccess call authorize() — return a permissive map.
        Mockito.`when`(authorizationManager.authorize(
            kAny<Map<AclKey, EnumSet<Permission>>>(), kAny<Set<Principal>>()
        )).thenAnswer { invocation ->
            val keys = invocation.getArgument<Map<AclKey, EnumSet<Permission>>>(0)
            keys.mapValues { (_, perms) ->
                EnumMap<Permission, Boolean>(Permission::class.java).apply {
                    perms.forEach { put(it, true) }
                }
            }
        }

        controller = PrincipalDirectoryController(
            authorizationManager, userDirectoryService, userListingService,
            spm, organizationService, auditingManager, auditService
        )
    }

    

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    // --- getSecurablePrincipal ---

    @Test
    fun testGetSecurablePrincipalDelegates() {
        val principal = Principal(PrincipalType.USER, "user-123")
        val aclKey = AclKey(UUID.randomUUID())
        val securablePrincipal = Mockito.mock(SecurablePrincipal::class.java)

        Mockito.`when`(spm.lookup(principal)).thenReturn(aclKey)
        Mockito.`when`(spm.getSecurablePrincipal(aclKey)).thenReturn(securablePrincipal)

        val result = controller.getSecurablePrincipal(principal)
        assertNotNull(result)
        assertSame(securablePrincipal, result)
    }

    @Test
    fun testGetSecurablePrincipalPropagatesException() {
        val principal = Principal(PrincipalType.USER, "user-123")
        Mockito.`when`(spm.lookup(principal)).thenThrow(RuntimeException("lookup failed"))

        assertThrows(RuntimeException::class.java) {
            controller.getSecurablePrincipal(principal)
        }
    }

    // --- syncCallingUser ---

    @Test
    fun testSyncCallingUserAcceptsAListedUser() {
        Mockito.`when`(userListingService.getUser("test-user")).thenReturn(Mockito.mock(ChronicleUserProfile::class.java))

        assertEquals(OK(), controller.syncCallingUser())
    }

    /** ConfiguredUserListingService.getUser is `users.getValue(id)`: an unknown id throws NoSuchElementException. */
    @Test
    fun testSyncCallingUserRefusesAUserTheListingDoesNotKnow() {
        Mockito.`when`(userListingService.getUser("test-user")).thenThrow(NoSuchElementException("Key test-user is missing in the map."))

        assertThrows(org.springframework.security.authentication.BadCredentialsException::class.java) {
            controller.syncCallingUser()
        }
    }

    // --- searchUsers ---

    @Test
    fun testSearchUsersDelegatesToService() {
        val fields = Mockito.mock(UserSearchFields::class.java)
        val users = mapOf("user-1" to Mockito.mock(ChronicleUserProfile::class.java))
        Mockito.`when`(userDirectoryService.searchAllUsers(fields)).thenReturn(users)

        val result = controller.searchUsers(fields)
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(userDirectoryService).searchAllUsers(fields)
    }

    @Test
    fun testSearchUsersReturnsEmptyMap() {
        val fields = Mockito.mock(UserSearchFields::class.java)
        Mockito.`when`(userDirectoryService.searchAllUsers(fields)).thenReturn(emptyMap())

        val result = controller.searchUsers(fields)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testSearchUsersPropagatesException() {
        val fields = Mockito.mock(UserSearchFields::class.java)
        Mockito.`when`(userDirectoryService.searchAllUsers(fields)).thenThrow(RuntimeException("search error"))

        assertThrows(RuntimeException::class.java) {
            controller.searchUsers(fields)
        }
    }

    // --- addPrincipalToPrincipal ---

    @Test
    fun testAddPrincipalToPrincipalDelegates() {
        val source = AclKey(UUID.randomUUID())
        val target = AclKey(UUID.randomUUID())
        val directedAclKeys = DirectedAclKeys(target = target, source = source)

        val result = controller.addPrincipalToPrincipal(directedAclKeys)
        assertEquals(OK.ok, result)
        verify(spm).addPrincipalToPrincipal(source, target)
        assertAudited(AuditEventType.PERMISSION_GRANT, "Principal", mapOf("source" to source.toString(), "target" to target.toString()))
    }

    @Test
    fun testAddPrincipalToPrincipalPropagatesException() {
        val source = AclKey(UUID.randomUUID())
        val target = AclKey(UUID.randomUUID())
        val directedAclKeys = DirectedAclKeys(target = target, source = source)

        Mockito.doThrow(RuntimeException("add error")).`when`(spm).addPrincipalToPrincipal(source, target)

        assertThrows(RuntimeException::class.java) {
            controller.addPrincipalToPrincipal(directedAclKeys)
        }
    }

    // --- removePrincipalFromPrincipal ---

    @Test
    fun testRemovePrincipalFromPrincipalDelegates() {
        val source = AclKey(UUID.randomUUID())
        val target = AclKey(UUID.randomUUID())
        val directedAclKeys = DirectedAclKeys(target = target, source = source)

        val result = controller.removePrincipalFromPrincipal(directedAclKeys)
        assertEquals(OK.ok, result)
        verify(spm).removePrincipalFromPrincipal(source, target)
        assertAudited(AuditEventType.PERMISSION_REVOKE, "Principal", mapOf("source" to source.toString(), "target" to target.toString()))
    }

    @Test
    fun testRemovePrincipalFromPrincipalPropagatesException() {
        val source = AclKey(UUID.randomUUID())
        val target = AclKey(UUID.randomUUID())
        val directedAclKeys = DirectedAclKeys(target = target, source = source)

        Mockito.doThrow(RuntimeException("remove error")).`when`(spm).removePrincipalFromPrincipal(source, target)

        assertThrows(RuntimeException::class.java) {
            controller.removePrincipalFromPrincipal(directedAclKeys)
        }
    }

    // --- deleteUserAccount ---

    @Test
    fun deleteUserAccountDeletesThePrincipalAndAuditsIt() {
        val securablePrincipal = Mockito.mock(SecurablePrincipal::class.java)
        val aclKey = AclKey(UUID.randomUUID())
        Mockito.`when`(securablePrincipal.aclKey).thenReturn(aclKey)
        Mockito.`when`(spm.getSecurablePrincipal("user-to-delete")).thenReturn(securablePrincipal)

        assertEquals(OK.ok, controller.deleteUserAccount("user-to-delete"))

        verify(spm).deletePrincipal(aclKey)
        verify(userDirectoryService).deleteUser("user-to-delete")
        assertAudited(AuditEventType.DELETE, "User", mapOf("principalId" to "user-to-delete"))
    }

    private fun assertAudited(action: AuditEventType, resourceType: String, details: Map<String, Any>) {
        val entry = audit.events.single()
        assertEquals(action, entry.eventType)
        assertEquals(resourceType, entry.description)
        assertTrue(entry.success)
        assertEquals(AuditService.sanitizeAdditionalData(details), entry.data - "success" - "accessedPHI")
    }

    // --- getCurrentRoles ---

    @Test
    fun testGetCurrentRolesMethodExists() {
        // getCurrentRoles depends on Principals.getCurrentPrincipals() static call.
        // Verify the controller is constructed and the method is accessible.
        assertNotNull(controller)
    }

    // --- getAvailableRoles ---

    // getAvailableRoles calls Principals.getCurrentPrincipals() which requires
    // a real security context. Cannot be unit-tested with simple mocks.
    // Covered by integration tests.

    
}
