package com.openlattice.chronicle.study

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import java.util.UUID
import java.time.OffsetDateTime

class StudyComplianceTests : ChronicleServerTests() {

    @Test
    fun testGetComplianceViolationsNewStudy() {
        val study = Study(title = "Default compliance settings", contact = "test@example.org")
        val studyId = clientUser1.studyApi.createStudy(study)
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)

        val device = TestDataFactory.androidDevice()
        val enrolled = enrollUsageFixture(studyId, participant.participantId, device)

        val violations = clientUser1.studyComplianceApi.getStudyComplianceViolations(studyId)
        Assert.assertTrue("An enrolled Android device without uploads violates the default upload policy",
            violations.getValue(studyId).getValue(participant.participantId).any { it.reason == ViolationReason.NO_RECENT_DATA_UPLOADED })

        // A phone back from 30 days offline drains old captures today: the server's receipt makes it compliant,
        // from the accepted response on, whether the upload still waits in the buffer or the mover stored it.
        val captured = OffsetDateTime.now().minusDays(30)
        val backlog = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId, 3)
            .map { (it as ChronicleUsageEvent).copy(timestamp = captured) }
        fun assertNoNoDataViolation(stage: String) {
            val afterBacklog = clientUser1.studyComplianceApi.getStudyComplianceViolations(studyId)
            Assert.assertTrue("an old capture received now is a recent upload $stage: $afterBacklog",
                afterBacklog[studyId]?.get(participant.participantId).orEmpty().none { it.reason == ViolationReason.NO_RECENT_DATA_UPLOADED })
        }
        hds.connection.use { mover ->
            mover.withTransaction(rollbackOnSuccess = true) {
                // Blocks drains at DeletionTableLockOrder.lockDrain; uploads and reads remain compatible.
                mover.createStatement().use { it.execute("LOCK TABLE public.upload_buffer IN SHARE UPDATE EXCLUSIVE MODE") }
                enrolled.mobileApi.uploadAndroidUsageEventDataV4(
                    studyId, participant.participantId, device.randomDeviceId, ChronicleData(backlog))
                mover.prepareStatement("SELECT count(*) FROM upload_buffer WHERE study_id = ? AND participant_id = ?").use {
                    it.setObject(1, studyId); it.setString(2, participant.participantId)
                    it.executeQuery().use { rows -> rows.next(); Assert.assertEquals("the upload is still buffered", 1, rows.getInt(1)) }
                }
                assertNoNoDataViolation("while buffered")
            }
        }
        RLSRequestContext.withSystemContext { testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage() }
        assertNoNoDataViolation("once stored")
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

        val before = listOf(studyId1, studyId2).associateWith(::complianceTriggerAudits)

        clientAdmin.testStudyComplianceApi.triggerStudyComplianceNotifications(
            setOf(studyId1, studyId2)
        )

        // The trigger is recorded once for each requested study, in the same transaction as the notifications.
        for (studyId in listOf(studyId1, studyId2)) {
            Assert.assertEquals(before.getValue(studyId) + 1, complianceTriggerAudits(studyId))
        }
    }

    /** The trigger's audit rows carry the study only in their ACL key (`AuditableEvent.study` is left unset). */
    private fun complianceTriggerAudits(studyId: UUID): Int = hds.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM audit WHERE acl_key = ? AND audit_event_type = ?").use {
            it.setString(1, AclKey(studyId).index)
            it.setString(2, AuditEventType.TRIGGER_STUDY_COMPLIANCE_NOTIFICATIONS.name)
            it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }
}
