package com.openlattice.chronicle.study

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test

class StudyComplianceTests : ChronicleServerTests() {

    @Test
    fun testGetComplianceViolationsNewStudy() {
        val study = Study(title = "Default compliance settings", contact = "test@example.org")
        val studyId = clientUser1.studyApi.createStudy(study)
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)

        val device = TestDataFactory.androidDevice()
        enrollUsageFixture(studyId, participant.participantId, device.deviceId, device)

        val violations = clientUser1.studyComplianceApi.getStudyComplianceViolations(studyId)
        Assert.assertTrue("An enrolled Android device without uploads violates the default upload policy",
            violations.getValue(studyId).getValue(participant.participantId).any { it.reason == ViolationReason.NO_RECENT_DATA_UPLOADED })
    }

    @Test
    fun testGetComplianceViolationsEmptyStudy() {
        val study = TestDataFactory.study()
        val studyId = clientUser1.studyApi.createStudy(study)

        val violations = clientUser1.studyComplianceApi.getStudyComplianceViolations(studyId)
        Assert.assertNotNull("Violations map should not be null", violations)
        Assert.assertTrue("Empty study should have no violations", violations.isEmpty())
    }

    @Test
    fun testTriggerComplianceNotifications() {
        val studyId1 = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studyId2 = clientUser1.studyApi.createStudy(TestDataFactory.study())

        clientAdmin.testStudyComplianceApi.triggerStudyComplianceNotifications(
            setOf(studyId1, studyId2)
        )
    }
}
