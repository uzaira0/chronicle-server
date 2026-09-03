package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.admin.AdminApi
import com.openlattice.chronicle.admin.AdminApi.Companion.CONTROLLER
import com.openlattice.chronicle.admin.AdminApi.Companion.ID
import com.openlattice.chronicle.admin.AdminApi.Companion.ID_PATH
import com.openlattice.chronicle.admin.AdminApi.Companion.NAME
import com.openlattice.chronicle.admin.AdminApi.Companion.NAME_PATH
import com.openlattice.chronicle.admin.AdminApi.Companion.PRINCIPALS
import com.openlattice.chronicle.admin.AdminApi.Companion.EVENT_STORAGE
import com.openlattice.chronicle.admin.AdminApi.Companion.RELOAD_CACHE
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.services.upload.AppDataUploadService
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.slf4j.LoggerFactory
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.inject.Inject

@SuppressFBWarnings(
    value = ["RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", "BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
    justification = "Allowing redundant kotlin null check on lateinit variables, " +
            "Allowing kotlin collection mapping cast to List"
)
@RestController
@RequestMapping(CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.ADMIN)
public open class AdminController(
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager
) : AdminApi, AuthorizingComponent {
    public companion object {
        private val logger = LoggerFactory.getLogger(AdminController::class.java)!!
    }

    @Inject
    private lateinit var hazelcast: HazelcastInstance

    @Inject
    private lateinit var appDataUploadService: AppDataUploadService

    @Inject
    private lateinit var auditService: AuditService

    // reason: boundary catch — any failure type from the move job is audited as failed then rethrown so the admin action is recorded
    @Suppress("TooGenericExceptionCaught")
    @Timed
    @GetMapping(value = [EVENT_STORAGE])
    override fun moveToEventStorage() {
        ensureAdminAccess()
        try {
            appDataUploadService.moveToEventStorage()
            auditService.logWithContext {
                action(AuditAction.JOB_CREATED)
                resourceType("EventStorage")
                success(true)
            }
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.JOB_CREATED)
                resourceType("EventStorage")
                failed(ex.message ?: "Move to event storage failed")
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(value = [RELOAD_CACHE])
    @SuppressFBWarnings("NP_ALWAYS_NULL", justification = "Issue with spotbugs handling of Kotlin")
    override fun reloadCache() {
        ensureAdminAccess()
        HazelcastMap.values().forEach {
            logger.info("Reloading map $it")
            try {
                it.getMap(hazelcast).loadAll(true)
            } catch (e: IllegalArgumentException) {
                logger.error("Unable to reload map $it", e)
            }
        }
        auditService.logWithContext {
            action(AuditAction.CONFIGURATION_CHANGE)
            resourceType("Cache")
            success(true)
            additionalData(mapOf("scope" to "all"))
        }
    }

    // reason: boundary catch — any cache-reload failure type is audited as failed then rethrown so the admin action is recorded
    @Suppress("TooGenericExceptionCaught")
    @Timed
    @GetMapping(value = [RELOAD_CACHE + NAME_PATH])
    override fun reloadCache(@PathVariable(NAME) name: String) {
        ensureAdminAccess()
        try {
            HazelcastMap.valueOf(name).getMap(hazelcast).loadAll(true)
            auditService.logWithContext {
                action(AuditAction.CONFIGURATION_CHANGE)
                resourceType("Cache")
                success(true)
                additionalData(mapOf("cacheName" to name))
            }
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditAction.CONFIGURATION_CHANGE)
                resourceType("Cache")
                failed(ex.message ?: "Cache reload failed")
                additionalData(mapOf("cacheName" to name))
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(value = [PRINCIPALS + ID_PATH])
    override fun getUserPrincipals(@PathVariable(ID) principalId: String): Set<Principal> {
        ensureAdminAccess()
        val principals = Principals.getUserPrincipals(principalId)
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("Principal")
            success(true)
            additionalData(mapOf("targetPrincipalId" to principalId, "principalCount" to principals.size))
        }
        return principals
    }

    @Timed
    @GetMapping(value = [PRINCIPALS])
    override fun getCurrentUserPrincipals(): Set<Principal> {
        ensureAuthenticated()
        val principals = Principals.getCurrentPrincipals()
        auditService.logWithContext {
            action(AuditAction.VIEW)
            resourceType("Principal")
            success(true)
            additionalData(mapOf("endpoint" to "getCurrentUserPrincipals"))
        }
        return principals
    }

}
