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
        assertEquals(
            listOf("monitorThreshold", "deviceActivityExport", "reportSummary", "shortcutSnapshot"),
            ScreenTimeCaptureSource.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "appleDeviceActivity",
                "exactParticipantSubmitted",
                "thresholdEstimate",
                "externalShortcut",
                "reportViewOnly",
            ),
            ScreenTimeConfidence.entries.map { it.name },
        )
        assertEquals(
            listOf("application", "webDomain", "categorySummary", "thresholdEvent"),
            ScreenTimeUsageRowKind.entries.map { it.name },
        )
        assertEquals(
            listOf("appBecameActive", "manualInApp", "notificationAction", "shortcut", "screenTimeThreshold"),
            UserIdentificationTrigger.entries.map { it.name },
        )
        assertEquals(
            listOf("participant", "someoneElse"),
            UserIdentificationChoice.entries.map { it.name },
        )
    }

    @Test
    fun `rejects unsupported Screen Time enum values during wire decoding`() {
        val record = validScreenTimeRecord()
        assertUnknownEnumRejected(record, "source", "unknownSource", ScreenTimeUsageRecord::class.java)
        assertUnknownEnumRejected(record, "confidence", "unknownConfidence", ScreenTimeUsageRecord::class.java)
        assertUnknownEnumRejected(record, "rowKind", "unknownRowKind", ScreenTimeUsageRecord::class.java)
    }

    @Test
    fun `rejects unsupported user-identification enum values during wire decoding`() {
        val record = validUserIdentificationRecord()
        assertUnknownEnumRejected(record, "trigger", "unknownTrigger", UserIdentificationRecord::class.java)
        assertUnknownEnumRejected(record, "choice", "unknownChoice", UserIdentificationRecord::class.java)
    }

    @Test
    fun `rejects numeric enum values during production wire decoding`() {
        val tree = mapper.valueToTree<ObjectNode>(validScreenTimeRecord())
        tree.put("source", 0)
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
            source = ScreenTimeCaptureSource.deviceActivityExport,
            confidence = ScreenTimeConfidence.appleDeviceActivity,
            rowKind = ScreenTimeUsageRowKind.application,
            capturedAt = capturedAt,
            observationStart = capturedAt.minusMinutes(5),
            observationEnd = capturedAt,
            timezoneIdentifier = "UTC",
            durationSeconds = 300,
        )
    }

    private fun validUserIdentificationRecord(): UserIdentificationRecord = UserIdentificationRecord(
        id = UUID.fromString("00000000-0000-4000-8000-000000000302"),
        capturedAt = OffsetDateTime.parse("2026-07-01T12:00:00Z"),
        timezoneIdentifier = "UTC",
        trigger = UserIdentificationTrigger.manualInApp,
        choice = UserIdentificationChoice.participant,
    )
}
