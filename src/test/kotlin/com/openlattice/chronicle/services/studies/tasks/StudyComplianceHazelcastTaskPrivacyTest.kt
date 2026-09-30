package com.openlattice.chronicle.services.studies.tasks

import com.openlattice.chronicle.study.ComplianceViolation
import com.openlattice.chronicle.study.ViolationReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyComplianceHazelcastTaskPrivacyTest {

    @Test
    fun researcherMessagesCarryOnlyTheParticipantWhoseScopeIsPersisted() {
        val studyId = java.util.UUID.randomUUID()
        val studies = org.mockito.kotlin.mock<com.openlattice.chronicle.services.studies.StudyManager>()
        org.mockito.kotlin.whenever(studies.getStudy(studyId)).thenReturn(
            com.openlattice.chronicle.study.Study(studyId, "scope study", contact = "researcher@example.org"))
        val resolver = org.mockito.kotlin.mock<com.openlattice.chronicle.storage.StorageResolver>()
        val source = org.mockito.kotlin.mock<com.zaxxer.hikari.HikariDataSource>()
        val connection = org.mockito.kotlin.mock<java.sql.Connection>()
        org.mockito.kotlin.whenever(resolver.getPlatformStorage()).thenReturn(source)
        org.mockito.kotlin.whenever(source.connection).thenReturn(connection)
        val notifications = org.mockito.kotlin.mock<com.openlattice.chronicle.services.notifications.NotificationManager>()
        val captured = mutableListOf<com.openlattice.chronicle.services.notifications.ResearcherNotification>()
        org.mockito.kotlin.whenever(notifications.sendResearcherNotifications(
            org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any(), org.mockito.kotlin.any(),
        )).thenAnswer { call -> captured.addAll(call.getArgument<List<com.openlattice.chronicle.services.notifications.ResearcherNotification>>(2)); 1 }
        val dependencies = StudyComplianceHazelcastTaskDependencies(org.mockito.kotlin.mock(), studies, resolver, notifications)
        val task = object : StudyComplianceHazelcastTask() { override fun getDependency() = dependencies }
        task.notifyNonCompliantStudies(mapOf(studyId to mapOf(
            "subject-one" to listOf(ComplianceViolation(ViolationReason.NO_DATA_UPLOADED, "first detail")),
            "subject-two" to listOf(ComplianceViolation(ViolationReason.NO_RECENT_DATA_UPLOADED, "second detail")),
        )))
        org.junit.Assert.assertEquals(setOf("subject-one", "subject-two"), captured.map { it.participantId }.toSet())
        captured.forEach { notice ->
            assertTrue(notice.message.contains(notice.participantId))
            val other = if (notice.participantId == "subject-one") "subject-two" else "subject-one"
            assertFalse(notice.message.contains(other))
        }
    }

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
