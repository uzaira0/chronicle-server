package com.openlattice.chronicle.study

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import java.util.UUID

class StudyLimitsTests : ChronicleServerTests() {

    @Test
    fun testSetAndGetStudyLimits() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val limits = TestDataFactory.studyLimits()
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, limits)
        val actual = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(limits.studyDuration, actual.studyDuration)
        Assert.assertEquals(limits.dataRetentionDuration, actual.dataRetentionDuration)
        Assert.assertEquals(limits.participantLimit, actual.participantLimit)
    }

    @Test
    fun testGetDefaultStudyLimits() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val actual = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(25, actual.participantLimit)
    }

    @Test
    fun testSetStudyLimitsUpdatesExisting() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val limits1 = StudyLimits(
            studyDuration = StudyDuration(years = 1),
            dataRetentionDuration = StudyDuration(days = 90),
            participantLimit = 30
        )
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, limits1)
        val actual1 = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(30, actual1.participantLimit)

        val limits2 = StudyLimits(
            studyDuration = StudyDuration(years = 3),
            dataRetentionDuration = StudyDuration(days = 365),
            participantLimit = 100
        )
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, limits2)
        val actual2 = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(100, actual2.participantLimit)
        Assert.assertEquals(StudyDuration(years = 3), actual2.studyDuration)
    }

    @Test(expected = RhizomeRetrofitCallException::class)
    fun testGetStudyLimitsForInvalidStudy() {
        clientUser1.studyLimitsApi.getStudyLimits(UUID.randomUUID())
    }
}
