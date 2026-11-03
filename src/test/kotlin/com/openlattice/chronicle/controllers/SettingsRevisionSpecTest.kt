package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SettingsRevisionSpecTest {
    /**
     * These operations answer 428 without a settings revision ([parseSettingsRevisionPrecondition]),
     * and generated clients read the spec, so each must declare the required If-Match header.
     */
    @Test
    fun everySettingsRevisionGuardedWriteDeclaresIfMatch() {
        val spec = ObjectMapper(YAMLFactory()).readTree(File("../chronicle-api/chronicle.yaml"))
        val declaring = spec["paths"].flatMap { path -> path.toList() }
            .filter { operation ->
                operation.path("parameters").any {
                    it.path("\$ref").asText() == "#/components/parameters/ifMatchSettingsRevision"
                }
            }
            .map { it["operationId"].asText() }
            .toSet()

        assertEquals(
            setOf("updateStudy", "updateStudySettings", "setStudyLimits", "setAppsFilteredForStudyAppUsageSurvey"),
            declaring,
        )
    }
}
