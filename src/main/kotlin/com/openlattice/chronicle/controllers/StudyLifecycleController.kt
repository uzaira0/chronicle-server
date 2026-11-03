package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import jakarta.validation.Valid
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyLifecycleApi
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

private const val INVALID_STUDY_REVISION = "error.study.invalidSettingsRevision"

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
public open class StudyLifecycleController(
    private val lifecycleService: StudyLifecycleService
) : StudyLifecycleApi {

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.ARCHIVE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun archiveStudy(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestHeader(EditRevisionApi.IF_MATCH, required = false) revision: String?,
    ): OK {
        val userId = Principals.getCurrentUser().id
        lifecycleService.archiveStudy(studyId, userId, parseRevisionPrecondition(revision, INVALID_STUDY_REVISION))
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.UNARCHIVE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun unarchiveStudy(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestHeader(EditRevisionApi.IF_MATCH, required = false) revision: String?,
    ): OK {
        val userId = Principals.getCurrentUser().id
        lifecycleService.unarchiveStudy(studyId, userId, parseRevisionPrecondition(revision, INVALID_STUDY_REVISION))
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.CLONE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun cloneStudy(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid request: StudyCloneRequest
    ): UUID {
        val userId = Principals.getCurrentUser().id
        return lifecycleService.cloneStudy(studyId, userId, request)
    }

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @DeleteMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.SCHEDULE_DELETE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun scheduleStudyDeletion(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): OK {
        val userId = Principals.getCurrentUser().id
        lifecycleService.requestStudyDeletion(studyId, userId)
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.SCHEDULE_DELETE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun cancelScheduledDeletion(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): OK {
        val userId = Principals.getCurrentUser().id
        lifecycleService.cancelScheduledDeletion(studyId, userId)
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyLifecycleApi.LIFECYCLE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStudyLifecycleStatus(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): StudyLifecycleStatus {
        return lifecycleService.getLifecycleStatus(studyId)
    }
}
