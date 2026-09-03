package com.openlattice.chronicle.controllers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class StudyControllerSensorAvailabilitySqlTest {

    @Test
    fun sensorAvailabilitySqlUsesDerivedDeviceIdColumn() {
        val source = readStudyControllerSource()
        val availabilitySqlRegion = source.substringAfter("UPSERT_SENSOR_AVAILABILITY_SQL")
            .substringBefore("GET_STUDY_DEVICES_SQL")

        assertTrue(availabilitySqlRegion.contains("device_id"))
        assertTrue(availabilitySqlRegion.contains("ON CONFLICT (study_id, participant_id, device_id)"))
        assertFalse(
            "Android sensor availability should not use the pre-derived source_device_id schema",
            availabilitySqlRegion.contains("source_device_id")
        )
    }

    private fun readStudyControllerSource(): String {
        val candidates = listOf(
            Paths.get("src/main/kotlin/com/openlattice/chronicle/controllers/StudyController.kt"),
            Paths.get("chronicle-server/src/main/kotlin/com/openlattice/chronicle/controllers/StudyController.kt")
        )
        val sourcePath: Path = candidates.firstOrNull { Files.exists(it) }
            ?: error("StudyController.kt not found from ${Paths.get("").toAbsolutePath()}")
        return Files.readString(sourcePath)
    }
}
