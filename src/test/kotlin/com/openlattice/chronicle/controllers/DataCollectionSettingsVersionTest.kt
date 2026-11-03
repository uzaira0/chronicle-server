package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.studies.collectionSettingsForFeatures
import com.openlattice.chronicle.services.studies.stampDataCollectionSettingsVersion
import com.openlattice.chronicle.services.studies.stampInitialDataCollectionSettings
import com.openlattice.chronicle.services.studies.mergeAndStampDataCollectionSettingsVersionLocked
import com.openlattice.chronicle.services.studies.mergeStudySetting
import com.openlattice.chronicle.services.studies.validateClientSupportedCollectionPolicy

import com.fasterxml.jackson.core.JsonProcessingException
import com.openlattice.chronicle.survey.SurveySettings
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.study.StudyWithdrawalSetting
import com.openlattice.chronicle.study.WithdrawalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class DataCollectionSettingsVersionTest {
    private val collectionFeatures = setOf(StudyFeature.CHRONICLE_DATA_COLLECTION.name)

    @Test fun `revision stamping requires explicit prior and requested features`() {
        assertTrue(::stampDataCollectionSettingsVersion.parameters.none { it.isOptional })
    }

    @Test fun withdrawalDefaultsToRetentionAndRejectsInvalidSettings() {
        assertEquals(StudyWithdrawalSetting(WithdrawalMode.KEEP_PRIOR_DATA),
            Study(title = "withdrawal policy", contact = "research@example.org").settings[StudySettingType.Withdrawal])
        assertThrows(IllegalArgumentException::class.java) {
            mergeStudySetting(StudySettings(), StudySettingType.Withdrawal, SurveySettings())
        }
        assertThrows(JsonProcessingException::class.java) {
            ObjectMappers.getJsonMapper().readValue(
                """{"Withdrawal":{"@class":"com.openlattice.chronicle.study.StudyWithdrawalSetting","mode":"UNKNOWN_MODE"}}""",
                StudySettings::class.java,
            )
        }
    }

    @Test
    fun `operational modules cannot require an unavailable consent decision`() {
        CollectionModuleId.entries.filterNot { it.consentGated }.forEach { module ->
            val settings = AndroidDataCollectionSetting(modules = mapOf(
                module to CollectionModuleSetting(enabled = true, required = true),
            ))
            val failure = assertThrows("${module.id} must not demand unsupported consent", IllegalArgumentException::class.java) {
                validateClientSupportedCollectionPolicy(settings)
            }
            assertTrue(failure.message.orEmpty().contains("no per-module consent decision"))
        }
        validateClientSupportedCollectionPolicy(AndroidDataCollectionSetting(modules = mapOf(
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true, required = true),
        )))
    }

    @Test
    fun `new study without collection policy receives server issued initial default`() {
        val study = Study(title = "initial policy", contact = "research@example.org",
            modules = mapOf(StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()))

        val stamped = stampInitialDataCollectionSettings(study)

        assertEquals(
            CollectionDefaults.androidDataCollectionSetting(),
            stamped.settings[StudySettingType.DataCollection],
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
            modules = mapOf(CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false)),
            settingsVersion = 99,
        )

        val stamped = stampDataCollectionSettingsVersion(
            settings(baseSetting), update(changed),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

        assertEquals(5, dataCollection(stamped).settingsVersion)
    }

    @Test fun testIdenticalPolicyPreservesServerRevision() {
        val stamped = stampDataCollectionSettingsVersion(
            settings(baseSetting),
            update(baseSetting.copy(settingsVersion = 99)),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

        assertEquals(4, dataCollection(stamped).settingsVersion)
    }

    @Test fun `writing back the read form of a sparse policy is not a change`() {
        // GET settings/type/DataCollection answers the complete effective map; the stored map is sparse.
        val readForm = collectionSettingsForFeatures(settings(baseSetting), collectionFeatures)
            .getValue(StudySettingType.DataCollection) as AndroidDataCollectionSetting

        val stamped = stampDataCollectionSettingsVersion(
            settings(baseSetting), update(readForm),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

        assertEquals(baseSetting, dataCollection(stamped))
    }

    @Test fun testUnchangedPolicyKeepsStoredSetOrder() {
        fun withRecordTypes(vararg types: HealthConnectRecordType) = baseSetting.copy(
            modules = mapOf(
                CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(
                    enabled = true,
                    healthConnectRecordTypes = linkedSetOf(*types),
                ),
            ),
        )
        val mapper = ObjectMappers.getJsonMapper()
        val stored = mapper.writeValueAsString(
            settings(withRecordTypes(HealthConnectRecordType.SLEEP, HealthConnectRecordType.STEPS)),
        )
        val prior = mapper.readValue(stored, StudySettings::class.java)
        val reordered = withRecordTypes(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP)

        val stamped = stampDataCollectionSettingsVersion(
            prior, update(reordered.copy(settingsVersion = 99)),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

        // jsonb array equality is ordered: the revision trigger refuses any other order.
        assertEquals(
            mapper.readTree(stored).get("DataCollection"),
            mapper.readTree(mapper.writeValueAsString(stamped.settings)).get("DataCollection"),
        )
    }

    @Test fun testFirstDataCollectionSettingStartsAtInitialRevision() {
        val stamped = stampDataCollectionSettingsVersion(
            null, update(baseSetting.copy(settingsVersion = 99)),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

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
        val featureStatement = mock<PreparedStatement>()
        val featureRows = mock<ResultSet>()
        whenever(connection.prepareStatement("SELECT modules FROM studies WHERE study_id = ? FOR UPDATE"))
            .thenReturn(featureStatement)
        whenever(featureStatement.executeQuery()).thenReturn(featureRows)
        whenever(featureRows.next()).thenReturn(true)
        whenever(featureRows.getString("modules")).thenReturn("{\"CHRONICLE_DATA_COLLECTION\":{}}")
        val changed = baseSetting.copy(
            modules = mapOf(CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false)),
            settingsVersion = 99,
        )

        val locked = mergeAndStampDataCollectionSettingsVersionLocked(connection, studyId, update(changed))

        assertEquals(5, dataCollection(locked.stampedStudy).settingsVersion)
        verify(statement).setObject(1, studyId)
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
        val sensorDelta = SurveySettings(appUsageThresholdInSeconds = 42)

        val merged = mergeStudySetting(authoritative, StudySettingType.Survey, sensorDelta)

        assertEquals(authoritative[StudySettingType.DataCollection], merged[StudySettingType.DataCollection])
        assertEquals(sensorDelta, merged[StudySettingType.Survey])
    }

    @Test
    fun testEnabledHealthConnectRequiresAnExplicitRecordTypeScope() {
        assertThrows(IllegalArgumentException::class.java) {
            validateClientSupportedCollectionPolicy(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(enabled = true),
                    ),
                ),
            )
        }
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

        val stamped = stampDataCollectionSettingsVersion(
            null, update(scoped),
            priorFeatures = collectionFeatures,
            requestedFeatures = collectionFeatures,
        )

        assertEquals(
            setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
            dataCollection(stamped).modules.getValue(CollectionModuleId.HEALTH_CONNECT)
                .healthConnectRecordTypes,
        )
    }

    @Test
    fun testNonHealthConnectModuleRejectsHealthConnectRecordTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            validateClientSupportedCollectionPolicy(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                            enabled = true,
                            healthConnectRecordTypes = setOf(HealthConnectRecordType.STEPS),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun testRejectsEverySignedPolicyKnobThePlayBuildDoesNotEnforce() {
        val unsupported = listOf(
            CollectionModuleSetting(
                enabled = true,
                collectionCadence = CollectionCadence(intervalSeconds = 60),
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
                    priorFeatures = collectionFeatures,
                    requestedFeatures = collectionFeatures,
                )
            }
            assertTrue(error.message.orEmpty().contains("not supported by the Android app"))
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

            val stamped = stampDataCollectionSettingsVersion(
                null, update(setting),
                priorFeatures = collectionFeatures,
                requestedFeatures = collectionFeatures,
            )

            assertEquals(
                60L,
                dataCollection(stamped).modules.getValue(moduleId).collectionCadence.intervalSeconds,
            )
        }
    }

    @Test
    fun testRejectsJitterEvenForSupportedPullModules() {
        assertThrows(IllegalArgumentException::class.java) {
            validateClientSupportedCollectionPolicy(
                AndroidDataCollectionSetting(
                    modules = mapOf(
                        CollectionModuleId.DEVICE_SETTINGS to CollectionModuleSetting(
                            enabled = true,
                            collectionCadence = CollectionCadence(intervalSeconds = 60, jitterSeconds = 5),
                        ),
                    ),
                ),
            )
        }
    }

    private fun settings(setting: AndroidDataCollectionSetting): StudySettings =
        StudySettings(mapOf(StudySettingType.DataCollection to setting))

    @Test
    fun `disabling upload telemetry is rejected because the Play client refuses to enroll without it`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateClientSupportedCollectionPolicy(
                AndroidDataCollectionSetting(
                    modules = mapOf(CollectionModuleId.UPLOAD_TELEMETRY to CollectionModuleSetting(enabled = false)),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("upload_telemetry cannot be disabled"))
    }

    private fun update(setting: AndroidDataCollectionSetting): StudyUpdate = StudyUpdate(settings = settings(setting))

    private fun dataCollection(update: StudyUpdate): AndroidDataCollectionSetting =
        update.settings?.get(StudySettingType.DataCollection) as AndroidDataCollectionSetting
}
