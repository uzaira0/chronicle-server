package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings

internal fun collectionSettingsForFeatures(study: Study, settings: StudySettings = study.settings): StudySettings =
    collectionSettingsForFeatures(settings, study.modules.keys.map { it.name }.toSet())

/** Read-side policy only: retain stored choices, but stop collection while the feature is off. */
internal fun collectionSettingsForFeatures(settings: StudySettings, features: Set<String>): StudySettings {
    return com.openlattice.chronicle.collection.collectionSettingsForFeatures(settings, features)
}
