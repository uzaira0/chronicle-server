package com.openlattice.chronicle.observability

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiMetricsFilterTest {

    @Test
    fun `normalizePath redacts mobile upload identifiers`() {
        val path = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                "/participant/u15-device-owner/android/iphone-identifier/upload"

        assertEquals(
            "/chronicle/v3/study/{studyId}/participant/{participantId}/android/{sourceDeviceId}/upload",
            ApiMetricsFilter.normalizePath(path)
        )
    }

    @Test
    fun `normalizePath keeps static platform route and strips query string`() {
        val path = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000" +
                "/android/sensors/availability?participantId=u15-device-owner"

        assertEquals(
            "/chronicle/v3/study/{studyId}/android/sensors/availability",
            ApiMetricsFilter.normalizePath(path)
        )
    }
}
