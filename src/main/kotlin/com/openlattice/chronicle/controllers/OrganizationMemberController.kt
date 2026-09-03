package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.annotations.RequiresOrganizationAccess
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.organizations.OrganizationApi
import com.openlattice.chronicle.organizations.OrganizationMember
import com.openlattice.chronicle.organizations.OrganizationMemberApi
import com.openlattice.chronicle.organizations.OrganizationQuotas
import com.openlattice.chronicle.organizations.OrganizationRole
import com.openlattice.chronicle.services.organizations.OrganizationMemberService
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [OrganizationMemberApi.ORGANIZATION_BASE, OrganizationApi.CONTROLLER])
@Timed
@PreAuthorize("isAuthenticated()")
@RateLimit(type = RateLimitType.DEFAULT)
public open class OrganizationMemberController(
    private val memberService: OrganizationMemberService
) : OrganizationMemberApi {

    @RequiresOrganizationAccess(minRole = OrganizationRole.ADMIN)
    @PostMapping(
        path = [OrganizationMemberApi.ORGANIZATION_ID_PATH + OrganizationMemberApi.MEMBERS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun addMember(
        @PathVariable("organizationId") organizationId: UUID,
        @RequestBody @Valid member: OrganizationMember
    ): OK {
        memberService.addMember(organizationId, member)
        return OK.ok
    }

    @RequiresOrganizationAccess(minRole = OrganizationRole.VIEWER)
    @GetMapping(
        path = [OrganizationMemberApi.ORGANIZATION_ID_PATH + OrganizationMemberApi.MEMBERS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun listMembers(@PathVariable("organizationId") organizationId: UUID): List<OrganizationMember> {
        return memberService.listMembers(organizationId)
    }

    @RequiresOrganizationAccess(minRole = OrganizationRole.ADMIN)
    @DeleteMapping(
        path = [OrganizationMemberApi.ORGANIZATION_ID_PATH + OrganizationMemberApi.MEMBERS_PATH + OrganizationMemberApi.USER_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun removeMember(
        @PathVariable("organizationId") organizationId: UUID,
        @PathVariable(OrganizationMemberApi.USER_ID) userId: String
    ): OK {
        memberService.removeMember(organizationId, userId)
        return OK.ok
    }

    @RequiresOrganizationAccess(minRole = OrganizationRole.VIEWER)
    @GetMapping(
        path = [OrganizationMemberApi.ORGANIZATION_ID_PATH + OrganizationMemberApi.QUOTAS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getQuotas(@PathVariable("organizationId") organizationId: UUID): OrganizationQuotas {
        return memberService.getQuotas(organizationId)
    }

    @RequiresOrganizationAccess(minRole = OrganizationRole.OWNER)
    @PutMapping(
        path = [OrganizationMemberApi.ORGANIZATION_ID_PATH + OrganizationMemberApi.QUOTAS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateQuotas(
        @PathVariable("organizationId") organizationId: UUID,
        @RequestBody @Valid quotas: OrganizationQuotas
    ): OrganizationQuotas {
        return memberService.updateQuotas(organizationId, quotas)
    }
}
