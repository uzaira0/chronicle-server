package com.openlattice.chronicle.authorization

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.openlattice.chronicle.authorization.principals.PrincipalsMapManager
import com.openlattice.chronicle.authorization.processors.PermissionRemover
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.AbstractMap
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

class HazelcastAuthorizationOwnerLockTest {

    private val hazelcastInstance = mock<HazelcastInstance>()
    private val storageResolver = mock<StorageResolver>()
    private val eventBus = mock<com.google.common.eventbus.EventBus>()
    private val principalsMapManager = mock<PrincipalsMapManager>()
    private val dataSource = mock<HikariDataSource>()
    private val aces = mock<IMap<AceKey, AceValue>>()
    private val securableObjectTypes = mock<IMap<AclKey, SecurableObjectType>>()
    private lateinit var service: HazelcastAuthorizationService

    @Before
    fun setUp() {
        whenever(
            hazelcastInstance.getMap<AceKey, AceValue>(HazelcastMap.PERMISSIONS.name)
        ).thenReturn(aces)
        whenever(
            hazelcastInstance.getMap<AclKey, SecurableObjectType>(
                HazelcastMap.SECURABLE_OBJECT_TYPES.name
            )
        ).thenReturn(securableObjectTypes)
        whenever(storageResolver.getDefaultPlatformStorage())
            .thenReturn(PostgresFlavor.VANILLA to dataSource)
        whenever(principalsMapManager.getAclKeyByPrincipal(any())).thenAnswer { invocation ->
            invocation.getArgument<Set<Principal>>(0).associateWith { AclKey(UUID.randomUUID()) }
        }
        service = HazelcastAuthorizationService(
            hazelcastInstance,
            storageResolver,
            eventBus,
            principalsMapManager
        )
    }

    @Test
    fun lastOwnerCheckAndMutationRunInsideTheSameAclLock() {
        val aclKey = AclKey(UUID.randomUUID())
        val target = Principal(PrincipalType.USER, "target-owner")
        stubTargetAsActiveOwner(aclKey)
        whenever(aces.keySet(any<Predicate<AceKey, AceValue>>()))
            .thenReturn(setOf(AceKey(aclKey, Principal(PrincipalType.USER, "other-owner"))))

        service.removePermission(
            aclKey,
            target,
            EnumSet.of(Permission.OWNER)
        )

        val order = inOrder(securableObjectTypes, aces)
        order.verify(securableObjectTypes).lock(aclKey)
        order.verify(aces).getAll(any<Set<AceKey>>())
        order.verify(aces).keySet(any<Predicate<AceKey, AceValue>>())
        order.verify(aces).executeOnKey(
            org.mockito.kotlin.eq(AceKey(aclKey, target)),
            any<PermissionRemover>()
        )
        order.verify(securableObjectTypes).unlock(aclKey)
    }

    @Test
    fun removingOwnerFlagFromNonOwnerDoesNotRequireAnotherOwner() {
        val aclKey = AclKey(UUID.randomUUID())
        val target = Principal(PrincipalType.USER, "non-owner")
        whenever(aces.getAll(any<Set<AceKey>>())).thenReturn(
            mapOf(
                AceKey(aclKey, target) to AceValue(
                    EnumSet.of(Permission.READ),
                    SecurableObjectType.Study,
                    OffsetDateTime.MAX
                )
            )
        )

        service.removePermission(aclKey, target, EnumSet.of(Permission.OWNER))

        verify(aces, never()).keySet(any<Predicate<AceKey, AceValue>>())
        verify(aces).executeOnKey(
            org.mockito.kotlin.eq(AceKey(aclKey, target)),
            any<PermissionRemover>()
        )
    }

    @Test
    fun convertingPermanentOwnerToFiniteFutureExpiryStillEnforcesLastOwnerRule() {
        val aclKey = AclKey(UUID.randomUUID())
        val target = Principal(PrincipalType.USER, "last-owner")
        stubTargetAsActiveOwner(aclKey)
        whenever(aces.keySet(any<Predicate<AceKey, AceValue>>())).thenReturn(emptySet())

        assertThrows(IllegalStateException::class.java) {
            service.addPermission(
                aclKey,
                target,
                EnumSet.of(Permission.READ),
                SecurableObjectType.Study,
                OffsetDateTime.now().plusDays(1)
            )
        }

        verify(aces, never()).executeOnKey(
            org.mockito.kotlin.eq(AceKey(aclKey, target)),
            any<com.openlattice.chronicle.authorization.processors.PermissionMerger>()
        )
    }

    @Test
    fun directPermissionReadExcludesExpiredAce() {
        val aclKey = AclKey(UUID.randomUUID())
        val activePrincipal = Principal(PrincipalType.USER, "active-reader")
        val expiredPrincipal = Principal(PrincipalType.USER, "expired-reader")
        whenever(aces.getAll(any<Set<AceKey>>())).thenReturn(
            mapOf(
                AceKey(aclKey, activePrincipal) to AceValue(
                    EnumSet.of(Permission.READ),
                    SecurableObjectType.Study,
                    OffsetDateTime.MAX
                ),
                AceKey(aclKey, expiredPrincipal) to AceValue(
                    EnumSet.of(Permission.WRITE),
                    SecurableObjectType.Study,
                    OffsetDateTime.MIN
                )
            )
        )

        val permissions = service.getSecurableObjectPermissions(
            aclKey,
            setOf(activePrincipal, expiredPrincipal)
        )

        assertEquals(setOf(Permission.READ), permissions)
    }

    @Test
    fun returnedAcePreservesItsExpiration() {
        val aclKey = AclKey(UUID.randomUUID())
        val principal = Principal(PrincipalType.USER, "active-reader")
        val expiration = OffsetDateTime.now().plusDays(1)
        val aceValue = AceValue(
            EnumSet.of(Permission.READ),
            SecurableObjectType.Study,
            expiration
        )
        whenever(aces.entrySet(any<Predicate<AceKey, AceValue>>())).thenReturn(
            setOf(AbstractMap.SimpleEntry(AceKey(aclKey, principal), aceValue))
        )

        val acl = service.getAllSecurableObjectPermissions(aclKey)

        assertEquals(expiration, acl.aces.single().expirationDate)
    }

    @Test
    fun concurrentOwnerMutationsForOneAclAreSerialized() {
        val aclKey = AclKey(UUID.randomUUID())
        val firstOwner = Principal(PrincipalType.USER, "first-owner")
        val secondOwner = Principal(PrincipalType.USER, "second-owner")
        val javaLock = ReentrantLock()
        val lockAttempts = CountDownLatch(2)
        val criticalSections = AtomicInteger()
        val maxConcurrentCriticalSections = AtomicInteger()

        doAnswer {
            lockAttempts.countDown()
            javaLock.lock()
            val concurrent = criticalSections.incrementAndGet()
            maxConcurrentCriticalSections.accumulateAndGet(concurrent, ::maxOf)
            lockAttempts.await(2, TimeUnit.SECONDS)
            null
        }.whenever(securableObjectTypes).lock(aclKey)
        doAnswer {
            criticalSections.decrementAndGet()
            javaLock.unlock()
            null
        }.whenever(securableObjectTypes).unlock(aclKey)
        stubTargetAsActiveOwner(aclKey)
        whenever(aces.keySet(any<Predicate<AceKey, AceValue>>()))
            .thenReturn(setOf(AceKey(aclKey, Principal(PrincipalType.USER, "remaining-owner"))))

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                service.removePermission(aclKey, firstOwner, EnumSet.of(Permission.OWNER))
            }
            val second = executor.submit {
                service.removePermission(aclKey, secondOwner, EnumSet.of(Permission.OWNER))
            }

            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, maxConcurrentCriticalSections.get())
        assertTrue(lockAttempts.await(0, TimeUnit.SECONDS))
    }

    private fun stubTargetAsActiveOwner(aclKey: AclKey) {
        whenever(aces.getAll(any<Set<AceKey>>())).thenAnswer { invocation ->
            invocation.getArgument<Set<AceKey>>(0).associateWith {
                AceValue(
                    EnumSet.of(Permission.OWNER),
                    SecurableObjectType.Study,
                    OffsetDateTime.MAX
                )
            }
        }
    }
}
