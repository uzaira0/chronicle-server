package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.sensorkit.AppUsage
import com.openlattice.chronicle.sensorkit.DeviceUsageData
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorSourceDevice
import com.openlattice.chronicle.sensorkit.SensorType
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.AssertTrue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

public enum class ScreenTimeCaptureSource {
    monitorThreshold,
    deviceActivityExport,
    reportSummary,
    shortcutSnapshot,
}

public enum class ScreenTimeConfidence {
    appleDeviceActivity,
    exactParticipantSubmitted,
    thresholdEstimate,
    externalShortcut,
    reportViewOnly,
}

public enum class ScreenTimeUsageRowKind {
    application,
    webDomain,
    categorySummary,
    thresholdEvent,
}

public enum class UserIdentificationTrigger {
    appBecameActive,
    manualInApp,
    notificationAction,
    shortcut,
    screenTimeThreshold,
}

public enum class UserIdentificationChoice {
    participant,
    someoneElse,
}

public object ScreenTimeUsageUploadService {
    public fun toSensorDataSamples(
        envelope: ScreenTimeUsageEnvelope,
        sourceDeviceId: String,
    ): List<SensorDataSample> {
        require(envelope.records.size <= 10_000) {
            "Screen Time upload batch too large: ${envelope.records.size} records (max 10,000)"
        }
        require(envelope.records.all { it.observationEnd.isAfter(it.observationStart) }) {
            "Screen Time observationEnd must be after observationStart"
        }

        val deviceJson = SensorDataUploadService.mapper.writeValueAsString(
            SensorSourceDevice(
                model = "iOS",
                name = sourceDeviceId,
                systemName = "iOS",
                systemVersion = "unknown",
            )
        )

        return envelope.records
            .filterNot { it.isCategorySummary() || it.isThresholdEstimate() }
            .map { record ->
                SensorDataSample(
                    id = record.stableSampleId(sourceDeviceId),
                    dateRecorded = record.capturedAt,
                    duration = ChronoUnit.MILLIS.between(record.observationStart, record.observationEnd) / 1000.0,
                    data = SensorDataUploadService.mapper.writeValueAsString(record.toScreenTimeDeviceUsageData()),
                    device = deviceJson,
                    timezone = record.timezoneIdentifier,
                    sensor = SensorType.deviceUsage,
                    startDate = record.observationStart,
                    endDate = record.observationEnd,
                )
            }
    }

    private fun ScreenTimeUsageRecord.stableSampleId(sourceDeviceId: String): UUID {
        if (source != ScreenTimeCaptureSource.deviceActivityExport) {
            return id
        }

        val key = listOf(
            "chronicle-ios-screen-time-v2",
            sourceDeviceId,
            source.name,
            confidence.name,
            rowKind?.name.orEmpty(),
            capturedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
            observationStart.truncatedTo(ChronoUnit.SECONDS).toString(),
            observationEnd.truncatedTo(ChronoUnit.SECONDS).toString(),
            timezoneIdentifier,
            appName.orEmpty(),
            bundleIdentifier.orEmpty(),
            webDomain.orEmpty(),
            categoryName.orEmpty(),
            durationSeconds.toString(),
            notificationCount?.toString().orEmpty(),
            pickupCount?.toString().orEmpty(),
        ).joinToString(separator = "\u001F")

        return deterministicUuidFromSha256(key)
    }

    private fun deterministicUuidFromSha256(value: String): UUID {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        return UUID(bytesToLong(digest, 0), bytesToLong(digest, 8))
    }

    private fun bytesToLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in offset until offset + Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (bytes[index].toLong() and 0xffL)
        }
        return value
    }

    private fun ScreenTimeUsageRecord.isCategorySummary(): Boolean {
        return rowKind == ScreenTimeUsageRowKind.categorySummary ||
            rawSourceLabel == "DeviceActivityData category" ||
            (
                source == ScreenTimeCaptureSource.deviceActivityExport &&
                    bundleIdentifier.isNullOrBlank() &&
                    webDomain.isNullOrBlank() &&
                    appName.isNullOrBlank() &&
                    !categoryName.isNullOrBlank()
                )
    }

    private fun ScreenTimeUsageRecord.isThresholdEstimate(): Boolean {
        return rowKind == ScreenTimeUsageRowKind.thresholdEvent ||
            source == ScreenTimeCaptureSource.monitorThreshold ||
            confidence == ScreenTimeConfidence.thresholdEstimate ||
            thresholdSeconds != null ||
            rawSourceLabel?.startsWith("chronicle.screenTime.window.") == true ||
            rawSourceLabel?.contains(":chronicle.screenTime.window.") == true
    }

    private fun ScreenTimeUsageRecord.toScreenTimeDeviceUsageData(): IosScreenTimeDeviceUsageData {
        val duration = durationSeconds.toDouble().coerceAtLeast(0.0)
        val category = appName ?: categoryName ?: rawSourceLabel ?: "Screen Time"
        val appUsage = if (webDomain.isNullOrBlank()) {
            mapOf(
                category to listOf(
                    AppUsage(
                        usageTime = duration,
                        textInputSessions = emptyMap(),
                        bundleIdentifier = bundleIdentifier ?: rawSourceLabel ?: "unknown.ios.screen-time",
                    )
                )
            )
        } else {
            emptyMap()
        }
        val webUsage = if (!webDomain.isNullOrBlank()) {
            mapOf(webDomain to duration)
        } else {
            emptyMap()
        }

        return IosScreenTimeDeviceUsageData(
            totalScreenWakes = pickupCount ?: 0,
            totalUnlocks = pickupCount ?: 0,
            totalUnlockDuration = 0.0,
            appUsage = appUsage,
            webUsage = webUsage,
            screenTimeSource = source.name,
            screenTimeConfidence = confidence.name,
            screenTimeRowKind = rowKind?.name,
            screenTimeAppLabel = appName,
            screenTimeBundleIdentifier = bundleIdentifier,
            screenTimeWebDomain = webDomain,
            screenTimeRawSourceLabel = rawSourceLabel,
            screenTimeNotificationCount = notificationCount,
            screenTimePickupCount = pickupCount,
        )
    }
}

public data class IosScreenTimeDeviceUsageData(
    val totalScreenWakes: Int,
    val totalUnlocks: Int,
    val totalUnlockDuration: Double,
    val appUsage: Map<String, List<AppUsage>>,
    val webUsage: Map<String, Double>,
    val screenTimeSource: String? = null,
    val screenTimeConfidence: String? = null,
    val screenTimeRowKind: String? = null,
    val screenTimeAppLabel: String? = null,
    val screenTimeBundleIdentifier: String? = null,
    val screenTimeWebDomain: String? = null,
    val screenTimeRawSourceLabel: String? = null,
    val screenTimeNotificationCount: Int? = null,
    val screenTimePickupCount: Int? = null,
)

public data class ScreenTimeUsageEnvelope(
    @field:Min(value = 1, message = "Schema version must be positive")
    val schemaVersion: Int = 1,

    @field:NotBlank(message = "Device ID is required")
    @field:Size(max = 255, message = "Device ID exceeds maximum length")
    val deviceId: String,

    @field:NotBlank(message = "Study ID is required")
    @field:Size(max = 64, message = "Study ID exceeds maximum length")
    val studyId: String,

    @field:NotBlank(message = "Participant ID is required")
    @field:Size(max = 255, message = "Participant ID exceeds maximum length")
    val participantId: String,

    @field:NotNull(message = "Generated-at timestamp is required")
    val generatedAt: OffsetDateTime,

    @field:Valid
    @field:Size(max = 10_000, message = "Screen Time upload exceeds maximum record count")
    val records: List<ScreenTimeUsageRecord>,
)

public data class ScreenTimeUsageRecord(
    @field:NotNull(message = "Record ID is required")
    val id: UUID,

    @field:NotNull(message = "Source is required")
    val source: ScreenTimeCaptureSource,

    @field:NotNull(message = "Confidence is required")
    val confidence: ScreenTimeConfidence,

    val rowKind: ScreenTimeUsageRowKind? = null,

    @field:NotNull(message = "Captured-at timestamp is required")
    val capturedAt: OffsetDateTime,

    @field:NotNull(message = "Observation start is required")
    val observationStart: OffsetDateTime,

    @field:NotNull(message = "Observation end is required")
    val observationEnd: OffsetDateTime,

    @field:NotBlank(message = "Timezone is required")
    @field:Size(max = 100, message = "Timezone exceeds maximum length")
    val timezoneIdentifier: String,

    @field:Size(max = 500, message = "App name exceeds maximum length")
    val appName: String? = null,

    @field:Size(max = 500, message = "Bundle identifier exceeds maximum length")
    val bundleIdentifier: String? = null,

    @field:Size(max = 500, message = "Web domain exceeds maximum length")
    val webDomain: String? = null,

    @field:Size(max = 500, message = "Category name exceeds maximum length")
    val categoryName: String? = null,

    @field:Min(value = 0, message = "Duration cannot be negative")
    val durationSeconds: Int,

    @field:Min(value = 0, message = "Notification count cannot be negative")
    val notificationCount: Int? = null,

    @field:Min(value = 0, message = "Pickup count cannot be negative")
    val pickupCount: Int? = null,

    @field:Min(value = 0, message = "Threshold cannot be negative")
    val thresholdSeconds: Int? = null,

    @field:Size(max = 500, message = "Raw source label exceeds maximum length")
    val rawSourceLabel: String? = null,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "Observation end must be after observation start")
    val isObservationRangeValid: Boolean
        get() = observationEnd.isAfter(observationStart)
}

public object UserIdentificationUploadService {
    public fun toSensorDataSamples(
        envelope: UserIdentificationEnvelope,
        sourceDeviceId: String,
    ): List<SensorDataSample> {
        require(envelope.records.size <= 10_000) {
            "User identification upload batch too large: ${envelope.records.size} records (max 10,000)"
        }

        val deviceJson = SensorDataUploadService.mapper.writeValueAsString(
            SensorSourceDevice(
                model = "iOS",
                name = sourceDeviceId,
                systemName = "iOS",
                systemVersion = "unknown",
            )
        )

        return envelope.records.map { record ->
            val end = record.capturedAt.plusSeconds(1)
            SensorDataSample(
                id = record.id,
                dateRecorded = record.capturedAt,
                duration = 1.0,
                data = SensorDataUploadService.mapper.writeValueAsString(record.toDeviceUsageData()),
                device = deviceJson,
                timezone = record.timezoneIdentifier,
                sensor = SensorType.deviceUsage,
                startDate = record.capturedAt,
                endDate = end,
            )
        }
    }

    private fun UserIdentificationRecord.toDeviceUsageData(): DeviceUsageData {
        val category = listOfNotNull("chronicle.userIdentification", trigger.name, sourceLabel)
            .joinToString(".")
            .take(500)
        val choiceIdentifier = "chronicle.userIdentification.choice.${choice.name}"

        return DeviceUsageData(
            totalScreenWakes = 0,
            totalUnlocks = 0,
            totalUnlockDuration = 0.0,
            appUsage = mapOf(
                category to listOf(
                    AppUsage(
                        usageTime = 1.0,
                        textInputSessions = emptyMap(),
                        bundleIdentifier = choiceIdentifier,
                    )
                )
            ),
            webUsage = emptyMap(),
        )
    }
}

public data class UserIdentificationEnvelope(
    @field:Min(value = 1, message = "Schema version must be positive")
    val schemaVersion: Int = 1,

    @field:NotBlank(message = "Device ID is required")
    @field:Size(max = 255, message = "Device ID exceeds maximum length")
    val deviceId: String,

    @field:NotBlank(message = "Study ID is required")
    @field:Size(max = 64, message = "Study ID exceeds maximum length")
    val studyId: String,

    @field:NotBlank(message = "Participant ID is required")
    @field:Size(max = 255, message = "Participant ID exceeds maximum length")
    val participantId: String,

    @field:NotNull(message = "Generated-at timestamp is required")
    val generatedAt: OffsetDateTime,

    @field:Valid
    @field:Size(max = 10_000, message = "User identification upload exceeds maximum record count")
    val records: List<UserIdentificationRecord>,
)

public data class UserIdentificationRecord(
    @field:NotNull(message = "Record ID is required")
    val id: UUID,

    @field:NotNull(message = "Captured-at timestamp is required")
    val capturedAt: OffsetDateTime,

    @field:NotBlank(message = "Timezone is required")
    @field:Size(max = 100, message = "Timezone exceeds maximum length")
    val timezoneIdentifier: String,

    @field:NotNull(message = "Trigger is required")
    val trigger: UserIdentificationTrigger,

    @field:NotNull(message = "Choice is required")
    val choice: UserIdentificationChoice,

    val promptId: UUID? = null,

    @field:Size(max = 500, message = "Source label exceeds maximum length")
    val sourceLabel: String? = null,
)
