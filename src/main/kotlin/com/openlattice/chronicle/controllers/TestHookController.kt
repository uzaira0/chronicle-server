package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.services.upload.AppDataUploadService
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
    public fun flushPipeline(
        @RequestBody request: FlushPipelineRequest,
    ): ResponseEntity<Map<String, String>> {
        if (!isTestingMode()) {
            return ResponseEntity.notFound().build()
        }
        appDataUploadService.moveToEventStorage(request.studyId, request.participantId)
        return ResponseEntity.ok(mapOf("status" to "flushed"))
    }

    private fun isTestingMode(): Boolean {
        return authConfiguration.testingLoginEnabled &&
            (!isProductionProfileActive() || authConfiguration.allowProductionTestingLogin)
    }

    private fun isProductionProfileActive(): Boolean {
        return environment.activeProfiles.any { it.equals("production", ignoreCase = true) }
    }
}

public data class FlushPipelineRequest(
    val studyId: UUID,
    val participantId: String,
)
