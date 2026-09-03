package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.dashboard.DashboardApi
import com.openlattice.chronicle.dashboard.StudyEvent
import com.openlattice.chronicle.dashboard.StudyRealtimeStats
import com.openlattice.chronicle.services.dashboard.DashboardService
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.STUDY)
public open class DashboardController(
    private val dashboardService: DashboardService
) : DashboardApi {

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + DashboardApi.DASHBOARD_PATH + DashboardApi.STATS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getStats(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): StudyRealtimeStats {
        return dashboardService.getStats(studyId)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + DashboardApi.DASHBOARD_PATH + DashboardApi.EVENTS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getRecentEvents(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestParam("limit", defaultValue = "100") limit: Int,
        @RequestParam("since", required = false) since: String?
    ): List<StudyEvent> {
        val sinceDate = since?.let { OffsetDateTime.parse(it) }
        return dashboardService.getRecentEvents(studyId, limit, sinceDate)
    }
}
