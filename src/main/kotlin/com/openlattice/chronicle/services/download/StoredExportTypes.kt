package com.openlattice.chronicle.services.download

import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.ParticipantDataType
import org.springframework.stereotype.Service
import java.util.UUID

public data class StudyExportAvailability(val dataTypes: List<ParticipantDataType>, val timeUseDiary: Boolean)

/** Export discovery follows stored rows, independently of today's collection switches. */
@Service
public class StoredExportTypes(private val storedData: StoredDataRepository, private val studies: StudyManager) {
    public fun availability(studyId: UUID): StudyExportAvailability {
        val modules = studies.getStudy(studyId).modules
        val types = if (StudyFeature.CHRONICLE_DATA_COLLECTION in modules) ParticipantDataType.entries else {
            ParticipantDataType.entries.filter { type ->
                type == ParticipantDataType.UploadDiagnostics ||
                    storedData.hasRows(studyId, tableFor(type), eventStorage = type in EVENT_TYPES)
            }
        }
        return StudyExportAvailability(
            dataTypes = types,
            timeUseDiary = StudyFeature.TIME_USE_DIARY in modules ||
                storedData.hasRows(studyId, ChroniclePostgresTables.TIME_USE_DIARY_SUBMISSIONS),
        )
    }

    internal fun tableFor(type: ParticipantDataType): PostgresTableDefinition =
        DataDownloadService.COLLECTION_EXPORTS[type]?.table ?: when (type) {
            ParticipantDataType.UsageEvents -> PostgresEventTables.CHRONICLE_USAGE_EVENTS
            ParticipantDataType.AndroidSensor -> ChroniclePostgresTables.ANDROID_SENSOR_DATA
            ParticipantDataType.AppUsageSurvey -> ChroniclePostgresTables.APP_USAGE_SURVEY
            ParticipantDataType.UploadDiagnostics -> PostgresTableDefinition(UploadDiagnosticsUploadService.TABLE)
            else -> error("Missing export source for ${type.name}")
        }

    private companion object {
        val EVENT_TYPES = setOf(ParticipantDataType.UsageEvents)
    }
}
