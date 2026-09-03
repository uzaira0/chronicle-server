package com.openlattice.chronicle.directory

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.users.UserSearchFields
import org.springframework.stereotype.Service

@Service
public open class ConfiguredUserDirectoryService(
    authConfiguration: ChronicleAuthConfiguration
) : UserDirectoryService {

    private val users: MutableMap<String, ChronicleAuthUser> =
        authConfiguration.users
            .associateBy { it.toChronicleUserProfile().id }
            .toMutableMap()

    override fun getAllUsers(): Map<String, ChronicleUserProfile> {
        return users.mapValues { it.value.toChronicleUserProfile() }
    }

    override fun getUser(userId: String): ChronicleUserProfile {
        return users.getValue(userId).toChronicleUserProfile()
    }

    override fun getUsers(userIds: Set<String>): Map<String, ChronicleUserProfile> {
        return userIds.associateWith { users.getValue(it).toChronicleUserProfile() }
    }

    override fun searchAllUsers(fields: UserSearchFields): Map<String, ChronicleUserProfile> {
        val email = fields.email ?: ""
        val name = fields.name ?: ""

        return users.values
            .filter { user ->
                val profile = user.toChronicleUserProfile()
                val haystack = listOf(
                    profile.email,
                    profile.name,
                    profile.nickname,
                    profile.givenName,
                    profile.familyName,
                    profile.username,
                ).filterNotNull() + profile.connections + profile.identityUserIds
                haystack.any { candidate -> email.contains(candidate) || name.contains(candidate) }
            }
            .associateBy { it.toChronicleUserProfile().id }
            .mapValues { it.value.toChronicleUserProfile() }
    }

    override fun deleteUser(userId: String) {
        users.remove(userId)
    }
}
