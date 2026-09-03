package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AccessCheck
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.SecurableObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*
import java.util.stream.Stream

class AuthorizationsControllerTest {

    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)

    private lateinit var controller: AuthorizationsController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
        controller = AuthorizationsController(auditingManager, authorizationManager)
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsDependencies() {
        val am = Mockito.mock(AuditingManager::class.java)
        val authm = Mockito.mock(AuthorizationManager::class.java)
        val ctrl = AuthorizationsController(am, authm)
        assertNotNull(ctrl)
    }

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    @Test
    fun testAuditingManagerAccessible() {
        assertSame(auditingManager, controller.auditingManager)
    }

    // --- checkAuthorizations ---

    @Test
    fun testCheckAuthorizationsDelegatesToManager() {
        val aclKey = AclKey(UUID.randomUUID())
        val permissions = EnumSet.of(Permission.READ)
        val accessCheck = AccessCheck(aclKey, permissions)
        val queries = setOf(accessCheck)

        val authorization = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kEq(queries), kAny<Set<Principal>>()
        )).thenReturn(listOf(authorization))

        val result = controller.checkAuthorizations(queries)
        assertNotNull(result)
    }

    @Test
    fun testCheckAuthorizationsReturnsIterable() {
        val queries = emptySet<AccessCheck>()
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kEq(queries), kAny<Set<Principal>>()
        )).thenReturn(emptyList())

        val result = controller.checkAuthorizations(queries)
        assertNotNull(result)
        assertEquals(0, result.count())
    }

    @Test
    fun testCheckAuthorizationsWithMultipleQueries() {
        val aclKey1 = AclKey(UUID.randomUUID())
        val aclKey2 = AclKey(UUID.randomUUID())
        val accessCheck1 = AccessCheck(aclKey1, EnumSet.of(Permission.READ))
        val accessCheck2 = AccessCheck(aclKey2, EnumSet.of(Permission.WRITE))
        val queries = setOf(accessCheck1, accessCheck2)

        val auth1 = Mockito.mock(Authorization::class.java)
        val auth2 = Mockito.mock(Authorization::class.java)
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kEq(queries), kAny<Set<Principal>>()
        )).thenReturn(listOf(auth1, auth2))

        val result = controller.checkAuthorizations(queries)
        assertEquals(2, result.count())
    }

    @Test(expected = RuntimeException::class)
    fun testCheckAuthorizationsPropagatesException() {
        val queries = setOf<AccessCheck>()
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kAny<Set<AccessCheck>>(), kAny<Set<Principal>>()
        )).thenThrow(RuntimeException("auth check error"))

        controller.checkAuthorizations(queries).iterator().next()
    }

    // --- getAccessibleObjects ---

    @Test
    fun testGetAccessibleObjectsDelegatesToManager() {
        val objectType = SecurableObjectType.Study
        val permission = Permission.READ
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kEq(objectType), kAny<EnumSet<Permission>>()
        )).thenReturn(Stream.empty())

        val result = controller.getAccessibleObjects(objectType, permission, "")
        assertNotNull(result)
    }

    @Test
    fun testGetAccessibleObjectsReturnsSearchResult() {
        val objectType = SecurableObjectType.Study
        val permission = Permission.READ
        val aclKey = AclKey(UUID.randomUUID())
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kEq(objectType), kAny<EnumSet<Permission>>()
        )).thenReturn(Stream.of(aclKey))

        val result = controller.getAccessibleObjects(objectType, permission, "")
        assertNotNull(result)
        assertNotNull(result.authorizedObjects)
    }

    @Test
    fun testGetAccessibleObjectsReturnsEmptyPagingToken() {
        val objectType = SecurableObjectType.Study
        val permission = Permission.READ
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kEq(objectType), kAny<EnumSet<Permission>>()
        )).thenReturn(Stream.empty())

        val result = controller.getAccessibleObjects(objectType, permission, "token")
        assertEquals("", result.pagingToken)
    }

    @Test
    fun testGetAccessibleObjectsWithDifferentPermissions() {
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kAny<SecurableObjectType>(), kAny<EnumSet<Permission>>()
        )).thenAnswer { Stream.empty<AclKey>() }

        val resultRead = controller.getAccessibleObjects(SecurableObjectType.Study, Permission.READ, "")
        assertNotNull(resultRead)

        val resultWrite = controller.getAccessibleObjects(SecurableObjectType.Study, Permission.WRITE, "")
        assertNotNull(resultWrite)

        val resultOwner = controller.getAccessibleObjects(SecurableObjectType.Study, Permission.OWNER, "")
        assertNotNull(resultOwner)
    }

    @Test
    fun testGetAccessibleObjectsWithDifferentObjectTypes() {
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kAny<SecurableObjectType>(), kAny<EnumSet<Permission>>()
        )).thenAnswer { Stream.empty<AclKey>() }

        val result1 = controller.getAccessibleObjects(SecurableObjectType.Study, Permission.READ, "")
        assertNotNull(result1)

        val result2 = controller.getAccessibleObjects(SecurableObjectType.Organization, Permission.READ, "")
        assertNotNull(result2)
    }

    @Test(expected = RuntimeException::class)
    fun testGetAccessibleObjectsPropagatesException() {
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kAny<SecurableObjectType>(), kAny<EnumSet<Permission>>()
        )).thenThrow(RuntimeException("accessible objects error"))

        controller.getAccessibleObjects(SecurableObjectType.Study, Permission.READ, "")
    }

    @Test
    fun testCheckAuthorizationsPassesPrincipals() {
        val queries = emptySet<AccessCheck>()
        Mockito.`when`(authorizationManager.accessChecksForPrincipals(
            kEq(queries), kAny<Set<Principal>>()
        )).thenReturn(emptyList())

        // checkAuthorizations returns a lazy Iterable; force iteration so the underlying call fires.
        controller.checkAuthorizations(queries).toList()
        verify(authorizationManager).accessChecksForPrincipals(kEq(queries), kAny<Set<Principal>>())
    }

    @Test
    fun testGetAccessibleObjectsPassesObjectType() {
        val objectType = SecurableObjectType.Study
        Mockito.`when`(authorizationManager.getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kEq(objectType), kAny<EnumSet<Permission>>()
        )).thenReturn(Stream.empty())

        controller.getAccessibleObjects(objectType, Permission.READ, "")
        verify(authorizationManager).getAuthorizedObjectsOfType(
            kAny<Set<Principal>>(), kEq(objectType), kAny<EnumSet<Permission>>()
        )
    }
}
