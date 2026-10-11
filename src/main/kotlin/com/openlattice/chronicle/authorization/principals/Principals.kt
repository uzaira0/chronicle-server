/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 */
package com.openlattice.chronicle.authorization.principals

import com.google.common.base.Preconditions
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.SortedPrincipalSet
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.SystemUser
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.hazelcast.HazelcastMap
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.*
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

public class Principals private constructor() {

    // reason: companion is a security-principal utility namespace; functions are cohesive, splitting risks behavior
    @Suppress("TooManyFunctions")
    internal companion object {
        private val logger = LoggerFactory
            .getLogger(Principals::class.java)
        private val startupLock: Lock = ReentrantLock()
        private lateinit var securablePrincipals: IMap<String, SecurablePrincipal>
        private lateinit var principalManager: SecurePrincipalsManager
        private lateinit var principalResolver: com.openlattice.chronicle.authorization.CreatorPrincipalResolver

        // reason: field name is a reflection contract — TestSecurityUtils.injectStaticMock looks up
        // the static field by the literal name "principals"; renaming breaks the test-security harness
        @Suppress("MemberNameEqualsClassName")
        private lateinit var principals: IMap<String, SortedPrincipalSet>

        public fun init(
            spm: SecurePrincipalsManager,
            hazelcastInstance: HazelcastInstance,
            resolver: com.openlattice.chronicle.authorization.CreatorPrincipalResolver,
        ) {
            if (startupLock.tryLock()) {
                principalManager = spm
                principalResolver = resolver
                securablePrincipals = HazelcastMap.SECURABLE_PRINCIPALS.getMap(
                    hazelcastInstance
                )
                principals = HazelcastMap.RESOLVED_PRINCIPAL_TREES.getMap(
                    hazelcastInstance
                )
            } else {
                logger.error("Principals security processing can only be initialized once.")
                error("Principals context already initialized.")
            }
        }

        public fun ensureRole(principal: Principal) {
            Preconditions.checkArgument(
                principal.type == PrincipalType.ROLE,
                "Only role principal type allowed."
            )
        }

        public fun ensureUser(principal: Principal) {
            Preconditions.checkState(principal.type == PrincipalType.USER, "Only user principal type allowed.")
        }

        public fun ensureUserOrRole(principal: Principal) {
            Preconditions.checkState(
                principal.type == PrincipalType.USER || (principal.type
                        == PrincipalType.ROLE), "Only user and role principal types allowed."
            )
        }

        /**
         * Retrieves the current user, or throws [AuthenticationCredentialsNotFoundException] if authentication is absent.
         *
         * @return The principal for the current request.
         */

        public fun getCurrentUser(): Principal = getUserPrincipal(getCurrentPrincipalId())
        public fun getCurrentSecurablePrincipal(): SecurablePrincipal {
            val principalId = getCurrentPrincipalId()
            securablePrincipals[principalId]?.let { return it }
            val key = SecurityContextHolder.getContext().authentication as? ApiKeyAuthenticationToken
            if (key == null) {
                val user = getUserPrincipal(principalId)
                val stored = principalManager.getSecurablePrincipals(setOf(user)).firstOrNull()
                if (stored != null) return stored
                return SecurablePrincipal(
                    Optional.of(getUserId(principalId)), user, principalId, Optional.of("Auto-provisioned local user"),
                )
            }
            return SecurablePrincipal(
                Optional.of(key.keyId),
                Principal(PrincipalType.USER, principalId),
                principalId,
                Optional.of("Auto-provisioned local user")
            )
        }

        public fun getAdminRole(): Principal {
            return SystemRole.adminRole
        }

        public fun getAnonymousUser(): Principal = SystemRole.ANONYMOUS_USER.principal
        public fun getAnonymousSecurablePrincipal(): SecurablePrincipal {
            val anonPrincipal = getAnonymousUser()
            return securablePrincipals[anonPrincipal.id] ?: SecurablePrincipal(
                Optional.of(UUID.randomUUID()),
                anonPrincipal,
                "AnonymousUser",
                Optional.of("Auto-provisioned anonymous principal")
            )
        }

        public fun getUserPrincipal(principalId: String): Principal {
            return Principal(PrincipalType.USER, principalId)
        }

        // Account identity is stable before its first ACL is provisioned; this is not a credential.
        internal fun getUserId(principalId: String): UUID =
            // nosemgrep: chronicle-uuid-from-string -- Stable account identifier, never a credential.
            UUID.nameUUIDFromBytes("chronicle-user:$principalId".toByteArray(
                Charsets.UTF_8))

        public fun getUserPrincipals(principalId: String): NavigableSet<Principal> {
            return getStoredUserPrincipals(principalId)
        }

        /**
         * What a user who is not making this request holds: their resolved principal tree, or just their user
         * principal. Roles that only arrive in a login token are not known here.
         */
        public fun getStoredUserPrincipals(principalId: String): NavigableSet<Principal> =
            TreeSet<Principal>(principals[principalId] ?: principalResolver.resolve(principalId))
                .apply { add(getUserPrincipal(principalId)) }

        public fun getCurrentPrincipals(): NavigableSet<Principal> {
            return TreeSet<Principal>(getStoredUserPrincipals(getCurrentPrincipalId())).apply {
                addAll(currentAuthenticationPrincipals())
            }
        }


        public fun fromPrincipal(p: Principal): SimpleGrantedAuthority = SimpleGrantedAuthority(p.type.name + "|" + p.id)

        private fun currentAuthenticationPrincipals(): Set<Principal> {
            val authentication = SecurityContextHolder.getContext().authentication ?: return emptySet()
            return authentication.authorities.mapNotNull { authority ->
                val name = authority.authority ?: return@mapNotNull null
                val parts = name.split("|", limit = 2)
                if (parts.size != 2) {
                    null
                } else {
                    val principalType = runCatching { PrincipalType.valueOf(parts[0]) }.getOrNull()
                    principalType?.let { Principal(it, parts[1]) }
                }
            }.toSet()
        }


        private fun getPrincipalId(authentication: Authentication): String {
            val principal = authentication.principal
            // Use JWT 'sub' claim as principal ID for stable identity across token refreshes.
            // Previously used toString() which included the Java object hash, causing
            // principal IDs to change on every token regeneration.
            return when (principal) {
                is Jwt -> principal.subject?.takeIf { it.isNotBlank() } ?: principal.toString()
                is String -> principal
                else -> principal.toString()
            }
        }

        private fun getCurrentPrincipalId(): String {
            val authentication = SecurityContextHolder.getContext().authentication
                ?: throw AuthenticationCredentialsNotFoundException("No authentication available")
            return getPrincipalId(authentication)
        }

        public fun invalidatePrincipalCache(principalId: String) {
            securablePrincipals.evict(principalId)
            principals.evict(principalId)
        }

        public fun getChroniclePrincipal(): Principal = SystemUser.CHRONICLE.principal
    }
}
