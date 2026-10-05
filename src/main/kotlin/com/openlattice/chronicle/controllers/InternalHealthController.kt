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

    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chronicle-readiness-probe").apply { isDaemon = true }
    }
    private var probe: java.util.concurrent.Future<Boolean>? = null

    @Synchronized
    private fun startProbe(): java.util.concurrent.Future<Boolean>? {
        if (probe?.isDone == false) return null
        return probeExecutor.submit<Boolean> {
            storageResolver.getPlatformStorage().connection.use { it.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS) }
        }.also { probe = it }
    }

    @jakarta.annotation.PreDestroy
    public fun close() { probeExecutor.shutdownNow() }

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
            // A stuck checkout keeps the sole probe occupied; requests never spawn replacements.
            // The worker closes a connection even if checkout completes after the response deadline.
            val databaseReady = startProbe()?.get(DATABASE_VALIDATION_TIMEOUT_SECONDS.toLong(),
                java.util.concurrent.TimeUnit.SECONDS) ?: false
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
