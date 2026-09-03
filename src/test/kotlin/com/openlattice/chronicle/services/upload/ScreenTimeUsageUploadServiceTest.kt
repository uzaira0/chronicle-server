package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.module.kotlin.readValue
import com.openlattice.chronicle.sensorkit.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class ScreenTimeUsageUploadServiceTest {
    @Test
    fun mapsScreenTimeEnvelopeToIosDeviceUsageSamples() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:05:00Z")
        val record = ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            source = ScreenTimeCaptureSource.shortcutSnapshot,
            confidence = ScreenTimeConfidence.externalShortcut,
            capturedAt = end,
            observationStart = start,
            observationEnd = end,
            timezoneIdentifier = "UTC",
            appName = "Maps",
            bundleIdentifier = "com.apple.Maps",
            categoryName = "Travel",
            durationSeconds = 300,
            pickupCount = 2,
        )
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = "device-1",
            studyId = UUID.randomUUID().toString(),
            participantId = "participant-1",
            generatedAt = end,
            records = listOf(record),
        )

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(envelope, "device-1")
        val payload: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(samples.single().data)

        assertEquals(SensorType.deviceUsage, samples.single().sensor)
        assertEquals(start, samples.single().startDate)
        assertEquals(end, samples.single().endDate)
        assertEquals(300.0, samples.single().duration, 0.001)
        assertEquals(2, payload.totalScreenWakes)
        assertEquals(2, payload.totalUnlocks)
        assertEquals(300.0, payload.appUsage.getValue("Maps").single().usageTime, 0.001)
        assertEquals("com.apple.Maps", payload.appUsage.getValue("Maps").single().bundleIdentifier)
        assertEquals("shortcutSnapshot", payload.screenTimeSource)
        assertEquals("externalShortcut", payload.screenTimeConfidence)
        assertEquals("Maps", payload.screenTimeAppLabel)
        assertEquals("com.apple.Maps", payload.screenTimeBundleIdentifier)
    }

    @Test
    fun mapsWebDomainRowsToDeviceUsageWebUsage() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:01:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = "device-1",
            studyId = UUID.randomUUID().toString(),
            participantId = "participant-1",
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    source = ScreenTimeCaptureSource.shortcutSnapshot,
                    confidence = ScreenTimeConfidence.externalShortcut,
                    capturedAt = end,
                    observationStart = start,
                    observationEnd = end,
                    timezoneIdentifier = "UTC",
                    webDomain = "example.com",
                    durationSeconds = 60,
                )
            ),
        )

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(envelope, "device-1")
        val payload: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(samples.single().data)

        assertTrue(payload.appUsage.isEmpty())
        assertEquals(60.0, payload.webUsage.getValue("example.com"), 0.001)
        assertEquals("shortcutSnapshot", payload.screenTimeSource)
        assertEquals("externalShortcut", payload.screenTimeConfidence)
        assertEquals("example.com", payload.screenTimeWebDomain)
    }

    @Test
    fun preservesShortcutAppLabelWithoutUsingItAsBundleIdentifier() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T01:39:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = "device-1",
            studyId = UUID.randomUUID().toString(),
            participantId = "participant-1",
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    source = ScreenTimeCaptureSource.shortcutSnapshot,
                    confidence = ScreenTimeConfidence.externalShortcut,
                    rowKind = ScreenTimeUsageRowKind.application,
                    capturedAt = end,
                    observationStart = start,
                    observationEnd = end,
                    timezoneIdentifier = "UTC",
                    appName = "Shortcuts",
                    durationSeconds = 2340,
                    rawSourceLabel = "shortcuts-text-import",
                )
            ),
        )

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(envelope, "device-1")
        val payload: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(samples.single().data)
        val usage = payload.appUsage.getValue("Shortcuts").single()

        assertEquals(2340.0, usage.usageTime, 0.001)
        assertEquals("shortcuts-text-import", usage.bundleIdentifier)
        assertEquals("shortcutSnapshot", payload.screenTimeSource)
        assertEquals("externalShortcut", payload.screenTimeConfidence)
        assertEquals("application", payload.screenTimeRowKind)
        assertEquals("Shortcuts", payload.screenTimeAppLabel)
        assertEquals("shortcuts-text-import", payload.screenTimeRawSourceLabel)
    }

    @Test
    fun preservesDirectExportAppLabelAndRawSourceWhenBundleIdentifierIsMissing() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T02:00:00Z")
        val envelope = ScreenTimeUsageEnvelope(
            deviceId = "device-1",
            studyId = UUID.randomUUID().toString(),
            participantId = "participant-1",
            generatedAt = end,
            records = listOf(
                ScreenTimeUsageRecord(
                    id = UUID.randomUUID(),
                    source = ScreenTimeCaptureSource.deviceActivityExport,
                    confidence = ScreenTimeConfidence.appleDeviceActivity,
                    rowKind = ScreenTimeUsageRowKind.application,
                    capturedAt = end,
                    observationStart = start,
                    observationEnd = end,
                    timezoneIdentifier = "UTC",
                    appName = "Chronicle for Research",
                    durationSeconds = 90,
                    rawSourceLabel = "DeviceActivityData application",
                )
            ),
        )

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(envelope, "device-1")
        val payload: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(samples.single().data)
        val usage = payload.appUsage.getValue("Chronicle for Research").single()

        assertEquals(90.0, usage.usageTime, 0.001)
        assertEquals("DeviceActivityData application", usage.bundleIdentifier)
        assertEquals("deviceActivityExport", payload.screenTimeSource)
        assertEquals("appleDeviceActivity", payload.screenTimeConfidence)
        assertEquals("application", payload.screenTimeRowKind)
        assertEquals("Chronicle for Research", payload.screenTimeAppLabel)
        assertEquals("DeviceActivityData application", payload.screenTimeRawSourceLabel)
    }

    @Test
    fun directExportRowsUseStableIdsForRetriesAndDistinctIdsForLaterCaptures() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T02:00:00Z")
        val first = ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            source = ScreenTimeCaptureSource.deviceActivityExport,
            confidence = ScreenTimeConfidence.appleDeviceActivity,
            rowKind = ScreenTimeUsageRowKind.application,
            capturedAt = OffsetDateTime.parse("2026-06-24T02:05:00Z"),
            observationStart = start,
            observationEnd = end,
            timezoneIdentifier = "UTC",
            appName = "Instagram",
            bundleIdentifier = "com.burbn.instagram",
            categoryName = "Social",
            durationSeconds = 120,
        )
        val second = first.copy(
            id = UUID.randomUUID(),
            capturedAt = OffsetDateTime.parse("2026-06-24T02:20:00Z"),
        )
        val retry = first.copy(id = UUID.randomUUID())

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(
            ScreenTimeUsageEnvelope(
                deviceId = "device-1",
                studyId = UUID.randomUUID().toString(),
                participantId = "participant-1",
                generatedAt = end,
                records = listOf(first, second, retry),
            ),
            "device-1",
        )

        assertTrue(samples[0].id != samples[1].id)
        assertEquals(samples[0].id, samples[2].id)
        assertEquals(8, samples[0].id.version())
        assertEquals(first.capturedAt, samples[0].dateRecorded)
        assertEquals(second.capturedAt, samples[1].dateRecorded)
    }

    @Test
    fun filtersCategorySummariesAndThresholdEstimatesFromDeviceUsageSamples() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T02:00:00Z")
        val valid = ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            source = ScreenTimeCaptureSource.deviceActivityExport,
            confidence = ScreenTimeConfidence.appleDeviceActivity,
            rowKind = ScreenTimeUsageRowKind.application,
            capturedAt = end,
            observationStart = start,
            observationEnd = end,
            timezoneIdentifier = "UTC",
            appName = "Instagram",
            bundleIdentifier = "com.burbn.instagram",
            categoryName = "Social",
            durationSeconds = 120,
        )
        val categorySummary = ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            source = ScreenTimeCaptureSource.deviceActivityExport,
            confidence = ScreenTimeConfidence.appleDeviceActivity,
            rowKind = ScreenTimeUsageRowKind.categorySummary,
            capturedAt = end,
            observationStart = start,
            observationEnd = end,
            timezoneIdentifier = "UTC",
            categoryName = "Social",
            durationSeconds = 120,
            rawSourceLabel = "DeviceActivityData category",
        )
        val thresholdEstimate = ScreenTimeUsageRecord(
            id = UUID.randomUUID(),
            source = ScreenTimeCaptureSource.monitorThreshold,
            confidence = ScreenTimeConfidence.thresholdEstimate,
            rowKind = ScreenTimeUsageRowKind.thresholdEvent,
            capturedAt = end,
            observationStart = start,
            observationEnd = end,
            timezoneIdentifier = "UTC",
            durationSeconds = 60,
            thresholdSeconds = 60,
            rawSourceLabel = "chronicle.screenTime.window.0:chronicle.screenTime.window.0.threshold.1",
        )

        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(
            ScreenTimeUsageEnvelope(
                deviceId = "device-1",
                studyId = UUID.randomUUID().toString(),
                participantId = "participant-1",
                generatedAt = end,
                records = listOf(valid, categorySummary, thresholdEstimate),
            ),
            "device-1",
        )
        val payload: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(samples.single().data)

        assertEquals(1, samples.size)
        assertEquals("com.burbn.instagram", payload.appUsage.getValue("Instagram").single().bundleIdentifier)
        assertEquals("Instagram", payload.screenTimeAppLabel)
        assertEquals("application", payload.screenTimeRowKind)
    }
}
