/*
 * Copyright (C) 2020. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.chronicle.users

import java.time.Instant

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface UserListingService {
    /**
     * Retrieves all users as a sequence.
     */
    public fun getAllUsers() : Sequence<ChronicleUserProfile>

    /**
     * Retrieves updated users where update was happening after [from] (exclusive) and at or before [to] (inclusive)
     * as a sequence.
     */
    public fun getUpdatedUsers(from: Instant, to: Instant) : Sequence<ChronicleUserProfile>

    /**
     * Retrieves a single user by id
     */
    public fun getUser(userId: String): ChronicleUserProfile

    /**
     * Issues a testing JWT for the configured local auth bridge when available.
     */
    public fun issueTestingToken(userId: String? = null): String? = null

    /**
     * Issues a dashboard session JWT for the configured local auth bridge when available.
     *
     * Distinct from [issueTestingToken] only in that it is NOT gated on
     * `testingLoginEnabled`: the caller is expected to have already verified a credential
     * (see `POST /chronicle/v3/auth/dashboard-login`), which is a strictly stronger gate
     * than the unauthenticated testing bridge this replaces.
     */
    public fun issueDashboardToken(userId: String? = null): String? = null
}
