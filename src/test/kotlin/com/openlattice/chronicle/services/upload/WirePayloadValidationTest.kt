package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class WirePayloadValidationTest {
    private val mapper: ObjectMapper = JacksonSecurityConfig().secureObjectMapper()

    @Test
    fun `canonical wire enums retain their exact OpenAPI values`() {
        assertEquals(listOf("application", "webDomain"), ScreenTimeUsageRowKind.entries.map { it.name })
    }

    @Test
    fun `rejects unsupported Screen Time enum values during wire decoding`() {
        val record = validScreenTimeRecord()
        listOf("unknownRowKind", "categorySummary", "thresholdEvent").forEach { kind ->
            assertUnknownEnumRejected(record, "rowKind", kind, ScreenTimeUsageRecord::class.java)
        }
    }

    @Test
    fun `a record without a row kind or with a field the bare app never sends is refused`() {
        val missingKind = mapper.valueToTree<ObjectNode>(validScreenTimeRecord()).apply { remove("rowKind") }
        assertThrows(JsonProcessingException::class.java) { mapper.treeToValue(missingKind, ScreenTimeUsageRecord::class.java) }
        listOf("source", "confidence", "thresholdSeconds", "rawSourceLabel").forEach { field ->
            val tree = mapper.valueToTree<ObjectNode>(validScreenTimeRecord()).put(field, "x")
            assertThrows(field, JsonProcessingException::class.java) { mapper.treeToValue(tree, ScreenTimeUsageRecord::class.java) }
        }
    }

    @Test
    fun `rejects numeric enum values during production wire decoding`() {
        val tree = mapper.valueToTree<ObjectNode>(validScreenTimeRecord())
        tree.put("rowKind", 0)
        assertThrows(JsonProcessingException::class.java) {
            mapper.treeToValue(tree, ScreenTimeUsageRecord::class.java)
        }
    }

    private fun <T : Any> assertUnknownEnumRejected(
        value: T,
        field: String,
        unsupportedValue: String,
        valueClass: Class<T>,
    ) {
        val tree = mapper.valueToTree<ObjectNode>(value)
        tree.put(field, unsupportedValue)
        assertThrows(JsonProcessingException::class.java) {
            mapper.treeToValue(tree, valueClass)
        }
    }

    private fun validScreenTimeRecord(): ScreenTimeUsageRecord {
        val capturedAt = OffsetDateTime.parse("2026-07-01T12:00:00Z")
        return ScreenTimeUsageRecord(
            id = UUID.fromString("00000000-0000-4000-8000-000000000301"),
            rowKind = ScreenTimeUsageRowKind.application,
            capturedAt = capturedAt,
            observationStart = capturedAt.minusMinutes(5),
            observationEnd = capturedAt,
            timezoneIdentifier = "UTC",
            appName = "Maps",
            durationSeconds = 300,
        )
    }
}
