package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.organizations.OrganizationSettings
import com.openlattice.chronicle.settings.AppComponent
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class OrganizationControllerTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val chronicleOrganizationService = Mockito.mock(ChronicleOrganizationService::class.java)

    private lateinit var controller: OrganizationController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        controller = OrganizationController(
            storageResolver, idGenerationService, authorizationManager, auditingManager
        )

        // Set the injected field via reflection
        val field = OrganizationController::class.java.getDeclaredField("chronicleOrganizationService")
        field.isAccessible = true
        field.set(controller, chronicleOrganizationService)
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
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

    @Test(expected = RuntimeException::class)
    fun testCreateOrganizationPropagatesException() {
        val org = Mockito.mock(Organization::class.java)
        Mockito.`when`(chronicleOrganizationService.createOrganization(kAny(), kEq(org)))
            .thenThrow(RuntimeException("create failed"))

        controller.createOrganization(org)
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

    @Test(expected = RuntimeException::class)
    fun testGetOrganizationPropagatesException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(chronicleOrganizationService.getOrganization(orgId))
            .thenThrow(RuntimeException("get failed"))

        controller.getOrganization(orgId)
    }

    @Test
    fun testGetOrganizationPassesCorrectOrgId() {
        val orgId = UUID.randomUUID()
        val org = Mockito.mock(Organization::class.java)
        Mockito.`when`(chronicleOrganizationService.getOrganization(orgId)).thenReturn(org)

        controller.getOrganization(orgId)
        verify(chronicleOrganizationService).getOrganization(orgId)
    }

    // --- searchOrganizations ---

    @Test
    fun testSearchOrganizationsDelegatesToService() {
        val orgs = listOf(Mockito.mock(Organization::class.java))
        Mockito.`when`(chronicleOrganizationService.searchOrganizations()).thenReturn(orgs)

        val result = controller.searchOrganizations()
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(chronicleOrganizationService).searchOrganizations()
    }

    @Test
    fun testSearchOrganizationsReturnsEmptyList() {
        Mockito.`when`(chronicleOrganizationService.searchOrganizations()).thenReturn(emptyList())

        val result = controller.searchOrganizations()
        assertTrue(result.isEmpty())
    }

    // --- getOrganizationSettings ---

    @Test
    fun testGetOrganizationSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val settings = Mockito.mock(OrganizationSettings::class.java)
        Mockito.`when`(chronicleOrganizationService.getOrganizationSettings(orgId)).thenReturn(settings)

        val result = controller.getOrganizationSettings(orgId)
        assertNotNull(result)
        assertSame(settings, result)
        verify(chronicleOrganizationService).getOrganizationSettings(orgId)
    }

    @Test(expected = RuntimeException::class)
    fun testGetOrganizationSettingsPropagatesException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(chronicleOrganizationService.getOrganizationSettings(orgId))
            .thenThrow(RuntimeException("settings error"))

        controller.getOrganizationSettings(orgId)
    }

    // --- setOrganizationSettings ---

    @Test
    fun testSetOrganizationSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val settings = Mockito.mock(OrganizationSettings::class.java)

        controller.setOrganizationSettings(orgId, settings)
        verify(chronicleOrganizationService).setOrganizationSettings(orgId, settings)
    }

    @Test(expected = RuntimeException::class)
    fun testSetOrganizationSettingsPropagatesException() {
        val orgId = UUID.randomUUID()
        val settings = Mockito.mock(OrganizationSettings::class.java)
        Mockito.doThrow(RuntimeException("set error"))
            .`when`(chronicleOrganizationService).setOrganizationSettings(orgId, settings)

        controller.setOrganizationSettings(orgId, settings)
    }

    // --- getChronicleDataCollectionSettings ---

    @Test
    fun testGetChronicleDataCollectionSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val settings = Mockito.mock(ChronicleDataCollectionSettings::class.java)
        Mockito.`when`(chronicleOrganizationService.getChronicleDataCollectionSettings(orgId)).thenReturn(settings)

        val result = controller.getChronicleDataCollectionSettings(orgId)
        assertNotNull(result)
        assertSame(settings, result)
    }

    @Test(expected = RuntimeException::class)
    fun testGetChronicleDataCollectionSettingsPropagatesException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(chronicleOrganizationService.getChronicleDataCollectionSettings(orgId))
            .thenThrow(RuntimeException("collection error"))

        controller.getChronicleDataCollectionSettings(orgId)
    }

    // --- setChronicleDataCollectionSettings ---

    @Test
    fun testSetChronicleDataCollectionSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val settings = Mockito.mock(ChronicleDataCollectionSettings::class.java)

        controller.setChronicleDataCollectionSettings(orgId, settings)
        verify(chronicleOrganizationService).setChronicleDataCollectionSettings(orgId, settings)
    }

    // --- getAppComponentSettings ---

    @Test
    fun testGetAppComponentSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val appComponent = AppComponent.CHRONICLE_SURVEYS
        val settingsMap = mapOf("key" to "value" as Any)
        Mockito.`when`(chronicleOrganizationService.getAppComponentSettings(orgId, appComponent))
            .thenReturn(settingsMap)

        val result = controller.getAppComponentSettings(orgId, appComponent)
        assertNotNull(result)
        assertEquals("value", result["key"])
    }

    @Test
    fun testGetAppComponentSettingsReturnsEmptyMap() {
        val orgId = UUID.randomUUID()
        val appComponent = AppComponent.CHRONICLE_SURVEYS
        Mockito.`when`(chronicleOrganizationService.getAppComponentSettings(orgId, appComponent))
            .thenReturn(emptyMap())

        val result = controller.getAppComponentSettings(orgId, appComponent)
        assertTrue(result.isEmpty())
    }

    // --- setAppComponentSettings ---

    @Test
    fun testSetAppComponentSettingsDelegatesToService() {
        val orgId = UUID.randomUUID()
        val appComponent = AppComponent.CHRONICLE_SURVEYS
        val settings = mapOf("key" to "value" as Any)

        controller.setAppComponentSettings(orgId, appComponent, settings)
        verify(chronicleOrganizationService).setAppComponentSettings(orgId, appComponent, settings)
    }

    @Test(expected = RuntimeException::class)
    fun testSetAppComponentSettingsPropagatesException() {
        val orgId = UUID.randomUUID()
        val appComponent = AppComponent.CHRONICLE_SURVEYS
        val settings = mapOf("key" to "value" as Any)
        Mockito.doThrow(RuntimeException("set error"))
            .`when`(chronicleOrganizationService).setAppComponentSettings(orgId, appComponent, settings)

        controller.setAppComponentSettings(orgId, appComponent, settings)
    }

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    @Test
    fun testAuditingManagerAccessible() {
        assertSame(auditingManager, controller.auditingManager)
    }
}
