package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.export.ExportApi
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.services.export.ExportService
import com.openlattice.chronicle.util.PaginationDefaults
import com.openlattice.chronicle.study.StudyApi
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping(StudyApi.CONTROLLER)
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class ExportController(
    private val exportService: ExportService
) : ExportApi {

    @RequiresStudyAccess(StudyPermission.EXPORT_DATA)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + ExportApi.EXPORT_PATH + ExportApi.ASYNC_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun createAsyncExport(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @Valid @RequestBody request: ExportRequest
    ): ExportJobInfo {
        val userId = Principals.getCurrentUser().id
        return exportService.createAsyncExport(studyId, userId, request)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + ExportApi.EXPORT_PATH + ExportApi.EXPORT_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getExportStatus(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(ExportApi.EXPORT_ID) exportId: UUID
    ): ExportJobInfo {
        return exportService.getExportStatus(studyId, exportId)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + ExportApi.EXPORT_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun listExports(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<ExportJobInfo> {
        val safeLimit = PaginationDefaults.clampLimit(limit)
        val safeOffset = PaginationDefaults.clampOffset(offset)
        return exportService.listExports(studyId, safeLimit, safeOffset)
    }

    override fun downloadExport(studyId: UUID, exportId: UUID) {
        // Retrofit-only interface method; browser downloads use downloadExportFile below
    }

    @RequiresStudyAccess(StudyPermission.EXPORT_DATA)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + ExportApi.EXPORT_PATH + ExportApi.EXPORT_ID_PATH + ExportApi.DOWNLOAD_PATH]
    )
    public fun downloadExportFile(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(ExportApi.EXPORT_ID) exportId: UUID,
        response: HttpServletResponse
    ) {
        val userId = Principals.getCurrentUser().id
        val jobInfo = exportService.getExportStatusForDownload(studyId, exportId, userId)

        check(jobInfo.status == ExportJobStatus.COMPLETED) { "Export is not completed" }

        val contentType = when (jobInfo.format) {
            ExportFormat.CSV -> "text/csv"
            ExportFormat.JSON -> "application/json"
            ExportFormat.EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        val extension = when (jobInfo.format) {
            ExportFormat.CSV -> "csv"
            ExportFormat.JSON -> "json"
            ExportFormat.EXCEL -> "xlsx"
        }

        response.contentType = contentType
        response.setHeader("Content-Disposition", "attachment; filename=\"export_${exportId}.$extension\"")

        exportService.streamExportFile(studyId, exportId, userId, response.outputStream)
        response.flushBuffer()
    }
}
