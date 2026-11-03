package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.services.download.StoredExportTypes
import com.openlattice.chronicle.services.download.StudyExportAvailability
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Which data types a study can export; the export itself is the synchronous participants-data stream. */
@RestController
@RequestMapping(StudyApi.CONTROLLER)
@Timed
public open class ExportController(private val storedTypes: StoredExportTypes) {
    @RequiresStudyAccess(StudyPermission.EXPORT_DATA)
    @GetMapping(path = ["/{studyId}/export/availability"], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun exportAvailability(@PathVariable studyId: UUID): StudyExportAvailability = storedTypes.availability(studyId)
}
