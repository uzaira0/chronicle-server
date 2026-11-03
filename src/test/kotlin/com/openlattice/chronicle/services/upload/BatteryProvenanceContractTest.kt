package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.databind.JsonMappingException
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.IosBatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Capture metadata survives real wire decoding; an upload must never invent a legacy collector version. */
class BatteryProvenanceContractTest {
    private val mapper = ObjectMappers.newJsonMapper()
    private val android = """{"id":"battery-1","timestamp":"2026-10-01T12:00:00Z","timezone":"UTC",
        "levelPercent":42,"chargingState":"DISCHARGING","plugType":"UNPLUGGED",
        "temperatureDeciC":220,"voltageMillivolts":4000,"health":"GOOD"}"""
    private val ios = """{"id":"battery-2","timestamp":"2026-10-01T12:00:00Z","timezone":"UTC",
        "levelPercent":42,"chargingState":"DISCHARGING","lowPowerMode":false}"""

    @Test fun captureVersionRoundTripsForBothPlatformsAndLegacyStaysUnknown() {
        for ((json, type) in listOf(android to BatterySample::class.java, ios to IosBatterySample::class.java)) {
            for (version in listOf("2026.10.01 (80)", "v".repeat(64), "\uD801\uDC00".repeat(64))) {
                val captured = mapper.readTree(json).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                    .put("producerVersion", version)
                val decoded = mapper.readValue(mapper.writeValueAsBytes(captured), type)
                assertEquals(version, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(decoded)
                    .path("producerVersion").asText())
            }
            val legacy = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapper.readValue(json, type))
            assertEquals(true, legacy.path("producerVersion").isMissingNode || legacy.path("producerVersion").isNull)
        }
    }

    @Test fun presentVersionsAreBoundedAndNonblankAtTheWireBoundary() {
        for ((json, type) in listOf(android to BatterySample::class.java, ios to IosBatterySample::class.java)) {
            for (invalid in listOf("", " ", "\u2003", "v".repeat(65), "\uD801\uDC00".repeat(65))) {
                val input = mapper.readTree(json).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                    .put("producerVersion", invalid)
                assertThrows(JsonMappingException::class.java) { mapper.readValue(mapper.writeValueAsBytes(input), type) }
            }
        }
    }
}
