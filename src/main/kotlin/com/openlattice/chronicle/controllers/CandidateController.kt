@file:Suppress("DEPRECATION")
package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.candidates.CandidateApi
import com.openlattice.chronicle.candidates.CandidateApi.Companion.CONTROLLER
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import jakarta.inject.Inject

@Deprecated("Candidate endpoints are deprecated. Candidate data is no longer stored.")
@RestController
@RequestMapping(CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.DEFAULT)
public open class CandidateController @Inject constructor(
    public val storageResolver: StorageResolver,
    override val auditingManager: AuditingManager,
    override val authorizationManager: AuthorizationManager,
    public val auditService: AuditService
) : CandidateApi, AuthorizingComponent {

    @Inject
    private lateinit var candidateService: CandidateService

    @Timed
    @PostMapping(
        path = ["", "/"],
        consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    // reason: boundary catch — controller handler records a failure audit event for any error
    // before rethrowing so registration failures are always audited
    @Suppress("TooGenericExceptionCaught")
    override fun registerCandidate(@Valid @RequestBody candidate: Candidate): UUID {
        ensureAuthenticated()
        ensureUninitializedId(candidate.id) { "cannot register candidate with the given id" }
        return try {
            val candidateId = storageResolver.getPlatformStorage().connection.use { conn ->
                AuditedTransactionBuilder<UUID>(conn, auditingManager)
                    .transaction { connection -> candidateService.registerCandidate(connection, candidate) }
                    .audit { candidateId ->
                        listOf(
                            AuditableEvent(
                                AclKey(candidateId),
                                eventType = AuditEventType.REGISTER_CANDIDATE,
                            )
                        )
                    }
                    .buildAndRun()
            }
            authorizationManager.ensureAceIsLoaded(AclKey(candidateId), Principals.getCurrentUser())
            auditService.logWithContext {
                action(AuditAction.CREATE)
                resourceType("Candidate")
                resourceId(candidateId)
                success(true)
            }
            candidateId
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.CREATE)
                resourceType("Candidate")
                failed(ex.message ?: "Candidate registration failed")
            }
            throw ex
        }
    }
}
