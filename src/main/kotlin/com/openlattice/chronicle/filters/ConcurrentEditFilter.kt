package com.openlattice.chronicle.filters

import com.openlattice.chronicle.storage.withTransaction
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.studies.StudySnapshotRepository
import com.openlattice.chronicle.services.surveys.QuestionnaireRepository
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.util.normalizeEntityTag
import com.openlattice.chronicle.util.parsedPath
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.pattern.PathPatternParser
import org.springframework.http.HttpStatus

/** Strong content revisions, checked under the same row lock and transaction as the write. */
internal class ConcurrentEditFilter(
    private val storage: StorageResolver,
    private val authorization: StudyAuthorizationService,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        guarded(request, response, chain)
    }

    internal fun guarded(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.parsedPath() ?: return chain.doFilter(request, response)
        val annotation = ANNOTATIONS.matchAndExtract(path)
        val questionnaire = QUESTIONNAIRE.matchAndExtract(path)
        val match = annotation ?: questionnaire ?: return chain.doFilter(request, response)
        if (request.method !in listOf("GET", "HEAD", "PATCH")) return chain.doFilter(request, response)
        val study = runCatching { UUID.fromString(match.uriVariables.getValue(StudyApi.STUDY_ID).trim()) }.getOrNull()
            ?: return chain.doFilter(request, response)
        val participant = annotation?.uriVariables?.getValue(StudyApi.PARTICIPANT_ID)
        val questionnaireId = questionnaire?.let {
            runCatching { UUID.fromString(it.uriVariables.getValue(SurveyApi.QUESTIONNAIRE_ID).trim()) }.getOrNull()
                ?: return chain.doFilter(request, response)
        }
        if (permitsParticipantQuestionnaireRead(request, study, questionnaireId)) return chain.doFilter(request, response)
        if (!authorizeResearcherSnapshot(request, response, study)) return
        val pool = storage.getPlatformStorage()
        pool.connection.use { connection ->
            connection.withTransaction {
                val json = if (participant != null) {
                    StudySnapshotRepository(connection).loadLockedParticipantAnnotations(study, participant)
                } else {
                    QuestionnaireRepository(connection).loadSnapshot(
                        studyId = study, questionnaireId = requireNotNull(questionnaireId),
                    )
                }
                if (json == null) {
                    writeFilterError(response, HttpStatus.NOT_FOUND.value(), "Resource not found")
                    connection.rollback(); return@withTransaction
                }
                val revision = revision(json)
                response.setHeader("ETag", revision)
                if (request.method == "PATCH" && !matchesRevision(request, response, revision)) {
                    connection.rollback(); return@withTransaction
                }
                if (request.method == "HEAD") {
                    connection.commit()
                    response.status = HttpStatus.NO_CONTENT.value()
                } else {
                    if (request.method == "PATCH") response.setHeader("ETag", null)
                    PinnedPlatformConnection.committing(pool, connection, response) { buffered ->
                        chain.doFilter(request, buffered)
                    }
                }
            }
        }
    }

    private fun matchesRevision(request: HttpServletRequest, response: HttpServletResponse, revision: String): Boolean {
        val expected = request.getHeader("If-Match")
        val failure = when {
            expected == null -> HttpStatus.PRECONDITION_REQUIRED
            normalizeEntityTag(expected) != normalizeEntityTag(revision) -> HttpStatus.PRECONDITION_FAILED
            else -> return true
        }
        writeFilterError(response, failure.value(), if (failure == HttpStatus.PRECONDITION_REQUIRED)
            "Reload before saving; a resource revision is required" else "This resource changed; reload and merge before saving")
        return false
    }

    private fun authorizeResearcherSnapshot(
        request: HttpServletRequest,
        response: HttpServletResponse,
        studyId: UUID,
    ): Boolean = try {
        authorization.requirePermission(
            studyId,
            if (request.method == "PATCH") StudyPermission.MODIFY_STUDY else StudyPermission.READ_STUDY,
        )
        true
    } catch (_: ForbiddenException) {
        writeFilterError(response, HttpStatus.FORBIDDEN.value(), "Access denied")
        false
    }

    /** A verified participant capability may read its questionnaire without researcher editing access. */
    private fun permitsParticipantQuestionnaireRead(
        request: HttpServletRequest,
        studyId: UUID,
        questionnaireId: UUID?,
    ): Boolean {
        if (request.method != "GET" || questionnaireId == null) return false
        val scope = request.getAttribute(ParticipantFormAccessFilter.REQUEST_SCOPE_ATTRIBUTE) as? ParticipantFormAccessScope
        return scope?.permits(
            ParticipantFormKind.QUESTIONNAIRE,
            studyId, null, questionnaireId,
        ) == true
    }

    internal companion object {
        private val ANNOTATIONS = PathPatternParser.defaultInstance.parse(
            StudyApi.BASE + StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANT_PATH +
                StudyApi.PARTICIPANT_ID_PATH + StudyApi.ANNOTATIONS_PATH,
        )
        private val QUESTIONNAIRE = PathPatternParser.defaultInstance.parse(
            SurveyApi.BASE + SurveyApi.STUDY_ID_PATH + SurveyApi.QUESTIONNAIRE_PATH + SurveyApi.QUESTIONNAIRE_ID_PATH,
        )
        val revisionReadPaths: Set<String> = setOf(ANNOTATIONS.patternString, QUESTIONNAIRE.patternString)
        fun revision(json: String): String = "\"" + MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + "\""
    }
}

/** The servlet transaction entry point publishes its response only after the owner commits. */
internal fun PinnedPlatformConnection.committing(
    pool: HikariDataSource,
    connection: Connection,
    response: HttpServletResponse,
    block: (HttpServletResponse) -> Unit,
) {
    check(owningConnection(pool) == null) { "A servlet response must own its outer transaction" }
    val buffered = ContentCachingResponseWrapper(response)
    committing(pool, connection, { buffered.status < HttpStatus.BAD_REQUEST.value() }) { block(buffered) }
    buffered.copyBodyToResponse()
}
