package com.openlattice.chronicle.studies.tasks

import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.participants.Participant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID
import java.util.concurrent.TimeUnit

class StudyLimitsEnforcementTaskTest {
    @Test
    fun bootImmediatelyPausesExpiredStudiesAndRevokesRetentionAccess() {
        val limits = Mockito.mock(StudyLimitsManager::class.java)
        val studies = Mockito.mock(StudyManager::class.java)
        val study = UUID.randomUUID()
        val participant = Mockito.mock(Participant::class.java)
        Mockito.`when`(participant.participantId).thenReturn("expired-participant")
        Mockito.`when`(limits.getStudiesExceedingDurationLimit()).thenReturn(setOf(study))
        Mockito.`when`(limits.getStudiesExcceedingDataRetentionPeriod()).thenReturn(setOf(study))
        Mockito.`when`(studies.getStudyParticipants(study)).thenReturn(setOf(participant))
        val deps = StudyLimitsEnforcementTaskDependencies(Mockito.mock(StorageResolver::class.java), limits, studies)
        val task = object : StudyLimitsEnforcementTask() {
            override fun getDependency() = deps
        }
        assertEquals(0L, task.getInitialDelay())
        assertEquals(1L, task.getPeriod())
        assertEquals(TimeUnit.HOURS, task.getTimeUnit())
        task.runTask()
        Mockito.verify(studies).updateParticipationStatus(study, "expired-participant", ParticipationStatus.PAUSED)
        Mockito.verify(studies).expireStudies(setOf(study))
        Mockito.verify(studies).getStudyParticipants(study)
        Mockito.verifyNoMoreInteractions(studies)
    }
}
