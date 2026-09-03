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
package com.openlattice.chronicle.observability

import com.openlattice.chronicle.util.LogSanitizer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Servlet filter that records API request latency and error metrics in Prometheus.
 *
 * Normalizes URL paths to reduce cardinality and avoid storing request identifiers:
 * - study, participant, device, export, webhook, and questionnaire IDs are route-shaped
 * - query strings are stripped before metric labels are emitted
 *
 * Skips static assets and health/metrics endpoints.
 */
public class ApiMetricsFilter : OncePerRequestFilter() {

    internal companion object {
        private val SKIP_PATHS = setOf(
            "/prometheus/",
            "/health",
            "/internal/health",
            "/chronicle/internal/health/live",
        )

        /**
         * Normalizes a request path to a low-cardinality, identifier-safe route shape.
         */
        public fun normalizePath(path: String): String = LogSanitizer.sanitizeRequestPath(path)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI

        // Skip metrics/health endpoints to avoid self-referential metrics
        if (SKIP_PATHS.any { path.startsWith(it) }) {
            filterChain.doFilter(request, response)
            return
        }

        val normalizedPath = normalizePath(path)
        val method = request.method
        val startNanos = System.nanoTime()

        ChronicleMetrics.activeRequests.inc()
        try {
            filterChain.doFilter(request, response)
        } finally {
            ChronicleMetrics.activeRequests.dec()

            val durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
            ChronicleMetrics.apiRequestDuration
                .labels(normalizedPath, method)
                .observe(durationSeconds)

            val status = response.status
            if (status >= 400) {
                ChronicleMetrics.apiErrorsTotal
                    .labels(normalizedPath, method, status.toString())
                    .inc()
            }
        }
    }
}
