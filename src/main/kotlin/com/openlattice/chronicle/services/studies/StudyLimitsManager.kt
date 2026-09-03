package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import java.sql.Connection
import java.util.*
import javax.naming.InsufficientResourcesException

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
// reason: cohesive study-limits/enrollment-capacity contract — these operations form one
// management surface and splitting the interface would scatter the limits API across types
@Suppress("TooManyFunctions")
public interface StudyLimitsManager {
    public fun initializeStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits = StudyLimits())


    /**
     * Allocates enrollment capacity for this transaction. It used SELECT FOR UPDATE to lock rows
     * in the limits table, preventing other threads from allocating enrollment. This is necessary
     * because two researchers adding at the same time could end up going over the limit.
     */
    public fun lockStudyForEnrollments(connection: Connection, studyId: UUID): Int
    public fun getEnrollmentCapacity(studyId: UUID): Int
    public fun setEnrollmentCapacity(studyId: UUID, capacity: Int)

    public fun setStudyDuration(studyId: UUID, studyDuration: StudyDuration)
    public fun getStudyDuration(studyId: UUID): StudyDuration

    public fun setDataRetentionPeriod(studyId: UUID, dataRetentionPeriod: StudyDuration)
    public fun getDataRetentionPeriod(studyId: UUID): StudyDuration

    public fun getStudyFeatures(studyId: UUID): Set<StudyFeature>
    public fun setStudyFeatures(studyId: UUID, studyFeatures: Set<StudyFeature>)

    public fun setStudyLimits(studyId: UUID, studyLimits: StudyLimits)
    public fun getStudyLimits(studyId: UUID): StudyLimits

    public fun getStudiesExceedingDurationLimit(): Set<UUID>
    public fun getStudiesExcceedingDataRetentionPeriod(): Set<UUID>

    public fun countStudyParticipants(connection: Connection, studyIds: Set<UUID>): Map<UUID, Long>
    public fun countStudyParticipants(studyId: UUID): Long
    public fun countStudyParticipants(studyIds: Set<UUID>): Map<UUID, Long>
    public fun reserveEnrollmentCapacity(connection: Connection, studyId: UUID, capacity: Int = 1) {
        val maxParticipantCount = lockStudyForEnrollments(connection, studyId)
        val neededParticipants = (countStudyParticipants(connection, setOf(studyId))[studyId] ?: 0) + capacity
        if (neededParticipants > maxParticipantCount) {
            throw InsufficientResourcesException("Insufficient remaining capacity to add particpants")
        }
    }
}

