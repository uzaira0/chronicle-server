package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.BatteryPolicy
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.NetworkPolicy
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class DataCollectionSettingsVersionTest {
    @Test
    fun `new study without collection policy receives server issued initial default`() {
        val study = Study(title = "initial policy", contact = "research@example.org")

        val stamped = stampInitialDataCollectionSettings(study)

        assertEquals(
            CollectionDefaults.androidDataCollectionSetting(),
            stamped.settings[StudySettingType.DataCollection],
        )
    }

    @Test
    fun `new study preserves an explicitly supplied legacy collection policy`() {
        val legacy = ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY)
        val study = Study(
            title = "legacy policy",
            contact = "research@example.org",
            settings = StudySettings(mapOf(StudySettingType.DataCollection to legacy)),
        )

        val stamped = stampInitialDataCollectionSettings(study)

        assertEquals(legacy, stamped.settings[StudySettingType.DataCollection])
    }

    @Test
    fun `study encryption remains disabled until encrypted payloads can be exported`() {
        val enabled = StudyEncryptionSetting(
            enabled = true,
            keyId = "release-gate-key",
            publicKeyPem = "PUBLIC KEY",
            mlkemPublicKey = "public-key-material",
        )
        val settings = StudySettings(mapOf(StudySettingType.Encryption to enabled))

        assertThrows(IllegalArgumentException::class.java) {
            stampDataCollectionSettingsVersion(null, StudyUpdate(settings = settings))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeStudySetting(StudySettings(emptyMap()), StudySettingType.Encryption, enabled)
        }

        requireExportableStudyEncryption(
            StudySettings(mapOf(StudySettingType.Encryption to StudyEncryptionSetting(enabled = false))),
        )
    }
    private val baseSetting = AndroidDataCollectionSetting(
        modules = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
        ),
        settingsVersion = 4,
    )

    @Test fun testPolicyChangeIncrementsServerRevisionAndIgnoresClientRevision() {
        val changed = baseSetting.copy(
            modules = mapOf(CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true)),
            settingsVersion = 99,
        )

        val stamped = stampDataCollectionSettingsVersion(settings(baseSetting), update(changed))

        assertEquals(5, dataCollection(stamped).settingsVersion)
    }

    @Test fun testIdenticalPolicyPreservesServerRevision() {
        val stamped = stampDataCollectionSettingsVersion(
            settings(baseSetting),
            update(baseSetting.copy(settingsVersion = 99)),
        )

        assertEquals(4, dataCollection(stamped).settingsVersion)
    }

    @Test fun testFirstDataCollectionSettingStartsAtInitialRevision() {
        val stamped = stampDataCollectionSettingsVersion(null, update(baseSetting.copy(settingsVersion = 99)))

        assertEquals(AndroidDataCollectionSetting.INITIAL_SETTINGS_VERSION, dataCollection(stamped).settingsVersion)
    }

    @Test fun testAuthoritativeRevisionReadsCurrentSettingsUnderStudyRowLock() {
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val resultSet = mock<ResultSet>()
        val studyId = UUID.randomUUID()
        whenever(connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ? FOR UPDATE"))
            .thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(resultSet)
        whenever(resultSet.next()).thenReturn(true)
        whenever(resultSet.getString("settings"))
            .thenReturn(ObjectMappers.getJsonMapper().writeValueAsString(settings(baseSetting)))
        val changed = baseSetting.copy(
            modules = mapOf(CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true)),
            settingsVersion = 99,
        )

        val locked = stampDataCollectionSettingsVersionLocked(connection, studyId, update(changed))

        assertEquals(5, dataCollection(locked.stampedStudy).settingsVersion)
        verify(statement).setObject(1, studyId)
    }

    @Test fun testLegacyEndpointCannotReplaceVersionedDataCollectionSettings() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            mergeLegacyDataCollectionSettings(
                settings(baseSetting),
                ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY),
            )
        }

        assertEquals(409, exception.statusCode.value())
    }

    @Test fun testLegacyEndpointMergesWithoutDroppingOtherAuthoritativeSettings() {
        val legacy = ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY)
        val prior = StudySettings(
            mapOf(StudySettingType.AndroidSensor to AndroidSensorSetting.NO_SENSORS),
        )

        val merged = mergeLegacyDataCollectionSettings(prior, legacy)

        assertEquals(legacy, merged[StudySettingType.DataCollection])
        assertEquals(
            AndroidSensorSetting.NO_SENSORS,
            merged[StudySettingType.AndroidSensor],
        )
    }

    @Test fun testPerTypeDeltaMergesIntoLockedAuthoritativeSettingsWithoutRevertingDataCollection() {
        val authoritative = settings(
            baseSetting.copy(
                modules = mapOf(
                    CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true),
                ),
                settingsVersion = 5,
            ),
        )
        val sensorDelta = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))

        val merged = mergeStudySetting(authoritative, StudySettingType.AndroidSensor, sensorDelta)

        assertEquals(authoritative[StudySettingType.DataCollection], merged[StudySettingType.DataCollection])
        assertEquals(sensorDelta, merged[StudySettingType.AndroidSensor])
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEnabledHealthConnectRequiresAnExplicitRecordTypeScope() {
        stampDataCollectionSettingsVersion(
            null,
            update(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(enabled = true),
                    ),
                ),
            ),
        )
    }

    @Test fun testEnabledHealthConnectAcceptsAnExplicitRecordTypeScope() {
        val scoped = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(
                    enabled = true,
                    healthConnectRecordTypes = setOf(
                        HealthConnectRecordType.STEPS,
                        HealthConnectRecordType.SLEEP,
                    ),
                ),
            ),
        )

        val stamped = stampDataCollectionSettingsVersion(null, update(scoped))

        assertEquals(
            setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
            dataCollection(stamped).modules.getValue(CollectionModuleId.HEALTH_CONNECT)
                .healthConnectRecordTypes,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNonHealthConnectModuleRejectsHealthConnectRecordTypes() {
        stampDataCollectionSettingsVersion(
            null,
            update(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                            enabled = true,
                            healthConnectRecordTypes = setOf(HealthConnectRecordType.STEPS),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun testRejectsEverySignedPolicyKnobThePlayBuildDoesNotEnforce() {
        val unsupported = listOf(
            CollectionModuleSetting(
                enabled = true,
                collectionCadence = CollectionCadence(intervalSeconds = 60),
            ),
            baseSetting.modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).copy(
                uploadCadence = CollectionCadence(intervalSeconds = 120),
            ),
            baseSetting.modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).copy(
                batteryPolicy = BatteryPolicy(minLevelPercent = 20),
            ),
            baseSetting.modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).copy(
                networkPolicy = NetworkPolicy(requireUnmetered = true),
            ),
        )

        unsupported.forEach { moduleSetting ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                stampDataCollectionSettingsVersion(
                    null,
                    update(
                        AndroidDataCollectionSetting(
                            modules = mapOf(CollectionModuleId.USAGE_EVENTS to moduleSetting),
                        ),
                    ),
                )
            }
            org.junit.Assert.assertTrue(error.message.orEmpty().contains("not supported by the Play build"))
        }
    }

    @Test
    fun testAllowsSupportedPullIntervalsWithoutJitter() {
        val supportedModules = setOf(
            CollectionModuleId.CONNECTIVITY_STATE,
            CollectionModuleId.DEVICE_SETTINGS,
            CollectionModuleId.APP_NETWORK_USAGE,
            CollectionModuleId.HEALTH_CONNECT,
            CollectionModuleId.BATTERY_TELEMETRY,
        )

        supportedModules.forEach { moduleId ->
            val setting = AndroidDataCollectionSetting(
                modules = mapOf(
                    moduleId to CollectionModuleSetting(
                        enabled = true,
                        collectionCadence = CollectionCadence(intervalSeconds = 60, jitterSeconds = 0),
                        healthConnectRecordTypes = if (moduleId == CollectionModuleId.HEALTH_CONNECT) {
                            setOf(HealthConnectRecordType.STEPS)
                        } else {
                            emptySet()
                        },
                    ),
                ),
            )

            val stamped = stampDataCollectionSettingsVersion(null, update(setting))

            assertEquals(
                60L,
                dataCollection(stamped).modules.getValue(moduleId).collectionCadence.intervalSeconds,
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRejectsJitterEvenForSupportedPullModules() {
        stampDataCollectionSettingsVersion(
            null,
            update(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.DEVICE_SETTINGS to CollectionModuleSetting(
                            enabled = true,
                            collectionCadence = CollectionCadence(intervalSeconds = 60, jitterSeconds = 5),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun settings(setting: AndroidDataCollectionSetting): StudySettings =
        StudySettings(mapOf(StudySettingType.DataCollection to setting))

    private fun update(setting: AndroidDataCollectionSetting): StudyUpdate = StudyUpdate(settings = settings(setting))

    private fun dataCollection(update: StudyUpdate): AndroidDataCollectionSetting =
        update.settings?.get(StudySettingType.DataCollection) as AndroidDataCollectionSetting
}
