package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.services.delete.DataDeletionOperation
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.study.StudyApi
import jakarta.validation.Valid
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

public data class PlaceRetentionHoldRequest(
    @field:Size(min = 10, max = 2_000)
    val reason: String,
    @field:Future
    val reviewAt: OffsetDateTime,
)

public data class ReleaseRetentionHoldRequest(
    @field:Size(min = 10, max = 2_000)
    val reason: String,
)

public data class RetentionHoldResponse(val holdId: UUID)

/** Narrow owner/DELETE_DATA control surface for the durable deletion ledger. */
@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class DataDeletionController(
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
) {
    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @GetMapping(
        path = ["/{studyId}/deletions/{operationId}"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun getOperation(
        @PathVariable studyId: UUID,
        @PathVariable operationId: UUID,
    ): DataDeletionOperation = dataDeletionOrchestrator.getOperation(operationId).also {
        require(it.studyId == studyId) { "Deletion operation does not belong to this study" }
    }

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @PostMapping(
        path = ["/{studyId}/deletions/{operationId}/holds"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun placeHold(
        @PathVariable studyId: UUID,
        @PathVariable operationId: UUID,
        @Valid @RequestBody request: PlaceRetentionHoldRequest,
    ): RetentionHoldResponse = RetentionHoldResponse(
        dataDeletionOrchestrator.placeHold(
            operationId = operationId,
            studyId = studyId,
            reason = request.reason,
            createdBy = Principals.getCurrentUser().id,
            reviewAt = request.reviewAt,
        )
    )

    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @PostMapping(
        path = ["/{studyId}/deletions/{operationId}/holds/{holdId}/release"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    public fun releaseHold(
        @PathVariable studyId: UUID,
        @PathVariable operationId: UUID,
        @PathVariable holdId: UUID,
        @Valid @RequestBody request: ReleaseRetentionHoldRequest,
    ): OK {
        dataDeletionOrchestrator.releaseHold(
            operationId = operationId,
            holdId = holdId,
            studyId = studyId,
            releasedBy = Principals.getCurrentUser().id,
            releaseReason = request.reason,
        )
        return OK.ok
    }
}
