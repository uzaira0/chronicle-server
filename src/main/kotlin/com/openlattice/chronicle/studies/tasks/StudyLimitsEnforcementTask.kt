package com.openlattice.chronicle.studies.tasks

import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.HazelcastTaskDependencies
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class StudyLimitsEnforcementTask : HazelcastFixedRateTask<StudyLimitsEnforcementTaskDependencies> {
    internal companion object {
        private val logger = LoggerFactory.getLogger(StudyLimitsEnforcementTask::class.java)
    }

    override fun getInitialDelay(): Long = 10_000

    override fun getPeriod(): Long = 1

    override fun getTimeUnit(): TimeUnit = TimeUnit.HOURS

    override fun runTask() {
        RLSRequestContext.withSystemContext {
            pauseParticipantsForStudiesOverDuration()
            expireStudiesOutsideOfRetentionPeriod()
        }
    }

    override fun getName(): String = "STUDY_LIMITS_ENFORCEMENT"

    override fun getDependenciesClass(): Class<StudyLimitsEnforcementTaskDependencies> =
        StudyLimitsEnforcementTaskDependencies::class.java

    private fun pauseParticipantsForStudiesOverDuration() {
        val deps = getDependency()
        deps.studyLimitsManager.getStudiesExceedingDurationLimit().forEach { studyId ->
            logger.info("Pausing data collection for all participants in study {}", studyId)
            deps.studyService.getStudyParticipants(studyId).forEach { participant ->
                deps.studyService.updateParticipationStatus(
                    studyId,
                    participant.participantId,
                    ParticipationStatus.PAUSED
                )
            }
        }
    }

    /**
     * Expiry only revokes non-admin access; it never erases data. Owner decision 2026-09-28: a
     * date or clock bug must not be able to destroy study data, so erasure stays an explicit,
     * human-started operation. Do not start a study erasure from here.
     */
    private fun expireStudiesOutsideOfRetentionPeriod() {
        val deps = getDependency()
        val studiesToExpire = deps.studyLimitsManager.getStudiesExcceedingDataRetentionPeriod()
        logger.info("Revoking non-admin access to studies past their retention period: {}", studiesToExpire)
        deps.studyService.expireStudies(studiesToExpire)
    }
}

public data class StudyLimitsEnforcementTaskDependencies(
    val storageResolver: StorageResolver,
    val studyLimitsManager: StudyLimitsManager,
    val studyService: StudyManager
) : HazelcastTaskDependencies
