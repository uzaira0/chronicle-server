package com.openlattice.chronicle.audit

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditObservation
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.util.ClientIpRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

class AuditServiceTest {
    private val manager = RecordingAuditingManager()
    private val auditService = AuditService(manager)

    @After
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `a success follows its transaction and a failure is committed on its own`() {
        auditService.logWithContext { action(AuditEventType.VIEW); resourceType("Study"); success(true) }
        auditService.logWithContext { action(AuditEventType.VIEW); resourceType("Study"); success(false) }

        assertTrue(manager.observations[0] is AuditObservation.CommittedEffect)
        assertTrue(manager.observations[1] is AuditObservation.Intent)
        assertEquals(listOf(true, false), manager.events.map { it.data["success"] })
    }

    @Test
    fun `request context is recorded with route-shaped paths and redacted identifiers`() {
        val studyId = "550e8400-e29b-41d4-a716-446655440000"
        val participantId = "u15-device-owner"
        val randomDeviceId = "iphone-idfv"
        val request = MockHttpServletRequest(
            "POST", "/chronicle/v4/study/$studyId/participant/$participantId/ios/screen-time",
        ).apply {
            remoteAddr = "203.0.113.99"
            addHeader("User-Agent", "ChronicleTest/1.0")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        auditService.logWithContext {
            action(AuditEventType.SENSOR_DATA_UPLOAD)
            resourceType("SensorData")
            studyId(UUID.fromString(studyId))
            success(true)
            phiFields(listOf("sensor_data"))
            additionalData(mapOf("participantId" to participantId, "randomDeviceId" to randomDeviceId, "recordCount" to 3,
                "apiKey" to "ck_live_secret"))
        }

        val event = manager.events.single()
        val recorded = requireNotNull(event.request)
        assertEquals("participant requests never record an address", ClientIpRecord.WITHHELD, recorded.clientIpRef)
        assertEquals("/chronicle/v4/study/{studyId}/participant/{participantId}/ios/screen-time", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("ChronicleTest/1.0", recorded.userAgent)
        assertEquals(true, event.data["accessedPHI"])
        assertEquals(listOf("sensor_data"), event.data["phiFields"])
        assertTrue((event.data["participantId"] as String).startsWith("participant:"))
        assertTrue((event.data["randomDeviceId"] as String).startsWith("device:"))
        assertEquals(3, event.data["recordCount"])
        assertEquals("[REDACTED]", event.data["apiKey"])
        assertFalse(event.data.values.joinToString().contains(participantId))
    }

    @Test
    fun `an unauthenticated caller is recorded as the anonymous role`() {
        auditService.logWithContext { action(AuditEventType.LOGIN_FAILED); resourceType("Authentication"); success(false) }

        assertEquals(Principals.getAnonymousUser(), manager.events.single().principal)
    }
}
