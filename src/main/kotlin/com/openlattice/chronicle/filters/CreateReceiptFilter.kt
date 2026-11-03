package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.candidates.CandidateApi
import com.openlattice.chronicle.organizations.OrganizationApi
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.services.receipts.CreateReceiptConflictException
import com.openlattice.chronicle.services.receipts.CreateReceiptResponse
import com.openlattice.chronicle.services.receipts.CreateReceiptOwner
import com.openlattice.chronicle.services.receipts.ResearcherCreateReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.StudyApi
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.UrlPathHelper
import java.io.ByteArrayInputStream
import java.util.UUID

/** Unique durable receipts and the mutation share one transaction, including lost HTTP responses. */
internal class CreateReceiptFilter(
    storage: StorageResolver,
    private val authorization: StudyAuthorizationService,
    private val secret: () -> String?,
) : OncePerRequestFilter() {
    private val receipts = ResearcherCreateReceiptService(storage)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        guarded(request, response, chain)
    }

    internal fun guarded(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method != "POST") return chain.doFilter(request, response)
        val route = createRoute(UrlPathHelper.defaultInstance.getRequestUri(request)
            .removePrefix(StudyApi.SERVICE).trimEnd('/')) ?: return chain.doFilter(request, response)
        val key = request.getHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER)
        if (key == null) {
            writeFilterError(response, 428, "Idempotency-Key is required; keep the same key when retrying this create")
            return
        }
        if (key.length !in 1..128 || !key.matches(Regex("[A-Za-z0-9_-]+"))) {
            writeFilterError(response, 400, "Idempotency-Key must contain 1–128 letters, digits, underscores or hyphens")
            return
        }
        val encryptionSecret = secret()?.takeIf { it.length >= 32 }
        if (encryptionSecret == null) { writeFilterError(response, 503, "Create receipt encryption is unavailable"); return }
        val study = route.studyId
        if (study != null) {
            try {
                authorization.requirePermission(study, StudyPermission.MODIFY_STUDY)
            } catch (denied: com.geekbeast.controllers.exceptions.ForbiddenException) {
                writeFilterError(response, 403, "Access denied")
                return
            }
        }
        val owner = currentReceiptOwner()
        if (owner == null) {
            writeFilterError(response, 403, "Access denied")
            return
        }
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) { writeFilterError(response, 413, "Create request is too large"); return }
        val cached = ContentCachingResponseWrapper(response)
        val result = try {
            receipts.execute(owner, route.path, key, body, encryptionSecret, study) {
                chain.doFilter(ReplayableRequest(request, body), cached)
                CreateReceiptResponse(cached.status, cached.contentType, cached.contentAsByteArray)
            }
        } catch (conflict: CreateReceiptConflictException) {
            writeFilterError(response, 409, conflict.message)
            return
        }
        if (result.replayed) {
            response.status = result.value.status
            response.contentType = result.value.contentType
            response.setHeader(REPLAYED_HEADER, "true")
            response.outputStream.write(result.value.body)
        } else {
            cached.copyBodyToResponse()
        }
    }

    private fun currentReceiptOwner(): CreateReceiptOwner? {
        val context = RLSRequestContext.current() ?: return null
        return context.receiptOwnerKind?.takeIf { context.principalId.isNotBlank() }
            ?.let { CreateReceiptOwner(context.principalId, it) }
    }

    private class ReplayableRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val stream = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = stream.read()
                override fun isFinished(): Boolean = stream.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException("Synchronous create request") }
            }
        }
        override fun getReader(): java.io.BufferedReader = getInputStream().bufferedReader(Charsets.UTF_8)
    }

    internal companion object {
        internal const val REPLAYED_HEADER = "Idempotency-Replayed"
        private const val MAX_BODY = 2 * 1024 * 1024
        // A rotation issues a credential like a create, so a lost response must replay the same key.
        private val STUDY_ROUTE = Regex("/v3/study/([^/]+)/(api-keys|api-keys/[^/]+/rotate|webhooks|clone)")
        private val SURVEY_ROUTE = Regex("/v3/survey/([^/]+)/questionnaire")
        /** Creates that mint a server id and belong to no study yet, so no study permission applies. */
        @Suppress("DEPRECATION") // the candidate create route is deprecated but still served
        private val STUDYLESS_CREATE_ROUTES = setOf(StudyApi.CONTROLLER, OrganizationApi.CONTROLLER, CandidateApi.CONTROLLER)

        internal fun createRoute(path: String): CreateRoute? {
            if (path in STUDYLESS_CREATE_ROUTES) return CreateRoute(path)
            val match = STUDY_ROUTE.matchEntire(path) ?: SURVEY_ROUTE.matchEntire(path) ?: return null
            val study = runCatching { UUID.fromString(match.groupValues[1].trim()) }.getOrNull() ?: return null
            return CreateRoute(path.replaceRange(requireNotNull(match.groups[1]).range, study.toString()), study)
        }
    }

    internal data class CreateRoute(val path: String, val studyId: UUID? = null)
}
