package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedTransactionBuilder
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.StudyComplianceManager
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyComplianceApi
import com.openlattice.chronicle.study.StudyComplianceApi.Companion.NOTIFICATION
import com.openlattice.chronicle.study.StudyLimitsApi.Companion.STUDY
import com.openlattice.chronicle.study.StudyLimitsApi.Companion.STUDY_ID
import com.openlattice.chronicle.study.StudyLimitsApi.Companion.STUDY_ID_PATH
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
import jakarta.inject.Inject

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@RestController
// Dual-mapped on purpose. The dashboard's RTK Query base is /chronicle/api/web, which both
// reverse proxies rewrite to /chronicle/v3/..., so the browser-facing form of this controller
// is /chronicle/v3/compliance/... The unversioned form stays for the Retrofit StudyComplianceApi
// client, matching how AuthTokenController declares its two paths.
@RequestMapping(value = [StudyComplianceApi.CONTROLLER, StudyComplianceApi.V3_CONTROLLER])
@Validated
@RateLimit(type = RateLimitType.ADMIN)
public open class StudyComplianceController @Inject constructor(
    // reason: @Inject-wired DI dependency retained intentionally; removing it would alter the
    // controller's injected collaborator set
    @Suppress("UnusedPrivateProperty")
    private val studyLimitsMgr: StudyLimitsManager,
    private val studyService: StudyService,
    private val studyComplianceManager: StudyComplianceManager,
    private val studyComplianceHazelcastTask: StudyComplianceHazelcastTask,
    private val storageResolver: StorageResolver,
    override val auditingManager: AuditingManager,
    override val authorizationManager: AuthorizationManager,
) : StudyComplianceApi, AuthorizingComponent {
    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyComplianceController::class.java)
    }

    @GetMapping(
        value = [STUDY + STUDY_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyComplianceViolations(@PathVariable(STUDY_ID) studyId: UUID): Map<UUID, Map<String, List<ComplianceViolation>>> {
        ensureReadAccess(AclKey(studyId))
        check(studyService.isValidStudy(studyId)) { "$studyId is not valid." }

        return studyComplianceManager.getNonCompliantStudies(listOf(studyId))
    }

    @PostMapping(
        value = [NOTIFICATION],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun triggerStudyComplianceNotifications(@Valid @RequestBody studyIds: Set<UUID>): OK {
        ensureAdminAccess()
        val nonCompliantStudies = studyComplianceManager.getNonCompliantStudies(studyIds)
        logger.info("Triggering notifications for the following non-compliant studies: $nonCompliantStudies")
        check(studyIds.containsAll(nonCompliantStudies.keys) ) { "Received unrequested non-compliant studies must be a bug." }

        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction {
                    studyComplianceHazelcastTask.notifyNonCompliantStudies(nonCompliantStudies)
                }.audit {
                    studyIds.map { studyId ->
                        AuditableEvent(
                            AclKey(studyId),
                            eventType = AuditEventType.TRIGGER_STUDY_COMPLIANCE_NOTIFICATIONS,
                            description = "Trigger study compliance notification job"
                        )
                    }
                }.buildAndRun()
        }
        return OK()
    }

    @GetMapping(
        value = [NOTIFICATION],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun triggerComplianceNotificationsForAllStudies(): OK {
        ensureAdminAccess()
        logger.info("Triggering notifications for all non-compliant study participants.")
        val nonCompliantStudies = studyComplianceManager.getAllNonCompliantStudies()
        storageResolver.getPlatformStorage().connection.use { connection ->
            AuditedTransactionBuilder<Unit>(connection, auditingManager)
                .transaction {
                    studyComplianceHazelcastTask.notifyNonCompliantStudies(nonCompliantStudies)
                }.audit {
                    listOf(
                        AuditableEvent(
                            AclKey(IdConstants.CHRONICLE.id),
                            eventType = AuditEventType.TRIGGER_STUDY_COMPLIANCE_NOTIFICATIONS,
                            description = "Trigger study compliance notification job"
                        )
                    )
                }.buildAndRun()
        }
        return OK()
    }
}
