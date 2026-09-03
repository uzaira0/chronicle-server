package com.openlattice.chronicle.services.studies.tasks

import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.ViolationReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyComplianceHazelcastTaskPrivacyTest {

    @Test
    fun complianceLogSummaryUsesCountsAndStableReferencesNeverParticipantDetails() {
        val participantId = "participant-jane-doe"
        val privateDescription = "medication adherence detail for Jane Doe"
        val violations = mapOf(
            participantId to listOf(
                ComplianceViolation(ViolationReason.NO_DATA_UPLOADED, privateDescription),
                ComplianceViolation(ViolationReason.NO_RECENT_DATA_UPLOADED, "private second detail"),
            )
        )

        val summary = StudyComplianceHazelcastTask.complianceLogSummary(violations)

        assertTrue(summary.contains("participantCount=1"))
        assertTrue(summary.contains("violationCount=2"))
        assertTrue(summary.contains("participantRefs=[participant:"))
        assertFalse(summary.contains(participantId))
        assertFalse(summary.contains(privateDescription))
        assertFalse(summary.contains("private second detail"))
        assertFalse(summary.contains(ViolationReason.NO_DATA_UPLOADED.name))
    }
}
