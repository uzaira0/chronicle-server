package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyUpdate
import java.sql.Connection
import java.util.UUID

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
// reason: cohesive study-service contract; splitting would fan out across all implementers/callers with no behavior gain
@Suppress("TooManyFunctions")
public interface StudyManager {
    public fun createStudy(connection: Connection, study: Study)
    public fun createStudy(study: Study): UUID
    public fun deleteStudies(connection: Connection, studyIds: Collection<UUID>): Int
    public fun getOrgStudies(organizationId: UUID, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<Study>
    public fun getOrganizationIdForLegacyStudy(studyId: UUID): UUID
    public fun getParticipantStats(studyId: UUID, participantId: String): ParticipantStats?
    public fun getStudies(studyIds: Collection<UUID>): Iterable<Study>
    public fun getStudy(studyId: UUID): Study
    public fun getStudyId(maybeLegacyMaybeRealStudyId: UUID): UUID?
    public fun getStudyParticipantStats(studyId: UUID, limit: Int = Int.MAX_VALUE, offset: Int = 0): Map<String, ParticipantStats>
    public fun getStudyParticipants(studyId: UUID, limit: Int = Int.MAX_VALUE, offset: Int = 0): Iterable<Participant>
    public fun getStudySensors(studyId: UUID): Set<SensorType>
    public fun getStudySettings(studyId: UUID): Map<StudySettingType, StudySetting>
    public fun getStudySettings(studyIds: Collection<UUID>): Map<UUID, Map<StudySettingType, StudySetting>>
    public fun insertOrUpdateParticipantStats(stats: ParticipantStats)
    public fun isNotificationsEnabled(studyId: UUID): Boolean
    public fun isValidStudy(studyId: UUID): Boolean
    public fun refreshStudyCache(studyIds: Set<UUID>)

    /**
     * Callers of this function must ensure that load the participant ace after they commit changes.
     */
    public fun registerParticipant(connection: Connection, studyId: UUID, participant: Participant): UUID
    public fun registerParticipant(studyId: UUID, participant: Participant): UUID
    public fun removeAllParticipantsFromStudies(connection: Connection, studyIds: Collection<UUID>): Int
    public fun removeParticipantsFromStudy(connection: Connection, studyId: UUID, participantIds: Collection<String>): Int
    public fun removeStudiesFromOrganizations(connection: Connection, studyIds: Collection<UUID>): Int
    public fun updateStudy(connection: Connection, studyId: UUID, study: StudyUpdate)
    public fun getStudyPhoneNumber(studyId: UUID): String?
    public fun updateParticipantAnnotations(studyId: UUID, participantId: String, annotations: Map<String, Any?>)
    public fun updateParticipationStatus(studyId: UUID, participantId: String, participationStatus: ParticipationStatus)
    public fun countStudyParticipants(connection: Connection, studyIds: Set<UUID>): Map<UUID, Long>
    public fun countStudyParticipants(studyId: UUID): Long
    public fun countStudyParticipants(studyIds: Set<UUID>): Map<UUID, Long>

    public fun updateLastDevicePing(studyId: UUID, participantId: String, sourceDevice: SourceDevice)
    public fun updateLastDevicePing(studyId: UUID, participantId: String)
    public fun expireStudies(studyIds: Set<UUID>)
    public fun getAllStudyIds(): Iterable<UUID>
}
