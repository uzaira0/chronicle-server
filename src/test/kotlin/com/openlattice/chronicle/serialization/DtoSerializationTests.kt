package com.openlattice.chronicle.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyCreateResponse
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.Ace
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Action
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.Role
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.collection.AudioContentType
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.AudioOutputRoute
import com.openlattice.chronicle.collection.AudioPlaybackState
import com.openlattice.chronicle.collection.AudioRingerMode
import com.openlattice.chronicle.collection.NotificationEventType
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.study.ViolationReason
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageBlock
import com.openlattice.chronicle.survey.AppUsageDayApp
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.survey.AppUsageDaySurvey
import com.openlattice.chronicle.survey.DeviceUsage
import com.openlattice.chronicle.survey.Question
import com.openlattice.chronicle.survey.Questionnaire
import com.openlattice.chronicle.survey.QuestionnaireUpdate
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.users.DirectedAclKeys
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.webhooks.WebhookCreateRequest
import com.openlattice.chronicle.webhooks.WebhookDeliveryInfo
import com.openlattice.chronicle.webhooks.WebhookDeliveryState
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.openlattice.chronicle.webhooks.WebhookRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

/**
 * Serialization round-trip tests for all DTOs that are sent/received over HTTP.
 * These tests catch Jackson configuration issues (e.g. @JsonCreator conflicts,
 * missing modules, constructor problems) without needing a running server.
 */
// reason: one round-trip test per HTTP DTO — the size reflects the breadth of the serialization
// contract surface; splitting it would scatter the wire-format coverage across files
@Suppress("LargeClass")
class DtoSerializationTests {
    private val logger = LoggerFactory.getLogger(DtoSerializationTests::class.java)
    private val mapper: ObjectMapper = ObjectMappers.getJsonMapper()

    private inline fun <reified T> assertRoundTrip(original: T, label: String) {
        val json = mapper.writerFor(T::class.java).writeValueAsString(original)
        logger.info("{} JSON: {}", label, json)
        val deserialized: T = mapper.readerFor(T::class.java).readValue(json)
        assertEquals("$label round-trip failed", original, deserialized)
    }

    // ======================== StudyDuration ========================

    @Test
    fun testStudyDurationSerialization() {
        val duration = StudyDuration(years = 1, months = 6, days = 15)
        assertRoundTrip(duration, "StudyDuration")
    }

    @Test
    fun testStudyDurationDefaultsSerialization() {
        val duration = StudyDuration()
        assertRoundTrip(duration, "StudyDuration(defaults)")
    }

    @Test
    fun testStudyDurationFromJson() {
        val json = """{"years":3,"months":11,"days":365}"""
        val d: StudyDuration = mapper.readValue(json)
        assertEquals(3, d.years.toInt())
        assertEquals(11, d.months.toInt())
        assertEquals(365, d.days.toInt())
    }

    // ======================== StudyLimits ========================

    @Test
    fun testStudyLimitsSerialization() {
        val limits = StudyLimits(
            studyDuration = StudyDuration(years = 2),
            dataRetentionDuration = StudyDuration(days = 180),
        )
        assertRoundTrip(limits, "StudyLimits")
    }

    @Test
    fun testStudyLimitsDefaultsSerialization() {
        val limits = StudyLimits()
        assertRoundTrip(limits, "StudyLimits(defaults)")
    }

    @Test
    fun testStudyLimitsFromJson() {
        val json = """
            {
                "studyDuration": {"years": 1, "months": 0, "days": 0},
                "dataRetentionDuration": {"years": 0, "months": 0, "days": 90}
            }
        """.trimIndent()
        val limits: StudyLimits = mapper.readValue(json)
        assertEquals(1, limits.studyDuration?.years?.toInt())
        assertEquals(90, limits.dataRetentionDuration.days.toInt())
    }

    @Test
    fun limitsWithoutADurationHaveNoEnd() {
        val limits: StudyLimits = mapper.readValue("""{"dataRetentionDuration": {"days": 30}}""")
        assertNull(limits.studyDuration)
        assertNull(limits.studyEnds)
        assertNull(limits.studyDataExpires)
    }

    @Test
    fun testStudyLimitsFactoryMethodSerialization() {
        val limits = TestDataFactory.studyLimits()
        assertRoundTrip(limits, "StudyLimits(factory)")
    }

    // ======================== StudyUpdate ========================

    @Test
    fun testStudyUpdateSerialization() {
        val update = StudyUpdate(
            title = "Updated Title",
            description = "Updated Description",
            lat = 37.7749,
            lon = -122.4194,
            contact = "test@example.com"
        )
        assertRoundTrip(update, "StudyUpdate")
    }

    @Test
    fun testStudyUpdateNullFieldsSerialization() {
        val update = StudyUpdate(title = "Only Title")
        val json = mapper.writeValueAsString(update)
        val deserialized: StudyUpdate = mapper.readValue(json)
        assertEquals("Only Title", deserialized.title)
        assertNull(deserialized.description)
        assertNull(deserialized.lat)
    }

    @Test
    fun studyUpdateModulesAcceptOnlyKnownFeatures() {
        val update: StudyUpdate = mapper.readValue("""{"modules": {"CHRONICLE_DATA_COLLECTION": {}}}""")
        assertEquals(setOf(StudyFeature.CHRONICLE_DATA_COLLECTION), update.modules?.keys)
        // An unknown key stored on the study would break every later read of it, so the request fails instead.
        assertThrows(InvalidFormatException::class.java) {
            mapper.readValue<StudyUpdate>("""{"modules": {"NOT_A_FEATURE": {}}}""")
        }
    }

    @Test
    fun testStudyUpdateDefaultsSerialization() {
        val update = StudyUpdate()
        assertRoundTrip(update, "StudyUpdate(defaults)")
    }

    @Test
    fun testStudyUpdateAllFieldsSerialization() {
        val update = StudyUpdate(
            title = "Full Update",
            description = "Full description",
            lat = 45.0,
            lon = -90.0,
            group = "group-1",
            version = "v2",
            contact = "admin@test.com",
            storage = "chronicle"
        )
        assertRoundTrip(update, "StudyUpdate(allFields)")
    }

    @Test
    fun testStudyUpdateFromFrontendJson() {
        val json = """{"title":"New Title","contact":"new@test.com"}"""
        val update: StudyUpdate = mapper.readValue(json)
        assertEquals("New Title", update.title)
        assertEquals("new@test.com", update.contact)
        assertNull(update.description)
    }

    // ======================== Study ========================

    @Test
    fun testStudySerialization() {
        val study = TestDataFactory.study()
        val json = mapper.writeValueAsString(study)
        logger.info("Study JSON: {}", json)
        val deserialized: Study = mapper.readValue(json)
        assertEquals(study.title, deserialized.title)
        assertEquals(study.contact, deserialized.contact)
    }

    @Test
    fun testStudyFromFrontendJson() {
        val json = """{
            "title": "Frontend Study",
            "contact": "researcher@university.edu",
            "description": "A study created from the frontend"
        }"""
        val study: Study = mapper.readValue(json)
        assertEquals("Frontend Study", study.title)
        assertEquals("researcher@university.edu", study.contact)
        assertEquals("A study created from the frontend", study.description)
    }

    // ======================== StudySettings ========================

    @Test
    fun testStudySettingsSerialization() {
        val settings = TestDataFactory.randomSettings()
        val json = mapper.writeValueAsString(settings)
        logger.info("StudySettings JSON: {}", json)
        val deserialized: StudySettings = mapper.readValue(json)
        assertEquals(settings.size, deserialized.size)
    }

    @Test
    fun testStudySettingsEmptySerialization() {
        val settings = StudySettings()
        val json = mapper.writeValueAsString(settings)
        logger.info("StudySettings(empty) JSON: {}", json)
        val deserialized: StudySettings = mapper.readValue(json)
        assertTrue(deserialized.isEmpty())
    }

    // ======================== StudyCloneRequest ========================

    @Test
    fun testStudyCloneRequestSerialization() {
        val request = StudyCloneRequest(
            newTitle = "Cloned Study",
            includeSettings = true
        )
        assertRoundTrip(request, "StudyCloneRequest")
    }

    @Test
    fun testStudyCloneRequestDefaultsSerialization() {
        val request = StudyCloneRequest()
        assertRoundTrip(request, "StudyCloneRequest(defaults)")
    }

    @Test
    fun testStudyCloneRequestFromJson() {
        val json = """{"newTitle":"Copy"}"""
        val request: StudyCloneRequest = mapper.readValue(json)
        assertEquals("Copy", request.newTitle)
        assertTrue(request.includeSettings) // default
    }

    // ======================== ComplianceViolation ========================

    @Test
    fun testComplianceViolationSerialization() {
        val violation = ComplianceViolation(
            reason = ViolationReason.NO_DATA_UPLOADED,
            description = "No data received in the last 48 hours"
        )
        assertRoundTrip(violation, "ComplianceViolation")
    }

    @Test
    fun testComplianceViolationAllReasons() {
        ViolationReason.values().forEach { reason ->
            val violation = ComplianceViolation(reason = reason, description = "Test for $reason")
            assertRoundTrip(violation, "ComplianceViolation($reason)")
        }
    }

    // ======================== AndroidSensorSetting ========================

    @Test
    fun testAndroidSensorSettingDefaultsSerialization() {
        val setting = AndroidSensorSetting()
        assertRoundTrip(setting, "AndroidSensorSetting(defaults)")
    }

    @Test
    fun testAndroidSensorSettingWithSensorsSerialization() {
        val setting = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope, AndroidSensorType.magnetometer),
            samplingRateHz = 10,
            dutyCycleActiveSeconds = 60,
            dutyCyclePeriodSeconds = 600
        )
        assertRoundTrip(setting, "AndroidSensorSetting(full)")
    }

    @Test
    fun testAndroidSensorSettingEmptySensorsSerialization() {
        val setting = AndroidSensorSetting(sensors = emptySet())
        assertRoundTrip(setting, "AndroidSensorSetting(emptySensors)")
    }

    @Test
    fun testAndroidSensorSettingAllSensors() {
        val setting = AndroidSensorSetting(
            sensors = EnumSet.allOf(AndroidSensorType::class.java)
        )
        assertRoundTrip(setting, "AndroidSensorSetting(allSensors)")
    }

    @Test
    fun testAndroidSensorSettingClassMarkerInJson() {
        val setting = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))
        val json = mapper.writeValueAsString(setting)
        // AndroidSensorSetting does NOT have its own @JsonTypeInfo, it inherits from StudySetting
        // but when serialized standalone it may or may not have @class depending on context
        val deserialized: AndroidSensorSetting = mapper.readValue(json)
        assertEquals(setting, deserialized)
    }

    @Test
    fun testAndroidSensorSettingFromJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.android.AndroidSensorSetting",
            "sensors": ["accelerometer", "gyroscope"],
            "samplingRateHz": 15,
            "dutyCycleActiveSeconds": 120,
            "dutyCyclePeriodSeconds": 1200
        }"""
        val setting: AndroidSensorSetting = mapper.readValue(json)
        assertEquals(2, setting.sensors.size)
        assertTrue(setting.sensors.contains(AndroidSensorType.accelerometer))
        assertTrue(setting.sensors.contains(AndroidSensorType.gyroscope))
        assertEquals(15, setting.samplingRateHz)
        assertEquals(120, setting.dutyCycleActiveSeconds)
    }

    // ======================== StudyNotificationSettings ========================

    @Test
    fun testStudyNotificationSettingsSerialization() {
        val settings = StudyNotificationSettings(
            labFriendlyName = "Neuro Lab",
            studyFriendlyName = "Sleep Study",
            notifyResearchers = true,
            notifyOnEnrollment = true,
            researcherPhoneNumbers = "+1234567890"
        )
        assertRoundTrip(settings, "StudyNotificationSettings")
    }

    @Test
    fun testStudyNotificationSettingsDefaultsSerialization() {
        val settings = StudyNotificationSettings(
            labFriendlyName = "Lab",
            studyFriendlyName = "Study"
        )
        assertRoundTrip(settings, "StudyNotificationSettings(defaults)")
    }

    @Test
    fun testStudyNotificationSettingsClassMarkerPresent() {
        val settings = StudyNotificationSettings(
            labFriendlyName = "Lab",
            studyFriendlyName = "Study"
        )
        val json = mapper.writeValueAsString(settings)
        assertTrue("@class should be present", json.contains("@class"))
        assertTrue("Should contain full class name", json.contains("StudyNotificationSettings"))
    }

    @Test
    fun testStudyNotificationSettingsFromFrontendJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.notifications.StudyNotificationSettings",
            "labFriendlyName": "Research Lab",
            "studyFriendlyName": "Child Development Study",
            "notifyResearchers": true,
            "notifyOnEnrollment": false,
            "researcherPhoneNumbers": "",
            "noDataUploaded": {"years": 0, "months": 0, "days": 2}
        }"""
        val settings: StudyNotificationSettings = mapper.readValue(json)
        assertEquals("Research Lab", settings.labFriendlyName)
        assertTrue(settings.notifyResearchers)
        assertEquals(2, settings.noDataUploaded.days.toInt())
    }

    @Test
    fun testStudyNotificationSettingsWithCustomDurations() {
        val settings = StudyNotificationSettings(
            labFriendlyName = "Lab",
            studyFriendlyName = "Study",
            noDataUploaded = StudyDuration(days = 7),
        )
        assertRoundTrip(settings, "StudyNotificationSettings(customDurations)")
    }

    // ======================== SurveySettings ========================

    @Test
    fun testSurveySettingsSerialization() {
        val settings = SurveySettings(
            appUsageThresholdInSeconds = 600,
            deviceUsageThresholdInSeconds = 900
        )
        assertRoundTrip(settings, "SurveySettings")
    }

    @Test
    fun testSurveySettingsDefaultsSerialization() {
        val settings = SurveySettings()
        assertRoundTrip(settings, "SurveySettings(defaults)")
    }

    @Test
    fun testSurveySettingsClassMarkerPresent() {
        val settings = SurveySettings()
        val json = mapper.writeValueAsString(settings)
        assertTrue("@class should be present", json.contains("@class"))
        assertTrue("Should contain SurveySettings class", json.contains("SurveySettings"))
    }

    @Test
    fun testSurveySettingsFromFrontendJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.survey.SurveySettings",
            "appUsageThresholdInSeconds": 300,
            "deviceUsageThresholdInSeconds": 600
        }"""
        val settings: SurveySettings = mapper.readValue(json)
        assertEquals(300, settings.appUsageThresholdInSeconds)
        assertEquals(600, settings.deviceUsageThresholdInSeconds)
    }

    // ======================== TimeUseDiarySettings ========================

    @Test
    fun testTimeUseDiarySettingsSerialization() {
        val settings = TimeUseDiarySettings(
            enableChangesForSherbrookeUniversity = true,
            enableChangesForOhioStateUniversity = false,
            language = "fr"
        )
        assertRoundTrip(settings, "TimeUseDiarySettings")
    }

    @Test
    fun testTimeUseDiarySettingsDefaultsSerialization() {
        val settings = TimeUseDiarySettings()
        assertRoundTrip(settings, "TimeUseDiarySettings(defaults)")
    }

    @Test
    fun testTimeUseDiarySettingsClassMarkerPresent() {
        val settings = TimeUseDiarySettings()
        val json = mapper.writeValueAsString(settings)
        assertTrue("@class should be present", json.contains("@class"))
        assertTrue("Should contain class name", json.contains("TimeUseDiarySettings"))
    }

    @Test
    fun testTimeUseDiarySettingsFromFrontendJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.timeusediary.TimeUseDiarySettings",
            "enableChangesForSherbrookeUniversity": true,
            "enableChangesForOhioStateUniversity": false,
            "language": "en"
        }"""
        val settings: TimeUseDiarySettings = mapper.readValue(json)
        assertTrue(settings.enableChangesForSherbrookeUniversity)
        assertEquals("en", settings.language)
    }

    // ======================== SensorSetting ========================

    // ======================== AndroidDevice ========================

    @Test
    fun testAndroidDeviceSerialization() {
        val device = TestDataFactory.androidDevice()
        assertRoundTrip(device, "AndroidDevice")
    }

    @Test
    fun testAndroidDeviceWithAdditionalInfoSerialization() {
        val device = AndroidDevice(
            device = "Pixel",
            model = "Pixel 7",
            codename = "panther",
            brand = "Google",
            osVersion = "14",
            sdkVersion = "34",
            product = "panther",
            randomDeviceId = "abc-123",
            additionalInfo = mapOf("screenSize" to "6.3", "ram" to 8),
            fcmRegistrationToken = "fcm-token-xyz"
        )
        assertRoundTrip(device, "AndroidDevice(full)")
    }

    @Test
    fun testAndroidDeviceClassMarkerPresent() {
        val device = TestDataFactory.androidDevice()
        val json = mapper.writeValueAsString(device)
        assertTrue("@class should be present", json.contains("@class"))
        assertTrue("Should contain AndroidDevice class", json.contains("AndroidDevice"))
    }

    // ======================== IOSDevice ========================

    @Test
    fun testIOSDeviceSerialization() {
        val device = IOSDevice(
            name = "iPhone",
            systemName = "iOS",
            model = "iPhone 15",
            localizedModel = "iPhone",
            version = "17.0",
            randomDeviceId = "test-random-device-id",
            apnDeviceToken = "test-token"
        )
        assertRoundTrip(device, "IOSDevice")
    }

    @Test
    fun testIOSDeviceMinimalSerialization() {
        val device = IOSDevice(
            name = "iPad",
            systemName = "iPadOS",
            model = "iPad Pro",
            localizedModel = "iPad Pro",
            version = "17.1",
            randomDeviceId = "device-456"
        )
        assertRoundTrip(device, "IOSDevice(minimal)")
    }

    @Test
    fun testIOSDeviceClassMarkerPresent() {
        val device = IOSDevice(
            name = "iPhone",
            systemName = "iOS",
            model = "iPhone 15",
            localizedModel = "iPhone",
            version = "17.0",
            randomDeviceId = "test-id"
        )
        val json = mapper.writeValueAsString(device)
        assertTrue("@class should be present", json.contains("@class"))
        assertTrue("Should contain IOSDevice class", json.contains("IOSDevice"))
    }

    // ======================== AndroidSensorSample ========================

    @Test
    fun testAndroidSensorSampleSerialization() {
        val sample = AndroidSensorSample(
            id = UUID.randomUUID(),
            sensor = AndroidSensorType.accelerometer,
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            x = 1.5f,
            y = -0.3f,
            z = 9.81f,
            w = null,
            accuracy = 3
        )
        assertRoundTrip(sample, "AndroidSensorSample")
    }

    @Test
    fun testAndroidSensorSampleMinimalSerialization() {
        val sample = AndroidSensorSample(
            id = UUID.randomUUID(),
            sensor = AndroidSensorType.light,
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "UTC"
        )
        assertRoundTrip(sample, "AndroidSensorSample(minimal)")
    }

    @Test
    fun testAndroidSensorSampleFromJson() {
        val id = UUID.randomUUID()
        val json = """{
            "id": "$id",
            "sensor": "gyroscope",
            "timestamp": "2025-01-15T10:30:00Z",
            "timezone": "US/Eastern",
            "x": 0.1,
            "y": 0.2,
            "z": 0.3
        }"""
        val sample: AndroidSensorSample = mapper.readValue(json)
        assertEquals(id, sample.id)
        assertEquals(AndroidSensorType.gyroscope, sample.sensor)
        assertNull(sample.w)
        assertNull(sample.accuracy)
    }

    // ======================== ChronicleUsageEvent ========================

    @Test
    fun testChronicleUsageEventSerialization() {
        val studyId = UUID.randomUUID()
        val event = ChronicleUsageEvent(
            studyId = studyId,
            participantId = "P001",
            appPackageName = "com.example.app",
            interactionType = "Move to Foreground",
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            user = "user1",
            applicationLabel = "Example App",
            activityClass = "com.example.MainActivity"
        )
        assertRoundTrip(event, "ChronicleUsageEvent")
    }

    @Test
    fun testChronicleUsageEventFactorySerialization() {
        val studyId = UUID.randomUUID()
        val data = TestDataFactory.chronicleUsageEvents(studyId, "P002", 3)
        data.forEach { event ->
            assertTrue(event is ChronicleUsageEvent)
            event as ChronicleUsageEvent
            val json = mapper.writeValueAsString(event)
            val deserialized: ChronicleUsageEvent = mapper.readValue(json)
            assertEquals(studyId, deserialized.studyId)
            assertEquals("P002", deserialized.participantId)
            assertEquals(event.activityClass, deserialized.activityClass)
        }
    }

    // ======================== ChronicleData ========================

    // ======================== AndroidDeviceSensorAvailability ========================

    @Test
    fun testAndroidDeviceSensorAvailabilitySerialization() {
        val availability = AndroidDeviceSensorAvailability(
            participantId = "P001",
            randomDeviceId = "device-123",
            availableSensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            reportedAt = OffsetDateTime.now(ZoneOffset.UTC),
            // Populated static display context — round-trips the four capture-once fields.
            screenWidthPixels = 1080,
            screenHeightPixels = 2340,
            screenDensityDpi = 440,
            displayRotation = 1
        )
        assertRoundTrip(availability, "AndroidDeviceSensorAvailability")
    }

    @Test
    fun sensorAvailabilityCarriesOneListOfTheSensorsTheDeviceHas() {
        val older = """{"availableSensors":["gyroscope"],"unavailableSensors":["proximity"]}"""
        val availability: AndroidDeviceSensorAvailability = mapper.readValue(older)
        assertEquals(setOf(AndroidSensorType.gyroscope), availability.availableSensors)
        val written = mapper.readTree(mapper.writeValueAsString(availability)).fieldNames().asSequence().toSet()
        assertTrue(written.toString(), "availableSensors" in written && "unavailableSensors" !in written)
    }

    @Test
    fun testAndroidDeviceSensorAvailabilityDefaultsSerialization() {
        val availability = AndroidDeviceSensorAvailability()
        assertRoundTrip(availability, "AndroidDeviceSensorAvailability(defaults)")
    }

    // ======================== Candidate ========================

    @Test
    fun testCandidateSerialization() {
        val candidate = TestDataFactory.candidate()
        assertRoundTrip(candidate, "Candidate")
    }

    @Test
    fun testCandidateMinimalSerialization() {
        val candidate = Candidate()
        assertRoundTrip(candidate, "Candidate(minimal)")
    }

    @Test
    fun testCandidateFromJson() {
        val json = """{"id": "00000000-0000-0000-0000-000000000000"}"""
        val candidate: Candidate = mapper.readValue(json)
        assertEquals(UUID(0, 0), candidate.id)
    }

    // ======================== Participant ========================

    @Test
    fun testParticipantSerialization() {
        val participant = TestDataFactory.participant()
        assertRoundTrip(participant, "Participant")
    }

    @Test
    fun testParticipantWithNotesSerialization() {
        val participant = Participant(
            participantId = "P-100",
            candidate = TestDataFactory.candidate(),
            participationStatus = ParticipationStatus.ENROLLED,
            participantNotes = "This participant needs accommodation",
            participantTags = setOf("group-a", "morning-session")
        )
        assertRoundTrip(participant, "Participant(withNotes)")
    }

    @Test
    fun testParticipantAllStatusesSerialization() {
        ParticipationStatus.values().forEach { status ->
            val participant = TestDataFactory.participant(status)
            assertRoundTrip(participant, "Participant($status)")
        }
    }

    // ======================== ParticipantStats ========================

    @Test
    fun testParticipantStatsSerialization() {
        val stats = TestDataFactory.participantStats()
        assertRoundTrip(stats, "ParticipantStats")
    }

    @Test
    fun testParticipantStatsMinimalSerialization() {
        val stats = ParticipantStats(
            studyId = UUID.randomUUID(),
            participantId = "P-200"
        )
        assertRoundTrip(stats, "ParticipantStats(minimal)")
    }

    // ======================== ParticipantNotification ========================

    @Test
    fun testParticipantNotificationSerialization() {
        val notification = ParticipantNotification(
            participantId = "P001",
            notificationType = NotificationType.ENROLLMENT,
            deliveryType = EnumSet.of(DeliveryType.SMS),
            message = "Welcome to the study!"
        )
        assertRoundTrip(notification, "ParticipantNotification")
    }

    @Test
    fun testParticipantNotificationAllDeliveryTypes() {
        val notification = ParticipantNotification(
            participantId = "P002",
            notificationType = NotificationType.PASSIVE_DATA_COLLECTION_COMPLIANCE,
            deliveryType = EnumSet.of(DeliveryType.SMS, DeliveryType.EMAIL),
            message = "Please enable data collection"
        )
        assertRoundTrip(notification, "ParticipantNotification(allDelivery)")
    }

    @Test
    fun testParticipantNotificationFromJson() {
        val json = """{
            "participantId": "P003",
            "notificationType": "TUD_SUBMISSION_COMPLIANCE",
            "deliveryType": ["EMAIL"],
            "message": "Please submit your time use diary"
        }"""
        val notification: ParticipantNotification = mapper.readValue(json)
        assertEquals("P003", notification.participantId)
        assertEquals(NotificationType.TUD_SUBMISSION_COMPLIANCE, notification.notificationType)
        assertTrue(notification.deliveryType.contains(DeliveryType.EMAIL))
    }

    // ======================== Questionnaire & QuestionnaireUpdate ========================

    @Test
    fun testQuestionnaireSerialization() {
        val questionnaire = Questionnaire(
            id = UUID.randomUUID(),
            title = "Daily Check-In",
            dateCreated = OffsetDateTime.now(ZoneOffset.UTC),
            description = "Brief daily questionnaire",
            active = true,
            questions = listOf(
                Question(title = "How are you feeling?", choices = setOf("Good", "OK", "Bad")),
                Question(title = "Any notes?")
            ),
            recurrenceRule = "FREQ=DAILY"
        )
        assertRoundTrip(questionnaire, "Questionnaire")
    }

    @Test
    fun testQuestionnaireMinimalSerialization() {
        val questionnaire = Questionnaire(
            id = null,
            title = "Simple Survey",
            dateCreated = null,
            questions = listOf(Question(title = "Open ended question")),
            recurrenceRule = null
        )
        assertRoundTrip(questionnaire, "Questionnaire(minimal)")
    }

    @Test
    fun testQuestionnaireUpdateSerialization() {
        val update = QuestionnaireUpdate(
            title = "Updated Survey",
            description = "Updated description",
            recurrenceRule = null,
            active = false,
            questions = listOf(Question(title = "New question", choices = setOf("Yes", "No")))
        )
        assertRoundTrip(update, "QuestionnaireUpdate")
    }

    @Test
    fun testQuestionnaireUpdatePartialSerialization() {
        val update = QuestionnaireUpdate(
            title = "Just title",
            description = null,
            recurrenceRule = null,
            active = null,
            questions = null
        )
        assertRoundTrip(update, "QuestionnaireUpdate(partial)")
    }

    // ======================== App-usage survey day ========================

    @Test
    fun testAppUsageDaySerialization() {
        val start = OffsetDateTime.parse("2026-04-04T00:00:00-03:00")
        val day = AppUsageDaySurvey(LocalDate.parse("2026-04-04"), "America/Santiago", AppUsageDayState.NEW_APPS, listOf(
            AppUsageDayApp("com.example.app", "Example App", listOf(AppUsageBlock(start, start.plusHours(25), listOf("Child")))),
        ))
        assertRoundTrip(day, "AppUsageDaySurvey")
        assertRoundTrip(AppUsageAnswer("com.example.app", start, listOf("I don't know")), "AppUsageAnswer")
    }

    // ======================== DeviceUsage (survey) ========================

    @Test
    fun testDeviceUsageSerialization() {
        val usage = DeviceUsage(
            totalTime = 3600.0,
            usageByPackage = mapOf("com.example.app" to 1800.0, "com.other.app" to 900.0),
            categoryByPackage = mapOf("com.example.app" to "Social", "com.other.app" to "Games"),
            users = listOf("user1")
        )
        assertRoundTrip(usage, "DeviceUsage")
    }

    @Test
    fun testDeviceUsageEmptyMapsSerialization() {
        val usage = DeviceUsage(
            totalTime = 0.0,
            usageByPackage = emptyMap(),
            categoryByPackage = emptyMap()
        )
        assertRoundTrip(usage, "DeviceUsage(empty)")
    }

    // ======================== TimeUseDiaryResponse ========================

    @Test
    fun testTimeUseDiaryResponseSerialization() {
        val responses = TestDataFactory.timeUseDiaryResponses(5)
        responses.forEach { response ->
            assertRoundTrip(response, "TimeUseDiaryResponse")
        }
    }

    @Test
    fun testTimeUseDiaryResponseFromOlFormatJson() {
        val json = """{
            "ol.code": "activity_01",
            "ol.title": "What were you doing?",
            "ol.values": ["Sleeping", "Resting"],
            "ol.datetimestart": "2025-01-15T22:00:00Z",
            "ol.datetimeend": "2025-01-16T06:00:00Z"
        }"""
        val response: TimeUseDiaryResponse = mapper.readValue(json)
        assertEquals("activity_01", response.code)
        assertEquals("What were you doing?", response.question)
        assertEquals(2, response.response.size)
    }

    @Test
    fun testTimeUseDiaryResponseFromAliasJson() {
        val json = """{
            "code": "q1",
            "question": "What happened?",
            "response": ["Nothing"],
            "startDateTime": "2025-01-15T10:00:00Z",
            "endDateTime": "2025-01-15T11:00:00Z"
        }"""
        val response: TimeUseDiaryResponse = mapper.readValue(json)
        assertEquals("q1", response.code)
        assertEquals("What happened?", response.question)
    }

    @Test
    fun testTimeUseDiaryResponseNullDatesSerialization() {
        val response = TimeUseDiaryResponse(
            code = "q2",
            question = "Where were you?",
            response = setOf("Home"),
            startDateTime = null,
            endDateTime = null
        )
        assertRoundTrip(response, "TimeUseDiaryResponse(nullDates)")
    }

    // ======================== Webhook models ========================

    @Test
    fun testWebhookCreateRequestSerialization() {
        val request = WebhookCreateRequest(
            url = "https://example.com/webhook",
            secret = "my-secret-123",
            eventTypes = setOf(WebhookEventType.PARTICIPANT_ENROLLED, WebhookEventType.DATA_SUBMITTED),
            description = "Enrollment notifications"
        )
        assertRoundTrip(request, "WebhookCreateRequest")
    }

    @Test
    fun testWebhookCreateRequestMinimalSerialization() {
        val request = WebhookCreateRequest(url = "https://example.com/hook", secret = "", eventTypes = emptySet())
        assertRoundTrip(request, "WebhookCreateRequest(minimal)")
    }

    @Test
    fun testWebhookRegistrationSerialization() {
        val reg = WebhookRegistration(
            webhookId = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            url = "https://example.com/webhook",
            eventTypes = setOf(WebhookEventType.STUDY_STATUS_CHANGED),
            enabled = true,
            description = "Status notifications",
            createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        )
        assertRoundTrip(reg, "WebhookRegistration")
    }

    @Test
    fun testWebhookRegistrationDefaultsSerialization() {
        val reg = WebhookRegistration()
        assertRoundTrip(reg, "WebhookRegistration(defaults)")
    }

    @Test
    fun testWebhookDeliveryInfoSerialization() {
        val info = WebhookDeliveryInfo(
            deliveryId = UUID.randomUUID(),
            webhookId = UUID.randomUUID(),
            eventType = WebhookEventType.STUDY_STATUS_CHANGED,
            status = 200,
            attemptCount = 1,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC),
            lastAttemptAt = OffsetDateTime.now(ZoneOffset.UTC),
            deliveryState = WebhookDeliveryState.SUCCEEDED,
            completedAt = OffsetDateTime.now(ZoneOffset.UTC),
        )
        assertRoundTrip(info, "WebhookDeliveryInfo")
    }

    @Test
    fun testWebhookDeliveryInfoFailedSerialization() {
        val info = WebhookDeliveryInfo(
            deliveryId = UUID.randomUUID(),
            webhookId = UUID.randomUUID(),
            eventType = WebhookEventType.DATA_SUBMITTED,
            status = 500,
            attemptCount = 3,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC)
        )
        assertRoundTrip(info, "WebhookDeliveryInfo(failed)")
    }

    // ======================== API Key models ========================

    @Test
    fun testApiKeyCreateRequestSerialization() {
        val request = ApiKeyCreateRequest(
            name = "CI Pipeline Key",
            scope = ApiKeyScope.WRITE,
            expiresInDays = 30
        )
        assertRoundTrip(request, "ApiKeyCreateRequest")
    }

    @Test
    fun testApiKeyCreateRequestDefaultsSerialization() {
        val request = ApiKeyCreateRequest(name = "")
        assertRoundTrip(request, "ApiKeyCreateRequest(defaults)")
    }

    @Test
    fun testApiKeyCreateRequestFromJson() {
        val json = """{"name":"My Key","scope":"ADMIN","expiresInDays":365}"""
        val request: ApiKeyCreateRequest = mapper.readValue(json)
        assertEquals("My Key", request.name)
        assertEquals(ApiKeyScope.ADMIN, request.scope)
        assertEquals(365, request.expiresInDays)
    }

    @Test
    fun testApiKeyInfoSerialization() {
        val info = ApiKeyInfo(
            keyId = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            prefix = "chk_abc",
            name = "Production Key",
            scope = ApiKeyScope.READ_ONLY,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC),
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(90),
            lastUsedAt = OffsetDateTime.now(ZoneOffset.UTC),
            usageCount = 150
        )
        assertRoundTrip(info, "ApiKeyInfo")
    }

    @Test
    fun testApiKeyInfoMinimalSerialization() {
        val info = ApiKeyInfo(
            keyId = UUID.randomUUID(),
            studyId = UUID.randomUUID(),
            prefix = "chk_xyz",
            name = "Test Key",
            scope = ApiKeyScope.READ_ONLY,
            createdAt = OffsetDateTime.now(ZoneOffset.UTC),
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(90)
        )
        assertRoundTrip(info, "ApiKeyInfo(minimal)")
    }

    @Test
    fun testApiKeyCreateResponseSerialization() {
        val response = ApiKeyCreateResponse(
            keyId = UUID.randomUUID(),
            rawKey = "chk_abc123def456ghi789",
            info = ApiKeyInfo(
                keyId = UUID.randomUUID(),
                studyId = UUID.randomUUID(),
                prefix = "chk_abc",
                name = "New Key",
                scope = ApiKeyScope.WRITE,
                createdAt = OffsetDateTime.now(ZoneOffset.UTC),
                expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(90)
            )
        )
        assertRoundTrip(response, "ApiKeyCreateResponse")
    }

    // ======================== Organization models ========================

    @Test
    fun testOrganizationSerialization() {
        val org = TestDataFactory.organization()
        assertRoundTrip(org, "Organization")
    }

    // ======================== Authorization models ========================

    @Test
    fun testAclKeySerialization() {
        val aclKey = AclKey(UUID.randomUUID(), UUID.randomUUID())
        assertRoundTrip(aclKey, "AclKey")
    }

    @Test
    fun testAclKeySingleUuidSerialization() {
        val aclKey = AclKey(UUID.randomUUID())
        assertRoundTrip(aclKey, "AclKey(single)")
    }

    @Test
    fun testAuthorizationSerialization() {
        val auth = Authorization(
            AclKey(UUID.randomUUID()),
            mapOf(Permission.READ to true, Permission.WRITE to false)
        )
        assertRoundTrip(auth, "Authorization")
    }

    @Test
    fun testAclDataSerialization() {
        val aclData = AclData(
            Acl(AclKey(UUID.randomUUID()), listOf(Ace(Principal(PrincipalType.USER, "test"), EnumSet.of(Permission.READ)))),
            Action.ADD
        )
        assertRoundTrip(aclData, "AclData")
    }

    @Test
    fun testAclDataFactorySerialization() {
        val aclData = TestDataFactory.aclData()
        assertRoundTrip(aclData, "AclData(factory)")
    }

    @Test
    fun testDirectedAclKeysSerialization() {
        val directed = DirectedAclKeys(
            target = AclKey(UUID.randomUUID()),
            source = AclKey(UUID.randomUUID())
        )
        assertRoundTrip(directed, "DirectedAclKeys")
    }

    @Test
    fun testAceSerialization() {
        val ace = TestDataFactory.ace()
        assertRoundTrip(ace, "Ace")
    }

    @Test
    fun testAceValueSerialization() {
        val aceValue = TestDataFactory.aceValue()
        assertRoundTrip(aceValue, "AceValue")
    }

    @Test
    fun testAclSerialization() {
        val acl = TestDataFactory.acl()
        assertRoundTrip(acl, "Acl")
    }

    @Test
    fun testPrincipalSerialization() {
        val userPrincipal = TestDataFactory.userPrincipal()
        assertRoundTrip(userPrincipal, "Principal(user)")

        val rolePrincipal = TestDataFactory.rolePrincipal()
        assertRoundTrip(rolePrincipal, "Principal(role)")

        val orgPrincipal = TestDataFactory.organizationPrincipal()
        assertRoundTrip(orgPrincipal, "Principal(org)")
    }

    // ======================== Role & SecurablePrincipal ========================

    @Test
    fun testRoleSerialization() {
        val role = TestDataFactory.role()
        val json = mapper.writeValueAsString(role)
        logger.info("Role JSON: {}", json)
        val deserialized: Role = mapper.readValue(json)
        assertEquals(role.title, deserialized.title)
        assertEquals(role.organizationId, deserialized.organizationId)
        assertEquals(role.principal, deserialized.principal)
    }

    @Test
    fun testRoleWithOrgIdSerialization() {
        val orgId = UUID.randomUUID()
        val role = TestDataFactory.role(orgId)
        val json = mapper.writeValueAsString(role)
        val deserialized: Role = mapper.readValue(json)
        assertEquals(orgId, deserialized.organizationId)
    }

    @Test
    fun testSecurablePrincipalSerialization() {
        PrincipalType.values().filter { it != PrincipalType.APP }.forEach { type ->
            val sp = TestDataFactory.securablePrincipal(type)
            val json = mapper.writeValueAsString(sp)
            logger.info("SecurablePrincipal({}) JSON: {}", type, json)
            val deserialized: SecurablePrincipal = mapper.readValue(json)
            assertEquals(sp.title, deserialized.title)
            assertEquals(sp.principal, deserialized.principal)
        }
    }

    // ======================== AndroidAudioActivityEvent ========================

    @Test
    fun testAndroidAudioActivityEventSerialization() {
        val event = AndroidAudioActivityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            eventType = AudioEventType.ROUTE_CHANGE,
            audioActive = true,
            audioPackage = "com.spotify.music",
            contentType = AudioContentType.MUSIC,
            playbackState = AudioPlaybackState.PLAYING,
            outputRoute = AudioOutputRoute.BLUETOOTH,
            routeConnected = true,
            mediaVolume = 7,
            maxMediaVolume = 15,
            ringerMode = AudioRingerMode.NORMAL,
            dndActive = false,
            callActive = false,
        )
        assertRoundTrip(event, "AndroidAudioActivityEvent")
    }

    // ======================== AndroidAudioContentEvent ========================

    @Test
    fun testAndroidAudioContentEventSerialization() {
        val event = AndroidAudioContentEvent(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            audioPackage = "com.spotify.music",
            title = "Some Title",
            artist = "Some Artist",
            album = "Some Album",
            durationMillis = 210_000L,
            positionMillis = 42_000L,
        )
        assertRoundTrip(event, "AndroidAudioContentEvent")
    }

    // ======================== AndroidNotificationActivityEvent ========================

    @Test
    fun testAndroidNotificationActivityEventSerialization() {
        val event = AndroidNotificationActivityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            eventType = NotificationEventType.POSTED,
            packageName = "com.android.messaging",
            category = "msg",
            ongoing = false,
            importance = 3,
        )
        assertRoundTrip(event, "AndroidNotificationActivityEvent")
    }
}
