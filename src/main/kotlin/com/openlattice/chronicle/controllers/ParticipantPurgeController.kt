package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import jakarta.validation.Valid
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.study.ParticipantDataPurgeSummary
import com.openlattice.chronicle.study.ParticipantPurgeApi
import com.openlattice.chronicle.study.ParticipantPurgeRequest
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
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
public open class ParticipantPurgeController(
    private val purgeService: ParticipantPurgeService,
) : ParticipantPurgeApi {

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @GetMapping(
        path = [
            StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANTS_PATH + StudyApi.PARTICIPANT_ID_PATH +
                ParticipantPurgeApi.PURGE_PATH + ParticipantPurgeApi.PREVIEW_PATH
        ],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun previewParticipantPurge(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(StudyApi.PARTICIPANT_ID) participantId: String,
    ): ParticipantDataPurgeSummary {
        return purgeService.previewPurge(studyId, participantId)
    }

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANTS_PATH + ParticipantPurgeApi.PURGE_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun executeParticipantPurge(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid request: ParticipantPurgeRequest,
    ): Iterable<UUID> {
        return purgeService.executePurge(studyId, request)
    }
}
