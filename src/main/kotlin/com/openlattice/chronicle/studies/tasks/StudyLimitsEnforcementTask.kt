package com.openlattice.chronicle.studies.tasks

import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.HazelcastTaskDependencies
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

    override fun getInitialDelay(): Long = 0

    override fun getPeriod(): Long = 1

    override fun getTimeUnit(): TimeUnit = TimeUnit.HOURS

    @Suppress("TooGenericExceptionCaught")
    override fun runTask() {
        RLSRequestContext.withSystemContext {
            try {
                pauseParticipantsForStudiesOverDuration()
            } catch (exception: Exception) {
                logger.error("Unable to enumerate studies past their duration", exception)
            }
            expireStudiesOutsideOfRetentionPeriod()
        }
    }

    override fun getName(): String = "STUDY_LIMITS_ENFORCEMENT"

    override fun getDependenciesClass(): Class<StudyLimitsEnforcementTaskDependencies> =
        StudyLimitsEnforcementTaskDependencies::class.java

    @Suppress("TooGenericExceptionCaught")
    private fun pauseParticipantsForStudiesOverDuration() {
        val deps = getDependency()
        deps.studyLimitsManager.getStudiesExceedingDurationLimit().forEach { studyId ->
            try {
                val paused = deps.studyService.pauseEnrolledParticipants(studyId)
                logger.info("Paused {} enrolled participants in study {}", paused, studyId)
            } catch (exception: Exception) {
                logger.error("Unable to pause enrolled participants in study {}", studyId, exception)
            }
        }
    }

    /** Retention expiry alerts operators; deletion remains an explicit study action. */
    private fun expireStudiesOutsideOfRetentionPeriod() {
        val deps = getDependency()
        val studiesToExpire = deps.studyLimitsManager.getStudiesExcceedingDataRetentionPeriod()
        com.openlattice.chronicle.observability.ChronicleMetrics.studiesRetentionExpired.set(studiesToExpire.size.toDouble())
        logger.info("{} studies have ended their retention period and require deletion", studiesToExpire.size)
    }
}

public data class StudyLimitsEnforcementTaskDependencies(
    val storageResolver: StorageResolver,
    val studyLimitsManager: StudyLimitsManager,
    val studyService: StudyManager
) : HazelcastTaskDependencies
