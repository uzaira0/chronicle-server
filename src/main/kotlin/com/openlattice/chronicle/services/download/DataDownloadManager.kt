package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.study.ParticipantDataType as StudyParticipantDataType
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface DataDownloadManager {

    /** Survey days that start inside [startDateTime, endDateTime) in [studyZone], the study's current zone. */
    public fun getParticipantsAppUsageSurveyData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        studyZone: ZoneId,
    ): Iterable<Map<String, Any>>

    public fun getParticipantsUsageEventsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>>

    public fun getParticipantsAndroidSensorData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
        sensorTypes: Set<String>? = null
    ): Iterable<Map<String, Any>>

    /** Exports one of the dedicated collection tables through a bounded, study-scoped query. */
    public fun getParticipantsCollectionData(
        studyId: UUID,
        participantIds: Set<String>,
        dataType: StudyParticipantDataType,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>>

    /** Full retained Android upload-diagnostic history, filtered by diagnostic day. */
    public fun getParticipantsUploadDiagnosticsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>>

    public fun getQuestionnaireResponses(
        studyId: UUID,
        questionnaireId: UUID
    ): Iterable<Map<String, Any>>
}
