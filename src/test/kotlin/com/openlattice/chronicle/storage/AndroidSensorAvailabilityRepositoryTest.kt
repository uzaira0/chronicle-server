package com.openlattice.chronicle.storage

import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.UsageEnrollmentFixtures.databaseNow
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class AndroidSensorAvailabilityRepositoryTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("sensor_availability")

        @BeforeClass
        @JvmStatic
        fun migrate() = ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    @Test
    fun aRepeatedReportReplacesTheDevicesRow() {
        val studyId = UUID.randomUUID()
        val randomDeviceId = UUID.randomUUID()
        val latest = AndroidDeviceSensorAvailability(
            availableSensors = setOf(AndroidSensorType.gyroscope),
            screenWidthPixels = 1080,
            interactionPointerCaptureCapability = InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE,
        )
        postgres.createConnection("").use { connection ->
            val repository = AndroidSensorAvailabilityRepository(connection)
            repository.upsert(studyId, "participant", randomDeviceId,
                AndroidDeviceSensorAvailability(availableSensors = setOf(AndroidSensorType.accelerometer)))
            val beforeLatest = databaseNow(connection).toInstant()
            // A phone-supplied time is ignored: the row carries the server's receipt of the latest report.
            repository.upsert(studyId, "participant", randomDeviceId,
                latest.copy(reportedAt = OffsetDateTime.parse("2001-01-01T00:00:00Z")))
            val afterLatest = databaseNow(connection).toInstant()

            val rows = repository.listForStudy(studyId)

            assertEquals(1, rows.size)
            val reportedAt = rows.single().reportedAt!!.toInstant()
            assertTrue("$reportedAt outside [$beforeLatest, $afterLatest]",
                reportedAt in beforeLatest..afterLatest)
            assertEquals(latest.copy(participantId = "participant", randomDeviceId = randomDeviceId.toString()),
                rows.single().copy(reportedAt = null))
        }
    }

    @Test
    fun anUnknownStoredSensorNameFailsTheRead() {
        val studyId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO android_device_sensor_availability
                    (study_id, participant_id, random_device_id, available_sensors, reported_at)
                VALUES (?, 'participant', ?::uuid, ARRAY['retired_sensor'], now())
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, UUID.randomUUID().toString())
                it.executeUpdate()
            }

            assertThrows(IllegalArgumentException::class.java) {
                AndroidSensorAvailabilityRepository(connection).listForStudy(studyId)
            }
        }
    }
}
