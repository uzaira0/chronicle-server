package com.openlattice.chronicle.serialization

import org.junit.Assert.assertThrows

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tests focused on polymorphic deserialization -- the most dangerous Jackson pattern.
 * These verify that JsonTypeInfo annotations work correctly for all subtype hierarchies.
 */
class PolymorphicSerializationTest {
    private val mapper: ObjectMapper = ObjectMappers.getJsonMapper()

    // ======================== StudySetting hierarchy ========================

    

    @Test
    fun testSurveySettingsAsStudySettingRoundTrip() {
        val original: StudySetting = SurveySettings(appUsageThresholdInSeconds = 600, deviceUsageThresholdInSeconds = 900)
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("SurveySettings"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is SurveySettings)
        assertEquals(600, (deserialized as SurveySettings).appUsageThresholdInSeconds)
    }

    @Test
    fun testTimeUseDiarySettingsAsStudySettingRoundTrip() {
        val original: StudySetting = TimeUseDiarySettings(language = "es", enableChangesForSherbrookeUniversity = true)
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("TimeUseDiarySettings"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is TimeUseDiarySettings)
        assertEquals("es", (deserialized as TimeUseDiarySettings).language)
    }

    @Test
    fun testStudyNotificationSettingsAsStudySettingRoundTrip() {
        val original: StudySetting = StudyNotificationSettings(
            labFriendlyName = "Neuro Lab",
            studyFriendlyName = "Sleep Study",
            notifyResearchers = true
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("StudyNotificationSettings"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is StudyNotificationSettings)
        assertEquals("Neuro Lab", (deserialized as StudyNotificationSettings).labFriendlyName)
    }

    

    

    // ======= AndroidDataCollectionSetting (generalized DataCollection) =======

    @Test
    fun testAndroidDataCollectionSettingAsStudySettingRoundTrip() {
        val original: StudySetting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS),
                CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(
                    enabled = true,
                    sensorPolicy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer)),
                ),
            ),
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("AndroidDataCollectionSetting"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is AndroidDataCollectionSetting)
        val setting = deserialized as AndroidDataCollectionSetting
        assertEquals(2, setting.modules.size)
        assertEquals(AndroidDataCollectionSetting.CURRENT_VERSION, setting.version)
        assertTrue(setting.modules.getValue(CollectionModuleId.SENSOR_ACCELEROMETER).enabled)
        assertEquals(
            setOf(AndroidSensorType.accelerometer),
            setting.modules.getValue(CollectionModuleId.SENSOR_ACCELEROMETER).sensorPolicy?.sensors,
        )
    }

    @Test
    fun testAndroidDataCollectionSettingInStudySettingsMap() {
        val settings = StudySettings(mapOf(
            StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting(),
        ))
        val json = mapper.writeValueAsString(settings)
        val deserialized: StudySettings = mapper.readValue(json)
        val dc = deserialized[StudySettingType.DataCollection]
        assertTrue("DataCollection must round-trip as AndroidDataCollectionSetting", dc is AndroidDataCollectionSetting)
        // every active module present, no reserved/inactive module leaked
        assertEquals(CollectionModuleId.activeModules, (dc as AndroidDataCollectionSetting).modules.keys)
    }

    @Test
    fun testAndroidDataCollectionSettingIgnoresUnknownModuleId() {
        // Unknown module IDs in the wire JSON must be dropped, not fatal.
        val json = """{
            "@class": "com.openlattice.chronicle.collection.AndroidDataCollectionSetting",
            "modules": {
                "usage_events": {"enabled": true},
                "totally_not_a_real_module": {"enabled": true}
            },
            "version": 1
        }"""
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is AndroidDataCollectionSetting)
        val setting = deserialized as AndroidDataCollectionSetting
        assertEquals(setOf(CollectionModuleId.USAGE_EVENTS), setting.modules.keys)
    }

    @Test
    fun testAndroidDataCollectionSettingCarriesNoSecrets() {
        // The DTO carries no apiKey / signing secret /
        // participantId. Assert the serialized form never contains those tokens.
        val json = mapper.writeValueAsString(CollectionDefaults.androidDataCollectionSetting())
        listOf("apiKey", "MOBILE_SIGNING_SECRET", "participantId").forEach { forbidden ->
            assertTrue(
                "AndroidDataCollectionSetting JSON must not contain '$forbidden'",
                !json.contains(forbidden),
            )
        }
    }

    // ======================== Frontend-style JSON deserialization ========================

    @Test
    fun testFrontendStyleSurveySettingsJson() {
        val json = """{
            "Survey": {
                "@class": "com.openlattice.chronicle.survey.SurveySettings",
                "appUsageThresholdInSeconds": 300,
                "deviceUsageThresholdInSeconds": 600
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val survey = settings[StudySettingType.Survey] as SurveySettings
        assertEquals(300, survey.appUsageThresholdInSeconds)
        assertEquals(600, survey.deviceUsageThresholdInSeconds)
    }

    @Test
    fun testFrontendStyleNotificationsJson() {
        val json = """{
            "Notifications": {
                "@class": "com.openlattice.chronicle.notifications.StudyNotificationSettings",
                "labFriendlyName": "Research Lab",
                "studyFriendlyName": "Child Study",
                "notifyResearchers": true,
                "notifyOnEnrollment": true,
                "researcherPhoneNumbers": "+15551234567",
                "noDataUploaded": {"years": 0, "months": 0, "days": 2}
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val notif = settings[StudySettingType.Notifications] as StudyNotificationSettings
        assertEquals("Research Lab", notif.labFriendlyName)
        assertTrue(notif.notifyResearchers)
        assertTrue(notif.notifyOnEnrollment)
        assertEquals(2, notif.noDataUploaded.days.toInt())
    }

    @Test
    fun testFrontendStyleTimeUseDiarySettingsJson() {
        val json = """{
            "TimeUseDiary": {
                "@class": "com.openlattice.chronicle.timeusediary.TimeUseDiarySettings",
                "enableChangesForSherbrookeUniversity": true,
                "enableChangesForOhioStateUniversity": false,
                "language": "fr"
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val tud = settings[StudySettingType.TimeUseDiary] as TimeUseDiarySettings
        assertTrue(tud.enableChangesForSherbrookeUniversity)
        assertEquals("fr", tud.language)
    }

    

    

    @Test
    fun testFrontendStyleMixedSettingsJson() {
        val json = """{
            "Survey": {
                "@class": "com.openlattice.chronicle.survey.SurveySettings",
                "appUsageThresholdInSeconds": 180,
                "deviceUsageThresholdInSeconds": 180
            },
            "Notifications": {
                "@class": "com.openlattice.chronicle.notifications.StudyNotificationSettings",
                "labFriendlyName": "",
                "studyFriendlyName": "Test Study",
                "notifyResearchers": false
            },
            "TimeUseDiary": {
                "@class": "com.openlattice.chronicle.timeusediary.TimeUseDiarySettings",
                "language": "en",
                "enableChangesForSherbrookeUniversity": false,
                "enableChangesForOhioStateUniversity": false
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(3, settings.size)
        assertTrue(settings[StudySettingType.Survey] is SurveySettings)
        assertTrue(settings[StudySettingType.Notifications] is StudyNotificationSettings)
        assertTrue(settings[StudySettingType.TimeUseDiary] is TimeUseDiarySettings)
    }

    // ======================== Wrong class type ========================

    @Test
    fun testWrongClassForStudySetting() {
        val json = """{"@class": "com.openlattice.chronicle.nonexistent.FakeClass", "someField": 42}"""
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue<StudySetting>(json)
        }
    }

    @Test
    fun testWrongClassForSourceDevice() {
        val json = """{"@class": "com.openlattice.chronicle.sources.FakeDevice", "name": "test"}"""
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue<SourceDevice>(json)
        }
    }

    // ======================== SourceDevice hierarchy ========================

    @Test
    fun testAndroidDeviceAsSourceDeviceRoundTrip() {
        val original: SourceDevice = AndroidDevice(
            device = "Pixel 7",
            model = "Pixel 7",
            codename = "panther",
            brand = "Google",
            osVersion = "14",
            sdkVersion = "34",
            product = "panther",
            randomDeviceId = "abc-123"
        )
        val json = mapper.writeValueAsString(original)
        assertEquals("com.openlattice.chronicle.sources.AndroidDevice", mapper.readTree(json)["@class"].asText())
        val deserialized: SourceDevice = mapper.readValue(json)
        assertTrue(deserialized is AndroidDevice)
        assertEquals("Pixel 7", (deserialized as AndroidDevice).model)
    }

    @Test
    fun testIOSDeviceAsSourceDeviceRoundTrip() {
        val original: SourceDevice = IOSDevice(
            name = "iPhone 15",
            systemName = "iOS",
            model = "iPhone 15 Pro",
            localizedModel = "iPhone",
            version = "17.2",
            randomDeviceId = "ios-random-device-id"
        )
        val json = mapper.writeValueAsString(original)
        assertEquals("com.openlattice.chronicle.sources.IOSDevice", mapper.readTree(json)["@class"].asText())
        val deserialized: SourceDevice = mapper.readValue(json)
        assertTrue(deserialized is IOSDevice)
        assertEquals("iPhone 15", (deserialized as IOSDevice).name)
    }

    @Test
    fun testAndroidDeviceFromFrontendJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.sources.AndroidDevice",
            "device": "Samsung Galaxy S23",
            "model": "SM-S911B",
            "codename": "dm1q",
            "brand": "samsung",
            "osVersion": "14",
            "sdkVersion": "34",
            "product": "dm1qxxx",
            "randomDeviceId": "device-abc-123",
            "additionalInfo": {"screenDensity": "440"},
            "fcmRegistrationToken": "fcm-tok-xyz"
        }"""
        val device: SourceDevice = mapper.readValue(json)
        assertTrue(device is AndroidDevice)
        val android = device as AndroidDevice
        assertEquals("samsung", android.brand)
        assertEquals("device-abc-123", android.randomDeviceId)
        assertEquals(1, android.additionalInfo.size)
    }

    @Test
    fun testIOSDeviceFromFrontendJson() {
        val json = """{
            "@class": "com.openlattice.chronicle.sources.IOSDevice",
            "name": "iPhone",
            "systemName": "iOS",
            "model": "iPhone",
            "localizedModel": "iPhone",
            "version": "17.0.2",
            "randomDeviceId": "E621E1F8-C36C-495A-93FC-0C247A3E6E5F",
            "apnDeviceToken": "apn_tok_abc"
        }"""
        val device: SourceDevice = mapper.readValue(json)
        assertTrue(device is IOSDevice)
        val ios = device as IOSDevice
        assertEquals("iOS", ios.systemName)
        assertEquals("apn_tok_abc", ios.apnDeviceToken)
    }

    // ======================== ChronicleSample hierarchy ========================

    @Test
    fun testChronicleUsageEventAsChronicleSampleRoundTrip() {
        val event = ChronicleUsageEvent(
            studyId = UUID.randomUUID(),
            participantId = "P001",
            appPackageName = "com.example.app",
            interactionType = "Move to Foreground",
            timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            timezone = "America/Chicago",
            user = "user1",
            applicationLabel = "Example",
            activityClass = "com.example.MainActivity"
        )
        val json = mapper.writeValueAsString(event as ChronicleSample)
        assertTrue("Must include class marker", json.contains("ChronicleUsageEvent"))
        val deserialized: ChronicleSample = mapper.readValue(json)
        assertTrue(deserialized is ChronicleUsageEvent)
        assertEquals(event.activityClass, (deserialized as ChronicleUsageEvent).activityClass)
    }

    @Test
    fun testChronicleDataPreservesSubtypes() {
        val studyId = UUID.randomUUID()
        val data = TestDataFactory.chronicleUsageEvents(studyId, "P001", 3)
        val json = mapper.writeValueAsString(data)
        assertTrue("Must include ChronicleUsageEvent class", json.contains("ChronicleUsageEvent"))
        val deserialized: ChronicleData = mapper.readValue(json)
        assertEquals(3, deserialized.size)
        deserialized.forEach { sample ->
            assertTrue("Each element should be ChronicleUsageEvent", sample is ChronicleUsageEvent)
        }
    }

    // ======================== StudySetting: verify type retention in map context ========================

    @Test
    fun testStudySettingsMapValuesRetainConcreteTypes() {
        val settings = StudySettings(mapOf(
            StudySettingType.Survey to SurveySettings(appUsageThresholdInSeconds = 42),
        ))
        val json = mapper.writeValueAsString(settings)
        val deserialized: StudySettings = mapper.readValue(json)

        val survey = deserialized[StudySettingType.Survey]!!
        assertTrue("Should be SurveySettings, was ${survey::class}", survey is SurveySettings)
        assertEquals(42, (survey as SurveySettings).appUsageThresholdInSeconds)

    }

    

    // ======================== Real production JSON from database ========================

    @Test
    fun testProductionStudySettingsJson() {
        val json = """{
            "Survey": {
                "@class": "com.openlattice.chronicle.survey.SurveySettings",
                "appUsageThresholdInSeconds": 180,
                "deviceUsageThresholdInSeconds": 180
            },
            "TimeUseDiary": {
                "@class": "com.openlattice.chronicle.timeusediary.TimeUseDiarySettings",
                "language": "en",
                "enableChangesForSherbrookeUniversity": false,
                "enableChangesForOhioStateUniversity": false
            },
            "Notifications": {
                "@class": "com.openlattice.chronicle.notifications.StudyNotificationSettings",
                "noDataUploaded": {"days": 1, "years": 0, "months": 0},
                "labFriendlyName": "",
                "notifyResearchers": false,
                "studyFriendlyName": "App Store Review Study",
                "notifyOnEnrollment": false,
                "researcherPhoneNumbers": ""
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(3, settings.size)
        assertTrue(settings[StudySettingType.Survey] is SurveySettings)
        assertTrue(settings[StudySettingType.TimeUseDiary] is TimeUseDiarySettings)
        assertTrue(settings[StudySettingType.Notifications] is StudyNotificationSettings)
        assertEquals("App Store Review Study",
            (settings[StudySettingType.Notifications] as StudyNotificationSettings).studyFriendlyName)
    }
}
