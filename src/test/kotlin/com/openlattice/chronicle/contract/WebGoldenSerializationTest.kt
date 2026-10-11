package com.openlattice.chronicle.contract

import com.fasterxml.jackson.databind.node.ObjectNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.dashboard.StudyEvent
import com.openlattice.chronicle.dashboard.StudyRealtimeStats
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.Question
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.LocalDate
import java.util.UUID

class WebGoldenSerializationTest {
    private val mapper = ObjectMappers.getJsonMapper()
    private val goldenDirectory = File("../chronicle-web/src/modern/test/golden")

    @Test fun productionModelsSerializeExactlyToWebGoldens() {
        val studyId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val participantId = "p-1"
        val created = OffsetDateTime.parse("2024-01-15T10:00:00Z")
        val reported = OffsetDateTime.parse("2024-06-15T14:30:00Z")
        val fixtures = mapOf(
            "participant" to Participant("p-golden-1", Candidate(UUID.fromString("11111111-1111-4111-8111-111111111111")),
                ParticipationStatus.ENROLLED, "Completed all sessions", linkedSetOf("control", "wave-2")),
            "participant-stats" to ParticipantStats(studyId, participantId,
                androidLastPing = reported, androidFirstDate = created, androidLastDate = reported,
                androidUniqueDates = linkedSetOf(LocalDate.parse("2024-01-15"), LocalDate.parse("2024-01-16"), LocalDate.parse("2024-01-17")),
                tudFirstDate = OffsetDateTime.parse("2024-03-01T00:00:00Z"),
                tudLastDate = OffsetDateTime.parse("2024-05-15T00:00:00Z"), tudUniqueDates = setOf(LocalDate.parse("2024-03-01"))),
            "questionnaire" to Questionnaire(UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "Daily Check-In", created, "A daily check-in survey", true,
                listOf(Question("How are you feeling?", linkedSetOf("Good", "Fair", "Poor")), Question("Any concerns?")), "FREQ=DAILY"),
            "study-duration" to StudyDuration(years = 1, months = 6),
            "study-event" to StudyEvent(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
                studyId, "ENROLLMENT", participantId, mapOf("source" to "android-app"), reported),
            "study-realtime-stats" to StudyRealtimeStats(studyId, 5, 142, 20, reported,
                mapOf("USAGE_EVENT" to 100L, "SENSOR_DATA" to 42L), OffsetDateTime.parse("2024-06-15T15:00:00Z")),
            "android-sensor-availability" to AndroidDeviceSensorAvailability(participantId, "device-abc-123",
                linkedSetOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope), setOf(AndroidSensorType.magnetometer),
                1080, 2340, 440, 0, InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION, reported),
            "study" to Study(studyId = studyId, title = "Sleep Study 2024", description = "A longitudinal study on sleep patterns",
                createdAt = created, updatedAt = OffsetDateTime.parse("2024-06-01T12:00:00Z"),
                startedAt = OffsetDateTime.parse("2024-02-01T00:00:00Z"), endedAt = OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                lat = 37.4275, lon = -122.1697, group = "neuroscience", version = "1.0", contact = "researcher@university.edu",
                organizationIds = setOf(UUID.fromString("33333333-3333-4333-8333-333333333333")), notificationsEnabled = true,
                settings = StudySettings(mapOf(StudySettingType.AndroidSensor to AndroidSensorSetting(setOf(AndroidSensorType.accelerometer)))),
                modules = mapOf(StudyFeature.ANDROID_SENSOR to emptyMap<String, Any>(), StudyFeature.TIME_USE_DIARY to emptyMap<String, Any>()),
                phoneNumber = "+15551234567"),
        )
        for ((name, fixture) in fixtures) {
            val file = File(goldenDirectory, "backend-$name.json")
            val actual = mapper.readTree(mapper.writeValueAsString(fixture))
            if (System.getenv("UPDATE_WEB_GOLDENS") == "1") {
                file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
            }
            assertEquals("$name serialization drift; regenerate with UPDATE_WEB_GOLDENS=1", mapper.readTree(file).also {
                (it as ObjectNode).remove("_comment")
            }, actual)
        }
    }

}
