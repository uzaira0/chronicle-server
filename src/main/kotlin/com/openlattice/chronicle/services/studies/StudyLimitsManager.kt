package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.study.StudyLimits
import java.sql.Connection
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
public interface StudyLimitsManager {
    public fun initializeStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits = StudyLimits())


    public fun setStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits)
    public fun getStudyLimits(studyId: UUID): StudyLimits

    public fun getStudiesExceedingDurationLimit(): Set<UUID>
    public fun getStudiesExcceedingDataRetentionPeriod(): Set<UUID>

    public fun countStudyParticipants(connection: Connection, studyIds: Set<UUID>): Map<UUID, Long>
    public fun countStudyParticipants(studyId: UUID): Long
    public fun countStudyParticipants(studyIds: Set<UUID>): Map<UUID, Long>
}
