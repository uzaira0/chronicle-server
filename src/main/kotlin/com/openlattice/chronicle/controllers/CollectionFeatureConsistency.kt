package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import java.sql.Connection
import java.util.UUID

/** Feature-off is a collection stop; upload diagnostics remain part of the disclosed operational inventory. */
internal fun collectionSettingsForFeatures(settings: StudySettings, features: Set<String>): StudySettings {
    if (StudyFeature.CHRONICLE_DATA_COLLECTION.name in features) return settings
    val collection = settings[StudySettingType.DataCollection] as? AndroidDataCollectionSetting ?: return settings
    val stopped = collection.copy(modules = collection.effectiveModules().mapValues { (id, setting) ->
        if (id == CollectionModuleId.UPLOAD_TELEMETRY) setting else setting.copy(enabled = false)
    })
    return StudySettings(settings + (StudySettingType.DataCollection to stopped))
}

/** The caller holds the studies row lock, shared with manifest validation and every settings writer. */
internal fun loadLockedStudyFeatures(connection: Connection, studyId: UUID): Set<String> =
    connection.prepareStatement("SELECT modules FROM studies WHERE study_id = ? FOR UPDATE").use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "Study is unavailable" }
            val json = rows.getString("modules") ?: "{}"
            ObjectMappers.getJsonMapper().readTree(json).fieldNames().asSequence().toSet()
        }
    }
