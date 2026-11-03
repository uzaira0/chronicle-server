package com.openlattice.chronicle.study

import org.junit.Assert.assertThrows

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import java.time.OffsetDateTime
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
    }

    @Test
    fun testGetDefaultStudyLimits() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val actual = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertNull(actual.studyDuration)
    }

    @Test
    fun testSetStudyLimitsUpdatesExisting() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val limits1 = StudyLimits(
            studyDuration = StudyDuration(years = 1),
            dataRetentionDuration = StudyDuration(days = 90),
        )
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, limits1)
        val actual1 = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(StudyDuration(years = 1), actual1.studyDuration)

        val limits2 = StudyLimits(
            studyDuration = StudyDuration(years = 3),
            dataRetentionDuration = StudyDuration(days = 365),
        )
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, limits2)
        val actual2 = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        Assert.assertEquals(StudyDuration(years = 3), actual2.studyDuration)
    }

    /** A limits write is one row: a commit replaces duration, end and expiry together; a rollback keeps the prior ones. */
    @Test
    fun limitsCommitTogetherAndARolledBackWriteKeepsThePriorLimits() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val now = OffsetDateTime.now().withNano(0)
        val committed = StudyLimits(studyDuration = StudyDuration(days = 30), dataRetentionDuration = StudyDuration(days = 400),
            studyEnds = now.plusDays(30), studyDataExpires = now.plusDays(430))
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, committed)
        assertLimits(committed, studyId)

        val storage = testServer.context.getBean(StorageResolver::class.java)
        val limits = testServer.context.getBean(StudyLimitsManager::class.java)
        RLSRequestContext.withSystemContext {
            storage.getPlatformStorage().connection.use { connection ->
                connection.autoCommit = false
                assertThrows(IllegalStateException::class.java) {
                    PinnedPlatformConnection.committing(storage.getPlatformStorage(), connection) {
                        limits.setStudyLimits(connection, studyId, StudyLimits(studyDuration = StudyDuration(days = 1),
                            dataRetentionDuration = StudyDuration(days = 2), studyEnds = now.minusDays(1), studyDataExpires = now))
                        error("the enclosing operation fails after its limits write")
                    }
                }
                connection.rollback()
            }
        }
        assertLimits(committed, studyId)
    }

    /** The API read and the stored row both carry every field of [expected]. */
    private fun assertLimits(expected: StudyLimits, studyId: UUID) {
        val served = clientUser1.studyLimitsApi.getStudyLimits(studyId)
        val stored = hds.connection.use { connection ->
            connection.prepareStatement("SELECT study_ends, data_expires FROM study_limits WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeQuery().use { rows ->
                    Assert.assertTrue(rows.next())
                    listOf(rows.getObject(1, OffsetDateTime::class.java), rows.getObject(2, OffsetDateTime::class.java))
                }
            }
        }
        Assert.assertEquals(expected.studyDuration, served.studyDuration)
        Assert.assertEquals(expected.dataRetentionDuration, served.dataRetentionDuration)
        val expectedInstants = listOf(expected.studyEnds, expected.studyDataExpires).map { it?.toInstant() }
        Assert.assertEquals(expectedInstants, listOf(served.studyEnds, served.studyDataExpires).map { it?.toInstant() })
        Assert.assertEquals(expectedInstants, stored.map { it?.toInstant() })
    }

    @Test
    fun testGetStudyLimitsForInvalidStudy() {
        assertThrows(RhizomeRetrofitCallException::class.java) {
            clientUser1.studyLimitsApi.getStudyLimits(UUID.randomUUID())
        }
    }
}
