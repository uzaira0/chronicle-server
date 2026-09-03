package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.sources.SourceDevice
import java.sql.Connection
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public interface EnrollmentManager {
    public fun registerDevice(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        sourceDevice: SourceDevice
    ): UUID

    public fun isKnownDatasource(studyId: UUID, participantId: String, deviceId: UUID): Boolean
    public fun isKnownParticipant(studyId: UUID, participantId: String): Boolean

    public fun getParticipant(studyId: UUID, participantId: String): Participant
    public fun getParticipationStatus(studyId: UUID, participantId: String): ParticipationStatus
    public fun getStudyParticipantIds(studyId: UUID): Set<String>
    public fun getStudyParticipants(studyId: UUID): Set<Participant>
    public fun studyExists(studyId: UUID): Boolean
    public fun getOrganizationIdForStudy(studyId: UUID): UUID

    public fun registerParticipant(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        candidateId: UUID,
        participationStatus: ParticipationStatus
    )
}
