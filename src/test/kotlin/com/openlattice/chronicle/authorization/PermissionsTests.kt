package com.openlattice.chronicle.authorization

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import java.util.EnumSet

class PermissionsTests : ChronicleServerTests() {

    @Test
    fun testGetAcl() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val acl = clientUser1.permissionsApi.getAcl(AclKey(studyId))
        Assert.assertNotNull("ACL should not be null", acl)
        Assert.assertTrue("Owner should have ACEs", acl.aces.toList().isNotEmpty())
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testGetAclNonOwner() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        clientUser2.permissionsApi.getAcl(AclKey(studyId))
    }

    @Test
    fun testUpdateAclAdd() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val aclKey = AclKey(studyId)

        val ace = Ace(testUser2, EnumSet.of(Permission.READ))
        val aclData = AclData(Acl(aclKey, listOf(ace)), Action.ADD)
        clientUser1.permissionsApi.updateAcl(aclData)

        // Verify user2 can now read the study
        val accessCheck = AccessCheck(aclKey, EnumSet.of(Permission.READ))
        val results = clientUser2.testAuthorizationsApi.checkAuthorizations(setOf(accessCheck))
        Assert.assertTrue("User2 should now have READ", results.isNotEmpty())
        Assert.assertTrue("User2 should have READ", results.first().permissions[Permission.READ] == true)
    }

    @Test
    fun testUpdateAclRemove() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val aclKey = AclKey(studyId)

        // Grant READ to user2
        val addAce = Ace(testUser2, EnumSet.of(Permission.READ))
        clientUser1.permissionsApi.updateAcl(AclData(Acl(aclKey, listOf(addAce)), Action.ADD))

        // Remove READ from user2
        val removeAce = Ace(testUser2, EnumSet.of(Permission.READ))
        clientUser1.permissionsApi.updateAcl(AclData(Acl(aclKey, listOf(removeAce)), Action.REMOVE))

        // Verify user2 no longer has READ
        val accessCheck = AccessCheck(aclKey, EnumSet.of(Permission.READ))
        val results = clientUser2.testAuthorizationsApi.checkAuthorizations(setOf(accessCheck))
        Assert.assertTrue("Should have results", results.isNotEmpty())
        Assert.assertFalse("User2 should no longer have READ", results.first().permissions[Permission.READ] == true)
    }

    @Test
    fun testUpdateAclSet() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val aclKey = AclKey(studyId)

        val ace = Ace(testUser2, EnumSet.of(Permission.READ, Permission.WRITE))
        clientUser1.permissionsApi.updateAcl(AclData(Acl(aclKey, listOf(ace)), Action.SET))

        val acl = clientUser1.permissionsApi.getAcl(aclKey)
        val user2Aces = acl.aces.filter { it.principal == testUser2 }
        Assert.assertTrue("User2 should have ACEs after SET", user2Aces.isNotEmpty())
        val perms = user2Aces.first().permissions
        Assert.assertTrue("Should have READ", perms.contains(Permission.READ))
        Assert.assertTrue("Should have WRITE", perms.contains(Permission.WRITE))
    }

    @Test
    fun testUpdateAcls() {
        val studyId1 = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studyId2 = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val aclDatas = listOf(
            AclData(
                Acl(AclKey(studyId1), listOf(Ace(testUser2, EnumSet.of(Permission.READ)))),
                Action.ADD
            ),
            AclData(
                Acl(AclKey(studyId2), listOf(Ace(testUser2, EnumSet.of(Permission.READ)))),
                Action.ADD
            )
        )
        clientUser1.testPermissionsApi.updateAcls(aclDatas)

        // Verify both studies are now accessible to user2
        val checks = setOf(
            AccessCheck(AclKey(studyId1), EnumSet.of(Permission.READ)),
            AccessCheck(AclKey(studyId2), EnumSet.of(Permission.READ))
        )
        val results = clientUser2.testAuthorizationsApi.checkAuthorizations(checks)
        Assert.assertEquals(2, results.size)
        results.forEach { auth ->
            Assert.assertTrue("User2 should have READ on each study", auth.permissions[Permission.READ] == true)
        }
    }

    @Test
    fun testGetAcls() {
        val studyId1 = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studyId2 = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val acls = clientUser1.testPermissionsApi.getAcls(
            setOf(AclKey(studyId1), AclKey(studyId2))
        )
        Assert.assertEquals("Should return ACLs for both studies", 2, acls.size)
    }

}
