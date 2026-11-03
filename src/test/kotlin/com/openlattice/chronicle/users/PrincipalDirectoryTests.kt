package com.openlattice.chronicle.users

import org.junit.Assert.assertThrows

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.base.OK
import org.junit.Assert
import org.junit.Test

class PrincipalDirectoryTests : ChronicleServerTests() {


    @Test
    fun testGetCurrentRoles() {
        val roles = clientUser1.principalApi.getCurrentRoles()
        Assert.assertNotNull("Roles should not be null", roles)
    }

    @Test
    fun testSyncCallingUser() {
        // test_user1 is in the configured user listing, so the sync is accepted.
        Assert.assertEquals(OK(), clientUser1.principalApi.syncCallingUser())
    }

    @Test
    fun testGetSecurablePrincipalNotFound() {
        val fakePrincipal = com.openlattice.chronicle.authorization.Principal(
            PrincipalType.USER, "nonexistent_user_that_does_not_exist"
        )
        assertThrows(RhizomeRetrofitCallException::class.java) {
            clientUser1.principalApi.getSecurablePrincipal(fakePrincipal)
        }
    }
}
