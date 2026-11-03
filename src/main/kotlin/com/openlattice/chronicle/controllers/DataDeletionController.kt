package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.services.delete.DataDeletionOperation
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import com.openlattice.chronicle.util.PaginationDefaults
import java.util.UUID

/** Narrow owner/DELETE_DATA control surface for the durable deletion ledger. */
@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
public open class DataDeletionController(
    private val dataDeletionOrchestrator: DataDeletionOrchestrator,
) {
    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @GetMapping(path = ["/{studyId}/deletions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun listOperations(
        @PathVariable studyId: UUID,
        @RequestParam(defaultValue = "${PaginationDefaults.DEFAULT_PAGE_SIZE}") limit: Int,
        @RequestParam(defaultValue = "${PaginationDefaults.DEFAULT_OFFSET}") offset: Int,
    ): List<DataDeletionOperation> = dataDeletionOrchestrator.listOperations(
        studyId, PaginationDefaults.clampLimit(limit), PaginationDefaults.clampOffset(offset),
    )

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

    /** The owner's explicit command that physically erases a requested deletion, now. */
    @RequiresStudyAccess(StudyPermission.DELETE_DATA)
    @PostMapping(path = ["/{studyId}/deletions/{operationId}/erase"], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun eraseNow(
        @PathVariable studyId: UUID,
        @PathVariable operationId: UUID,
    ): DataDeletionOperation = dataDeletionOrchestrator.eraseNow(operationId, studyId, Principals.getCurrentUser().id)
}
