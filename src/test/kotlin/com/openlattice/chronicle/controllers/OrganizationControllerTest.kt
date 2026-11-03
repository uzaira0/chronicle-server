package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.*

class OrganizationControllerTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val chronicleOrganizationService = Mockito.mock(ChronicleOrganizationService::class.java)

    private val applicationContext = AnnotationConfigApplicationContext()
    private lateinit var controller: OrganizationController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        applicationContext.beanFactory.registerSingleton("storageResolver", storageResolver)
        applicationContext.beanFactory.registerSingleton("idGenerationService", idGenerationService)
        applicationContext.beanFactory.registerSingleton("authorizationManager", authorizationManager)
        applicationContext.beanFactory.registerSingleton("auditingManager", auditingManager)
        applicationContext.beanFactory.registerSingleton("chronicleOrganizationService", chronicleOrganizationService)
        applicationContext.register(OrganizationController::class.java)
        applicationContext.refresh()
        controller = applicationContext.getBean(OrganizationController::class.java)
    }

    @After
    fun tearDown() {
        applicationContext.close()
    }

    

    // --- createOrganization ---

    @Test
    fun testCreateOrganizationDelegatesToService() {
        val org = Mockito.mock(Organization::class.java)
        val orgId = UUID.randomUUID()
        Mockito.`when`(chronicleOrganizationService.createOrganization(kAny(), kEq(org)))
            .thenReturn(orgId)

        val result = controller.createOrganization(org)
        assertNotNull(result)
        assertEquals(orgId, result)
    }

    @Test
    fun testCreateOrganizationPropagatesException() {
        val org = Mockito.mock(Organization::class.java)
        Mockito.`when`(chronicleOrganizationService.createOrganization(kAny(), kEq(org)))
            .thenThrow(RuntimeException("create failed"))

        assertThrows(RuntimeException::class.java) {
            controller.createOrganization(org)
        }
    }

    // --- getOrganization ---

    @Test
    fun testGetOrganizationDelegatesToService() {
        val orgId = UUID.randomUUID()
        val org = Mockito.mock(Organization::class.java)
        Mockito.`when`(chronicleOrganizationService.getOrganization(orgId)).thenReturn(org)

        val result = controller.getOrganization(orgId)
        assertNotNull(result)
        assertSame(org, result)
        verify(chronicleOrganizationService).getOrganization(orgId)
    }

    @Test
    fun testGetOrganizationPropagatesException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(chronicleOrganizationService.getOrganization(orgId))
            .thenThrow(RuntimeException("get failed"))

        assertThrows(RuntimeException::class.java) {
            controller.getOrganization(orgId)
        }
    }

    @Test
    fun testGetOrganizationPassesCorrectOrgId() {
        val orgId = UUID.randomUUID()
        val org = Mockito.mock(Organization::class.java)
        Mockito.`when`(chronicleOrganizationService.getOrganization(orgId)).thenReturn(org)

        controller.getOrganization(orgId)
        verify(chronicleOrganizationService).getOrganization(orgId)
    }

    // --- getOrganizationSettings ---

    

    

    // --- setOrganizationSettings ---

    

    

    // --- getChronicleDataCollectionSettings ---

    

    

    // --- setChronicleDataCollectionSettings ---

    

    // --- getAppComponentSettings ---

    

    

    // --- setAppComponentSettings ---

    

    

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    @Test
    fun testAuditingManagerAccessible() {
        assertSame(auditingManager, controller.auditingManager)
    }
}
