package com.openlattice.chronicle.storage

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

/** Reads and writes the latest sensor availability report, one row per study participant device. */
internal class AndroidSensorAvailabilityRepository(private val connection: Connection) {
    private companion object {
        private val UPSERT = """
            INSERT INTO android_device_sensor_availability
                (study_id, participant_id, random_device_id, available_sensors,
                 screen_width_pixels, screen_height_pixels, screen_density_dpi, display_rotation,
                 interaction_pointer_capture_capability, reported_at)
            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, random_device_id)
            DO UPDATE SET
                available_sensors = EXCLUDED.available_sensors,
                screen_width_pixels = EXCLUDED.screen_width_pixels,
                screen_height_pixels = EXCLUDED.screen_height_pixels,
                screen_density_dpi = EXCLUDED.screen_density_dpi,
                display_rotation = EXCLUDED.display_rotation,
                interaction_pointer_capture_capability = EXCLUDED.interaction_pointer_capture_capability,
                reported_at = EXCLUDED.reported_at
        """.trimIndent()

        private val LIST_FOR_STUDY = """
            SELECT participant_id, random_device_id, available_sensors,
                   screen_width_pixels, screen_height_pixels, screen_density_dpi, display_rotation,
                   interaction_pointer_capture_capability, reported_at
            FROM android_device_sensor_availability
            WHERE study_id = ?
        """.trimIndent()
    }

    fun upsert(studyId: UUID, participantId: String, randomDeviceId: UUID, availability: AndroidDeviceSensorAvailability): Int =
        connection.prepareStatement(UPSERT).use { ps ->
            ps.setObject(1, studyId)
            ps.setString(2, participantId)
            ps.setString(3, randomDeviceId.toString())
            ps.setArray(4, connection.createArrayOf("text", availability.availableSensors.map { it.name }.toTypedArray()))
            ps.setNullableInt(5, availability.screenWidthPixels)
            ps.setNullableInt(6, availability.screenHeightPixels)
            ps.setNullableInt(7, availability.screenDensityDpi)
            ps.setNullableInt(8, availability.displayRotation)
            ps.setString(9, availability.interactionPointerCaptureCapability?.name)
            ps.executeUpdate()
        }

    /** Every stored name was written from the enum, so an unknown name fails the read instead of vanishing. */
    fun listForStudy(studyId: UUID): List<AndroidDeviceSensorAvailability> =
        connection.prepareStatement(LIST_FOR_STUDY).use { ps ->
            ps.setObject(1, studyId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }

    private fun read(rs: ResultSet) = AndroidDeviceSensorAvailability(
        participantId = rs.getString("participant_id"),
        randomDeviceId = rs.getString("random_device_id"),
        availableSensors = rs.sensorTypes("available_sensors"),
        screenWidthPixels = rs.getObject("screen_width_pixels") as? Int,
        screenHeightPixels = rs.getObject("screen_height_pixels") as? Int,
        screenDensityDpi = rs.getObject("screen_density_dpi") as? Int,
        displayRotation = rs.getObject("display_rotation") as? Int,
        interactionPointerCaptureCapability = rs.getString("interaction_pointer_capture_capability")
            ?.let(InteractionPointerCaptureCapability::valueOf),
        reportedAt = rs.getObject("reported_at", OffsetDateTime::class.java),
    )

    private fun ResultSet.sensorTypes(column: String): Set<AndroidSensorType> =
        (getArray(column)?.array as? Array<*>).orEmpty().mapTo(mutableSetOf()) { AndroidSensorType.valueOf(it.toString()) }

    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) =
        if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
}
