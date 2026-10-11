package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType

/** Feature-off is a collection stop; upload diagnostics remain part of the disclosed operational inventory. */
internal fun collectionSettingsForFeatures(settings: StudySettings, features: Set<String>): StudySettings {
    if (StudyFeature.CHRONICLE_DATA_COLLECTION.name in features) return settings
    val collection = settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting ?: return settings
    val stopped = collection.copy(modules = collection.effectiveModules().mapValues { (id, setting) ->
        if (id == CollectionModuleId.UPLOAD_TELEMETRY) setting else setting.copy(enabled = false)
    })
    return StudySettings(settings + (StudySettingType.DataCollection to stopped))
}
