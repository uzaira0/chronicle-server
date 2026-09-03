package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.pipeline.PipelineApi
import com.openlattice.chronicle.pipeline.PipelineRunInfo
import com.openlattice.chronicle.pipeline.PipelineService
import com.openlattice.chronicle.study.StudyApi
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class PipelineController(
    private val pipelineService: PipelineService,
) : PipelineApi {

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + PipelineApi.PIPELINE_PATH + PipelineApi.TRIGGER_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun triggerPipeline(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
    ): PipelineRunInfo {
        return pipelineService.triggerPipeline(studyId)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + PipelineApi.PIPELINE_PATH + PipelineApi.RUNS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun listPipelineRuns(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
    ): List<PipelineRunInfo> {
        return pipelineService.listPipelineRuns(studyId)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + PipelineApi.PIPELINE_PATH + PipelineApi.RUNS_PATH + PipelineApi.RUN_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getPipelineRun(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(PipelineApi.RUN_ID) runId: UUID,
    ): PipelineRunInfo {
        return pipelineService.getPipelineRun(studyId, runId)
    }
}
