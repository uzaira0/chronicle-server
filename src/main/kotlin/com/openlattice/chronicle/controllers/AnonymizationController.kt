package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import jakarta.validation.Valid
import com.openlattice.chronicle.anonymization.AnonymizationApi
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.anonymization.AnonymizationConfig
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.services.anonymization.AnonymizationService
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class AnonymizationController(
    private val anonymizationService: AnonymizationService
) : AnonymizationApi {

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + AnonymizationApi.ANONYMIZATION_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getAnonymizationConfig(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): AnonymizationConfig {
        return anonymizationService.getConfig(studyId)
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PutMapping(
        path = [StudyApi.STUDY_ID_PATH + AnonymizationApi.ANONYMIZATION_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun updateAnonymizationConfig(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestBody @Valid config: AnonymizationConfig
    ): AnonymizationConfig {
        return anonymizationService.updateConfig(studyId, config)
    }
}
