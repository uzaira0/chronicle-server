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
 * Rate-limits a pre-authentication endpoint (login, enrollment, participant form code exchange).
 *
 * Authenticated endpoints carry no rate limit. Brute force against credentials is bounded here
 * and by [PreAuthenticationRateLimitFilter], which counts every failed unauthenticated attempt per IP.
 *
 * @author uzaira0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class RateLimit(
    /** Requests per minute; 0 uses the configured rate of [type]. */
    val requestsPerMinute: Long = 0,

    /** Bucket size; 0 derives it from the rate and the burst multiplier. */
    val burstCapacity: Long = 0,

    /** Configured rate used when [requestsPerMinute] is 0. */
    val type: RateLimitType = RateLimitType.AUTH,

    /** Who shares one bucket. */
    val keyStrategy: RateLimitKeyStrategy = RateLimitKeyStrategy.IP,
)

/** Which configured rate a pre-authentication endpoint gets. */
public enum class RateLimitType {
    /** Credential checks: login, session exchange, reviewer enrollment. */
    AUTH,

    /** Enrollment preview. */
    READ,

    /** Enrollment, participant form code exchange and form session recovery. */
    WRITE,
}

/** Who shares one rate-limit bucket. */
public enum class RateLimitKeyStrategy {
    /** The client IP address. */
    IP,

    /** One participant of one study from one IP, so a shared network never blocks a whole cohort. */
    PARTICIPANT_STUDY,
}
