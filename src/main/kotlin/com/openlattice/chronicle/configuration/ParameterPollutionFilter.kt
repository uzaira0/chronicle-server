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
// reason: file name reflects the filter feature; the top-level declaration is the @Configuration that supplies the filter bean
@file:Suppress("MatchingDeclarationName")

package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Configuration for HTTP Parameter Pollution (HPP) protection.
 *
 * HTTP Parameter Pollution occurs when attackers submit duplicate parameter names
 * in a single request. Different web frameworks handle duplicates differently:
 * - Some take the first value
 * - Some take the last value
 * - Some concatenate values
 * - Some return arrays
 *
 * This inconsistency can be exploited to:
 * - Bypass validation (first param validated, second used)
 * - Bypass WAF rules that check only first occurrence
 * - Cause application logic errors
 * - Exploit server-side processing differences
 *
 * Example Attack:
 *   /transfer?amount=100&amount=1000000
 *   - Validation checks first value (100) - passes limit check
 *   - Backend uses last value (1000000) - transfers huge amount
 *
 * This filter rejects requests with duplicate parameter names, providing
 * consistent and secure behavior.
 *
 * Exceptions:
 * - Some parameters legitimately need multiple values (e.g., checkboxes, multi-select)
 * - These can be whitelisted via the allowedDuplicates configuration
 */
@Configuration
public open class ParameterPollutionConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(ParameterPollutionConfig::class.java)

        /**
         * Parameter names that are allowed to have multiple values.
         * Extend this list if your application has legitimate multi-value parameters.
         *
         * Common examples:
         * - ids: API endpoints that accept multiple IDs
         * - tags: Multi-select tag filters
         * - categories: Multi-category filtering
         * - fields: Field selection for responses
         */
        public val ALLOWED_DUPLICATE_PARAMS: Set<String> = setOf(
            "ids",
            "id",
            "tags",
            "categories",
            "fields",
            "sort",
            "filter",
            "include",
            "exclude",
            "select",
            "expand"
        )

        /**
         * URL patterns where duplicate parameters are allowed.
         * Use with caution - only for specific endpoints that need multi-value params.
         */
        public val ALLOWED_DUPLICATE_PATHS: List<Regex> = listOf(
            // Example: Regex("^/api/v1/search.*"),
            // Example: Regex("^/api/v1/bulk.*")
        )

        /**
         * Maximum number of duplicate values allowed for whitelisted parameters.
         * Prevents abuse even for allowed duplicates.
         */
        public const val MAX_ALLOWED_DUPLICATES: Int = 100
    }

    /**
     * Filter that detects and rejects HTTP Parameter Pollution attacks.
     *
     * Returns 400 Bad Request when:
     * - A non-whitelisted parameter appears multiple times
     * - A whitelisted parameter has too many values (>MAX_ALLOWED_DUPLICATES)
     *
     * The filter logs suspicious requests for security monitoring.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    public fun parameterPollutionFilter(): Filter {
        return object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain
            ) {
                val pollutionResult = checkForParameterPollution(request)

                when (pollutionResult) {
                    is PollutionResult.Clean -> {
                        filterChain.doFilter(request, response)
                    }
                    is PollutionResult.DuplicateDetected -> {
                        logger.warn(
                            "HTTP Parameter Pollution detected - IP: ${LogSanitizer.sanitizeIp(request.remoteAddr)}, " +
                            "URI: ${LogSanitizer.sanitizeRequestPath(request.requestURI)}, " +
                            "Parameter: ${LogSanitizer.sanitize(pollutionResult.paramName, 100)}, " +
                            "Count: ${pollutionResult.count}"
                        )
                        response.sendError(
                            HttpStatus.BAD_REQUEST.value(),
                            "Duplicate parameter not allowed: ${pollutionResult.paramName}"
                        )
                    }
                    is PollutionResult.ExcessiveDuplicates -> {
                        logger.warn(
                            "Excessive duplicate parameters detected - IP: ${LogSanitizer.sanitizeIp(request.remoteAddr)}, " +
                            "URI: ${LogSanitizer.sanitizeRequestPath(request.requestURI)}, " +
                            "Parameter: ${LogSanitizer.sanitize(pollutionResult.paramName, 100)}, " +
                            "Count: ${pollutionResult.count} (max: $MAX_ALLOWED_DUPLICATES)"
                        )
                        response.sendError(
                            HttpStatus.BAD_REQUEST.value(),
                            "Too many values for parameter: ${pollutionResult.paramName}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Result of parameter pollution check.
     */
    private sealed class PollutionResult {
        object Clean : PollutionResult()
        data class DuplicateDetected(val paramName: String, val count: Int) : PollutionResult()
        data class ExcessiveDuplicates(val paramName: String, val count: Int) : PollutionResult()
    }

    /**
     * Checks a request for parameter pollution.
     *
     * @param request The HTTP request to check
     * @return PollutionResult indicating if pollution was detected
     */
    // reason: boundary catch — unreadable parameter map must let the request through, not break the filter, regardless of failure type
    @Suppress("TooGenericExceptionCaught")
    private fun checkForParameterPollution(request: HttpServletRequest): PollutionResult {
        // Check if this path is in the allow list
        val requestUri = request.requestURI
        if (ALLOWED_DUPLICATE_PATHS.any { it.matches(requestUri) }) {
            return PollutionResult.Clean
        }

        // Get parameter map (name -> array of values)
        val parameterMap: Map<String, Array<String>>
        try {
            parameterMap = request.parameterMap
        } catch (e: Exception) {
            // If we can't read parameters, let the request through
            // Other filters will catch malformed requests
            logger.debug("Could not read parameter map: ${e.message}")
            return PollutionResult.Clean
        }

        // Check each parameter
        return parameterMap.firstNotNullOfOrNull { (paramName, values) ->
            evaluateParameter(paramName, values)
        } ?: PollutionResult.Clean
    }

    /**
     * Evaluates a single parameter for pollution. Returns a non-Clean [PollutionResult]
     * when the parameter violates a rule, or null when it is acceptable.
     */
    private fun evaluateParameter(paramName: String, values: Array<String>): PollutionResult? {
        if (values.size <= 1) {
            return null
        }
        return if (isAllowedDuplicate(paramName)) {
            // Even whitelisted params have limits
            if (values.size > MAX_ALLOWED_DUPLICATES) {
                PollutionResult.ExcessiveDuplicates(paramName, values.size)
            } else {
                null
            }
        } else {
            // Duplicate not allowed
            PollutionResult.DuplicateDetected(paramName, values.size)
        }
    }

    /**
     * Checks if a parameter name is allowed to have duplicate values.
     *
     * @param paramName The parameter name to check
     * @return true if duplicates are allowed for this parameter
     */
    private fun isAllowedDuplicate(paramName: String): Boolean {
        // Case-insensitive check for common array parameter patterns
        val normalizedName = paramName.lowercase()

        // Check explicit whitelist
        if (ALLOWED_DUPLICATE_PARAMS.any { it.equals(normalizedName, ignoreCase = true) }) {
            return true
        }

        // Allow common array notation patterns
        // e.g., "ids[]", "tags[]", "item[0]", "filters[name]"
        if (normalizedName.endsWith("[]") || normalizedName.matches(Regex(".*\\[\\d+]$")) ||
            normalizedName.matches(Regex(".*\\[[a-z]+]$"))) {
            return true
        }

        return false
    }
}
