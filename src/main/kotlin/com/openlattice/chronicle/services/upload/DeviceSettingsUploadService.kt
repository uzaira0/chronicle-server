package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidDeviceSettingsEvent] snapshots uploaded by the Android `device_settings`
 * collection module into the per-row [ChroniclePostgresTables.DEVICE_SETTINGS] table, scoped
 * to a study + participant.
 *
 * Each snapshot becomes one row; [AndroidDeviceSettingsEvent.id] is the per-event de-duplication
 * key (`ON CONFLICT DO NOTHING`), making re-uploads idempotent
 * (mirrors [InteractionEventsUploadService]).
 *
 * DEVICE_STATE_METADATA-class data — a content-free/identity-free snapshot of device toggles.
 * Every descriptive column is nullable so a partial snapshot still persists.
 */
public open class DeviceSettingsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeviceSettingsUploadService::class.java)

        private val INSERT_DEVICE_SETTINGS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.DEVICE_SETTINGS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                dark_mode, font_scale, accessibility_enabled, dnd_active, battery_saver,
                thermal_status, auto_rotate, location_services_enabled,
                storage_free_bytes, storage_total_bytes,
                screen_brightness, screen_brightness_auto,
                media_volume, media_volume_max, ring_volume, ring_volume_max,
                notification_volume, notification_volume_max, alarm_volume, alarm_volume_max,
                ringer_mode, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    // reason: nested JDBC use{} resource scopes (StopWatch -> connection -> statement -> batch loop)
    // are inherent to the connection/statement lifecycle; per-column null-safe binding lives in
    // bindDeviceSettings so this method stays small.
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidDeviceSettingsEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Device settings upload batch too large: ${data.size} snapshots (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} device settings snapshots for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_DEVICE_SETTINGS_SQL).use { ps ->
                    data.forEach { event ->
                        bindDeviceSettings(ps, studyId, participantId, event)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }

    private fun bindDeviceSettings(
        ps: PreparedStatement,
        studyId: UUID,
        participantId: String,
        event: AndroidDeviceSettingsEvent,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setString(3, event.id)
        ps.setObject(4, event.timestamp)
        ps.setString(5, event.timezone)
        ps.setBooleanOrNull(6, event.darkMode)
        ps.setDoubleOrNull(7, event.fontScale?.toDouble())
        ps.setBooleanOrNull(8, event.accessibilityEnabled)
        ps.setBooleanOrNull(9, event.dndActive)
        ps.setBooleanOrNull(10, event.batterySaver)
        ps.setString(11, event.thermalStatus?.name)
        ps.setBooleanOrNull(12, event.autoRotate)
        ps.setBooleanOrNull(13, event.locationServicesEnabled)
        ps.setLongOrNull(14, event.storageFreeBytes)
        ps.setLongOrNull(15, event.storageTotalBytes)
        ps.setIntOrNull(16, event.screenBrightness)
        ps.setBooleanOrNull(17, event.screenBrightnessAuto)
        ps.setIntOrNull(18, event.mediaVolume)
        ps.setIntOrNull(19, event.mediaVolumeMax)
        ps.setIntOrNull(20, event.ringVolume)
        ps.setIntOrNull(21, event.ringVolumeMax)
        ps.setIntOrNull(22, event.notificationVolume)
        ps.setIntOrNull(23, event.notificationVolumeMax)
        ps.setIntOrNull(24, event.alarmVolume)
        ps.setIntOrNull(25, event.alarmVolumeMax)
        ps.setString(26, event.ringerMode?.name)
    }
}

private fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
    if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
}

private fun PreparedStatement.setLongOrNull(index: Int, value: Long?) {
    if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
}

private fun PreparedStatement.setBooleanOrNull(index: Int, value: Boolean?) {
    if (value != null) setBoolean(index, value) else setNull(index, Types.BOOLEAN)
}

private fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) {
    if (value != null) setDouble(index, value) else setNull(index, Types.DOUBLE)
}
