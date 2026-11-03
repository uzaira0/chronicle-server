package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// Test-only endpoints exposed when testingLoginEnabled=true.
// Used by the frontend Playwright DSL to drive pipeline operations that the in-JVM
// Kotlin tests perform via Spring beans. Returns 404 outside test mode so the
// endpoint appears non-existent in production.
@RestController
@RequestMapping("/v3/admin/test-only")
@Timed
@Profile("local & !production", "test & !production")
public open class TestHookController(
    private val appDataUploadService: AppDataUploadService,
    private val authConfiguration: ChronicleAuthConfiguration,
    private val environment: Environment,
    private val storageResolver: StorageResolver,
) {
    private val logger = LoggerFactory.getLogger(TestHookController::class.java)

    @PostConstruct
    public fun warnIfMisconfigured() {
        if (authConfiguration.testingLoginEnabled) {
            logger.warn(
                "TestHookController is REGISTERED with testingLoginEnabled=true. " +
                    "Production deployments must keep testingLoginEnabled=false."
            )
        }
        if (authConfiguration.testingLoginEnabled && isProductionProfileActive() && !authConfiguration.allowProductionTestingLogin) {
            logger.error("TestHookController is blocked: testingLoginEnabled=true while production profile is active.")
        }
    }

    @PostMapping(path = ["/flush-pipeline"], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun flushPipeline(): ResponseEntity<Map<String, String>> {
        if (!isTestingMode()) {
            return ResponseEntity.notFound().build()
        }
        appDataUploadService.drainEventStorage()
        return ResponseEntity.ok(mapOf("status" to "flushed"))
    }

    /** Moves the participant's enrollment [days] into the past, so a browser test has ended survey days to answer. */
    @PostMapping(path = ["/backdate-enrollment"], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun backdateEnrollment(@RequestBody request: BackdateEnrollmentRequest): ResponseEntity<Map<String, Any>> {
        if (!isTestingMode()) {
            return ResponseEntity.notFound().build()
        }
        if (request.days !in 1..MAX_BACKDATE_DAYS) {
            return ResponseEntity.badRequest().body(mapOf("error" to "days must be 1..$MAX_BACKDATE_DAYS"))
        }
        // The survey's enrolled time starts at the participant's first enrollment key (ParticipantCollectionGate).
        val keys = storageResolver.getPlatformStorage().connection.use { connection ->
            connection.withTransaction { RLSConnectionCustomizer.withAdminTransactionContext(connection) {
                connection.prepareStatement(
                    "UPDATE api_keys SET created_at = created_at - make_interval(hours => ?) WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    // Elapsed 24-hour days, including across a database session's DST transition.
                    statement.setInt(1, Math.multiplyExact(request.days, 24))
                    statement.setObject(2, request.studyId)
                    statement.setString(3, request.participantId)
                    statement.executeUpdate()
                }
            }.also { connection.commit() } }
        }
        if (keys == 0) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok(mapOf("status" to "backdated", "keys" to keys))
    }

    private fun isTestingMode(): Boolean {
        return authConfiguration.testingLoginEnabled &&
            (!isProductionProfileActive() || authConfiguration.allowProductionTestingLogin)
    }

    private fun isProductionProfileActive(): Boolean {
        return environment.activeProfiles.any { it.equals("production", ignoreCase = true) }
    }
}

public data class BackdateEnrollmentRequest(val studyId: UUID, val participantId: String, val days: Int)

private const val MAX_BACKDATE_DAYS = 400
