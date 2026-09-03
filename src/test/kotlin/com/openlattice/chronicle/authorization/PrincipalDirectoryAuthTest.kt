package com.openlattice.chronicle.authorization

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import org.junit.Assert
import org.junit.Test

/**
 * Verify that USER-type principal lookup now requires READ permission (Item 2).
 */
class PrincipalDirectoryAuthTest : ChronicleServerTests() {

    /**
     * A non-admin user without explicit READ permission on another user's principal
     * should get a 403 when calling getSecurablePrincipal for a USER-type principal.
     */
    @Test
    fun testUserPrincipalLookupRequiresReadAccess() {
        val principalApi = clientUser2.principalApi
        val targetPrincipal = Principal(PrincipalType.USER, testUser1.id)

        try {
            principalApi.getSecurablePrincipal(targetPrincipal)
            Assert.fail("Expected 403 Forbidden when looking up a USER principal without READ permission")
        } catch (e: RhizomeRetrofitCallException) {
            Assert.assertEquals(
                "USER-type principal lookup should now require READ access",
                403,
                e.code
            )
        }
    }

    /**
     * An admin user should still be able to look up any USER-type principal.
     */
    @Test
    fun testAdminCanLookUpUserPrincipal() {
        val principalApi = clientAdmin.principalApi
        val targetPrincipal = Principal(PrincipalType.USER, testUser1.id)

        val result = principalApi.getSecurablePrincipal(targetPrincipal)
        Assert.assertNotNull("Admin should be able to look up USER principals", result)
        Assert.assertEquals(PrincipalType.USER, result.principal.type)
    }
}
