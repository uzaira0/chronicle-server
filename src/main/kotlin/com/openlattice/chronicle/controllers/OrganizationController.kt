package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.authorization.READ_PERMISSION
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.ids.IdConstants
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.organizations.OrganizationApi
import com.openlattice.chronicle.organizations.OrganizationApi.Companion.CONTROLLER
import com.openlattice.chronicle.organizations.OrganizationApi.Companion.ORGANIZATION_ID
import com.openlattice.chronicle.organizations.OrganizationApi.Companion.ORGANIZATION_ID_PATH
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.stream.Collectors
import jakarta.inject.Inject

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@RestController
@RequestMapping(CONTROLLER)
public open class OrganizationController @Inject constructor(
    // reason: DI constructor dependency retained for wiring/parity with sibling controllers; not referenced in this controller's current handlers
    @Suppress("UnusedPrivateProperty") private val storageResolver: StorageResolver,
    // reason: DI constructor dependency retained for wiring/parity with sibling controllers; not referenced in this controller's current handlers
    @Suppress("UnusedPrivateProperty") private val idGenerationService: HazelcastIdGenerationService,
    override val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager
) : AuthorizingComponent, OrganizationApi {

    public companion object {
        private val logger = LoggerFactory.getLogger(OrganizationController::class.java)!!
    }

    @Inject
    private lateinit var chronicleOrganizationService: ChronicleOrganizationService

    @Timed
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun createOrganization(@Valid @RequestBody organization: Organization): UUID {
        ensureAuthenticated()
        logger.info("Creating organization with title ${organization.title}")
        return chronicleOrganizationService.createOrganization(Principals.getCurrentUser(),organization)
    }

    @Timed
    @GetMapping(
        path = [ORGANIZATION_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getOrganization(@PathVariable(ORGANIZATION_ID) organizationId: UUID): Organization {
        ensureReadAccess(AclKey(organizationId))
        return chronicleOrganizationService.getOrganization(organizationId)
    }

    @Timed
    @GetMapping(
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun getOrganizations(): Iterable<Organization> {
        ensureAuthenticated()
        val organizationIds = getAccessibleObjects(SecurableObjectType.Organization, READ_PERMISSION)
            .collect(Collectors.toSet())
            .mapNotNull { it?.firstOrNull() }
            .filter { it != IdConstants.SYSTEM_ORGANIZATION.id }
        return chronicleOrganizationService.getOrganizations(organizationIds)
    }


    

    

    

    

    

    

}
