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
package com.openlattice.chronicle.filters

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

/**
 * Authentication established only after a mobile enrollment request passes
 * the shared HMAC, timestamp, and nonce checks.
 *
 * Enrollment is the credential-bootstrap boundary, so it cannot present a
 * per-device API key yet. This token gives the database request exactly the
 * study scope encoded in the verified enrollment path; it grants no
 * organization or administrator scope.
 */
public class MobileApiHmacAuthenticationToken(
    public val studyId: UUID,
) : AbstractAuthenticationToken(
    listOf(SimpleGrantedAuthority("ROLE_MOBILE_HMAC_BOOTSTRAP"))
) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Any = "mobile-hmac-bootstrap"
}

/**
 * Study-scoped bootstrap identity established after validating a one-time enrollment code.
 * V4 consumption happens only after the accepted manifest digest matches; the raw code is
 * never retained in the authentication object.
 */
public class MobileEnrollmentAuthenticationToken(
    public val studyId: UUID,
) : AbstractAuthenticationToken(
    listOf(SimpleGrantedAuthority("ROLE_MOBILE_ENROLLMENT_BOOTSTRAP"))
) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Any = "mobile-enrollment-bootstrap"
}

/**
 * Authentication established only for the exact operator-configured Play reviewer
 * bootstrap endpoint. The reusable reviewer secret never grants a device, dashboard,
 * organization, or administrator identity; this token carries only the configured study.
 */
public class MobileReviewerAuthenticationToken(
    public val studyId: UUID,
) : AbstractAuthenticationToken(
    listOf(SimpleGrantedAuthority("ROLE_MOBILE_REVIEWER_BOOTSTRAP"))
) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Any = "mobile-reviewer-bootstrap"
}
