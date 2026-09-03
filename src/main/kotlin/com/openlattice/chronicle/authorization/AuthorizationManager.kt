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
package com.openlattice.chronicle.authorization

import com.codahale.metrics.annotation.Timed
import com.google.common.collect.SetMultimap
import com.hazelcast.query.Predicate
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.EnumMap
import java.util.stream.Stream

/**
 * The authorization manager manages permissions for all securable objects in the system.
 *
 *
 * Authorization behavior is summarized below:
 *
 *  * No inheritance and that all permissions are explicitly set.
 *  * For permissions that are present we follow a least restrictive model for determining access
 *  * If no relevant permissions are present for Principal set, access is denied.
 *
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
// reason: core authorization API surface — splitting this interface would fracture the auth/RLS contract across all implementers and call sites
@Suppress("TooManyFunctions")
public interface AuthorizationManager {
    /**
     * Creates a securable object, registers it's type, and ensures that the creating principal at least has at least
     * owner permissions so they can manage the ACL via the API. This version of the API is designed to be used in
     * transactions and requires the caller to ensure that in memory map is refreshed after the transactions commits
     * as the mapstore cannot read uncommited changes.
     *
     * NOTE: There is still a failure mode here if the principal is a role that is not assigned to anyone.
     *
     * @param connection The SQL connection to be used for ensuring the creation of object happens transactionally
     * along with any other required operations.
     * @param aclKey The unique acl key for the object.
     * @param principal The creating principal.
     * @param permissions The permissions to grant to that principal.
     */
    @Timed
    public fun createUnnamedSecurableObject(
        connection: Connection,
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission> = EnumSet.allOf(Permission::class.java),
        objectType: SecurableObjectType,
        expirationDate: OffsetDateTime =  OffsetDateTime.MAX
    )

    /**
     * Creates a securable object, registers it's type, and ensures that the creating principal at least has at least
     * owner permissions so they can manage the ACL via the API. This will take care of updating the cache once the
     * object is inserted into the database.
     *
     * NOTE: There is still a failure mode here if the principal is a role that is not assigned to anyone.
     *
     * @param aclKey The unique acl key for the object.
     * @param principal The creating principal.
     * @param permissions The permissions to grant to that principal.
     */
    @Timed
    public fun createUnnamedSecurableObject(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission> = EnumSet.allOf(Permission::class.java),
        objectType: SecurableObjectType,
        expirationDate: OffsetDateTime =  OffsetDateTime.MAX
    )

     @Timed
    public fun addPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>
    )

    @Timed
    public fun addPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: Set<Permission>,
        expirationDate: OffsetDateTime
    )

    @Timed
    public fun addPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: Set<Permission>,
        securableObjectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    )

    /**
     * Method for bulk adding permissions to a single principal across multiple acl keys of the same type.
     *
     * @param keys                The acl keys to which permissions will be added.
     * @param principal           The principal who will be receiving permissions.
     * @param permissions         The permissions that will be added.
     * @param securableObjectType The securable object type for which the permissions are being added. This will
     * override the existing object type, so care must be taken to call this for keys of the right type.
     */
    @Timed
    public fun addPermissions(
        keys: Set<AclKey>,
        principal: Principal,
        permissions: EnumSet<Permission>,
        securableObjectType: SecurableObjectType
    )

    /**
     * Method for bulk adding permissions to a single principal across multiple acl keys of the same type.
     *
     * @param keys                The acl keys to which permissions will be added.
     * @param principal           The principal who will be receiving permissions.
     * @param permissions         The permissions that will be added.
     * @param securableObjectType The securable object type for which the permissions are being added. This will
     * override the existing object type, so care must be taken to call this for keys of the right type.
     * @param expirationDate      The expiration data for the permission changes.
     */
    @Timed
    public fun addPermissions(
        keys: Set<AclKey>,
        principal: Principal,
        permissions: EnumSet<Permission>,
        securableObjectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    )

    @Timed
    public fun addPermissions(acls: List<Acl>)

    @Timed
    public fun removePermissions(acls: List<Acl>)

    @Timed
    public fun setPermissions(acls: List<Acl>)

    @Timed
    public fun removePermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>
    )

    @Timed
    public fun setPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>
    )

    @Timed
    public fun setPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>,
        expirationDate: OffsetDateTime
    )

    @Timed
    public fun setPermission(aclKeys: Set<AclKey>, principals: Set<Principal>, permissions: EnumSet<Permission>)

    @Timed
    public fun setPermissions(permissions: Map<AceKey, EnumSet<Permission>>)

    @Timed
    public fun deletePermissions(aclKey: AclKey)

    @Timed
    public fun deletePrincipalPermissions(principal: Principal)

    @Timed
    public fun authorize(
        requests: Map<AclKey, EnumSet<Permission>>,
        principals: Set<Principal>
    ): Map<AclKey, EnumMap<Permission, Boolean>>

    @Timed
    public fun accessChecksForPrincipals(
        accessChecks: Set<AccessCheck>,
        principals: Set<Principal>
    ): List<Authorization>

    @Timed
    public fun checkIfHasPermissions(
        aclKey: AclKey,
        principals: Set<Principal>,
        requiredPermissions: EnumSet<Permission>
    ): Boolean
    // Utility functions for retrieving permissions
    /**
     * @param aclKeySets the list of groups of AclKeys for wich to get the most restricted set of permissions
     * @param principals the pricipals to check against
     * @return the intersection of permission for each set of aclKeys
     */
    public fun getSecurableObjectSetsPermissions(
        aclKeySets: Collection<Set<AclKey>>,
        principals: Set<Principal>
    ): Map<Set<AclKey>, EnumSet<Permission>>

    public fun getSecurableObjectPermissions(
        aclKey: AclKey,
        principals: Set<Principal>
    ): Set<Permission>

    public fun getAllSecurableObjectPermissions(key: AclKey): Acl
    public fun getAllSecurableObjectPermissions(keys: Set<AclKey>): Set<Acl>

    /**
     * Returns all Principals, which have all the specified permissions on the securable object
     *
     * @param key         The securable object
     * @param permissions Set of permission to check for
     */
    public fun getAuthorizedPrincipalsOnSecurableObject(key: AclKey, permissions: EnumSet<Permission>): Set<Principal>
    public fun getAuthorizedObjectsOfType(
        principal: Principal,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): Stream<AclKey>

    public fun getAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): Stream<AclKey>

    @Timed
    public fun getAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>,
        additionalFilter: Predicate<*, *>
    ): Stream<AclKey>

    public fun getAuthorizedObjectsOfTypes(
        principals: Set<Principal>,
        objectTypes: Collection<SecurableObjectType>,
        permissions: EnumSet<Permission>
    ): Stream<AclKey>

    public fun getSecurableObjectOwners(key: AclKey): Set<Principal>

    @Timed
    public fun getOwnersForSecurableObjects(aclKeys: Collection<AclKey>): SetMultimap<AclKey, Principal>

    @Timed
    public fun deleteAllPrincipalPermissions(principal: Principal)
    @Timed
    public fun listAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): List<AclKey>

    public fun ensureAceIsLoaded(aclKey: AclKey, principal: Principal)
}
