package com.openlattice.chronicle.serialization

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
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.pipeline.PipelineConfig
import com.openlattice.chronicle.pipeline.PipelineStep
import com.openlattice.chronicle.pipeline.PipelineStepType
import com.openlattice.chronicle.sensorkit.SensorSetting
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.DataQualityConfig
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.survey.SurveySettings
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tests focused on polymorphic deserialization -- the most dangerous Jackson pattern.
 * These verify that JsonTypeInfo annotations work correctly for all subtype hierarchies.
 */
class PolymorphicSerializationTest {
    private val logger = LoggerFactory.getLogger(PolymorphicSerializationTest::class.java)
    private val mapper: ObjectMapper = ObjectMappers.getJsonMapper()

    // ======================== StudySetting hierarchy ========================

    @Test
    fun testStudySettingsWithAllSubtypesPreservesClass() {
        val settings = StudySettings(mapOf(
            StudySettingType.Sensor to SensorSetting(setOf(SensorType.deviceUsage)),
            StudySettingType.Survey to SurveySettings(),
            StudySettingType.Notifications to StudyNotificationSettings(
                labFriendlyName = "Lab",
                studyFriendlyName = "Study"
            ),
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(),
            StudySettingType.AndroidSensor to AndroidSensorSetting(
                sensors = setOf(AndroidSensorType.accelerometer)
            ),
            StudySettingType.DataQuality to DataQualityConfig(),
            StudySettingType.DataCollection to ChronicleDataCollectionSettings(AppUsageFrequency.DAILY),
            StudySettingType.Pipeline to PipelineConfig(),
        ))
        val json = mapper.writeValueAsString(settings)
        logger.info("Full StudySettings JSON: {}", json)

        // Verify class markers are present for types that have JsonTypeInfo
        assertTrue("JSON must contain SurveySettings class", json.contains("SurveySettings"))
        assertTrue("JSON must contain StudyNotificationSettings class", json.contains("StudyNotificationSettings"))
        assertTrue("JSON must contain TimeUseDiarySettings class", json.contains("TimeUseDiarySettings"))
        assertTrue("JSON must contain SensorSetting class", json.contains("SensorSetting"))

        // Round-trip
        val deserialized: StudySettings = mapper.readValue(json)
        assertEquals(settings.size, deserialized.size)
        assertTrue(deserialized[StudySettingType.Sensor] is SensorSetting)
        assertTrue(deserialized[StudySettingType.Survey] is SurveySettings)
        assertTrue(deserialized[StudySettingType.Notifications] is StudyNotificationSettings)
        assertTrue(deserialized[StudySettingType.TimeUseDiary] is TimeUseDiarySettings)
        assertTrue(deserialized[StudySettingType.AndroidSensor] is AndroidSensorSetting)
        assertTrue(deserialized[StudySettingType.DataQuality] is DataQualityConfig)
        assertTrue(deserialized[StudySettingType.Pipeline] is PipelineConfig)
    }

    @Test
    fun testSensorSettingAsStudySettingRoundTrip() {
        val original: StudySetting = SensorSetting(setOf(SensorType.phoneUsage, SensorType.messagesUsage))
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("SensorSetting"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is SensorSetting)
        val sensor = deserialized as SensorSetting
        assertTrue(sensor.contains(SensorType.phoneUsage))
        assertTrue(sensor.contains(SensorType.messagesUsage))
    }

    @Test
    fun testAndroidSensorSettingAsStudySettingRoundTrip() {
        val original: StudySetting = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            samplingRateHz = 20,
            dutyCycleActiveSeconds = 120,
            dutyCyclePeriodSeconds = 1200
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("AndroidSensorSetting"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is AndroidSensorSetting)
        val setting = deserialized as AndroidSensorSetting
        assertEquals(20, setting.samplingRateHz)
        assertEquals(2, setting.sensors.size)
    }

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

    @Test
    fun testDataQualityConfigAsStudySettingRoundTrip() {
        val original: StudySetting = DataQualityConfig(expectedDaysPerWeek = 7, alertThresholdPercent = 90)
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("DataQualityConfig"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is DataQualityConfig)
        assertEquals(7, (deserialized as DataQualityConfig).expectedDaysPerWeek)
    }

    @Test
    fun testPipelineConfigAsStudySettingRoundTrip() {
        val original: StudySetting = PipelineConfig(
            steps = listOf(PipelineStep(type = PipelineStepType.FEATURE_EXTRACTION, order = 0)),
            enabled = true
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("PipelineConfig"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is PipelineConfig)
        assertTrue((deserialized as PipelineConfig).enabled)
    }

    @Test
    fun testChronicleDataCollectionSettingsAsStudySettingRoundTrip() {
        val original: StudySetting = ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY)
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("ChronicleDataCollectionSettings"))
        val deserialized: StudySetting = mapper.readValue(json)
        assertTrue(deserialized is ChronicleDataCollectionSettings)
        assertEquals(AppUsageFrequency.HOURLY, (deserialized as ChronicleDataCollectionSettings).appUsageFrequency)
    }

    // ======= Phase 9A: AndroidDataCollectionSetting (generalized DataCollection) =======

    @Test
    fun testAndroidDataCollectionSettingAsStudySettingRoundTrip() {
        val original: StudySetting = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(CollectionModuleId.USAGE_EVENTS),
                CollectionModuleId.HARDWARE_SENSORS to CollectionModuleSetting(
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
        assertTrue(setting.modules.getValue(CollectionModuleId.HARDWARE_SENSORS).enabled)
        assertEquals(
            setOf(AndroidSensorType.accelerometer),
            setting.modules.getValue(CollectionModuleId.HARDWARE_SENSORS).sensorPolicy?.sensors,
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
        // Unknown module IDs in the wire JSON must be dropped, not fatal (design §1B.2).
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
        // Hard constraint (design §1B.3): the DTO carries no apiKey / signing secret /
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
    fun testFrontendStyleSensorSettingJson() {
        val json = """{"Sensor": ["com.openlattice.chronicle.sensorkit.SensorSetting", ["deviceUsage", "keyboardMetrics"]]}"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(1, settings.size)
        val sensor = settings[StudySettingType.Sensor] as SensorSetting
        assertTrue(sensor.contains(SensorType.deviceUsage))
        assertTrue(sensor.contains(SensorType.keyboardMetrics))
    }

    @Test
    fun testFrontendStyleAndroidSensorSettingJson() {
        val json = """{
            "AndroidSensor": {
                "@class": "com.openlattice.chronicle.android.AndroidSensorSetting",
                "sensors": ["accelerometer", "gyroscope"],
                "samplingRateHz": 10,
                "dutyCycleActiveSeconds": 60,
                "dutyCyclePeriodSeconds": 600
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(1, settings.size)
        val sensor = settings[StudySettingType.AndroidSensor] as AndroidSensorSetting
        assertEquals(10, sensor.samplingRateHz)
        assertEquals(2, sensor.sensors.size)
    }

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
                "noDataUploaded": {"years": 0, "months": 0, "days": 2},
                "noTudSubmitted": {"years": 0, "months": 0, "days": 3},
                "noAppUsageSurveySubmitted": {"years": 0, "months": 0, "days": 1}
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
    fun testFrontendStyleDataQualityConfigJson() {
        val json = """{
            "DataQuality": {
                "@class": "com.openlattice.chronicle.study.DataQualityConfig",
                "expectedDaysPerWeek": 6,
                "alertThresholdPercent": 70,
                "evaluationWindowDays": 21
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val dq = settings[StudySettingType.DataQuality] as DataQualityConfig
        assertEquals(6, dq.expectedDaysPerWeek)
        assertEquals(70, dq.alertThresholdPercent)
    }

    @Test
    fun testFrontendStylePipelineConfigJson() {
        val json = """{
            "Pipeline": {
                "@class": "com.openlattice.chronicle.pipeline.PipelineConfig",
                "steps": [
                    {"type": "DEIDENTIFICATION", "order": 0, "params": {}},
                    {"type": "AGGREGATION", "order": 1, "params": {}}
                ],
                "outputTable": "preprocessed_data",
                "timeBucketMinutes": 30,
                "enabled": true
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val pipeline = settings[StudySettingType.Pipeline] as PipelineConfig
        assertTrue(pipeline.enabled)
        assertEquals(2, pipeline.steps.size)
        assertEquals(30, pipeline.timeBucketMinutes)
    }

    @Test
    fun testFrontendStyleDataCollectionJson() {
        val json = """{
            "DataCollection": {
                "@class": "com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings",
                "appUsageFrequency": "HOURLY"
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        val dc = settings[StudySettingType.DataCollection] as ChronicleDataCollectionSettings
        assertEquals(AppUsageFrequency.HOURLY, dc.appUsageFrequency)
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
            },
            "Sensor": ["com.openlattice.chronicle.sensorkit.SensorSetting", ["messagesUsage", "deviceUsage"]]
        }"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(4, settings.size)
        assertTrue(settings[StudySettingType.Survey] is SurveySettings)
        assertTrue(settings[StudySettingType.Notifications] is StudyNotificationSettings)
        assertTrue(settings[StudySettingType.TimeUseDiary] is TimeUseDiarySettings)
        assertTrue(settings[StudySettingType.Sensor] is SensorSetting)
    }

    // ======================== Wrong class type ========================

    @Test(expected = JsonMappingException::class)
    fun testWrongClassForStudySetting() {
        val json = """{"@class": "com.openlattice.chronicle.nonexistent.FakeClass", "someField": 42}"""
        mapper.readValue<StudySetting>(json)
    }

    @Test(expected = JsonMappingException::class)
    fun testWrongClassForSourceDevice() {
        val json = """{"@class": "com.openlattice.chronicle.sources.FakeDevice", "name": "test"}"""
        mapper.readValue<SourceDevice>(json)
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
            deviceId = "abc-123"
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("AndroidDevice"))
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
            deviceId = "ios-device-id"
        )
        val json = mapper.writeValueAsString(original)
        assertTrue("Must include class marker", json.contains("IOSDevice"))
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
            "deviceId": "device-abc-123",
            "additionalInfo": {"screenDensity": "440"},
            "fcmRegistrationToken": "fcm-tok-xyz"
        }"""
        val device: SourceDevice = mapper.readValue(json)
        assertTrue(device is AndroidDevice)
        val android = device as AndroidDevice
        assertEquals("samsung", android.brand)
        assertEquals("device-abc-123", android.deviceId)
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
            "deviceId": "E621E1F8-C36C-495A-93FC-0C247A3E6E5F",
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
            StudySettingType.AndroidSensor to AndroidSensorSetting(
                sensors = setOf(AndroidSensorType.stepCounter),
                samplingRateHz = 1
            )
        ))
        val json = mapper.writeValueAsString(settings)
        val deserialized: StudySettings = mapper.readValue(json)

        val survey = deserialized[StudySettingType.Survey]!!
        assertTrue("Should be SurveySettings, was ${survey::class}", survey is SurveySettings)
        assertEquals(42, (survey as SurveySettings).appUsageThresholdInSeconds)

        val sensor = deserialized[StudySettingType.AndroidSensor]!!
        assertTrue("Should be AndroidSensorSetting, was ${sensor::class}", sensor is AndroidSensorSetting)
        val androidSensor = sensor as AndroidSensorSetting
        assertEquals(1, androidSensor.samplingRateHz)
        assertTrue(androidSensor.sensors.contains(AndroidSensorType.stepCounter))
    }

    @Test
    fun testStudySettingsSingleEntryRoundTrip() {
        val implementations = mapOf<StudySettingType, StudySetting>(
            StudySettingType.Survey to SurveySettings(),
            StudySettingType.Notifications to StudyNotificationSettings(labFriendlyName = "L", studyFriendlyName = "S"),
            StudySettingType.TimeUseDiary to TimeUseDiarySettings(),
            StudySettingType.AndroidSensor to AndroidSensorSetting(),
            StudySettingType.DataQuality to DataQualityConfig(),
            StudySettingType.DataCollection to ChronicleDataCollectionSettings(),
            StudySettingType.Pipeline to PipelineConfig(),
            StudySettingType.Sensor to SensorSetting(emptySet()),
        )

        implementations.forEach { (type, setting) ->
            val settings = StudySettings(mapOf(type to setting))
            val json = mapper.writeValueAsString(settings)
            val deserialized: StudySettings = mapper.readValue(json)
            assertEquals(1, deserialized.size)
            assertNotNull("$type should be present after round-trip", deserialized[type])
            assertEquals(
                "Concrete type mismatch for $type",
                setting::class,
                deserialized[type]!!::class
            )
        }
    }

    // ======================== Real production JSON from database ========================

    @Test
    fun testProductionStudySettingsJson() {
        val json = """{
            "Sensor": ["com.openlattice.chronicle.sensorkit.SensorSetting", ["messagesUsage", "deviceUsage"]],
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
                "noTudSubmitted": {"days": 1, "years": 0, "months": 0},
                "labFriendlyName": "",
                "notifyResearchers": false,
                "studyFriendlyName": "App Store Review Study",
                "notifyOnEnrollment": false,
                "researcherPhoneNumbers": "",
                "noAppUsageSurveySubmitted": {"days": 1, "years": 0, "months": 0}
            }
        }"""
        val settings: StudySettings = mapper.readValue(json)
        assertEquals(4, settings.size)
        assertTrue(settings[StudySettingType.Sensor] is SensorSetting)
        assertTrue(settings[StudySettingType.Survey] is SurveySettings)
        assertTrue(settings[StudySettingType.TimeUseDiary] is TimeUseDiarySettings)
        assertTrue(settings[StudySettingType.Notifications] is StudyNotificationSettings)
        assertEquals("App Store Review Study",
            (settings[StudySettingType.Notifications] as StudyNotificationSettings).studyFriendlyName)
    }
}
