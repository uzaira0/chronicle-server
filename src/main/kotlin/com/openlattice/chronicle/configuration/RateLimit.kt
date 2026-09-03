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

/**
 * Annotation to specify custom rate limits for individual endpoints.
 *
 * Can be applied at the method level (for specific endpoints) or at the
 * class level (for all endpoints in a controller).
 *
 * Method-level annotations override class-level annotations.
 *
 * Example usage:
 * ```kotlin
 * @RestController
 * @RateLimit(requestsPerMinute = 50)  // Controller-level default
 * class MyController {
 *
 *     @GetMapping("/fast")
 *     @RateLimit(requestsPerMinute = 200)  // Override for this endpoint
 *     fun fastEndpoint(): Response { ... }
 *
 *     @PostMapping("/sensitive")
 *     @RateLimit(requestsPerMinute = 5, type = RateLimitType.AUTH)  // Strict limit
 *     fun sensitiveEndpoint(): Response { ... }
 *
 *     @GetMapping("/internal")
 *     @RateLimit(bypass = true)  // No rate limiting
 *     fun internalEndpoint(): Response { ... }
 * }
 * ```
 *
 * @author uzaira0
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class RateLimit(
    /**
     * Maximum requests per minute.
     * If set to 0, uses the default from configuration based on [type].
     */
    val requestsPerMinute: Long = 0,

    /**
     * Burst capacity - maximum tokens the bucket can hold.
     * Allows temporary bursts above the steady-state rate.
     * If set to 0, calculated as requestsPerMinute * burstCapacityMultiplier.
     */
    val burstCapacity: Long = 0,

    /**
     * Type of rate limit to apply.
     * Determines the default rate if requestsPerMinute is not specified.
     */
    val type: RateLimitType = RateLimitType.DEFAULT,

    /**
     * Whether to bypass rate limiting entirely for this endpoint.
     * Use with caution - only for internal/admin endpoints.
     */
    val bypass: Boolean = false,

    /**
     * Key generation strategy for rate limiting.
     * Determines how to identify unique clients.
     */
    val keyStrategy: RateLimitKeyStrategy = RateLimitKeyStrategy.AUTO
)

/**
 * Type of rate limit, determining default rate if not explicitly specified.
 */
public enum class RateLimitType {
    /**
     * Default rate limit (100 req/min by default).
     * For general API endpoints.
     */
    DEFAULT,

    /**
     * Authentication rate limit (10 req/min per IP by default).
     * For login, password reset, and similar auth endpoints.
     * Stricter to prevent brute force attacks.
     */
    AUTH,

    /**
     * Sensitive/admin operation rate limit (20 req/min by default).
     * For operations like data export, admin operations, bulk operations, etc.
     */
    SENSITIVE,

    /**
     * Health/status endpoint rate limit (1000 req/min by default).
     * Generous limit for monitoring and health checks.
     */
    HEALTH,

    /**
     * Read endpoint rate limit (100 req/min per study by default).
     * For GET endpoints that retrieve study data.
     */
    READ,

    /**
     * Write endpoint rate limit (60 req/min per participant per study by default).
     * For POST endpoints that upload data (phone telemetry, sensors, etc.).
     */
    WRITE,

    /**
     * Admin endpoint rate limit (20 req/min by default).
     * For administrative operations like cache reload, import, etc.
     */
    ADMIN,

    /**
     * No rate limit applied.
     * Same as bypass=true.
     */
    UNLIMITED
}

/**
 * Strategy for generating rate limit keys (identifying unique clients).
 */
public enum class RateLimitKeyStrategy {
    /**
     * Automatically choose based on authentication status:
     * - Authenticated: Use user ID
     * - Unauthenticated: Use client IP
     */
    AUTO,

    /**
     * Always use client IP address.
     * Useful for public endpoints where user tracking isn't meaningful.
     */
    IP,

    /**
     * Always use authenticated user ID.
     * Requests without authentication will fail or fall back to IP.
     */
    USER,

    /**
     * Use combination of user ID (if authenticated) and IP.
     * Provides rate limiting per user-IP pair.
     */
    USER_AND_IP,

    /**
     * Use the endpoint path + IP/user.
     * Rate limits are per-endpoint rather than global per client.
     */
    ENDPOINT,

    /**
     * Per-study rate limiting.
     * Extracts study ID from the URL path and limits per study.
     * Prevents one compromised study from affecting others.
     */
    STUDY,

    /**
     * Per-participant-per-study rate limiting.
     * Extracts study and participant IDs from the URL path and scopes the bucket to the authenticated user or IP.
     * Used for write/upload endpoints to limit per participant within a study.
     */
    PARTICIPANT_STUDY
}
