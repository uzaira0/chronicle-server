package com.openlattice.chronicle.study

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.organizations.OrganizationSettings
import com.openlattice.chronicle.settings.AppComponent
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test

class OrganizationTests : ChronicleServerTests() {
    private val chronicleClient: ChronicleClient = clientUser1

    @Test
    fun createOrganization() {
        val organizationsApi = chronicleClient.organizationApi
        val expected = Organization(title = "This is a test study.")
        val orgId = organizationsApi.createOrganization(expected)
        expected.id = orgId
        val org = organizationsApi.getOrganization(orgId)
        Assert.assertEquals(expected, org)
    }

    @Test
    fun testGetOrganizations() {
        val orgs = (0 until 3).map {
            val org = TestDataFactory.organization()
            org.id = chronicleClient.organizationApi.createOrganization(org)
            org
        }
        val all = chronicleClient.organizationApi.getOrganizations().toList()
        val allIds = all.map { it.id }.toSet()
        orgs.forEach { org ->
            Assert.assertTrue("Should contain org ${org.id}", allIds.contains(org.id))
        }
    }

    @Test
    fun testSearchOrganizations() {
        val org = TestDataFactory.organization()
        org.id = chronicleClient.organizationApi.createOrganization(org)
        val results = chronicleClient.organizationApi.searchOrganizations()
        val resultIds = results.map { it.id }.toSet()
        Assert.assertTrue("Search should contain the created org", resultIds.contains(org.id))
    }

    @Test
    fun testGetOrganizationSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)
        val settings = chronicleClient.organizationApi.getOrganizationSettings(orgId)
        Assert.assertNotNull("Settings should not be null", settings)
    }

    @Test
    fun testSetAndGetOrganizationSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)

        val customSettings = OrganizationSettings(
            chronicleDataCollection = ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY)
        )
        chronicleClient.testOrganizationApi.setOrganizationSettings(orgId, customSettings)

        val actual = chronicleClient.organizationApi.getOrganizationSettings(orgId)
        Assert.assertEquals(
            AppUsageFrequency.HOURLY,
            actual.chronicleDataCollection.appUsageFrequency
        )
    }

    @Test
    fun testGetChronicleDataCollectionSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)
        val settings = chronicleClient.organizationApi.getChronicleDataCollectionSettings(orgId)
        Assert.assertNotNull("Data collection settings should not be null", settings)
    }

    @Test
    fun testSetAndGetChronicleDataCollectionSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)

        val hourly = ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY)
        chronicleClient.testOrganizationApi.setChronicleDataCollectionSettings(orgId, hourly)

        val actual = chronicleClient.organizationApi.getChronicleDataCollectionSettings(orgId)
        Assert.assertEquals(AppUsageFrequency.HOURLY, actual.appUsageFrequency)
    }

    @Test
    fun testGetAppComponentSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)
        val settings = chronicleClient.testOrganizationApi.getAppComponentSettings(
            orgId, AppComponent.CHRONICLE.name
        )
        Assert.assertNotNull("App component settings should not be null", settings)
    }

    @Test
    fun testSetAndGetAppComponentSettings() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)

        val componentSettings = mapOf<String, Any>("featureEnabled" to true, "maxRetries" to 3)
        chronicleClient.testOrganizationApi.setAppComponentSettings(
            orgId, AppComponent.CHRONICLE.name, componentSettings
        )

        val actual = chronicleClient.testOrganizationApi.getAppComponentSettings(
            orgId, AppComponent.CHRONICLE.name
        )
        Assert.assertEquals(true, actual["featureEnabled"])
        Assert.assertEquals(3, (actual["maxRetries"] as Number).toInt())
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testGetOrganizationAccessDenied() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)
        clientUser2.organizationApi.getOrganization(orgId)
    }
}
