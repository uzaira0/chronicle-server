package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.services.studies.ParticipantProcessingService
import com.openlattice.chronicle.study.ParticipantProcessingApi
import com.openlattice.chronicle.study.ParticipantProcessingBlockRequest
import com.openlattice.chronicle.study.StudyApi
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val INVALID_STATUS_REVISION = "error.participant.invalidStatusRevision"

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
public open class ParticipantProcessingController(private val service: ParticipantProcessingService) : ParticipantProcessingApi {
    @RequiresStudyAccess(StudyPermission.MANAGE_PARTICIPANTS)
    @PostMapping(ParticipantProcessingApi.PATH)
    override fun blockParticipantProcessing(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(StudyApi.PARTICIPANT_ID) participantId: String,
        @Valid @RequestBody request: ParticipantProcessingBlockRequest,
        @RequestHeader(EditRevisionApi.IF_MATCH, required = false) revision: String?,
    ): Unit = service.block(studyId, participantId, request.reason, parseRevisionPrecondition(revision, INVALID_STATUS_REVISION))

    @RequiresStudyAccess(StudyPermission.MANAGE_PARTICIPANTS)
    @DeleteMapping(ParticipantProcessingApi.PATH)
    override fun unblockParticipantProcessing(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(StudyApi.PARTICIPANT_ID) participantId: String,
        @RequestHeader(EditRevisionApi.IF_MATCH, required = false) revision: String?,
    ): Unit = service.unblock(studyId, participantId, parseRevisionPrecondition(revision, INVALID_STATUS_REVISION))
}
