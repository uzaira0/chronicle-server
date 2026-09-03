package com.openlattice.chronicle.study

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test

class StudyComplianceTests : ChronicleServerTests() {

    @Test
    fun testGetComplianceViolationsNewStudy() {
        val study = TestDataFactory.study()
        val studyId = clientUser1.studyApi.createStudy(study)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)

        val violations = clientUser1.studyComplianceApi.getStudyComplianceViolations(studyId)
        Assert.assertNotNull("Violations map should not be null", violations)
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
