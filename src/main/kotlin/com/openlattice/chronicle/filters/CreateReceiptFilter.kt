package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.services.receipts.CreateReceiptConflictException
import com.openlattice.chronicle.services.receipts.CreateReceiptResponse
import com.openlattice.chronicle.services.receipts.ResearcherCreateReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.ByteArrayInputStream
import java.util.UUID

/** Unique durable receipts and the mutation share one transaction, including lost HTTP responses. */
internal class CreateReceiptFilter(
    storage: StorageResolver,
    private val authorization: StudyAuthorizationService,
    private val secret: () -> String? = { System.getenv("CHRONICLE_INTERNAL_WEB_SECRET") },
) : OncePerRequestFilter() {
    private val receipts = ResearcherCreateReceiptService(storage)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        guarded(request, response, chain)
    }

    internal fun guarded(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val route = request.requestURI.removePrefix("/chronicle").trimEnd('/')
        if (request.method != "POST" || !isCreate(route)) return chain.doFilter(request, response)
        val key = request.getHeader(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER) ?: return chain.doFilter(request, response)
        if (key.length !in 1..128 || !key.matches(Regex("[A-Za-z0-9_-]+"))) {
            writeFilterError(response, 400, "Idempotency-Key must contain 1–128 letters, digits, underscores or hyphens")
            return
        }
        val encryptionSecret = secret()?.takeIf { it.length >= 32 }
        if (encryptionSecret == null) { writeFilterError(response, 503, "Create receipt encryption is unavailable"); return }
        val study = STUDY_ROUTE.matchEntire(route)?.groupValues?.get(1)
            ?: SURVEY_ROUTE.matchEntire(route)?.groupValues?.get(1)
        if (study != null) {
            try {
                authorization.requirePermission(UUID.fromString(study),
                    if (route.endsWith("/export/async")) StudyPermission.EXPORT_DATA else StudyPermission.MODIFY_STUDY)
            } catch (denied: com.geekbeast.controllers.exceptions.ForbiddenException) {
                writeFilterError(response, 403, "Access denied")
                return
            }
        }
        val principal = Principals.getCurrentUser().id
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) { writeFilterError(response, 413, "Create request is too large"); return }
        val cached = ContentCachingResponseWrapper(response)
        val result = try {
            receipts.execute(principal, route, key, body, encryptionSecret, study?.let(UUID::fromString)) {
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
            response.setHeader("Idempotency-Replayed", "true")
            response.outputStream.write(result.value.body)
        } else {
            cached.copyBodyToResponse()
        }
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
        private const val MAX_BODY = 2 * 1024 * 1024
        private val STUDY_ROUTE = Regex("/v3/study/([0-9a-fA-F-]+)/(api-keys|export/async)")
        private val SURVEY_ROUTE = Regex("/v3/survey/([0-9a-fA-F-]+)/questionnaire")
        fun isCreate(route: String): Boolean = route == "/v3/study" || STUDY_ROUTE.matches(route) || SURVEY_ROUTE.matches(route)
    }
}
