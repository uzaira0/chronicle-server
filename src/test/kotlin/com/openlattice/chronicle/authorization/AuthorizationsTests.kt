package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import java.util.EnumSet

class AuthorizationsTests : ChronicleServerTests() {

    @Test
    fun testCheckAuthorizationOwner() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val accessCheck = AccessCheck(
            AclKey(studyId),
            EnumSet.of(Permission.OWNER)
        )
        val results = clientUser1.testAuthorizationsApi.checkAuthorizations(setOf(accessCheck))
        Assert.assertTrue("Should have at least one result", results.isNotEmpty())
        val auth = results.first()
        Assert.assertTrue("Owner should have OWNER permission", auth.permissions[Permission.OWNER] == true)
    }

    @Test
    fun testCheckAuthorizationNonOwner() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val accessCheck = AccessCheck(
            AclKey(studyId),
            EnumSet.of(Permission.OWNER)
        )
        val results = clientUser2.testAuthorizationsApi.checkAuthorizations(setOf(accessCheck))
        Assert.assertTrue("Should have at least one result", results.isNotEmpty())
        val auth = results.first()
        Assert.assertFalse("Non-owner should not have OWNER permission", auth.permissions[Permission.OWNER] == true)
    }

    @Test
    fun testCheckMultipleAuthorizations() {
        val studyId1 = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studyId2 = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val checks = setOf(
            AccessCheck(AclKey(studyId1), EnumSet.of(Permission.OWNER)),
            AccessCheck(AclKey(studyId2), EnumSet.of(Permission.READ))
        )
        val results = clientUser1.testAuthorizationsApi.checkAuthorizations(checks)
        Assert.assertEquals("Should return results for both checks", 2, results.size)
    }

    @Test
    fun testGetAccessibleObjects() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val result = clientUser1.authorizationsApi.getAccessibleObjects(
            SecurableObjectType.Study,
            Permission.OWNER,
            ""
        )
        val aclKeys = result.authorizedObjects
        Assert.assertTrue(
            "Should contain the created study",
            aclKeys.any { it.contains(studyId) }
        )
    }
}
