package com.openlattice.chronicle.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class AuditServiceTest {

    @Test
    fun `database batch exception is requeued and retried`() {
        val repository = Mockito.mock(AuditLogRepository::class.java)
        Mockito.`when`(repository.saveBatch(anyList()))
            .thenThrow(IllegalStateException("database unavailable"))
            .thenAnswer { invocation -> invocation.getArgument<List<AuditLogEntry>>(0).size }
        val service = AuditService(repository, ObjectMapper())
        service.log(testEntry())

        service.flushBatch()
        service.flushBatch()

        verify(repository, times(2)).saveBatch(anyList())
        service.shutdown()
    }

    @Test
    fun `partial database batch result is requeued and retried`() {
        val repository = Mockito.mock(AuditLogRepository::class.java)
        Mockito.`when`(repository.saveBatch(anyList()))
            .thenReturn(0)
            .thenAnswer { invocation -> invocation.getArgument<List<AuditLogEntry>>(0).size }
        val service = AuditService(repository, ObjectMapper())
        service.log(testEntry())

        service.flushBatch()
        service.flushBatch()

        verify(repository, times(2)).saveBatch(anyList())
        service.shutdown()
    }

    private fun testEntry(): AuditLogEntry = AuditLogEntry(
        ipAddress = "127.0.0.1",
        action = AuditAction.VIEW,
        resourceType = "Test",
        success = true,
    )

    @Test
    fun `sanitizeForPersistence route-shapes paths and redacts audit identifiers`() {
        val studyId = "550e8400-e29b-41d4-a716-446655440000"
        val participantId = "u15-device-owner"
        val deviceId = "iphone-idfv"
        val entry = AuditLogEntry(
            ipAddress = "203.0.113.99",
            userAgent = "ChronicleTest/1.0",
            action = AuditAction.SENSOR_DATA_UPLOAD,
            resourceType = "SensorData",
            success = false,
            errorMessage = "SELECT * FROM secret_table WHERE token=SUPER-SECRET",
            requestPath = "/chronicle/v3/study/$studyId/participant/$participantId/ios/$deviceId/upload?token=SUPER-SECRET",
            requestMethod = "POST",
            additionalData = mapOf(
                "participantId" to participantId,
                "deviceId" to deviceId,
                "recordCount" to 3,
                "apiKey" to "ck_live_secret"
            )
        )

        val sanitized = AuditService.sanitizeForPersistence(entry)

        assertTrue(sanitized.ipAddress.startsWith("ip:"))
        assertFalse(sanitized.ipAddress.contains("203.0.113.99"))
        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/ios/{sourceDeviceId}/upload",
            sanitized.requestPath
        )
        assertFalse(sanitized.requestPath?.contains(studyId) == true)
        assertFalse(sanitized.requestPath?.contains(participantId) == true)
        assertFalse(sanitized.requestPath?.contains(deviceId) == true)
        assertFalse(sanitized.errorMessage?.contains("secret_table") == true)
        assertFalse(sanitized.errorMessage?.contains("SUPER-SECRET") == true)

        val additionalData = sanitized.additionalData!!
        assertTrue((additionalData["participantId"] as String).startsWith("participant:"))
        assertTrue((additionalData["deviceId"] as String).startsWith("device:"))
        assertEquals(3, additionalData["recordCount"])
        assertEquals("[REDACTED]", additionalData["apiKey"])
        assertFalse(additionalData.values.joinToString().contains(participantId))
        assertFalse(additionalData.values.joinToString().contains(deviceId))
    }
}
