package com.openlattice.chronicle.directory

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.users.UserSearchFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    /** Tokens carry the configured id as `sub`; a blank id would issue logins the decoder then refuses. */
    @Test fun aBlankConfiguredUserIdIsRefusedAtLoad() {
        for (id in listOf(null, "", "  ")) {
            assertThrows("id <$id>", IllegalArgumentException::class.java) { ChronicleAuthUser(id = id, name = "Admin") }
        }
    }
}
