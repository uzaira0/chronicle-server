package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.RoleApi
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.authorization.RoleAssignment
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.services.roles.RoleService
import com.openlattice.chronicle.study.StudyApi
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class RoleController(
    private val roleService: RoleService,
) : RoleApi {

    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + RoleApi.ROLES_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiresStudyAccess(StudyPermission.MANAGE_PERMISSIONS)
    override fun assignRole(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid assignment: RoleAssignment,
    ) {
        roleService.assignRole(studyId, assignment)
    }

    @DeleteMapping(
        path = [StudyApi.STUDY_ID_PATH + RoleApi.ROLES_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @RequiresStudyAccess(StudyPermission.MANAGE_PERMISSIONS)
    override fun revokeRole(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid assignment: RoleAssignment,
    ) {
        roleService.revokeRole(studyId, assignment)
    }

    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + RoleApi.ROLES_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @RequiresStudyAccess(StudyPermission.MANAGE_PERMISSIONS)
    override fun listRoleAssignments(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
    ): List<RoleAssignment> {
        return roleService.listRoleAssignments(studyId)
    }
}
