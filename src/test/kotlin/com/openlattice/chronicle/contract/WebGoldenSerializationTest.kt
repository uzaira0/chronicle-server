package com.openlattice.chronicle.contract

import com.openlattice.chronicle.collection.CollectionDefaults
import com.fasterxml.jackson.databind.node.ObjectNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.AndroidDiagnosticCodeSummary
import com.openlattice.chronicle.study.AndroidDiagnosticsHistoryRow
import com.openlattice.chronicle.study.AndroidDiagnosticsPage
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageBlock
import com.openlattice.chronicle.survey.AppUsageDayApp
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.survey.AppUsageSurveyDay
import com.openlattice.chronicle.survey.AppUsageSurveyDays
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.Question
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class WebGoldenSerializationTest {
    private val mapper = ObjectMappers.getJsonMapper()

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
            "android-sensor-availability" to AndroidDeviceSensorAvailability(participantId, "device-abc-123",
                linkedSetOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
                1080, 2340, 440, 0, InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION, reported),
            "study" to Study(studyId = studyId, title = "Sleep Study 2024", description = "A longitudinal study on sleep patterns",
                createdAt = created, updatedAt = OffsetDateTime.parse("2024-06-01T12:00:00Z"),
                startedAt = OffsetDateTime.parse("2024-02-01T00:00:00Z"), endedAt = OffsetDateTime.parse("2025-02-01T00:00:00Z"),
                lat = 37.4275, lon = -122.1697, group = "neuroscience", version = "1.0", contact = "researcher@university.edu",
                organizationIds = setOf(UUID.fromString("33333333-3333-4333-8333-333333333333")),
                settings = StudySettings(mapOf(StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting())),
                modules = mapOf(
                    StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>(),
                    StudyFeature.TIME_USE_DIARY to emptyMap<String, Any>(),
                ),
                phoneNumber = "+15551234567"),
        )
        for ((name, fixture) in fixtures) {
            val actual = mapper.readTree(mapper.writeValueAsString(fixture))
            val expected = checkNotNull(javaClass.getResourceAsStream("/web-golden/backend-$name.json")).use {
                mapper.readTree(it) as ObjectNode
            }.also { it.remove("_comment") }
            assertEquals("$name serialization drift; update the canonical web golden and run the root contract generator", expected, actual)
            if (name == "study") {
                // Stored settings name their "@class"; the same text must read back unchanged.
                assertEquals(expected, mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(expected, Study::class.java))))
            }
        }
    }

    /**
     * The survey-day, answer and diagnostics-history payloads in the shapes the web client's generated
     * types read and send (`chronicle-web/src/modern/types/chronicle-api.generated.ts`). Times keep the study-zone offset
     * of the 25-hour 4 April 2026 Santiago day; an unanswered block carries `users: null`.
     */
    @Test fun surveyAndDiagnosticPayloadsMatchTheWebShapes() {
        val santiago = ZoneId.of("America/Santiago")
        val day = LocalDate.parse("2026-04-04")
        val start = day.atStartOfDay(santiago).toOffsetDateTime()
        val end = day.plusDays(1).atStartOfDay(santiago).toOffsetDateTime()
        val occurred = OffsetDateTime.parse("2026-04-04T15:00:00Z")
        val payloads = mapOf(
            AppUsageSurveyDays("America/Santiago", LocalDate.parse("2026-04-06"), listOf(
                AppUsageSurveyDay(day, AppUsageDayState.NEW_APPS), AppUsageSurveyDay(day.plusDays(1), AppUsageDayState.UNANSWERED),
            )) to """
                {"timeZone":"America/Santiago","today":"2026-04-06","days":[
                  {"date":"2026-04-04","state":"NEW_APPS"},{"date":"2026-04-05","state":"UNANSWERED"}]}
            """,
            AppUsageDaySurvey(day, "America/Santiago", AppUsageDayState.NEW_APPS, listOf(
                AppUsageDayApp("org.example.maps", "Maps", listOf(AppUsageBlock(start, end, listOf("Child", "I don't know")))),
                AppUsageDayApp("org.example.chat", "Chat", listOf(AppUsageBlock(start, end))),
            )) to """
                {"date":"2026-04-04","timeZone":"America/Santiago","state":"NEW_APPS","apps":[
                  {"appPackageName":"org.example.maps","appLabel":"Maps","blocks":[
                    {"start":"2026-04-04T00:00:00-03:00","end":"2026-04-05T00:00:00-04:00","users":["Child","I don't know"]}]},
                  {"appPackageName":"org.example.chat","appLabel":"Chat","blocks":[
                    {"start":"2026-04-04T00:00:00-03:00","end":"2026-04-05T00:00:00-04:00","users":null}]}]}
            """,
            AndroidDiagnosticsPage(listOf(AndroidDiagnosticsHistoryRow("p-1",
                java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"), day, listOf(
                    AndroidDiagnosticCodeSummary("9b2f6c1e-4d0a-4c55-9a51-7f1b0f8f2a10", "USAGE_LIFECYCLE", "HTTP_SERVER_ERROR", 3,
                        occurred, occurred, 503, null),
                ))), "2026-04-04~cC0x~x~0~_") to """
                {"items":[{"participantId":"p-1","randomDeviceId":"00000000-0000-4000-8000-000000000001","day":"2026-04-04",
                  "codes":[{"eventId":"9b2f6c1e-4d0a-4c55-9a51-7f1b0f8f2a10","moduleFamily":"USAGE_LIFECYCLE",
                    "issueCode":"HTTP_SERVER_ERROR","occurrenceCount":3,"firstOccurredAt":"2026-04-04T15:00:00Z",
                    "lastOccurredAt":"2026-04-04T15:00:00Z","httpStatus":503,"errorType":null}]}],
                 "nextCursor":"2026-04-04~cC0x~x~0~_"}
            """,
        )
        for ((payload, expected) in payloads) {
            assertEquals(payload.javaClass.simpleName, mapper.readTree(expected), mapper.readTree(mapper.writeValueAsString(payload)))
        }
        // The page sends back each block start exactly as it read it.
        val sent = mapper.readValue("""
            [{"appPackageName":"org.example.maps","blockStart":"2026-04-04T00:00:00-03:00","users":["Child"]},
             {"appPackageName":"org.example.chat","blockStart":"2026-04-04T03:00:00Z","users":[]}]
        """, Array<AppUsageAnswer>::class.java).toList()
        assertEquals(listOf(start.toInstant(), start.toInstant()), sent.map { it.blockStart.toInstant() })
        assertEquals(listOf(listOf("Child"), emptyList()), sent.map { it.users })
    }
}
