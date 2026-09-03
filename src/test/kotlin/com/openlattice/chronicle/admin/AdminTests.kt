package com.openlattice.chronicle.admin

import com.openlattice.chronicle.ChronicleServerTests
import org.junit.Assert
import org.junit.Test

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
        clientAdmin.testAdminApi.reloadCache()
    }

    @Test
    fun testReloadSpecificCache() {
        clientAdmin.testAdminApi.reloadCache("PERMISSIONS")
    }
}
