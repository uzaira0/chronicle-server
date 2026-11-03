package com.openlattice.chronicle

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.openlattice.chronicle.study.ParticipantDataType

/** Independent CSV reader: quoted newlines stay inside a record. */
fun parseExportCsv(body: String): List<Map<String, String>> = CsvMapper().readerFor(Map::class.java)
    .with(CsvSchema.emptySchema().withHeader()).readValues<Map<String, String>>(body).readAll()

/** Documented pilot columns, independent of the production export projection. */
val pilotExportColumns = mapOf(
    ParticipantDataType.UsageEvents to setOf("app_package_name", "application_label", "activity_class",
        "interaction_type", "event_type", "event_timestamp", "timezone", "username", "uploaded_at", "client_event_id"),
    ParticipantDataType.BatteryTelemetry to setOf("sample_id", "sample_timestamp", "timezone", "level_percent",
        "charging_state", "plug_type", "health", "temperature_deci_c", "voltage_millivolts", "uploaded_at", "low_power_mode",
        "random_device_id", "device_type", "producer_version"),
    ParticipantDataType.SensorAvailability to setOf("random_device_id", "available_sensors", "reported_at",
        "screen_width_pixels", "screen_height_pixels", "screen_density_dpi", "display_rotation",
        "interaction_pointer_capture_capability"),
    ParticipantDataType.UploadDiagnostics to setOf("random_device_id", "event_id", "diagnostic_day", "module_family",
        "issue_code", "occurrence_count", "first_occurred_at", "last_occurred_at", "http_status", "error_type", "uploaded_at"),
).mapValues { it.value + setOf("study_id", "participant_id") }
