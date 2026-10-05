package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
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
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (ANNOTATIONS.matches(request.requestURI.removePrefix("/chronicle")) || QUESTIONNAIRE.matches(request.requestURI.removePrefix("/chronicle"))) guarded(request, response, chain)
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
            error(response, 403, "Access denied")
            return
        }
        val pool = storage.getPlatformStorage()
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                val sql = if (annotation != null) {
                    "SELECT jsonb_build_object('participantNotes', participant_notes, 'participantTags', participant_tags)::text " +
                        "FROM study_participants WHERE study_id = ? AND participant_id = ? FOR UPDATE"
                } else {
                    "SELECT jsonb_build_object('title', title, 'description', description, 'questions', questions, " +
                        "'active', active, 'recurrenceRule', recurrence_rule)::text " +
                        "FROM questionnaires WHERE study_id = ? AND questionnaire_id = ? FOR UPDATE"
                }
                val json = connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, study)
                    if (annotation != null) statement.setString(2, match.groupValues[2])
                    else statement.setObject(2, UUID.fromString(match.groupValues[2]))
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
                if (json == null) { error(response, 404, "Resource not found"); connection.rollback(); return }
                val revision = revision(json)
                response.setHeader("ETag", revision)
                if (request.method == "PATCH") {
                    val expected = request.getHeader("If-Match")
                    if (expected == null) { error(response, 428, "Reload before saving; a resource revision is required"); connection.rollback(); return }
                    if (expected != revision) { error(response, 412, "This resource changed; reload and merge before saving"); connection.rollback(); return }
                }
                if (annotation != null && request.method == "GET") {
                    response.status = 200
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write(json)
                    connection.commit()
                } else if (request.method == "HEAD") {
                    response.status = 204
                    connection.commit()
                } else {
                    PinnedPlatformConnection.pinning(pool, connection) { chain.doFilter(request, response) }
                    if (response.status < 400) connection.commit() else connection.rollback()
                }
            } catch (failure: Exception) { connection.rollback(); throw failure }
        }
    }

    private fun error(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write("""{"status":$status,"message":"$message"}""")
    }

    internal companion object {
        private val ANNOTATIONS = Regex("/v3/study/([0-9a-fA-F-]+)/participant/([^/]+)/annotations")
        private val QUESTIONNAIRE = Regex("/v3/survey/([0-9a-fA-F-]+)/questionnaire/([0-9a-fA-F-]+)")
        fun revision(json: String): String = "\"" + MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + "\""
    }
}
