package com.openlattice.chronicle.filters

import com.openlattice.chronicle.generated.BrowserDownloadContracts
import com.openlattice.chronicle.controllers.SynchronousExportRequest
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.util.parsedPath
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.util.pattern.PathPatternParser

/** The browser's native form downloads: each streams one export as the response to a CSRF-checked POST. */
public object NativeExportDownload {
    private val formRoutes = paths(StudyApi.CONTROLLER,
        StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANTS_PATH + StudyApi.DATA_PATH) +
        paths(TimeUseDiaryApi.CONTROLLER, TimeUseDiaryApi.STUDY_ID_PATH + TimeUseDiaryApi.DATA_PATH) +
        paths(TimeUseDiaryApi.CONTROLLER,
            TimeUseDiaryApi.STUDY_ID_PATH + TimeUseDiaryApi.PARTICIPANTS_PATH + TimeUseDiaryApi.DATA_PATH) +
        paths(SurveyApi.CONTROLLER,
            SurveyApi.STUDY_ID_PATH + SurveyApi.QUESTIONNAIRE_PATH + SurveyApi.QUESTIONNAIRE_ID_PATH + SurveyApi.DATA_PATH)

    private fun paths(controller: String, suffix: String) = listOf(controller, "/api/web/${controller.substringAfterLast('/')}").flatMap { base ->
        listOf(base, StudyApi.SERVICE + base).map { prefix ->
            PathPatternParser.defaultInstance.parse(prefix + suffix)
        }
    }

    internal fun allowsMultipleValues(request: HttpServletRequest, parameter: String): Boolean {
        if (request.method !in setOf("GET", "POST") ||
            parameter !in setOf(StudyApi.PARTICIPANT_ID, SynchronousExportRequest::sensorTypes.name)) return false
        val path = request.parsedPath() ?: return false
        return formRoutes.any { it.matches(path) }
    }

    public fun redirectForExpiredForm(request: HttpServletRequest, response: HttpServletResponse): Boolean {
        if (request.method != "POST" ||
            request.contentType?.substringBefore(';') != "application/x-www-form-urlencoded") return false
        val path = request.parsedPath() ?: return false
        if (formRoutes.none { it.matches(path) }) return false
        response.status = HttpServletResponse.SC_SEE_OTHER
        // The router path is relative to the dashboard's base, which is the API's /chronicle prefix when present.
        val base = if (request.requestURI.startsWith(StudyApi.SERVICE + "/")) StudyApi.SERVICE else ""
        response.setHeader("Location", base + BrowserDownloadContracts.BROWSER_DOWNLOAD_REAUTHENTICATION_PATH)
        response.setHeader("Cache-Control", "no-store")
        return true
    }
}
