package com.openlattice.chronicle.filters

import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.logWithContext
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.normalizeEntityTag
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.UUID

/** Strong content revisions, checked under the same row lock and transaction as the write. */
internal class ConcurrentEditFilter(
    private val storage: StorageResolver,
    private val authorization: StudyAuthorizationService,
    private val auditService: AuditService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (ANNOTATIONS.matches(request.requestURI.removePrefix("/chronicle")) || QUESTIONNAIRE.matches(request.requestURI.removePrefix(
            "/chronicle"))) guarded(request, response, chain)
        else chain.doFilter(request, response)
    }

    internal fun guarded(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix("/chronicle")
        val annotation = ANNOTATIONS.matchEntire(path)
        val questionnaire = QUESTIONNAIRE.matchEntire(path)
        val match = annotation ?: questionnaire ?: return chain.doFilter(request, response)
        if (request.method !in listOf("GET", "HEAD", "PATCH")) return chain.doFilter(request, response)
        val study = runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull()
            ?: return chain.doFilter(request, response)
        // ParticipantFormAccessFilter has already verified this capability and exact resource.
        // A participant questionnaire read is not a researcher editing snapshot.
        val formScope = request.getAttribute(ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE)
            as? com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
        if (request.method == "GET" && questionnaire != null && formScope?.permits(
                com.openlattice.chronicle.participantaccess.ParticipantFormKind.QUESTIONNAIRE,
                study, null, UUID.fromString(match.groupValues[2]),
            ) == true) return chain.doFilter(request, response)
        try {
            authorization.requirePermission(study, if (request.method == "PATCH") StudyPermission.MODIFY_STUDY else StudyPermission.READ_STUDY)
        } catch (denied: com.geekbeast.controllers.exceptions.ForbiddenException) {
            writeFilterError(response, 403, "Access denied")
            return
        }
        val pool = storage.getPlatformStorage()
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                val json = if (annotation != null) {
                    StudyService.loadLockedParticipantAnnotations(connection, study, match.groupValues[2])
                } else {
                    SurveysService.loadLockedQuestionnaireSnapshot(connection, study, UUID.fromString(match.groupValues[2]))
                }
                if (json == null) { writeFilterError(response, 404, "Resource not found"); connection.rollback(); return }
                val revision = revision(json)
                response.setHeader("ETag", revision)
                if (request.method == "PATCH") {
                    val expected = request.getHeader("If-Match")
                    if (expected == null) { writeFilterError(response, 428, "Reload before saving; a resource revision is required");
                        connection.rollback(); return }
                    if (normalizeEntityTag(expected) != normalizeEntityTag(revision)) { writeFilterError(response, 412,
                        "This resource changed; reload and merge before saving"); connection.rollback(); return }
                }
                if (annotation != null && request.method == "GET") {
                    response.status = 200
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write(json)
                    connection.commit()
                    auditService.logWithContext {
                        action(AuditAction.PARTICIPANT_DATA_ACCESS)
                        resourceType("ParticipantAnnotations")
                        studyId(study)
                        success(true)
                        accessedPHI(true)
                        phiFields(listOf("participantId", "participantNotes", "participantTags"))
                        additionalData(mapOf("participantId" to match.groupValues[2]))
                    }
                } else if (request.method == "HEAD") {
                    response.status = 204
                    connection.commit()
                } else {
                    if (request.method == "PATCH") response.setHeader("ETag", null)
                    PinnedPlatformConnection.committing(pool, connection, { response.status < 400 }) {
                        chain.doFilter(request, response)
                    }
                }
            } catch (failure: Exception) { connection.rollback(); throw failure }
        }
    }

    internal companion object {
        private val ANNOTATIONS = Regex("/v3/study/([0-9a-fA-F-]+)/participant/([^/]+)/annotations")
        private val QUESTIONNAIRE = Regex("/v3/survey/([0-9a-fA-F-]+)/questionnaire/([0-9a-fA-F-]+)")
        fun revision(json: String): String = "\"" + MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + "\""
    }
}
