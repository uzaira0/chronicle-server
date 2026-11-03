package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.util.UUID

class StoredExportTypesTest {
    private val studyId = UUID.randomUUID()
    private val storedData = Mockito.mock(StoredDataRepository::class.java)
    private val studies = Mockito.mock(StudyManager::class.java)
    private val types = StoredExportTypes(storedData, studies)

    private fun study(vararg modules: StudyFeature) =
        Study(studyId = studyId, title = "Pilot", contact = "pi@example.org", modules = modules.associateWith { true })

    /** A study still collecting offers every export type, without probing storage. */
    @Test
    fun collectingStudyOffersEveryExportType() {
        whenever(studies.getStudy(studyId)).thenReturn(study(StudyFeature.CHRONICLE_DATA_COLLECTION, StudyFeature.APP_USAGE_SURVEY))

        val availability = types.availability(studyId)

        assertEquals(ParticipantDataType.entries, availability.dataTypes)
        assertFalse(availability.timeUseDiary)
        Mockito.verify(storedData, Mockito.never()).hasRows(any(), any(), eq(true))
    }

    /**
     * The researcher switched collection off after the pilot. Export still offers exactly what was stored (usage
     * events in event storage, the daily survey in platform storage) plus upload diagnostics, and nothing that has
     * no rows.
     */
    @Test
    fun studyNoLongerCollectingOffersOnlyWhatWasStored() {
        whenever(studies.getStudy(studyId)).thenReturn(study())
        whenever(storedData.hasRows(any(), any(), anyOrNull())).thenReturn(false)
        whenever(storedData.hasRows(eq(studyId), eq(PostgresEventTables.CHRONICLE_USAGE_EVENTS), eq(true))).thenReturn(true)
        whenever(storedData.hasRows(eq(studyId), eq(ChroniclePostgresTables.APP_USAGE_SURVEY), eq(false))).thenReturn(true)

        val availability = types.availability(studyId)

        assertEquals(listOf(ParticipantDataType.UsageEvents, ParticipantDataType.AppUsageSurvey,
            ParticipantDataType.UploadDiagnostics), availability.dataTypes)
        assertFalse(availability.timeUseDiary)
    }

    @Test
    fun storedDiarySubmissionsKeepTheDiaryExportAfterTheFeatureIsOff() {
        whenever(studies.getStudy(studyId)).thenReturn(study())
        whenever(storedData.hasRows(any(), any(), anyOrNull())).thenReturn(false)
        whenever(storedData.hasRows(eq(studyId), eq(ChroniclePostgresTables.TIME_USE_DIARY_SUBMISSIONS), eq(false)))
            .thenReturn(true)

        assertTrue(types.availability(studyId).timeUseDiary)
    }

    /** Every exportable type names the table its rows live in; a new type without one fails here, not in an export. */
    @Test
    fun everyExportTypeNamesItsStorageTable() {
        val tables = ParticipantDataType.entries.associateWith { types.tableFor(it).name }
        assertEquals(PostgresEventTables.CHRONICLE_USAGE_EVENTS.name, tables[ParticipantDataType.UsageEvents])
        assertEquals(ChroniclePostgresTables.ANDROID_SENSOR_DATA.name, tables[ParticipantDataType.AndroidSensor])
        assertEquals(ChroniclePostgresTables.APP_USAGE_SURVEY.name, tables[ParticipantDataType.AppUsageSurvey])
        assertEquals(PostgresTableDefinition(UploadDiagnosticsUploadService.TABLE).name,
            tables[ParticipantDataType.UploadDiagnostics])
        DataDownloadService.COLLECTION_EXPORTS.forEach { (type, export) -> assertEquals(export.table.name, tables[type]) }
    }
}
