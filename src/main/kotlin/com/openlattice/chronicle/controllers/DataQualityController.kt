package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.services.quality.DataQualityService
import com.openlattice.chronicle.study.DataQualityDashboard
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class DataQualityController(
    private val dataQualityService: DataQualityService,
) {

    public companion object {
        public const val QUALITY_PATH: String = "/quality"
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + QUALITY_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    public fun getDataQualityDashboard(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
    ): DataQualityDashboard {
        return dataQualityService.getDataQualityDashboard(studyId)
    }
}
