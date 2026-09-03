package com.openlattice.chronicle.users

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.authorization.PrincipalType
import org.junit.Assert
import org.junit.Test

class PrincipalDirectoryTests : ChronicleServerTests() {

    @Test
    fun testGetAllUsers() {
        val users = clientUser1.principalApi.getAllUsers()
        Assert.assertTrue("Should contain test_user1", users.containsKey(testUser1.id))
        Assert.assertTrue("Should contain test_user2", users.containsKey(testUser2.id))
    }

    @Test
    fun testGetCurrentRoles() {
        val roles = clientUser1.principalApi.getCurrentRoles()
        Assert.assertNotNull("Roles should not be null", roles)
    }

    @Test
    fun testGetUser() {
        val user = clientUser1.principalApi.getUser(testUser1.id)
        Assert.assertNotNull("Should return User", user)
        Assert.assertEquals("Should preserve user ID", testUser1.id, user.id)
    }

    @Test
    fun testGetUsers() {
        val userIds = setOf(testUser1.id, testUser2.id)
        val users = clientUser1.testPrincipalApi.getUsers(userIds)
        Assert.assertEquals("Should return 2 users", 2, users.size)
        Assert.assertTrue("Should contain test_user1", users.containsKey(testUser1.id))
        Assert.assertTrue("Should contain test_user2", users.containsKey(testUser2.id))
        Assert.assertEquals("Should preserve test_user1 ID", testUser1.id, users.getValue(testUser1.id).id)
    }

    @Test
    fun testSyncCallingUser() {
        clientUser1.principalApi.syncCallingUser()
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testGetSecurablePrincipalNotFound() {
        val fakePrincipal = com.openlattice.chronicle.authorization.Principal(
            PrincipalType.USER, "nonexistent_user_that_does_not_exist"
        )
        clientUser1.principalApi.getSecurablePrincipal(fakePrincipal)
    }
}
