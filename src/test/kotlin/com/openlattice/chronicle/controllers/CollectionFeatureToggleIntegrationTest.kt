package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.withoutConsentEpochs
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.services.studies.CollectionSettingsRepository
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.withAdminContext
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionFeatureToggleIntegrationTest : ChronicleServerTests() {
    @Test
    fun `settings patch before feature activation preserves choices and issues effective revisions`() {
        val api = clientUser1.studyApi
        val id = api.createStudy(Study(title = "Collection toggle", contact = "research@example.org"))
        val initial = api.getStudySetting(id, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        val choices = CollectionDefaults.androidDataCollectionSetting().copy(modules =
            CollectionDefaults.androidDataCollectionSetting().modules.mapValues { (module, setting) ->
                setting.copy(enabled = module in setOf(CollectionModuleId.USAGE_EVENTS,
                    CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.UPLOAD_TELEMETRY))
            })
        api.updateStudySettings(id, StudySettingType.DataCollection, choices)
        val off = api.getStudySetting(id, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), off.effectiveEnabledModuleIds())
        val features = mapOf(StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>())
        api.updateStudy(id, StudyUpdate(modules = features))
        val on = api.getStudySetting(id, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        assertEquals(choices.modules, on.withoutConsentEpochs().modules)
        assertTrue(on.settingsVersion > off.settingsVersion)
        assertTrue(off.settingsVersion >= initial.settingsVersion)
        api.updateStudy(id, StudyUpdate(modules = emptyMap()))
        val stopped = api.getStudySetting(id, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), stopped.effectiveEnabledModuleIds())
        assertEquals(on.settingsVersion + 1, stopped.settingsVersion)
        assertEquals(choices.modules, api.getStudy(id).settings.androidDataCollection().modules)
        api.updateStudy(id, StudyUpdate(modules = features))
        val resumed = api.getStudySetting(id, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        assertEquals(choices.modules, resumed.withoutConsentEpochs().modules)
        assertEquals(stopped.settingsVersion + 1, resumed.settingsVersion)
        assertEquals(resumed.withoutConsentEpochs(), api.getStudySettings(id)[StudySettingType.DataCollection])
        testServer.context.getBean(StorageResolver::class.java).getPlatformStorage().connection.use { connection ->
            withAdminContext(connection) {
                val revisions = CollectionSettingsRepository(connection)
                assertEquals(stopped.withoutConsentEpochs(), revisions.issued(id, stopped.settingsVersion))
                assertEquals(on.withoutConsentEpochs(), revisions.issued(id, on.settingsVersion))
                assertEquals(resumed.withoutConsentEpochs(), revisions.latest(id))
                assertEquals(stopped.withoutConsentEpochs(), revisions.through(id, resumed.settingsVersion)
                    .single { it.settingsVersion == stopped.settingsVersion })
            }
        }
    }
}
