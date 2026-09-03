package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.Ace
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Action
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.base.OK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class PermissionsControllerTest {

    private val securePrincipalsManager = Mockito.mock(SecurePrincipalsManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)

    private lateinit var controller: PermissionsController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext(admin = false)

        controller = PermissionsController(
            securePrincipalsManager, auditingManager, authorizationManager, auditService
        )
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsDependencies() {
        val spm = Mockito.mock(SecurePrincipalsManager::class.java)
        val am = Mockito.mock(AuditingManager::class.java)
        val authm = Mockito.mock(AuthorizationManager::class.java)
        val as2 = Mockito.mock(AuditService::class.java)
        val ctrl = PermissionsController(spm, am, authm, as2)
        assertNotNull(ctrl)
    }

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    // --- updateAcl ---

    @Test
    fun testUpdateAclDelegatesToUpdateAcls() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.ADD)

        // Mock the owner check to pass
        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to true))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        val result = controller.updateAcl(aclData)
        assertEquals(OK.ok, result)
    }

    @Test
    fun testUpdateAclReturnsOk() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.ADD)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to true))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        val result = controller.updateAcl(aclData)
        assertSame(OK.ok, result)
    }

    // --- updateAcls ---

    @Test
    fun testUpdateAclsWithAddAction() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.ADD)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to true))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        val result = controller.updateAcls(listOf(aclData))
        assertEquals(OK.ok, result)
        @Suppress("UNCHECKED_CAST")
        verify(authorizationManager).addPermissions(Mockito.anyList<Acl>())
    }

    @Test
    fun testUpdateAclsWithRemoveAction() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.REMOVE)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to true))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        val result = controller.updateAcls(listOf(aclData))
        assertEquals(OK.ok, result)
        @Suppress("UNCHECKED_CAST")
        verify(authorizationManager).removePermissions(Mockito.anyList<Acl>())
    }

    @Test
    fun testUpdateAclsWithSetAction() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.SET)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to true))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        val result = controller.updateAcls(listOf(aclData))
        assertEquals(OK.ok, result)
        @Suppress("UNCHECKED_CAST")
        verify(authorizationManager).setPermissions(Mockito.anyList<Acl>())
    }

    @Test(expected = ForbiddenException::class)
    fun testUpdateAclsThrowsForbiddenWhenNotOwner() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Acl(aclKey, emptyList())
        val aclData = AclData(acl, Action.ADD)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorization.permissions).thenReturn(mapOf(Permission.OWNER to false))
        Mockito.`when`(authorization.aclKey).thenReturn(aclKey)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny(), kAny()
        )).thenReturn(listOf(authorization))

        controller.updateAcls(listOf(aclData))
    }

    @Test
    fun testUpdateAclsWithEmptyList() {
        val result = controller.updateAcls(emptyList())
        assertEquals(OK.ok, result)
    }

    // --- getAcl ---

    @Test
    fun testGetAclDelegatesToManager() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Mockito.mock(Acl::class.java)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(authorizationManager.getAllSecurableObjectPermissions(aclKey)).thenReturn(acl)

        val result = controller.getAcl(aclKey)
        assertNotNull(result)
        assertSame(acl, result)
    }

    @Test(expected = ForbiddenException::class)
    fun testGetAclThrowsForbiddenWhenNotOwner() {
        val aclKey = AclKey(UUID.randomUUID())

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        controller.getAcl(aclKey)
    }

    // --- getAcls ---

    @Test
    fun testGetAclsDelegatesToManager() {
        val aclKey = AclKey(UUID.randomUUID())
        val keys = setOf(aclKey)
        val acls = setOf(Mockito.mock(Acl::class.java))

        // Mock owner access check
        Mockito.`when`(authorizationManager.authorize(
            kAny(), kAny()
        )).thenReturn(mapOf(aclKey to EnumMap(mapOf(Permission.OWNER to true))))
        Mockito.`when`(authorizationManager.getAllSecurableObjectPermissions(keys)).thenReturn(acls)

        val result = controller.getAcls(keys)
        assertNotNull(result)
    }

    @Test
    fun testGetAclsReturnsEmptySet() {
        // Mock ensureOwnerAccess
        Mockito.`when`(authorizationManager.authorize(
            kAny(), kAny()
        )).thenReturn(emptyMap())
        @Suppress("UNCHECKED_CAST")
        Mockito.`when`(authorizationManager.getAllSecurableObjectPermissions(Mockito.anySet<AclKey>())).thenReturn(emptySet())

        val result = controller.getAcls(emptySet())
        assertNotNull(result)
    }

    // --- getAclExplanation ---

    @Test
    fun testGetAclExplanationRequiresOwnerAccess() {
        val aclKey = AclKey(UUID.randomUUID())
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        try {
            controller.getAclExplanation(aclKey)
            fail("Expected ForbiddenException")
        } catch (expected: ForbiddenException) {
            // expected
        }
    }

    @Test
    fun testGetAclExplanationReturnsCollection() {
        val aclKey = AclKey(UUID.randomUUID())
        val acl = Mockito.mock(Acl::class.java)
        val aces = emptyList<Ace>()
        Mockito.`when`(acl.aces).thenReturn(aces)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(authorizationManager.getAllSecurableObjectPermissions(aclKey)).thenReturn(acl)

        val result = controller.getAclExplanation(aclKey)
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testControllerImplementsAuthorizingComponent() {
        assertNotNull(controller.authorizationManager)
        assertNotNull(controller.auditingManager)
    }
}
