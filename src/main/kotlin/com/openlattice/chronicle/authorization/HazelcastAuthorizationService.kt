package com.openlattice.chronicle.authorization

import com.codahale.metrics.annotation.Timed
import com.google.common.eventbus.EventBus
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.hazelcast.query.Predicates
import com.openlattice.chronicle.authorization.aggregators.AuthorizationSetAggregator
import com.openlattice.chronicle.authorization.aggregators.PrincipalAggregator
import com.openlattice.chronicle.authorization.principals.PrincipalsMapManager
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore.Companion.ACL_KEY_INDEX
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore.Companion.PERMISSIONS_INDEX
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore.Companion.PRINCIPAL_INDEX
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore.Companion.PRINCIPAL_TYPE_INDEX
import com.openlattice.chronicle.mapstores.authorization.PermissionMapstore.Companion.SECURABLE_OBJECT_TYPE_INDEX
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PERMISSIONS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.SECURABLE_OBJECTS
import com.openlattice.chronicle.storage.StorageResolver
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.google.common.collect.HashMultimap
import com.google.common.collect.Maps
import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.processors.PermissionMerger
import com.openlattice.chronicle.authorization.processors.PermissionRemover
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.PostgresColumns.Companion.ACL_KEY
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PRINCIPAL_TYPE
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.*
import java.util.function.Function
import java.util.stream.Collectors
import java.util.stream.Stream

// reason: core authorization/RLS service — restructuring/splitting risks auth-check behavior; keep cohesive
@Suppress("TooManyFunctions")
@Service
public open class HazelcastAuthorizationService(
    private val hazelcastInstance: HazelcastInstance,
    storageResolver: StorageResolver,
    // reason: Spring DI-injected dependency retained on the constructor signature; removal would alter wiring
    @Suppress("UnusedPrivateProperty")
    private val eventBus: EventBus,
    private val principalsMapManager: PrincipalsMapManager,
) : AuthorizationManager {
    private val authorizationStorage = storageResolver.getDefaultPlatformStorage()
    private val aces: IMap<AceKey, AceValue> = HazelcastMap.PERMISSIONS.getMap(hazelcastInstance)
    private val securableObjectTypes = HazelcastMap.SECURABLE_OBJECT_TYPES.getMap(hazelcastInstance)

    // reason: predicate-builder helpers; SpreadOperator required by Hazelcast vararg Predicates API (arrays small)
    @Suppress("TooManyFunctions", "SpreadOperator")
    internal companion object {
        private val logger = LoggerFactory.getLogger(HazelcastAuthorizationService::class.java)

        /**
         *  1. acl key
         *  2. securable object type
         *  3. securable object id
         *  4. securable object name
         */
        private val INSERT_SECURABLE_OBJECT_SQL = """
            INSERT INTO ${SECURABLE_OBJECTS.name} VALUES (?,?,?,?) ON CONFLICT DO NOTHING
        """.trimIndent()

        /**
         *  1. acl key
         *  2. principal type
         *  3. principal id
         *  4. permissions
         *  5. expiration date
         */
        private val INSERT_ACES = """
            INSERT INTO ${PERMISSIONS.name} VALUES (?,?,?,?,?)
        """.trimIndent()

        private val DELETE_PRINCIPAL_PERMISSIONS = """
            DELETE FROM ${PERMISSIONS.name} WHERE ${PRINCIPAL_TYPE.name} = ? AND ${PRINCIPAL_ID.name} = ? 
            RETURNING ${ACL_KEY.name}
        """.trimIndent()

        private fun noAccess(permissions: EnumSet<Permission>): EnumMap<Permission, Boolean> {
            val pm = EnumMap<Permission, Boolean>(Permission::class.java)
            permissions.forEach { pm[it] = false }
            return pm
        }

        private fun matches(
            aclKeys: Collection<AclKey>,
            principals: Set<Principal>,
            evaluationTime: OffsetDateTime
        ): Predicate<AceKey, AceValue> {
            return Predicates.and<AceKey, AceValue>(
                hasAnyAclKeys(aclKeys),
                hasAnyPrincipals(principals),
                ActiveAcePredicate(evaluationTime)
            )
        }

        private fun matches(
            aclKey: AclKey,
            permissions: EnumSet<Permission>,
            evaluationTime: OffsetDateTime
        ): Predicate<AceKey, AceValue> {
            return Predicates.and<AceKey, AceValue>(
                hasAclKey(aclKey),
                hasExactPermissions(permissions),
                ActiveAcePredicate(evaluationTime)
            )
        }

        private fun hasExactPermissions(permissions: EnumSet<Permission>): Predicate<AceKey, AceValue> {

            val subPredicates = permissions
                .map { Predicates.equal<AceKey, AceValue>(PERMISSIONS_INDEX, it) }
                .toTypedArray()

            return Predicates.and(*subPredicates)
        }

        private fun hasAnyPrincipals(principals: Collection<Principal>): Predicate<AceKey, AceValue> {
            return Predicates.`in`<AceKey, AceValue>(PRINCIPAL_INDEX, *principals.toTypedArray())
        }

        private fun hasAnyAclKeys(aclKeys: Collection<AclKey>): Predicate<AceKey, AceValue> {
            return Predicates.`in`<AceKey, AceValue>(ACL_KEY_INDEX, *aclKeys.map { it.index }.toTypedArray())
        }

        private fun hasAclKey(aclKey: AclKey): Predicate<AceKey, AceValue> {
            return Predicates.equal(ACL_KEY_INDEX, aclKey.index)
        }

        private fun hasType(objectType: SecurableObjectType): Predicate<AceKey, AceValue> {
            return Predicates.equal(SECURABLE_OBJECT_TYPE_INDEX, objectType)
        }

        private fun hasAnyType(objectTypes: Collection<SecurableObjectType>): Predicate<AceKey, AceValue> {
            return Predicates.`in`(SECURABLE_OBJECT_TYPE_INDEX, *objectTypes.toTypedArray())
        }

        private fun hasPrincipal(principal: Principal): Predicate<AceKey, AceValue> {
            return Predicates.equal(PRINCIPAL_INDEX, principal)
        }

        private fun hasPrincipalType(type: PrincipalType): Predicate<AceKey, AceValue> {
            return Predicates.equal(PRINCIPAL_TYPE_INDEX, type)
        }
    }

    override fun createUnnamedSecurableObject(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>,
        objectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    ) {
        authorizationStorage.second.connection.use { connection ->
            createUnnamedSecurableObject(
                connection,
                aclKey,
                principal,
                permissions,
                objectType,
                expirationDate
            )
        }
        ensureAceIsLoaded(aclKey, principal)
    }

    override fun ensureAceIsLoaded(aclKey: AclKey, principal: Principal) {
        // Force-load from MapStore into cache. Using get() triggers a synchronous
        // MapStore.load() if the key is absent, which is fast (~ms) unlike loadAll()
        // which uses waitUntilLoaded() with Thread.sleep() polling (~2s).
        val aceKey = AceKey(aclKey, principal)
        aces.evict(aceKey)
        checkNotNull(aces.get(aceKey)) {
            "No committed ACE found for ${principal.type} on $aclKey"
        }
    }

    override fun createUnnamedSecurableObject(
        connection: Connection,
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>,
        objectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    ) {
        ensurePrincipalsExist(setOf(principal))
        val aclKeyArray = PostgresArrays.createUuidArray(connection, aclKey)

        /**
         * Only create the securable object entry if it hasn't already been created. Currently named objects from
         * [AbstractSecurableObject] get an entry here on registration, before the ACL is initialized.
         *
         * This is fine as in those cases we simply skip inserting the securable object and add permissions.
         *
         */
        if (!securableObjectTypes.containsKey(aclKey)) {
            val insertSecObj = connection.prepareStatement(INSERT_SECURABLE_OBJECT_SQL)

            insertSecObj.setArray(1, aclKeyArray)
            insertSecObj.setString(2, objectType.name)
            insertSecObj.setObject(3, aclKey.last())
            insertSecObj.setString(4, aclKey.last().toString()) //Unnamed objects so just use id as name
            insertSecObj.executeUpdate()
        }

        val insertPermissions = connection.prepareStatement(INSERT_ACES)
        insertPermissions.setArray(1, aclKeyArray)
        insertPermissions.setString(2, principal.type.name)
        insertPermissions.setString(3, principal.id)
        insertPermissions.setArray(4, PostgresArrays.createTextArray(connection, permissions.map { it.name }))
        insertPermissions.setObject(5, expirationDate)
        insertPermissions.executeUpdate()
    }

    /** Add Permissions **/

    override fun addPermission(aclKey: AclKey, principal: Principal, permissions: EnumSet<Permission>) {
        addPermission(aclKey, principal, permissions, OffsetDateTime.MAX)
    }

    override fun addPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: Set<Permission>,
        expirationDate: OffsetDateTime
    ) {
        // Future: We should do something better than reading the securable object type.
        val securableObjectType = getDefaultObjectType(securableObjectTypes, aclKey)

        addPermission(aclKey, principal, permissions, securableObjectType, expirationDate)
    }

    override fun addPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: Set<Permission>,
        securableObjectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    ) {
        ensurePrincipalsExist(setOf(principal))
        withAclMutationLock(aclKey) {
            if (expirationDate != OffsetDateTime.MAX) {
                ensureAclKeyHasOtherUserOwner(aclKey, setOf(principal))
            }
            aces.executeOnKey(
                AceKey(aclKey, principal),
                PermissionMerger(permissions, securableObjectType, expirationDate)
            )
        }
    }

    override fun addPermissions(
        keys: Set<AclKey>,
        principal: Principal,
        permissions: EnumSet<Permission>,
        securableObjectType: SecurableObjectType
    ) {
        addPermissions(keys, principal, permissions, securableObjectType, OffsetDateTime.MAX)
    }

    override fun addPermissions(
        keys: Set<AclKey>,
        principal: Principal,
        permissions: EnumSet<Permission>,
        securableObjectType: SecurableObjectType,
        expirationDate: OffsetDateTime
    ) {
        ensurePrincipalsExist(setOf(principal))
        keys.forEach { aclKey ->
            withAclMutationLock(aclKey) {
                if (expirationDate != OffsetDateTime.MAX) {
                    ensureAclKeyHasOtherUserOwner(aclKey, setOf(principal))
                }
                aces.executeOnKey(
                    AceKey(aclKey, principal),
                    PermissionMerger(permissions, securableObjectType, expirationDate)
                )
            }
        }
    }

    override fun addPermissions(acls: List<Acl>) {
        ensureAclPrincipalsExist(acls)
        acls.forEach { acl ->
            val aclKey = AclKey(acl.aclKey)
            withAclMutationLock(aclKey) {
                val principalsReceivingNonPermanentAce = acl.aces
                    .filterNot { it.expirationDate == OffsetDateTime.MAX }
                    .mapTo(mutableSetOf()) { it.principal }
                if (principalsReceivingNonPermanentAce.isNotEmpty()) {
                    ensureAclKeyHasOtherUserOwner(
                        aclKey,
                        principalsReceivingNonPermanentAce,
                    )
                }

                val updates = getAceValueToAceKeyMap(listOf(acl))
                updates.keySet().forEach {
                    val aceKeys = updates[it]
                    aces.executeOnKeys(
                        aceKeys,
                        PermissionMerger(it.permissions, it.securableObjectType, it.expirationDate)
                    )
                }
            }
        }
    }

    /** Remove Permissions **/

    override fun removePermissions(acls: List<Acl>) {
        acls.forEach { acl ->
            val aclKey = AclKey(acl.aclKey)
            val owners = acl.aces
                .filter { ace -> ace.permissions.contains(Permission.OWNER) }
                .map { ace -> ace.principal }
                .toSet()

            withAclMutationLock(aclKey) {
                if (owners.isNotEmpty()) {
                    ensureAclKeyHasOtherUserOwner(aclKey, owners)
                }
                val updates = getAceValueToAceKeyMap(listOf(acl))
                updates.keySet().forEach {
                    aces.executeOnKeys(updates[it], PermissionRemover(it.permissions))
                }
            }
        }
    }

    override fun removePermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>
    ) {
        withAclMutationLock(aclKey) {
            if (permissions.contains(Permission.OWNER)) {
                ensureAclKeyHasOtherUserOwner(aclKey, setOf(principal))
            }
            aces.executeOnKey(AceKey(aclKey, principal), PermissionRemover(permissions))
        }
    }

    override fun deletePermissions(aclKey: AclKey) {
        securableObjectTypes.delete(aclKey)
        aces.removeAll(hasAclKey(aclKey))
    }

    override fun deletePrincipalPermissions(principal: Principal) {
        aces.removeAll(hasPrincipal(principal))
    }

    /** Set Permissions **/

    override fun setPermissions(acls: List<Acl>) {
        ensureAclPrincipalsExist(acls)
        val types = getSecurableObjectTypeMapForAcls(acls)

        acls.forEach { acl ->
            val aclKey = AclKey(acl.aclKey)
            withAclMutationLock(aclKey) {
                val principalsLosingPermanentOwnership = acl.aces
                    .filterNot { ace ->
                        ace.permissions.contains(Permission.OWNER) &&
                                ace.expirationDate == OffsetDateTime.MAX
                    }
                    .mapTo(mutableSetOf()) { it.principal }
                if (principalsLosingPermanentOwnership.isNotEmpty()) {
                    ensureAclKeyHasOtherUserOwner(
                        aclKey,
                        principalsLosingPermanentOwnership,
                    )
                }

                val securableObjectType = getDefaultObjectType(types, aclKey)
                val updates = acl.aces.associate { ace ->
                    val permissions = EnumSet.copyOf(ace.permissions)
                    AceKey(aclKey, ace.principal) to
                            AceValue(permissions, securableObjectType, ace.expirationDate)
                }
                aces.putAll(updates)
            }
        }
    }

    private fun getSecurableObjectTypeMapForAcls(acls: Collection<Acl>): Map<AclKey, SecurableObjectType> {
        return securableObjectTypes.getAll(acls.map { it.aclKey }.toSet())
    }

    override fun setPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>
    ) {
        setPermission(aclKey, principal, permissions, OffsetDateTime.MAX)
    }

    override fun setPermission(
        aclKey: AclKey,
        principal: Principal,
        permissions: EnumSet<Permission>,
        expirationDate: OffsetDateTime
    ) {
        ensurePrincipalsExist(setOf(principal))
        withAclMutationLock(aclKey) {
            if (!permissions.contains(Permission.OWNER) || expirationDate != OffsetDateTime.MAX) {
                ensureAclKeyHasOtherUserOwner(aclKey, setOf(principal))
            }

            //This should be a rare call to overwrite all permissions, so it's okay to do a read before write.
            val securableObjectType = getDefaultObjectType(securableObjectTypes, aclKey)
            aces[AceKey(aclKey, principal)] = AceValue(permissions, securableObjectType, expirationDate)
        }
    }

    override fun setPermission(aclKeys: Set<AclKey>, principals: Set<Principal>, permissions: EnumSet<Permission>) {
        //This should be a rare call to overwrite all permissions, so it's okay to do a read before write.
        ensurePrincipalsExist(principals)
        val securableObjectTypesForAclKeys = securableObjectTypes.getAll(aclKeys)
        aclKeys.forEach { aclKey ->
            withAclMutationLock(aclKey) {
                if (!permissions.contains(Permission.OWNER)) {
                    ensureAclKeyHasOtherUserOwner(aclKey, principals)
                }
                val objectType = getDefaultObjectType(securableObjectTypesForAclKeys, aclKey)
                val newPermissions = principals.associateWith { principal ->
                    AceValue(EnumSet.copyOf(permissions), objectType, OffsetDateTime.MAX)
                }.mapKeys { (principal, _) -> AceKey(aclKey, principal) }
                aces.putAll(newPermissions)
            }
        }
    }

    override fun setPermissions(permissions: Map<AceKey, EnumSet<Permission>>) {
        ensurePrincipalsExist(permissions.keys.mapTo(mutableSetOf()) { it.principal })
        val securableObjectTypesForAclKeys = securableObjectTypes.getAll(permissions.keys.map { it.aclKey }.toSet())

        permissions.entries.groupBy { it.key.aclKey }.forEach { (aclKey, aclEntries) ->
            withAclMutationLock(aclKey) {
                val principalsLosingOwnership = aclEntries
                    .filterNot { it.value.contains(Permission.OWNER) }
                    .mapTo(mutableSetOf()) { it.key.principal }
                if (principalsLosingOwnership.isNotEmpty()) {
                    ensureAclKeyHasOtherUserOwner(aclKey, principalsLosingOwnership)
                }
                val objectType = getDefaultObjectType(securableObjectTypesForAclKeys, aclKey)
                val newPermissions = aclEntries.associate { (aceKey, acePermissions) ->
                    aceKey to AceValue(EnumSet.copyOf(acePermissions), objectType, OffsetDateTime.MAX)
                }
                aces.putAll(newPermissions)
            }
        }
    }

    /*** AUTH CHECKS ***/

    @Timed
    override fun authorize(
        requests: Map<AclKey, EnumSet<Permission>>,
        principals: Set<Principal>
    ): MutableMap<AclKey, EnumMap<Permission, Boolean>> {

        val permissionMap = requests.mapValues { noAccess(it.value) }.toMutableMap()

        val aceKeys = requests.keys
            .flatMap { aclKey -> principals.map { principal -> AceKey(aclKey, principal) } }
            .toSet()
        val evaluationTime = OffsetDateTime.now()

        aces.getAll(aceKeys)
            .filterValues { it.isActiveAt(evaluationTime) }
            .forEach { (aceKey, aceValue) ->
                val aclKeyPermissions = permissionMap.getValue(aceKey.aclKey)
                aceValue.permissions.forEach { permission ->
                    aclKeyPermissions.computeIfPresent(permission) { _, _ -> true }
                }
            }

        return permissionMap
    }

    @Timed
    override fun accessChecksForPrincipals(
        accessChecks: Set<AccessCheck>,
        principals: Set<Principal>
    ): List<Authorization> {
        val requests: MutableMap<AclKey, EnumSet<Permission>> = Maps.newLinkedHashMapWithExpectedSize(accessChecks.size)

        accessChecks.forEach {
            val p = requests.getOrDefault(it.aclKey, EnumSet.noneOf(Permission::class.java))
            p.addAll(it.permissions)
            requests[it.aclKey] = p
        }

        return authorize(requests, principals).map { Authorization(it.key, it.value) }
    }

    @Timed
    override fun checkIfHasPermissions(
        aclKey: AclKey,
        principals: Set<Principal>,
        requiredPermissions: EnumSet<Permission>
    ): Boolean {
        val aceKeys = principals.map { AceKey(aclKey, it) }.toSet()
        val evaluationTime = OffsetDateTime.now()

        return aces.getAll(aceKeys)
            .values
            .filter { it.isActiveAt(evaluationTime) }
            .flatMap { it.permissions }
            .toSet()
            .containsAll(requiredPermissions)
    }

    @Timed
    override fun getSecurableObjectSetsPermissions(
        aclKeySets: Collection<Set<AclKey>>,
        principals: Set<Principal>
    ): Map<Set<AclKey>, EnumSet<Permission>> {
        val evaluationTime = OffsetDateTime.now()
        return aclKeySets.parallelStream().collect(Collectors.toMap<Set<AclKey>, Set<AclKey>, EnumSet<Permission>>(
            Function.identity(),
            Function { getSecurableObjectSetPermissions(it, principals, evaluationTime) }
        ))
    }

    @Timed
    override fun getSecurableObjectPermissions(
        aclKey: AclKey,
        principals: Set<Principal>
    ): Set<Permission> {
        val objectPermissions = EnumSet.noneOf(Permission::class.java)
        val aceKeys = principals.map { AceKey(aclKey, it) }.toSet()
        val evaluationTime = OffsetDateTime.now()
        aces.getAll(aceKeys)
            .values
            .asSequence()
            .filter { it.isActiveAt(evaluationTime) }
            .forEach { objectPermissions.addAll(it.permissions) }

        return objectPermissions
    }

    @Timed
    @Suppress("DEPRECATION")
    override fun getAuthorizedObjectsOfType(
        principal: Principal,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): Stream<AclKey> {
        return getAuthorizedObjectsOfType(setOf(principal), objectType, permissions)
    }

    @Timed
    @Deprecated(
        message = "Deprecated inefficient version using stream",
        replaceWith = ReplaceWith("listAuthorizedObjectsOfType")
    )
    override fun getAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): Stream<AclKey> {
        val principalPredicate = if (principals.size == 1) hasPrincipal(principals.first()) else hasAnyPrincipals(
            principals
        )
        val p = Predicates.and<AceKey, AceValue>(
            principalPredicate,
            hasType(objectType),
            hasExactPermissions(permissions),
            ActiveAcePredicate(OffsetDateTime.now())
        )

        return aces.keySet(p)
            .stream()
            .map { it.aclKey }
            .distinct()
    }

    @Timed
    override fun listAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>
    ): List<AclKey> {
        val principalPredicate = if (principals.size == 1) hasPrincipal(principals.first()) else hasAnyPrincipals(
            principals
        )
        val p = Predicates.and<AceKey, AceValue>(
            principalPredicate,
            hasType(objectType),
            hasExactPermissions(permissions),
            ActiveAcePredicate(OffsetDateTime.now())
        )

        return aces.keySet(p).map { it.aclKey }
    }

    @Timed
    override fun getAuthorizedObjectsOfTypes(
        principals: Set<Principal>,
        objectTypes: Collection<SecurableObjectType>,
        permissions: EnumSet<Permission>
    ): Stream<AclKey> {
        val principalPredicate = if (principals.size == 1) hasPrincipal(principals.first()) else hasAnyPrincipals(
            principals
        )
        val p = Predicates.and<AceKey, AceValue>(
            principalPredicate,
            hasAnyType(objectTypes),
            hasExactPermissions(permissions),
            ActiveAcePredicate(OffsetDateTime.now())
        )

        return aces.keySet(p)
            .stream()
            .map { it.aclKey }
            .distinct()
    }

    @Timed
    override fun getAuthorizedObjectsOfType(
        principals: Set<Principal>,
        objectType: SecurableObjectType,
        permissions: EnumSet<Permission>,
        additionalFilter: Predicate<*, *>
    ): Stream<AclKey> {
        val p = Predicates.and<AceKey, AceValue>(
            hasAnyPrincipals(principals),
            hasType(objectType),
            hasExactPermissions(permissions),
            ActiveAcePredicate(OffsetDateTime.now()),
            additionalFilter
        )
        return aces.keySet(p)
            .stream()
            .map { obj: AceKey -> obj.aclKey }
            .distinct()
    }

    @Timed
    override fun getAllSecurableObjectPermissions(key: AclKey): Acl {
        val evaluationTime = OffsetDateTime.now()
        val acesWithPermissions = aces.entrySet(
            Predicates.and(hasAclKey(key), ActiveAcePredicate(evaluationTime))
        )
            .filter { it.value.isNotEmpty() }
            .map { Ace(it.key.principal, it.value.permissions, it.value.expirationDate) }
            .toSet()

        return Acl(key, acesWithPermissions)
    }

    @Timed
    override fun getAllSecurableObjectPermissions(keys: Set<AclKey>): Set<Acl> {
        val evaluationTime = OffsetDateTime.now()
        return aces.entrySet(
            Predicates.and(hasAnyAclKeys(keys), ActiveAcePredicate(evaluationTime))
        )
            .filter { it.value.isNotEmpty() }
            .groupBy { it.key.aclKey }
            .mapTo(mutableSetOf()) { entry ->
                Acl(
                    entry.key,
                    entry.value.mapTo(mutableSetOf()) {
                        Ace(it.key.principal, it.value.permissions, it.value.expirationDate)
                    }
                )
            }
    }

    @Timed
    override fun getAuthorizedPrincipalsOnSecurableObject(
        key: AclKey, permissions: EnumSet<Permission>
    ): Set<Principal> {
        val principalMap = mutableMapOf(key to PrincipalSet(mutableSetOf()))
        val evaluationTime = OffsetDateTime.now()

        return aces.aggregate(PrincipalAggregator(principalMap), matches(key, permissions, evaluationTime))
            .getResult()
            .getValue(key)
    }

    @Timed
    override fun getSecurableObjectOwners(key: AclKey): Set<Principal> {
        return getAuthorizedPrincipalsOnSecurableObject(key, EnumSet.of(Permission.OWNER))
    }

    @Timed
    override fun getOwnersForSecurableObjects(aclKeys: Collection<AclKey>): SetMultimap<AclKey, Principal> {
        val result: SetMultimap<AclKey, Principal> = HashMultimap.create()

        aces.keySet(
            Predicates.and(
                hasAnyAclKeys(aclKeys),
                hasExactPermissions(EnumSet.of(Permission.OWNER)),
                ActiveAcePredicate(OffsetDateTime.now())
            )
        )
            .forEach { result.put(it.aclKey, it.principal) }

        return result
    }

    override fun deleteAllPrincipalPermissions(principal: Principal) {
        /*
        This will delete from db and then evict from memory.
        Since we check if principal exists before adding a permission it should fail cleanly as long as principal
        was deleted before permissions were deleted.
        */
        BasePostgresIterable(
            PreparedStatementHolderSupplier(
                authorizationStorage.second,
                DELETE_PRINCIPAL_PERMISSIONS
            ) {
                it.setString(1, principal.type.name)
                it.setString(2, principal.id)
            }) { AceKey(ResultSetAdapters.aclKey(it), principal) }
            .forEach(aces::evict)
    }


    /** Private Helpers **/

    private fun ensureAclKeyHasOtherUserOwner(
        aclKey: AclKey,
        principals: Set<Principal>,
    ) {
        val userPrincipals = principals.stream()
            .filter { p: Principal -> p.type == PrincipalType.USER || p == Principals.getAdminRole() }
            .collect(Collectors.toSet())
        if (userPrincipals.isEmpty()) {
            return
        }

        val targetAceKeys = userPrincipals.mapTo(mutableSetOf()) { AceKey(aclKey, it) }
        val removesPermanentOwner = aces.getAll(targetAceKeys)
            .values
            .any { it.isPermanent() && it.permissions.contains(Permission.OWNER) }
        if (!removesPermanentOwner) {
            return
        }

        val allOtherUserOwnersPredicate = Predicates.and<AceKey, AceValue>(
            hasAclKey(aclKey),
            hasExactPermissions(EnumSet.of(Permission.OWNER)),
            PermanentAcePredicate(),
            Predicates.not<AceKey, AceValue>(hasAnyPrincipals(userPrincipals)),
            Predicates.or<AceKey, AceValue>(
                hasPrincipalType(PrincipalType.USER),
                hasPrincipal(Principals.getAdminRole())
            )
        )

        check(aces.keySet(allOtherUserOwnersPredicate).isNotEmpty()) {
            "Unable to remove owner permissions as the securable object will be left without a permanent owner " +
                    "of type USER or the admin role"
        }
    }

    private inline fun <T> withAclMutationLock(aclKey: AclKey, mutation: () -> T): T {
        securableObjectTypes.lock(aclKey)
        return try {
            mutation()
        } finally {
            securableObjectTypes.unlock(aclKey)
        }
    }

    private fun getAceValueToAceKeyMap(acls: List<Acl>): SetMultimap<AceValue, AceKey> {
        val map: SetMultimap<AceValue, AceKey> = HashMultimap.create()
        val types = getSecurableObjectTypeMapForAcls(acls)
        acls.forEach { acl: Acl ->
            val aclKey = AclKey(acl.aclKey)

            acl.aces.forEach {
                map.put(
                    AceValue(EnumSet.copyOf(it.permissions), getDefaultObjectType(types, aclKey), it.expirationDate),
                    AceKey(aclKey, it.principal)
                )
            }
        }
        return map
    }

    private fun getSecurableObjectSetPermissions(
        aclKeySet: Set<AclKey>,
        principals: Set<Principal>,
        evaluationTime: OffsetDateTime
    ): EnumSet<Permission> {

        val authorizationsMap = aclKeySet
            .associateWith { EnumSet.noneOf(Permission::class.java) }
            .toMutableMap()

        return aces.aggregate(
            AuthorizationSetAggregator(authorizationsMap),
            matches(aclKeySet, principals, evaluationTime)
        )
    }

    private fun getDefaultObjectType(map: Map<AclKey, SecurableObjectType>, aclKey: AclKey): SecurableObjectType {
        val securableObjectType = map[aclKey] ?: SecurableObjectType.Unknown

        if (securableObjectType == SecurableObjectType.Unknown) {
            logger.warn("Unrecognized object type for acl key {} key ", aclKey)
        }

        return securableObjectType
    }

    private fun ensureAclPrincipalsExist(acls: List<Acl>) {
        val principals = acls.flatMap { it.aces }.mapTo(mutableSetOf()) { it.principal }
        ensurePrincipalsExist(principals)
    }

    private fun ensurePrincipalsExist(principals: Set<Principal>) {
        val nonexistentPrincipals = principals - principalsMapManager.getAclKeyByPrincipal(principals).keys
        if (nonexistentPrincipals.isNotEmpty()) {
            // Auto-provision missing user principals (self-hosted / local mode)
            val usersToProvision = nonexistentPrincipals.filter { it.type == PrincipalType.USER }
            val nonUsers = nonexistentPrincipals - usersToProvision.toSet()
            if (usersToProvision.isNotEmpty()) {
                val principalsMap = HazelcastMap.PRINCIPALS.getMap(hazelcastInstance)
                val principalTreesMap = HazelcastMap.PRINCIPAL_TREES.getMap(hazelcastInstance)
                usersToProvision.forEach { principal ->
                    logger.info("Auto-provisioning user principal: {}", principal.id)
                    val aclKey = AclKey(UUID.randomUUID())
                    val sp = SecurablePrincipal(
                        Optional.of(aclKey.first()),
                        principal,
                        principal.id,
                        Optional.of("Auto-provisioned user")
                    )
                    principalsMap[aclKey] = sp
                    principalTreesMap[aclKey] = AclKeySet()
                }
            }
            check(nonUsers.isEmpty()) {
                "Could not update permissions because principals $nonUsers do not exist."
            }
        }
    }
}
