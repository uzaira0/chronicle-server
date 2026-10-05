package com.openlattice.chronicle.timeusediary

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class TimeUseDiaryTests : ChronicleServerTests() {

    private fun createStudyWithParticipant(): Pair<UUID, String> {
        val study = TestDataFactory.study()
        val studyId = clientUser1.studyApi.createStudy(study)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        return studyId to participant.participantId
    }

    @Test
    fun testSubmitTimeUseDiary() {
        val (studyId, participantId) = createStudyWithParticipant()
        val responses = TestDataFactory.timeUseDiaryResponses()
        val submissionId = clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(studyId, participantId, responses)
        Assert.assertNotNull("Submission should return a non-null UUID", submissionId)
    }

    @Test
    fun testGetParticipantSubmissionIds() {
        val (studyId, participantId) = createStudyWithParticipant()
        val responses = TestDataFactory.timeUseDiaryResponses()
        clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(studyId, participantId, responses)

        val start = OffsetDateTime.now().minusDays(1)
        val end = OffsetDateTime.now().plusDays(1)
        val submissions = clientUser1.timeUseDiaryApi.getParticipantTUDSubmissionIdsByDate(
            studyId, participantId, start, end
        )
        Assert.assertTrue("Should have at least one submission", submissions.isNotEmpty())
    }

    @Test
    fun testGetStudySubmissionIds() {
        val study = TestDataFactory.study()
        val studyId = clientUser1.studyApi.createStudy(study)

        val p1 = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        val p2 = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, p1)
        clientUser1.studyApi.registerParticipant(studyId, p2)

        clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(studyId, p1.participantId, TestDataFactory.timeUseDiaryResponses())
        clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(studyId, p2.participantId, TestDataFactory.timeUseDiaryResponses())

        val start = OffsetDateTime.now().minusDays(1)
        val end = OffsetDateTime.now().plusDays(1)
        val submissions = clientUser1.timeUseDiaryApi.getStudyTUDSubmissionIdsByDate(studyId, start, end)
        Assert.assertTrue("Should have submissions from both participants", submissions.isNotEmpty())

        val allIds = submissions.values.flatMap { it }
        Assert.assertTrue("Should have at least 2 submission IDs", allIds.size >= 2)
    }

    @Test
    fun testSubmitMultipleDiaries() {
        val (studyId, participantId) = createStudyWithParticipant()

        val ids = (0 until 3).map {
            clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(
                studyId, participantId, TestDataFactory.timeUseDiaryResponses()
            )
        }

        Assert.assertEquals("Should have 3 submission IDs", 3, ids.size)
        Assert.assertEquals("All IDs should be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun testGetSubmissionsEmptyDateRange() {
        val (studyId, participantId) = createStudyWithParticipant()

        val start = OffsetDateTime.now().minusDays(100)
        val end = OffsetDateTime.now().minusDays(90)
        val submissions = clientUser1.timeUseDiaryApi.getParticipantTUDSubmissionIdsByDate(
            studyId, participantId, start, end
        )
        Assert.assertTrue("Should return empty for past date range", submissions.isEmpty())
    }

    /** A group lists only what its download returns: the range end is exclusive for both. */
    @Test
    fun testSubmissionAtRangeEndIsNotListed() {
        val (studyId, participantId) = createStudyWithParticipant()
        val submissionId = clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(
            studyId, participantId, TestDataFactory.timeUseDiaryResponses()
        )
        val end = OffsetDateTime.parse("2026-10-02T00:00:00Z")
        hds.connection.use { connection ->
            connection.prepareStatement("UPDATE time_use_diary_submissions SET submission_date = ? WHERE submission_id = ?").use {
                it.setObject(1, end)
                it.setObject(2, submissionId)
                Assert.assertEquals(1, it.executeUpdate())
            }
        }

        val listed = clientUser1.timeUseDiaryApi.getStudyTUDSubmissionIdsByDate(studyId, end.minusDays(1), end)
        val nextDay = clientUser1.timeUseDiaryApi.getStudyTUDSubmissionIdsByDate(studyId, end, end.plusDays(1))

        Assert.assertTrue(listed.values.none { submissionId in it })
        Assert.assertTrue(nextDay.values.any { submissionId in it })
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testSubmitInvalidStudy() {
        clientUser1.testTimeUseDiaryApi.submitTimeUseDiary(
            UUID.randomUUID(), "nonexistent", TestDataFactory.timeUseDiaryResponses()
        )
    }
}
