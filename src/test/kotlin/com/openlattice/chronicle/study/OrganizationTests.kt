package com.openlattice.chronicle.study

import org.junit.Assert.assertThrows

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.filters.CreateReceiptFilter
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.organizations.OrganizationApi
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.organizations.Organization
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

        // The creator manages studies through the organization's own authorization chain; another researcher cannot.
        val studyId = chronicleClient.studyApi.createStudy(
            Study(title = "org study", contact = "test@openlattice.com", organizationIds = setOf(orgId)),
        )
        Assert.assertEquals(listOf(studyId), chronicleClient.studyApi.getOrgStudies(orgId).map { it.id })
        chronicleClient.studyApi.updateStudy(studyId, StudyUpdate(description = "managed by the org creator"))
        Assert.assertEquals("managed by the org creator", chronicleClient.studyApi.getStudy(studyId).description)
        assertThrows(RhizomeRetrofitCallException::class.java) {
            clientUser2.studyApi.createStudy(Study(title = "intruder", contact = "x@example.org", organizationIds = setOf(orgId)))
        }
        assertThrows(RhizomeRetrofitCallException::class.java) { clientUser2.studyApi.getOrgStudies(orgId) }
    }

    /** H-org-candidate-create: a create whose response was lost replays its first result instead of creating again. */
    @Test
    fun aRetriedOrganizationCreateStoresOneOrganizationAndAChangedBodyConflicts() {
        val title = "receipt org ${UUID.randomUUID()}"
        val key = "org-intent-${UUID.randomUUID()}"
        fun create(organization: Organization) = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${OrganizationApi.BASE}"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                .header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ObjectMappers.getJsonMapper().writeValueAsString(organization))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val organization = Organization(title = title)
        val first = create(organization)
        Assert.assertEquals(first.body(), 200, first.statusCode())
        val retry = create(organization)
        Assert.assertEquals(first.body(), retry.body())
        Assert.assertEquals("true", retry.headers().firstValue(CreateReceiptFilter.REPLAYED_HEADER).orElse(null))
        val changed = create(organization.copy(title = "$title changed"))
        Assert.assertEquals(409, changed.statusCode())
        val stored = chronicleClient.organizationApi.getOrganizations().filter { it.title.startsWith(title) }
        Assert.assertEquals(listOf(ObjectMappers.getJsonMapper().readValue(first.body(), UUID::class.java)), stored.map { it.id })
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
    fun testGetOrganizationAccessDenied() {
        val org = TestDataFactory.organization()
        val orgId = chronicleClient.organizationApi.createOrganization(org)
        assertThrows(RhizomeRetrofitCallException::class.java) {
            clientUser2.organizationApi.getOrganization(orgId)
        }
    }
}
