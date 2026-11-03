package com.openlattice.chronicle.filters

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.apikey.ApiKeyScope.ADMIN
import com.openlattice.chronicle.apikey.ApiKeyScope.READ_ONLY
import com.openlattice.chronicle.apikey.ApiKeyScope.WRITE
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.ParticipantProcessingApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.util.parsedPath
import java.util.UUID
import org.springframework.web.util.pattern.PathPatternParser
import org.springframework.web.util.pattern.PathPattern

/**
 * The only routes a researcher (non-device) API key may call, each with the scope it needs. Anything not
 * listed is refused, so a new endpoint never becomes reachable with a key by accident; key management, ACLs,
 * roles, study creation/deletion, organizations, admin, principals and auth are deliberately absent.
 *
 * READ_ONLY reads the study, settings, participants and data, and creates and downloads export jobs;
 * WRITE adds participant, settings and questionnaire/survey changes; ADMIN adds participant deletion/purge.
 */
internal object ResearcherApiKeyRoutes {
    private const val RESOURCE_ID = "resourceId"
    private const val UUID_PATTERN = "{$RESOURCE_ID}"
    private const val SEGMENT = "{routeValue}"
    private const val STUDY = StudyApi.BASE
    private const val SURVEY = SurveyApi.BASE
    private const val TUD = TimeUseDiaryApi.BASE

    internal data class Match(val studyId: UUID, val scope: ApiKeyScope)

    private class Route(val method: String, val pattern: PathPattern, val scope: ApiKeyScope) {
        constructor(method: String, base: String, suffix: String, scope: ApiKeyScope) :
            this(method, PathPatternParser.defaultInstance.parse(base + StudyApi.STUDY_ID_PATH + suffix), scope)
    }

    private val participantProcessingPath = ParticipantProcessingApi.PATH.removePrefix(StudyApi.STUDY_ID_PATH)
    private val routes = listOf(
        Route("GET", STUDY, "", READ_ONLY),
        Route("GET", STUDY, "/settings", READ_ONLY),
        Route("GET", STUDY, "/settings/type/$SEGMENT", READ_ONLY),
        Route("GET", STUDY, "/participants", READ_ONLY),
        Route("GET", STUDY, "/participants/stats", READ_ONLY),
        Route("GET", STUDY, "/participants/data", READ_ONLY),
        Route("GET", STUDY, "/devices", READ_ONLY),
        Route("GET", SURVEY, "/filtered", READ_ONLY),
        Route("GET", SURVEY, "/questionnaire/$SEGMENT/data", READ_ONLY),
        Route("GET", TUD, "/data", READ_ONLY),
        Route("GET", TUD, "/participants/data", READ_ONLY),

        Route("POST", STUDY, "/participant", WRITE),
        Route("PATCH", STUDY, "/participant/$SEGMENT/status", WRITE),
        Route("PATCH", STUDY, "/participant/$SEGMENT/annotations", WRITE),
        Route("POST", STUDY, participantProcessingPath, WRITE),
        Route("DELETE", STUDY, participantProcessingPath, WRITE),
        Route("PATCH", STUDY, "/settings/type/$SEGMENT", WRITE),
        Route("POST", SURVEY, "/questionnaire", WRITE),
        Route("PATCH", SURVEY, "/questionnaire/$SEGMENT", WRITE),
        Route("PUT", SURVEY, "/filtered", WRITE),
        Route("PATCH", SURVEY, "/filtered", WRITE),
        Route("DELETE", SURVEY, "/filtered", WRITE),

        Route("DELETE", STUDY, "/participants", ADMIN),
        Route("GET", STUDY, "/participants/$SEGMENT/purge/preview", ADMIN),
        Route("POST", STUDY, "/participants/purge", ADMIN),
        Route("GET", STUDY, "/deletions/$UUID_PATTERN", ADMIN),
        Route("DELETE", SURVEY, "/questionnaire/$SEGMENT", ADMIN),
    ) + ConcurrentEditFilter.revisionReadPaths.flatMap { path ->
        val pattern = PathPatternParser.defaultInstance.parse(path)
        listOf(Route("GET", pattern, WRITE), Route("HEAD", pattern, WRITE))
    }

    /** The study and scope of a listed route, or null when no researcher key may call it. */
    fun match(method: String, requestUri: String): Match? {
        val path = requestUri.parsedPath() ?: return null
        val upperMethod = method.uppercase()
        return routes.firstNotNullOfOrNull { route ->
            route.pattern.matchAndExtract(path)
                ?.takeIf { route.method == upperMethod }
                ?.let { match ->
                    runCatching {
                        match.uriVariables[RESOURCE_ID]?.let { UUID.fromString(it.trim()) }
                        Match(UUID.fromString(match.uriVariables.getValue(StudyApi.STUDY_ID).trim()), route.scope)
                    }.getOrNull()
                }
        }
    }
}
