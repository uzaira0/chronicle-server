package com.openlattice.chronicle.controllers

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
public class InternalHealthController(
    private val storageResolver: StorageResolver,
    private val hazelcastInstance: HazelcastInstance,
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(InternalHealthController::class.java)
        private const val DATABASE_VALIDATION_TIMEOUT_SECONDS = 2
    }

    @GetMapping("/internal/health/live")
    public fun live(): ResponseEntity<Void> = ResponseEntity.noContent().build()

    /**
     * Dependency-aware readiness. Liveness deliberately remains independent of
     * PostgreSQL and Hazelcast so an infrastructure outage does not cause a
     * restart loop; readiness removes the pod/container from service instead.
     */
    @GetMapping("/internal/health/ready")
    public fun ready(): ResponseEntity<Void> {
        return try {
            if (!hazelcastInstance.lifecycleService.isRunning) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
            val databaseReady = storageResolver.getPlatformStorage().connection.use { connection ->
                connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS)
            }
            if (databaseReady) {
                ResponseEntity.noContent().build()
            } else {
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
        } catch (ex: Exception) {
            logger.warn("Readiness dependency validation failed: {}", ex.javaClass.simpleName)
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
    }
}
