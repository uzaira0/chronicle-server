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

import com.hazelcast.query.Predicate
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.Role
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.users.ChronicleUserProfile
import java.util.UUID
import javax.annotation.Nonnull

// reason: public API surface — the full principal-management contract; splitting would break implementors/callers
@Suppress("TooManyFunctions")
public interface SecurePrincipalsManager {
    /**
     * @param owner     The owner of a role. Usually the organization.
     * @param principal The principal which to create.
     * @return True if the securable principal was created false otherwise.
     */
    public fun createSecurablePrincipalIfNotExists(owner: Principal, principal: SecurablePrincipal): Boolean

    /**
     * Retrieves a securable principal by acl key lookup.
     *
     * @param aclKey The acl key for the securable principal.
     * @return The securable principal identified by acl key.
     */
    public fun getSecurablePrincipal(aclKey: AclKey): SecurablePrincipal
    public fun getAllRolesInOrganization(organizationId: UUID): Collection<SecurablePrincipal>
    public fun getAllRolesInOrganizations(organizationIds: Collection<UUID>): Map<UUID, Collection<SecurablePrincipal>>
    public fun getSecurablePrincipals(p: Predicate<AclKey, SecurablePrincipal>): Collection<SecurablePrincipal>
    public fun updateTitle(aclKey: AclKey, title: String)
    public fun updateDescription(aclKey: AclKey, description: String)
    public fun deletePrincipal(aclKey: AclKey)
    public fun deleteAllRolesInOrganization(organizationId: UUID)
    public fun addPrincipalToPrincipal(source: AclKey, target: AclKey)

    /**
     * Grants an AclKey to a set of AclKeys, and returns any that were updated.
     *
     * @param source  The child AclKey to grant
     * @param targets The parent AclKeys that will be granted [source]
     * @return all AclKeys that were updated. Any target AclKey that already had [source] as a child will not be included.
     */
    public fun addPrincipalToPrincipals(source: AclKey, targets: Set<AclKey>): Set<AclKey>
    public fun removePrincipalFromPrincipal(source: AclKey, target: AclKey)
    public fun removePrincipalsFromPrincipals(sources: Set<AclKey>, target: Set<AclKey>)

    /**
     * Reads
     *
     * Note: Check if there is a bug as you may need role type and principal to uniquely identify, unless reservations
     * are by principal id only.
     */
    @Nonnull
    public fun getSecurablePrincipal(principalId: String): SecurablePrincipal
    public fun getSecurablePrincipals(aclKeys: Set<AclKey>): Map<AclKey, SecurablePrincipal>
    public fun getParentPrincipalsOfPrincipal(aclKey: AclKey): Collection<SecurablePrincipal>
    public fun getOrganizationMembers(organizationIds: Set<UUID>): Map<UUID, Set<SecurablePrincipal>>
    public fun getOrganizationMemberPrincipals(organizationId: UUID): Set<Principal>
    public fun principalHasChildPrincipal(parent: AclKey, child: AclKey): Boolean

    // Methods about users
    public fun getAllUserProfilesWithPrincipal(principal: AclKey): Collection<ChronicleUserProfile>
    public fun principalExists(p: Principal): Boolean
    public fun getUser(userId: String): ChronicleUserProfile
    public fun getRole(organizationId: UUID, roleId: UUID): Role
    public fun lookup(p: Principal): AclKey
    public fun lookup(principals: Set<Principal>): Map<Principal, AclKey>
    public fun lookupRole(principal: Principal): Role
    public fun getSecurablePrincipals(members: Collection<Principal>): Collection<SecurablePrincipal>
    public fun getAllPrincipals(sp: SecurablePrincipal): Collection<SecurablePrincipal>
    public fun bulkGetUnderlyingPrincipals(sps: Set<SecurablePrincipal>): Map<SecurablePrincipal, Set<Principal>>
    public fun getCurrentUserId(): UUID
    public fun ensurePrincipalsExist(aclKeys: Set<AclKey>)
    public fun getAllRoles(): Set<Role>
    public fun getAllUsers(): Set<SecurablePrincipal>
    public fun createSecurablePrincipalIfNotExists(principal: SecurablePrincipal): Boolean
}
