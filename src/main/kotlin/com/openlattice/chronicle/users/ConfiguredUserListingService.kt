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

import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.users.parseAlgorithm
import com.auth0.jwt.JWT
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 * @author uzaira0
 */
public open class ConfiguredUserListingService(
    authConfiguration: ChronicleAuthConfiguration,
    private val jwtKeyMaterial: JwtKeyMaterial,
    private val accessTokenExpiryMinutes: Long,
) : UserListingService {
    internal companion object {
        private val logger = LoggerFactory.getLogger(ConfiguredUserListingService::class.java)
    }

    private val users = authConfiguration.users.associateBy { it.toChronicleUserProfile().id }
    private val testingAuthConfig = authConfiguration.configurations.firstOrNull { it.testingTokenIssuer }
    private val defaultTestingUserId = authConfiguration.defaultTestingUserId
    private val testingLoginEnabled = authConfiguration.testingLoginEnabled
    private val roleClaimNamespace = authConfiguration.roleClaimNamespace
    private val _jwtTokens = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    public val jwtTokens: Map<String, List<String>> get() = _jwtTokens

    init {
        logger.info("************************* BEGIN LOCAL TEST JWT TOKENS *************************")
        authConfiguration.users.forEach { configuredUser ->
            val profile = configuredUser.toChronicleUserProfile()
            val jwt = issueToken(configuredClient = testingAuthConfig, configuredUser = configuredUser, issuedAt = Instant.now())
            if (jwt != null) {
                _jwtTokens[profile.id] = listOf(jwt)
                logger.info("${profile.id} -> issued local Chronicle testing token (expires in ${accessTokenExpiryMinutes}m)")
            }
        }

        logger.info("************************* DONE LISTING LOCAL TEST JWT TOKENS *************************")

    }

    private fun issueToken(
        configuredClient: ChronicleJwtClientConfiguration?,
        configuredUser: ChronicleAuthUser,
        issuedAt: Instant,
    ): String? {
        if (configuredClient == null) {
            return null
        }
        if (configuredClient.testingTokenIssuer.not() || configuredClient.jwkSetUri?.isNotBlank() == true) {
            return null
        }

        val profile = configuredUser.toChronicleUserProfile()
        val metadataRoles = configuredUser.appMetadata["roles"]
        val roles = (metadataRoles as? Collection<*>)?.filterIsInstance<String>() ?: emptyList()

        return JWT.create()
            .withSubject(profile.id)
            .withClaim("user_id", profile.id)
            .withClaim("email", profile.email)
            .withClaim("email_verified", configuredUser.emailVerified ?: false)
            .withIssuer(configuredClient.issuer)
            .withAudience(configuredClient.audience)
            .withIssuedAt(issuedAt)
            .withExpiresAt(issuedAt.plusSeconds(accessTokenExpiryMinutes * 60))
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(roleClaimNamespace, mapOf("roles" to roles))
            .sign(parseAlgorithm(configuredClient, jwtKeyMaterial))
    }

    override fun getAllUsers(): Sequence<ChronicleUserProfile> {
        return users.values.map { it.toChronicleUserProfile() }.asSequence()
    }

    override fun getUpdatedUsers(from: Instant, to: Instant): Sequence<ChronicleUserProfile> {
        return users.values.map { it.toChronicleUserProfile() }.asSequence()
    }

    override fun getUser(userId: String): ChronicleUserProfile {
        return users.getValue(userId).toChronicleUserProfile()
    }

    override fun issueTestingToken(userId: String?): String? {
        if (!testingLoginEnabled) {
            return null
        }
        return issueDashboardToken(userId)
    }

    override fun issueDashboardToken(userId: String?): String? {
        if (users.isEmpty() || testingAuthConfig == null) {
            return null
        }

        val requestedUserId = userId?.takeIf { it.isNotBlank() } ?: defaultTestingUserId ?: users.keys.firstOrNull()
        val requestedUser = requestedUserId?.let { users[it] } ?: return null

        val token = issueToken(testingAuthConfig, requestedUser, Instant.now()) ?: return null
        _jwtTokens.merge(requestedUserId, listOf(token)) { _, newValue -> newValue }

        return token
    }

}
