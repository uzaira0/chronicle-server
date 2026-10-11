package com.openlattice.chronicle.filters

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.apikey.ApiKeyScope.ADMIN
import com.openlattice.chronicle.apikey.ApiKeyScope.READ_ONLY
import com.openlattice.chronicle.apikey.ApiKeyScope.WRITE
import com.openlattice.chronicle.study.StudyApi
import java.util.UUID

/**
 * The only routes a researcher (non-device) API key may call, each with the scope it needs. Anything not
 * listed is refused, so a new endpoint never becomes reachable with a key by accident; key management, ACLs,
 * roles, study creation/deletion, organizations, admin, principals and auth are deliberately absent.
 *
 * READ_ONLY reads the study, settings, participants and data, and creates and downloads export jobs;
 * WRITE adds participant, settings and questionnaire/survey changes; ADMIN adds participant deletion/purge.
 */
internal object ResearcherApiKeyRoutes {
    private const val UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private const val SEGMENT = "[^/]+"
    private const val STUDY = "/chronicle/v3/study"
    private const val SURVEY = "/chronicle/v3/survey"
    private const val TUD = "/chronicle/v3/time-use-diary"

    internal data class Match(val studyId: UUID, val scope: ApiKeyScope)

    private class Route(val method: String, base: String, suffix: String, val scope: ApiKeyScope) {
        val pattern = Regex("$base/($UUID_PATTERN)$suffix")
    }

    private val routes = listOf(
        Route("GET", STUDY, "", READ_ONLY),
        Route("GET", STUDY, "/settings", READ_ONLY),
        Route("GET", STUDY, "/settings/type/$SEGMENT", READ_ONLY),
        Route("GET", STUDY, "/participants", READ_ONLY),
        Route("GET", STUDY, "/participants/stats", READ_ONLY),
        Route("GET", STUDY, "/participants/data", READ_ONLY),
        Route("GET", STUDY, "/devices", READ_ONLY),
        Route("GET", STUDY, "/export", READ_ONLY),
        Route("POST", STUDY, "/export/async", READ_ONLY),
        Route("GET", STUDY, "/export/$UUID_PATTERN", READ_ONLY),
        Route("GET", STUDY, "/export/$UUID_PATTERN/download", READ_ONLY),
        Route("GET", SURVEY, "/filtered", READ_ONLY),
        Route("GET", SURVEY, "/questionnaire/$SEGMENT/data", READ_ONLY),
        Route("GET", TUD, "/data", READ_ONLY),
        Route("GET", TUD, "/participants/data", READ_ONLY),

        Route("POST", STUDY, "/participant", WRITE),
        Route("PATCH", STUDY, "/participant/$SEGMENT/status", WRITE),
        Route("PATCH", STUDY, "/participant/$SEGMENT/annotations", WRITE),
        Route("PATCH", STUDY, "/settings/type/$SEGMENT", WRITE),
        Route("PUT", STUDY, StudyApi.DATA_COLLECTION, WRITE),
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
    )

    /** The study and scope of a listed route, or null when no researcher key may call it. */
    fun match(method: String, requestUri: String): Match? {
        val upperMethod = method.uppercase()
        return routes.firstNotNullOfOrNull { route ->
            route.pattern.matchEntire(requestUri)
                ?.takeIf { route.method == upperMethod }
                ?.let { Match(UUID.fromString(it.groupValues[1]), route.scope) }
        }
    }
}
