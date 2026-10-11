package com.openlattice.chronicle.directory

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.users.UserSearchFields
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguredUserDirectoryServiceTest {
    @Test fun partialNameMatchesStoredValuesAndBlankFieldsNeverMatch() {
        val service = ConfiguredUserDirectoryService(ChronicleAuthConfiguration(users = setOf(
            ChronicleAuthUser(id = "alice", name = "Alice", email = ""),
            ChronicleAuthUser(id = "blank", name = "", email = ""),
        )))
        assertEquals(setOf("alice"), service.searchAllUsers(UserSearchFields(email = null, name = "Al")).keys)
        assertEquals(emptySet<String>(), service.searchAllUsers(UserSearchFields(email = null, name = "unrelated")).keys)
    }
}
