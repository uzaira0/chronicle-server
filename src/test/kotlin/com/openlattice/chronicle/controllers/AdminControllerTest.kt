package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.hazelcast.map.IMap
import org.mockito.kotlin.mock
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify

class AdminControllerTest {

    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val hazelcast = Mockito.mock(HazelcastInstance::class.java)
    private val appDataUploadService = Mockito.mock(AppDataUploadService::class.java)
    private val audit = com.openlattice.chronicle.audit.RecordingAuditingManager()
    private val auditService = AuditService(audit)

    private lateinit var controller: AdminController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext(admin = true)

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        controller = AdminController(authorizationManager, auditingManager, hazelcast, appDataUploadService, auditService)
        Mockito.`when`(hazelcast.config).thenReturn(Config().addMapConfig(MapConfig(HazelcastMap.STUDIES.name)
            .setMapStoreConfig(MapStoreConfig().setEnabled(true))))
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
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

    @Test
    fun testMoveToEventStoragePropagatesException() {
        Mockito.doThrow(RuntimeException("event storage error"))
            .`when`(appDataUploadService).moveToEventStorage()

        assertThrows(RuntimeException::class.java) {
            controller.moveToEventStorage()
        }
    }

    // --- reloadCache ---
    @Test
    fun allMapReloadTouchesOnlyConfiguredPersistentMaps() {
        val map = mock<IMap<Any, Any>>()
        Mockito.`when`(hazelcast.getMap<Any, Any>(HazelcastMap.STUDIES.name)).thenReturn(map)
        controller.reloadCache()
        verify(map).loadAll(true)
        verify(hazelcast, Mockito.never()).getMap<Any, Any>(HazelcastMap.JWT_BLOCKLIST.name)
    }
    @Test
    fun oneFailedMapMakesTheAllMapReloadFail() {
        val map = mock<IMap<Any, Any>>()
        Mockito.`when`(hazelcast.getMap<Any, Any>(kAnyString())).thenReturn(map)
        Mockito.doThrow(IllegalArgumentException("Cache store unavailable")).`when`(map).loadAll(true)
        assertThrows(IllegalArgumentException::class.java) { controller.reloadCache() }
        assertEquals(false, audit.events.single().data["success"])
    }
    // Note: reloadCache depends on HazelcastMap enum which requires real Hazelcast setup.
    // These tests verify exception propagation rather than silently swallowing errors.

    @Test
    fun testReloadCacheWithInvalidNameThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.reloadCache("INVALID_MAP_NAME_THAT_DOES_NOT_EXIST")
        }
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
        val ctrl = AdminController(am, aum, hazelcast, appDataUploadService, auditService)
        assertNotNull(ctrl)
        assertSame(am, ctrl.authorizationManager)
    }
}
