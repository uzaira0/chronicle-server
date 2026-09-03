/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.ClientIpResolver
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Servlet filter that populates SLF4J MDC (Mapped Diagnostic Context) with request-scoped
 * correlation fields for structured logging and distributed tracing.
 *
 * Fields set in MDC:
 * - requestId: unique per-request UUID (or forwarded from X-Request-ID header)
 * - traceId: W3C traceparent trace ID (or generated if absent)
 * - spanId: W3C traceparent span ID (or generated if absent)
 * - studyId: stable one-way reference extracted from URL path
 * - participantId: stable one-way reference extracted from URL path
 * - userId: authenticated principal (if available)
 * - clientIp: client IP from X-Forwarded-For or remoteAddr
 * - httpMethod: GET, POST, etc.
 * - httpPath: sanitized route shape with sensitive path segments redacted
 *
 * IMPORTANT: No PII is logged. StudyId and participantId are logged only as stable
 * one-way references. The userId is the Auth0/JWT subject claim (not a name or email).
 * All values are sanitized via LogSanitizer to prevent log injection.
 */
public class ObservabilityFilter : OncePerRequestFilter() {

    internal companion object {

        // Pattern to extract studyId from URL: /chronicle/.../study/{uuid}/...
        private val STUDY_ID_PATTERN: Pattern = Pattern.compile(
            "/study/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
        )

        // Pattern to extract participantId from URL: .../participant/{id}/...
        private val PARTICIPANT_ID_PATTERN: Pattern = Pattern.compile(
            "/participant/([^/]+)"
        )

        // W3C traceparent header format: version-traceId-spanId-flags
        // Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
        private val TRACEPARENT_PATTERN: Pattern = Pattern.compile(
            "^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$"
        )

        private const val HEADER_REQUEST_ID = "X-Request-ID"
        private const val HEADER_TRACEPARENT = "traceparent"
        private val REQUEST_ID_PATTERN: Pattern = Pattern.compile("[a-zA-Z0-9_\\-]+")
        // Response headers for trace propagation
        private const val HEADER_X_REQUEST_ID = "X-Request-ID"
        private const val HEADER_X_TRACE_ID = "X-Trace-ID"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            populateMdc(request, response)
            filterChain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }

    private fun populateMdc(request: HttpServletRequest, response: HttpServletResponse) {
        // Request ID: use forwarded header or generate new
        val requestId = request.getHeader(HEADER_REQUEST_ID)
            ?.takeIf { it.length <= 64 && REQUEST_ID_PATTERN.matcher(it).matches() }
            ?: UUID.randomUUID().toString()
        MDC.put("requestId", requestId)

        // W3C Trace Context: parse traceparent or generate IDs
        val traceparent = request.getHeader(HEADER_TRACEPARENT)
        if (traceparent != null) {
            val matcher = TRACEPARENT_PATTERN.matcher(traceparent)
            if (matcher.matches()) {
                MDC.put("traceId", matcher.group(2))
                MDC.put("spanId", matcher.group(3))
            } else {
                MDC.put("traceId", generateTraceId())
                MDC.put("spanId", generateSpanId())
            }
        } else {
            MDC.put("traceId", generateTraceId())
            MDC.put("spanId", generateSpanId())
        }

        // Extract studyId from URL path (opaque UUID, not PII)
        val path = request.requestURI
        val studyMatcher = STUDY_ID_PATTERN.matcher(path)
        if (studyMatcher.find()) {
            MDC.put("studyId", LogSanitizer.stableFingerprint(studyMatcher.group(1), "study"))
        }

        // Extract participantId from URL path (sanitized, may be opaque or user-chosen)
        val participantMatcher = PARTICIPANT_ID_PATTERN.matcher(path)
        if (participantMatcher.find()) {
            val rawId = participantMatcher.group(1)
            MDC.put("participantId", LogSanitizer.stableFingerprint(rawId, "participant"))
        }

        // Authenticated user ID (JWT subject, not PII like email/name)
        try {
            val auth = SecurityContextHolder.getContext().authentication
            if (auth != null && auth.isAuthenticated) {
                val principal = auth.name
                if (principal != null && principal != "anonymousUser") {
                    MDC.put("userId", LogSanitizer.sanitize(principal, 200))
                }
            }
        } catch (_: Exception) {
            // Security context not available yet — skip
        }

        // Client IP from proxy headers only when the direct peer is trusted.
        val clientIp = ClientIpResolver.resolve(request)
        MDC.put("clientIp", LogSanitizer.sanitizeIp(clientIp))

        // HTTP method and sanitized path
        MDC.put("httpMethod", request.method)
        MDC.put("httpPath", LogSanitizer.sanitizeRequestPath(path))

        // Set response headers for trace correlation
        response.setHeader(HEADER_X_REQUEST_ID, requestId)
        response.setHeader(HEADER_X_TRACE_ID, MDC.get("traceId"))
    }

    private fun generateTraceId(): String {
        val uuid = UUID.randomUUID()
        return String.format(Locale.ROOT, "%016x%016x", uuid.mostSignificantBits, uuid.leastSignificantBits)
    }

    private fun generateSpanId(): String {
        return String.format(Locale.ROOT, "%016x", UUID.randomUUID().mostSignificantBits)
    }
}
