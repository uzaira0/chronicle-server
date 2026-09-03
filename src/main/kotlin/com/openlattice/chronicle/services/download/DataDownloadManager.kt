package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.constants.ParticipantDataType
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.study.ParticipantDataType as StudyParticipantDataType
import java.time.OffsetDateTime
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public interface DataDownloadManager {

    public fun getParticipantData(
        studyId: UUID,
        participantId: String,
        dataType: ParticipantDataType,
        token: String
    ): Iterable<Map<String, Any>>

    public fun getParticipantsSensorData(
        studyId: UUID,
        participantIds: Set<String>,
        sensors: Set<SensorType>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>>

    public fun getParticipantsAppUsageSurveyData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>>

    public fun getParticipantsUsageEventsData(
        studyId: UUID,
        participantIds: Set<String>,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime
    ): Iterable<Map<String, Any>>

    public fun getPreprocessedUsageEventsData(
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

    /** Exports one of the dedicated Play collection tables through a bounded, study-scoped query. */
    public fun getParticipantsCollectionData(
        studyId: UUID,
        participantIds: Set<String>,
        dataType: StudyParticipantDataType,
        startDateTime: OffsetDateTime,
        endDateTime: OffsetDateTime,
    ): Iterable<Map<String, Any>>

    public fun getQuestionnaireResponses(
        studyId: UUID,
        questionnaireId: UUID
    ): Iterable<Map<String, Any>>
}
