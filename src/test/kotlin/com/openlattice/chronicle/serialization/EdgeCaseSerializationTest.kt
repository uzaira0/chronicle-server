package com.openlattice.chronicle.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.anonymization.AnonymizationConfig
import com.openlattice.chronicle.anonymization.DateGeneralization
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.dashboard.StudyEvent
import com.openlattice.chronicle.dashboard.StudyRealtimeStats
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.organizations.OrganizationQuotas
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.pipeline.PipelineConfig
import com.openlattice.chronicle.pipeline.PipelineStep
import com.openlattice.chronicle.pipeline.PipelineStepType
import com.openlattice.chronicle.sensorkit.SensorSetting
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.DataQualityConfig
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.study.ViolationReason
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.openlattice.chronicle.webhooks.WebhookRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.EnumSet
import java.util.UUID

/**
 * Edge case serialization tests covering dangerous patterns: empty vs null,
 * UUID format, date-time formats, EnumSet, large payloads, unicode, special chars,
 * deeply nested objects, and enum key maps.
 */
class EdgeCaseSerializationTest {
    private val logger = LoggerFactory.getLogger(EdgeCaseSerializationTest::class.java)
    private val mapper: ObjectMapper = ObjectMappers.getJsonMapper()

    private inline fun <reified T> assertRoundTrip(original: T, label: String) {
        val json = mapper.writerFor(T::class.java).writeValueAsString(original)
        logger.info("{} JSON: {}", label, json)
        val deserialized: T = mapper.readerFor(T::class.java).readValue(json)
        assertEquals("$label round-trip failed", original, deserialized)
    }

    // ======================== UUID serialization ========================

    @Test
    fun testUuidSerializesAsString() {
        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val event = StudyEvent(
            eventId = id,
            studyId = UUID.randomUUID(),
            eventType = "TEST"
        )
        val json = mapper.writeValueAsString(event)
        assertTrue("UUID should serialize as string", json.contains("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun testUuidDeserializesFromString() {
        val json = """{"studyId":"550e8400-e29b-41d4-a716-446655440000","participantId":"P1"}"""
        val stats: ParticipantStats = mapper.readValue(json)
        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), stats.studyId)
    }

    @Test
    fun testNilUuidRoundTrip() {
        val quotas = OrganizationQuotas(organizationId = UUID(0, 0))
        assertRoundTrip(quotas, "OrganizationQuotas(nilUuid)")
    }

    // ======================== OffsetDateTime serialization ========================

    @Test
    fun testOffsetDateTimeRoundTrip() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val sample = AndroidSensorSample(
            id = UUID.randomUUID(),
            sensor = AndroidSensorType.accelerometer,
            timestamp = now,
            timezone = "UTC"
        )
        val json = mapper.writeValueAsString(sample)
        val deserialized: AndroidSensorSample = mapper.readValue(json)
        // Compare instants since offset representation may differ
        assertEquals(now.toInstant(), deserialized.timestamp.toInstant())
    }

    @Test
    fun testOffsetDateTimeWithDifferentZones() {
        val timestamps = listOf(
            OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHours(-5)),
            OffsetDateTime.of(2025, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
        )
        timestamps.forEach { ts ->
            val response = TimeUseDiaryResponse(
                code = "q1",
                question = "Test?",
                response = setOf("Yes"),
                startDateTime = ts,
                endDateTime = ts.plusHours(1)
            )
            val json = mapper.writeValueAsString(response)
            val deserialized: TimeUseDiaryResponse = mapper.readValue(json)
            assertEquals(ts.toInstant(), deserialized.startDateTime?.toInstant())
        }
    }

    @Test
    fun testOffsetDateTimeMaxValue() {
        // OffsetDateTime.MAX is used in Study.endedAt by default
        val json = """{"studyId":"${UUID.randomUUID()}","participantId":"P1","androidLastPing":null}"""
        val stats: ParticipantStats = mapper.readValue(json)
        assertNull(stats.androidLastPing)
    }

    @Test
    fun testCandidateRoundTrip() {
        val candidate = Candidate()
        assertRoundTrip(candidate, "Candidate(id-only)")
    }

    @Test
    fun testCandidateFromJson() {
        val json = """{"id":"00000000-0000-0000-0000-000000000000"}"""
        val candidate: Candidate = mapper.readValue(json)
        assertEquals(UUID(0, 0), candidate.id)
    }

    // ======================== EnumSet serialization ========================

    @Test
    fun testEnumSetRoundTrip() {
        val limits = StudyLimits(
            features = EnumSet.of(StudyFeature.APP_USAGE, StudyFeature.TIME_USE_DIARY)
        )
        val json = mapper.writeValueAsString(limits)
        logger.info("EnumSet JSON: {}", json)
        val deserialized: StudyLimits = mapper.readValue(json)
        assertEquals(limits.features, deserialized.features)
    }

    @Test
    fun testEmptyEnumSetRoundTrip() {
        val limits = StudyLimits(features = EnumSet.noneOf(StudyFeature::class.java))
        val json = mapper.writeValueAsString(limits)
        val deserialized: StudyLimits = mapper.readValue(json)
        assertTrue(deserialized.features.isEmpty())
    }

    @Test
    fun testFullEnumSetRoundTrip() {
        val limits = StudyLimits(features = EnumSet.allOf(StudyFeature::class.java))
        val json = mapper.writeValueAsString(limits)
        val deserialized: StudyLimits = mapper.readValue(json)
        assertEquals(StudyFeature.values().size, deserialized.features.size)
    }

    @Test
    fun testEnumSetFromJsonArray() {
        val json = """{"features":["APP_USAGE","TIME_USE_DIARY","ANDROID_SENSOR"]}"""
        val limits: StudyLimits = mapper.readValue(json)
        assertEquals(3, limits.features.size)
        assertTrue(limits.features.contains(StudyFeature.APP_USAGE))
        assertTrue(limits.features.contains(StudyFeature.ANDROID_SENSOR))
    }

    // ======================== Map with enum keys ========================

    @Test
    fun testMapWithEnumKeysRoundTrip() {
        val settings = StudySettings(mapOf(
            StudySettingType.Survey to SurveySettings(),
            StudySettingType.Notifications to StudyNotificationSettings(labFriendlyName = "L", studyFriendlyName = "S")
        ))
        val json = mapper.writeValueAsString(settings)
        // Verify enum keys are serialized as strings
        assertTrue("Should contain Survey key", json.contains("Survey"))
        assertTrue("Should contain Notifications key", json.contains("Notifications"))
        val deserialized: StudySettings = mapper.readValue(json)
        assertEquals(2, deserialized.size)
        assertNotNull(deserialized[StudySettingType.Survey])
        assertNotNull(deserialized[StudySettingType.Notifications])
    }

    // ======================== Empty string vs null ========================

    @Test
    fun testEmptyStringFieldsRoundTrip() {
        val update = StudyUpdate(
            title = "Title",
            description = "",
            group = "",
            version = "",
            contact = "test@test.com"
        )
        val json = mapper.writeValueAsString(update)
        val deserialized: StudyUpdate = mapper.readValue(json)
        assertEquals("", deserialized.description)
        assertEquals("", deserialized.group)
    }

    @Test
    fun testNullOptionalFieldsPreserved() {
        val stats = ParticipantStats(
            studyId = UUID.randomUUID(),
            participantId = "P001",
            androidLastPing = null,
            androidFirstDate = null,
            tudLastDate = null
        )
        val json = mapper.writeValueAsString(stats)
        val deserialized: ParticipantStats = mapper.readValue(json)
        assertNull(deserialized.androidLastPing)
        assertNull(deserialized.androidFirstDate)
        assertNull(deserialized.tudLastDate)
    }

    @Test
    fun testEmptyCollectionsRoundTrip() {
        val stats = ParticipantStats(
            studyId = UUID.randomUUID(),
            participantId = "P001",
            androidUniqueDates = emptySet(),
            iosUniqueDates = emptySet(),
            tudUniqueDates = emptySet()
        )
        assertRoundTrip(stats, "ParticipantStats(emptyCollections)")
    }

    // ======================== Unicode / special characters ========================

    @Test
    fun testUnicodeInStringFields() {
        val violation = ComplianceViolation(
            reason = ViolationReason.NO_DATA_UPLOADED,
            description = "Participante no ha subido datos en 48 horas"
        )
        assertRoundTrip(violation, "ComplianceViolation(unicode-spanish)")
    }

    @Test
    fun testChineseCharactersInStringFields() {
        val request = StudyCloneRequest(newTitle = "\u7814\u7a76\u514b\u9686")
        assertRoundTrip(request, "StudyCloneRequest(chinese)")
    }

    @Test
    fun testEmojiInStringFields() {
        val event = StudyEvent(
            studyId = UUID.randomUUID(),
            eventType = "USER_NOTE",
            metadata = mapOf("note" to "Study going well! \uD83D\uDE80 Great progress! \u2705")
        )
        assertRoundTrip(event, "StudyEvent(emoji)")
    }

    @Test
    fun testSpecialCharsInStringFields() {
        val violation = ComplianceViolation(
            reason = ViolationReason.NOT_ENROLLED,
            description = "Line1\nLine2\tTabbed \"quoted\" back\\slash"
        )
        assertRoundTrip(violation, "ComplianceViolation(specialChars)")
    }

    @Test
    fun testQuotesAndBackslashesInJson() {
        val json = """{"reason":"NO_DATA_UPLOADED","description":"Contains \"quotes\" and \\backslashes\\"}"""
        val violation: ComplianceViolation = mapper.readValue(json)
        assertTrue(violation.description.contains("\"quotes\""))
        assertTrue(violation.description.contains("\\backslashes\\"))
    }

    @Test
    fun testNewlinesInJsonStringField() {
        val json = """{"reason":"NOT_ENROLLED","description":"line1\nline2\nline3"}"""
        val violation: ComplianceViolation = mapper.readValue(json)
        assertTrue(violation.description.contains("\n"))
    }

    // ======================== Large payloads ========================

    @Test
    fun testLargeChronicleDataPayload() {
        val studyId = UUID.randomUUID()
        val data = TestDataFactory.chronicleUsageEvents(studyId, "P001", 500)
        val json = mapper.writeValueAsString(data)
        logger.info("Large ChronicleData JSON size: {} bytes", json.length)
        val deserialized: ChronicleData = mapper.readValue(json)
        assertEquals(500, deserialized.size)
    }

    @Test
    fun testLargeMapPayload() {
        val largeMetadata = (0 until 200).associate { "key_$it" to "value_$it" as Any }
        val event = StudyEvent(
            studyId = UUID.randomUUID(),
            eventType = "BULK_UPDATE",
            metadata = largeMetadata
        )
        val json = mapper.writeValueAsString(event)
        val deserialized: StudyEvent = mapper.readValue(json)
        assertEquals(200, deserialized.metadata.size)
    }

    @Test
    fun testLargeSetPayload() {
        val manyDates = (0 until 365).map { LocalDate.of(2025, 1, 1).plusDays(it.toLong()) }.toSet()
        val stats = ParticipantStats(
            studyId = UUID.randomUUID(),
            participantId = "P001",
            androidUniqueDates = manyDates
        )
        assertRoundTrip(stats, "ParticipantStats(365dates)")
    }

    @Test
    fun testLargeWebhookEventTypes() {
        val request = WebhookCreateRequest(
            url = "https://example.com/webhook",
            eventTypes = EnumSet.allOf(WebhookEventType::class.java),
            description = "All events"
        )
        assertRoundTrip(request, "WebhookCreateRequest(allEvents)")
    }

    // ======================== Deeply nested objects ========================

    @Test
    fun testDeeplyNestedStudySettings() {
        // StudySettings -> StudyNotificationSettings -> StudyDuration
        val settings = StudySettings(mapOf(
            StudySettingType.Notifications to StudyNotificationSettings(
                labFriendlyName = "Deep Lab",
                studyFriendlyName = "Deep Study",
                noDataUploaded = StudyDuration(years = 1, months = 6, days = 15),
                noTudSubmitted = StudyDuration(years = 0, months = 3, days = 0),
                noAppUsageSurveySubmitted = StudyDuration(years = 0, months = 0, days = 30)
            ),
            StudySettingType.Pipeline to PipelineConfig(
                steps = listOf(
                    PipelineStep(type = PipelineStepType.DEIDENTIFICATION, order = 0, params = mapOf("a" to "b")),
                    PipelineStep(type = PipelineStepType.FEATURE_EXTRACTION, order = 1, params = mapOf("c" to "d")),
                    PipelineStep(type = PipelineStepType.AGGREGATION, order = 2),
                    PipelineStep(type = PipelineStepType.TIME_BUCKETING, order = 3, params = mapOf("minutes" to "15")),
                    PipelineStep(type = PipelineStepType.CUSTOM_SQL, order = 4, params = mapOf("query" to "SELECT 1")),
                ),
                enabled = true,
                timeBucketMinutes = 15
            )
        ))
        val json = mapper.writeValueAsString(settings)
        val deserialized: StudySettings = mapper.readValue(json)
        val notif = deserialized[StudySettingType.Notifications] as StudyNotificationSettings
        assertEquals(1, notif.noDataUploaded.years.toInt())
        assertEquals(6, notif.noDataUploaded.months.toInt())
        val pipeline = deserialized[StudySettingType.Pipeline] as PipelineConfig
        assertEquals(5, pipeline.steps.size)
    }

    @Test
    fun testNestedMapInMetadata() {
        val event = StudyEvent(
            studyId = UUID.randomUUID(),
            eventType = "COMPLEX",
            metadata = mapOf(
                "participant" to mapOf("id" to "P001", "status" to "active"),
                "counts" to mapOf("events" to 42, "days" to 7),
                "tags" to listOf("group-a", "morning")
            )
        )
        val json = mapper.writeValueAsString(event)
        val deserialized: StudyEvent = mapper.readValue(json)
        assertEquals(3, deserialized.metadata.size)
    }

    // ======================== Enum values ========================

    @Test
    fun testAllStudySettingTypesAsMapKeys() {
        // Verify all StudySettingType values serialize correctly as map keys
        StudySettingType.values().forEach { type ->
            val json = """{"${type.name}":{"@class":"com.openlattice.chronicle.study.DataQualityConfig",""" +
                """"expectedDaysPerWeek":5,"alertThresholdPercent":50,"evaluationWindowDays":14}}"""
            val settings: StudySettings = mapper.readValue(json)
            assertEquals(1, settings.size)
            assertNotNull("Key $type should deserialize", settings[type])
        }
    }

    @Test
    fun testAllApiKeyScopesRoundTrip() {
        ApiKeyScope.values().forEach { scope ->
            val request = ApiKeyCreateRequest(name = "test-$scope", scope = scope)
            assertRoundTrip(request, "ApiKeyCreateRequest($scope)")
        }
    }

    @Test
    fun testAllDateGeneralizationsRoundTrip() {
        DateGeneralization.values().forEach { gen ->
            val config = AnonymizationConfig(dateGeneralization = gen)
            assertRoundTrip(config, "AnonymizationConfig($gen)")
        }
    }

    @Test
    fun testAllAndroidSensorTypesInSetting() {
        AndroidSensorType.values().forEach { sensorType ->
            val setting = AndroidSensorSetting(sensors = setOf(sensorType))
            assertRoundTrip(setting, "AndroidSensorSetting($sensorType)")
        }
    }

    @Test
    fun testAllSensorTypesInSetting() {
        SensorType.values().forEach { sensorType ->
            val setting = SensorSetting(setOf(sensorType))
            val json = mapper.writeValueAsString(setting)
            val deserialized: SensorSetting = mapper.readValue(json)
            assertTrue(deserialized.contains(sensorType))
        }
    }

    @Test
    fun testAllViolationReasonsRoundTrip() {
        ViolationReason.values().forEach { reason ->
            val violation = ComplianceViolation(reason = reason, description = "test")
            assertRoundTrip(violation, "ComplianceViolation($reason)")
        }
    }

    @Test
    fun testAllWebhookEventTypesRoundTrip() {
        WebhookEventType.values().forEach { eventType ->
            val reg = WebhookRegistration(
                eventTypes = setOf(eventType),
                url = "https://example.com"
            )
            assertRoundTrip(reg, "WebhookRegistration($eventType)")
        }
    }

    // ======================== Boundary values ========================

    @Test
    fun testStudyDurationZeroValues() {
        val duration = StudyDuration(years = 0, months = 0, days = 0)
        assertRoundTrip(duration, "StudyDuration(zeros)")
    }

    @Test
    fun testStudyDurationMaxValues() {
        val duration = StudyDuration(years = 100, months = 11, days = 365)
        assertRoundTrip(duration, "StudyDuration(max)")
    }

    @Test
    fun testDeviceUsageLargeNumbers() {
        val usage = DeviceUsage(
            totalTime = Double.MAX_VALUE / 2,
            usageByPackage = mapOf("app" to 999999999.99),
            categoryByPackage = emptyMap()
        )
        assertRoundTrip(usage, "DeviceUsage(largeNumbers)")
    }

    @Test
    fun testStatsWithZeroSubmissions() {
        val stats = StudyRealtimeStats(
            studyId = UUID.randomUUID(),
            activeParticipants24h = 0,
            dataSubmissions24h = 0,
            totalParticipants = 0
        )
        assertRoundTrip(stats, "StudyRealtimeStats(zeros)")
    }

    @Test
    fun testEmptyStringDescriptions() {
        val config = AnonymizationConfig(redactedFields = emptySet())
        assertRoundTrip(config, "AnonymizationConfig(emptySet)")
    }

    // ======================== Default value verification ========================

    @Test
    fun testDefaultValuesPreservedAfterDeserialization() {
        // Verify that when only required fields are provided, defaults are correct
        val json = """{"@class":"com.openlattice.chronicle.notifications.StudyNotificationSettings",""" +
            """"labFriendlyName":"Lab","studyFriendlyName":"Study"}"""
        val settings: StudyNotificationSettings = mapper.readValue(json)
        assertEquals(false, settings.notifyResearchers)
        assertEquals(false, settings.notifyOnEnrollment)
        assertEquals("", settings.researcherPhoneNumbers)
        assertEquals(1, settings.noDataUploaded.days.toInt())
    }

    @Test
    fun testAndroidSensorSettingDefaults() {
        val json = """{"@class":"com.openlattice.chronicle.android.AndroidSensorSetting"}"""
        val setting: AndroidSensorSetting = mapper.readValue(json)
        assertEquals(5, setting.samplingRateHz)
        assertEquals(30, setting.dutyCycleActiveSeconds)
        assertEquals(300, setting.dutyCyclePeriodSeconds)
        assertTrue(setting.sensors.isEmpty())
    }

    @Test
    fun testSurveySettingsDefaults() {
        val json = """{"@class":"com.openlattice.chronicle.survey.SurveySettings"}"""
        val settings: SurveySettings = mapper.readValue(json)
        assertEquals(180, settings.appUsageThresholdInSeconds)
        assertEquals(180, settings.deviceUsageThresholdInSeconds)
    }

    @Test
    fun testTimeUseDiarySettingsDefaults() {
        val json = """{"@class":"com.openlattice.chronicle.timeusediary.TimeUseDiarySettings"}"""
        val settings: TimeUseDiarySettings = mapper.readValue(json)
        assertEquals(false, settings.enableChangesForSherbrookeUniversity)
        assertEquals(false, settings.enableChangesForOhioStateUniversity)
        assertEquals("en", settings.language)
    }

    @Test
    fun testDataQualityConfigDefaults() {
        val json = """{"@class":"com.openlattice.chronicle.study.DataQualityConfig"}"""
        val config: DataQualityConfig = mapper.readValue(json)
        assertEquals(5, config.expectedDaysPerWeek)
        assertEquals(50, config.alertThresholdPercent)
        assertEquals(14, config.evaluationWindowDays)
    }

    @Test
    fun testPipelineConfigDefaults() {
        val json = """{"@class":"com.openlattice.chronicle.pipeline.PipelineConfig"}"""
        val config: PipelineConfig = mapper.readValue(json)
        assertEquals(false, config.enabled)
        assertEquals(60, config.timeBucketMinutes)
        assertEquals("preprocessed_usage_events", config.outputTable)
        assertEquals(2, config.steps.size)
    }

    // ======================== Extra/unknown fields ========================

    @Test(expected = UnrecognizedPropertyException::class)
    fun testExtraFieldsRejectedDuringDeserialization() {
        // Unknown request fields are rejected so clients cannot smuggle ignored data.
        val json = """{"newTitle":"Test","includeParticipants":false,"includeSettings":true,"unknownField":"ignored"}"""
        mapper.readValue<StudyCloneRequest>(json)
    }
}
