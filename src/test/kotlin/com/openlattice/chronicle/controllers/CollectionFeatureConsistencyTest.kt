package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyUpdate
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import com.geekbeast.mappers.mappers.ObjectMappers

class CollectionFeatureConsistencyTest {
    @Test fun featureOffStopsCollectionOnCreateAndOnLockedPartialUpdates() {
        val settings = StudySettings(mapOf(StudySettingType.DataCollection to CollectionDefaults.androidDataCollectionSetting()))
        val initial = stampInitialDataCollectionSettings(Study(title = "No collection", contact = "research@example.org", settings = settings))
        fun assertStopped(actual: StudySettings) {
            val collection = actual[StudySettingType.DataCollection] as AndroidDataCollectionSetting
            assertEquals(setOf(CollectionModuleId.UPLOAD_TELEMETRY), collection.effectiveEnabledModuleIds())
        }
        assertStopped(initial.settings)
        val connection: Connection = mock(); val statement: PreparedStatement = mock(); val rows: ResultSet = mock()
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(rows); whenever(rows.next()).thenReturn(true)
        whenever(rows.getString("settings")).thenReturn(ObjectMappers.getJsonMapper().writeValueAsString(settings))
        whenever(rows.getString("modules")).thenReturn("{\"CHRONICLE_DATA_COLLECTION\":{}}")
        val stopped = stampDataCollectionSettingsVersionLocked(connection, UUID.randomUUID(), StudyUpdate(modules = emptyMap()))
        assertStopped(stopped.stampedStudy.settings!!)
        assertEquals(2, (stopped.stampedStudy.settings!![StudySettingType.DataCollection] as AndroidDataCollectionSetting).settingsVersion)
        assertEquals(settings, collectionSettingsForFeatures(settings, setOf(StudyFeature.CHRONICLE_DATA_COLLECTION.name)))
    }
}
