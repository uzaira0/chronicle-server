package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

// Wire contract of POST v4 .../ios/screen-time (chronicle.yaml ScreenTimeUsageEnvelope). The iOS app's
// generated DTOs mirror these names and enum values; change them only together with the API document.

/** What one DeviceActivity record measures: one app, or one web domain. */
public enum class ScreenTimeUsageRowKind {
    application,
    webDomain,
}

public data class ScreenTimeUsageEnvelope(
    @field:Min(value = 1, message = "Schema version must be positive")
    val schemaVersion: Int = 1,

    @field:NotBlank(message = "Random device ID is required")
    @field:Size(max = 255, message = "Random device ID exceeds maximum length")
    val randomDeviceId: String,

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

    @field:NotNull(message = "Row kind is required")
    val rowKind: ScreenTimeUsageRowKind,

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
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "Observation end must be after observation start")
    val isObservationRangeValid: Boolean
        get() = observationEnd.isAfter(observationStart)

    /** Refuses the row unless its labels are those of its [rowKind]: an app names no domain, a domain names no app. */
    internal fun requireLabelsMatchRowKind(): Unit = requireUploadRow(
        when (rowKind) {
            ScreenTimeUsageRowKind.application ->
                webDomain == null && (!bundleIdentifier.isNullOrBlank() || !appName.isNullOrBlank())
            ScreenTimeUsageRowKind.webDomain ->
                webDomain != null && appName == null && bundleIdentifier == null
        },
    ) { "Screen Time record $id has labels that contradict its row kind $rowKind" }

    /** Refuses the row unless this server knows its zone id, which places the capture on the participant's day. */
    internal fun requireKnownTimezone(): Unit = requireUploadRow(
        runCatching { ZoneId.of(timezoneIdentifier) }.isSuccess,
    ) { "Screen Time record $id has a time zone this server does not know" }
}
