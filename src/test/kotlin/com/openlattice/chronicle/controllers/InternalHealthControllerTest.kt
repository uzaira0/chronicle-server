package com.openlattice.chronicle.controllers

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.LifecycleService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.sql.Connection

class InternalHealthControllerTest {
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val dataSource = Mockito.mock(HikariDataSource::class.java)
    private val connection = Mockito.mock(Connection::class.java)
    private val hazelcastInstance = Mockito.mock(HazelcastInstance::class.java)
    private val lifecycleService = Mockito.mock(LifecycleService::class.java)
    private val controller = InternalHealthController(storageResolver, hazelcastInstance)

    @Test
    fun livenessDoesNotTouchDependencies() {
        assertEquals(204, controller.live().statusCode.value())

        verify(storageResolver, never()).getPlatformStorage()
        verify(hazelcastInstance, never()).lifecycleService
    }

    @Test
    fun readinessRequiresRunningHazelcastAndValidDatabaseConnection() {
        Mockito.`when`(hazelcastInstance.lifecycleService).thenReturn(lifecycleService)
        Mockito.`when`(lifecycleService.isRunning).thenReturn(true)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.isValid(2)).thenReturn(true)

        assertEquals(204, controller.ready().statusCode.value())
        verify(connection).close()
    }

    @Test
    fun stoppedHazelcastMakesReadinessUnavailableWithoutOpeningDatabase() {
        Mockito.`when`(hazelcastInstance.lifecycleService).thenReturn(lifecycleService)
        Mockito.`when`(lifecycleService.isRunning).thenReturn(false)

        assertEquals(503, controller.ready().statusCode.value())
        verify(storageResolver, never()).getPlatformStorage()
    }

    @Test
    fun invalidDatabaseConnectionMakesReadinessUnavailable() {
        Mockito.`when`(hazelcastInstance.lifecycleService).thenReturn(lifecycleService)
        Mockito.`when`(lifecycleService.isRunning).thenReturn(true)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.isValid(2)).thenReturn(false)

        assertEquals(503, controller.ready().statusCode.value())
        verify(connection).close()
    }

    @Test
    fun databaseFailureMakesReadinessUnavailableWithoutLeakingDetails() {
        Mockito.`when`(hazelcastInstance.lifecycleService).thenReturn(lifecycleService)
        Mockito.`when`(lifecycleService.isRunning).thenReturn(true)
        Mockito.`when`(storageResolver.getPlatformStorage())
            .thenThrow(IllegalStateException("synthetic database failure"))

        val response = controller.ready()

        assertEquals(503, response.statusCode.value())
        assertEquals(null, response.body)
    }

    @Test
    fun hazelcastFailureMakesReadinessUnavailableWithoutLeakingDetails() {
        Mockito.`when`(hazelcastInstance.lifecycleService)
            .thenThrow(IllegalStateException("synthetic Hazelcast failure"))

        val response = controller.ready()

        assertEquals(503, response.statusCode.value())
        assertEquals(null, response.body)
        verify(storageResolver, never()).getPlatformStorage()
    }
}
