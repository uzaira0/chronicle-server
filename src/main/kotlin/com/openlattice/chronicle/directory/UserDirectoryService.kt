/*
 * Copyright (C) 2019. OpenLattice, Inc.
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

package com.openlattice.chronicle.directory

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.users.UserSearchFields
import com.openlattice.chronicle.users.ChronicleUserProfile

internal const val DEFAULT_PAGE_SIZE = 100
internal const val SEARCH_ENGINE_VERSION = "v3"

public interface UserDirectoryService {

    @Timed
    public fun getAllUsers(): Map<String, ChronicleUserProfile>

    @Timed
    public fun getUser(userId: String): ChronicleUserProfile

    @Timed
    public fun getUsers(userIds: Set<String>): Map<String, ChronicleUserProfile>

    //Future: Switch over to a Hazelcast map to relieve pressure from the external user directory
    @Timed
    public fun searchAllUsers(fields: UserSearchFields): Map<String, ChronicleUserProfile>

    @Timed
    public fun deleteUser(userId: String)
}


