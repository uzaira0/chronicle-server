package com.openlattice.chronicle.admin

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.authorization.AceKey
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AdminTests : ChronicleServerTests() {

    @Test
    fun testGetCurrentUserPrincipals() {
        val principals = clientAdmin.adminApi.getCurrentUserPrincipals()
        Assert.assertTrue("Admin should have non-empty principals", principals.isNotEmpty())
    }

    @Test
    fun testGetCurrentUserPrincipalsAsRegularUser() {
        val principals = clientUser1.adminApi.getCurrentUserPrincipals()
        Assert.assertTrue("Regular user should have non-empty principals", principals.isNotEmpty())
    }

    @Test
    fun testGetUserPrincipals() {
        val principals = clientAdmin.adminApi.getUserPrincipals(testUser1.id)
        Assert.assertTrue("Should return non-empty principals for test_user1", principals.isNotEmpty())
    }

    @Test
    fun testReloadAllCaches() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studies = HazelcastMap.STUDIES.getMap(hazelcastInstance)
        val aces = cachedAces(studyId)
        studies.evict(studyId)
        aces.forEach { permissions().evict(it) }
        assertFalse(studyId in studies.keys)

        clientAdmin.testAdminApi.reloadCache()

        // Every mapstore-backed cache is loaded again from Postgres, without a read that would load it lazily.
        assertTrue("the study is back in its cache", studyId in studies.keys)
        assertEquals(aces, cachedAces(studyId))
    }

    @Test
    fun testReloadSpecificCache() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val aces = cachedAces(studyId)
        assertTrue("creating a study caches its owner and admin ACEs", aces.size >= 2)
        aces.forEach { permissions().evict(it) }
        assertEquals(emptySet<AceKey>(), cachedAces(studyId))

        clientAdmin.testAdminApi.reloadCache(HazelcastMap.PERMISSIONS.name)

        assertEquals(aces, cachedAces(studyId))
    }

    private fun permissions() = HazelcastMap.PERMISSIONS.getMap(hazelcastInstance)

    /** In-memory keys only: `keys` never asks the mapstore, so an evicted entry stays absent until a reload. */
    private fun cachedAces(studyId: UUID): Set<AceKey> =
        permissions().keys.filterTo(mutableSetOf()) { it.aclKey == AclKey(studyId) }
}
