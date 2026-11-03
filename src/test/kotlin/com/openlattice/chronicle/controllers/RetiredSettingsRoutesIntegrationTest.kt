package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RetiredSettingsRoutesIntegrationTest : ChronicleServerTests() {
    @Test fun unusedResearcherAndOrganizationSettingsAreNotRegistered() {
        val study = clientUser1.studyApi.createStudy(Study(title = "retired settings", contact = "test@example.org"))
        val organization = clientUser1.organizationApi.createOrganization(TestDataFactory.organization())
        val routes = checkNotNull(javaClass.getResourceAsStream("/retired-settings-routes.json")).use {
            ObjectMappers.newJsonMapper().readTree(it)
        }
        assertFalse(routes.isEmpty)
        val http = HttpClient.newHttpClient()
        for (route in routes) {
            val path = route.path("path").asText().replace("{studyId}", study.toString())
                .replace("{organizationId}", organization.toString()).replace("{principalId}", testUser1.id)
            for (method in route.path("methods")) {
                val request = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort$path"))
                    .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
                    .header("Content-Type", "application/json")
                    .method(method.asText(), if (method.asText() == "GET") HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofString("{}"))
                val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
                assertEquals("${method.asText()} ${route.path("path").asText()}", 404, response.statusCode())
            }
        }
    }
}
