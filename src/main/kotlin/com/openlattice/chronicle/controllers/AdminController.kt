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
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.services.upload.AppDataUploadService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(CONTROLLER)
public open class AdminController(
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    private val hazelcast: HazelcastInstance,
    private val appDataUploadService: AppDataUploadService,
    private val auditService: AuditService,
) : AdminApi, AuthorizingComponent {


    // reason: boundary catch — any failure type from the move job is audited as failed then rethrown so the admin action is recorded
    @Suppress("TooGenericExceptionCaught")
    @Timed
    @GetMapping(value = [EVENT_STORAGE])
    override fun moveToEventStorage() {
        ensureAdminAccess()
        try {
            appDataUploadService.moveToEventStorage()
            auditService.logWithContext {
                action(AuditEventType.JOB_CREATED)
                resourceType("EventStorage")
                success(true)
            }
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.JOB_CREATED)
                resourceType("EventStorage")
                success(false)
            }
            throw ex
        }
    }

    @Timed
    @GetMapping(value = [RELOAD_CACHE])
    override fun reloadCache() {
        ensureAdminAccess()
        HazelcastMap.values().filter { hazelcast.config.findMapConfig(it.name).mapStoreConfig?.isEnabled == true }
            .forEach { reloadCache(it.name) }
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
                action(AuditEventType.CONFIGURATION_CHANGE)
                resourceType("Cache")
                success(true)
                additionalData(mapOf("cacheName" to name))
            }
        } catch (ex: Exception) {
            auditService.logWithContext {
                action(AuditEventType.CONFIGURATION_CHANGE)
                resourceType("Cache")
                success(false)
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
            action(AuditEventType.VIEW)
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
            action(AuditEventType.VIEW)
            resourceType("Principal")
            success(true)
            additionalData(mapOf("endpoint" to "getCurrentUserPrincipals"))
        }
        return principals
    }

}
