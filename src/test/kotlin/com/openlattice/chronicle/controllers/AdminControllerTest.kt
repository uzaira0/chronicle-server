package com.openlattice.chronicle.controllers

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify

class AdminControllerTest {

    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val hazelcast = Mockito.mock(HazelcastInstance::class.java)
    private val appDataUploadService = Mockito.mock(AppDataUploadService::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)

    private lateinit var controller: AdminController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext(admin = true)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        controller = AdminController(authorizationManager, auditingManager)

        // Set injected fields via reflection
        setField(controller, "hazelcast", hazelcast)
        setField(controller, "appDataUploadService", appDataUploadService)
        setField(controller, "auditService", auditService)
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    @Test
    fun testAuditingManagerAccessible() {
        assertSame(auditingManager, controller.auditingManager)
    }

    // --- moveToEventStorage ---

    @Test
    fun testMoveToEventStorageDelegatesToService() {
        controller.moveToEventStorage()
        verify(appDataUploadService).moveToEventStorage()
    }

    @Test(expected = RuntimeException::class)
    fun testMoveToEventStoragePropagatesException() {
        Mockito.doThrow(RuntimeException("event storage error"))
            .`when`(appDataUploadService).moveToEventStorage()

        controller.moveToEventStorage()
    }

    // --- reloadCache ---
    // Note: reloadCache depends on HazelcastMap enum which requires real Hazelcast setup.
    // These tests verify exception propagation rather than silently swallowing errors.

    @Test(expected = IllegalArgumentException::class)
    fun testReloadCacheWithInvalidNameThrows() {
        controller.reloadCache("INVALID_MAP_NAME_THAT_DOES_NOT_EXIST")
    }

    // --- moveToEventStorage isolation ---

    @Test
    fun testMoveToEventStorageNoOtherServiceInteractions() {
        controller.moveToEventStorage()
        verify(appDataUploadService).moveToEventStorage()
        Mockito.verifyNoMoreInteractions(appDataUploadService)
    }

    @Test
    fun testControllerAcceptsDependencies() {
        val am = Mockito.mock(AuthorizationManager::class.java)
        val aum = Mockito.mock(AuditingManager::class.java)
        val ctrl = AdminController(am, aum)
        assertNotNull(ctrl)
        assertSame(am, ctrl.authorizationManager)
    }
}
