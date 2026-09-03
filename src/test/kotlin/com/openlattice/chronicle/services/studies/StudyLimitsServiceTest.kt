package com.openlattice.chronicle.services.studies

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kAny
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.controllers.kAnyString
import com.openlattice.chronicle.controllers.kAnyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.*

class StudyLimitsServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var hazelcast: HazelcastInstance
    private lateinit var studyLimitsMap: IMap<UUID, StudyLimits>
    private lateinit var service: StudyLimitsService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Suppress("UNCHECKED_CAST")
    @Before
    fun setUp() {
        storageResolver = Mockito.mock(StorageResolver::class.java)
        hazelcast = Mockito.mock(HazelcastInstance::class.java)
        studyLimitsMap = Mockito.mock(IMap::class.java) as IMap<UUID, StudyLimits>
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(hazelcast.getMap<UUID, StudyLimits>(kAnyString())).thenReturn(studyLimitsMap)
        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.connection).thenReturn(mockConnection)
        val mockArray = Mockito.mock(java.sql.Array::class.java)
        `when`(mockConnection.createArrayOf(kAnyString(), kAny())).thenReturn(mockArray)

        service = StudyLimitsService(storageResolver, hazelcast)
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    // --- getStudyLimits tests ---

    @Test
    fun testGetStudyLimitsReturnsFromCache() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(participantLimit = 100)
        `when`(studyLimitsMap.get(studyId)).thenReturn(limits)

        val result = service.getStudyLimits(studyId)

        assertEquals(100, result.participantLimit)
        verify(studyLimitsMap).get(studyId)
    }

    @Test
    fun testGetStudyLimitsDefaultValues() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits()
        `when`(studyLimitsMap.get(studyId)).thenReturn(limits)

        val result = service.getStudyLimits(studyId)

        assertEquals(25, result.participantLimit)
        assertEquals(StudyDuration(years = 1), result.studyDuration)
        assertEquals(StudyDuration(days = 90), result.dataRetentionDuration)
    }

    // --- setStudyLimits tests ---

    @Test
    fun testSetStudyLimitsWritesToCache() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(participantLimit = 200)

        service.setStudyLimits(studyId, limits)

        verify(studyLimitsMap).set(studyId, limits)
    }

    @Test
    fun testSetStudyLimitsWithCustomDuration() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(
            studyDuration = StudyDuration(years = 5, months = 6),
            participantLimit = 500
        )

        service.setStudyLimits(studyId, limits)

        verify(studyLimitsMap).set(studyId, limits)
    }

    // --- getEnrollmentCapacity tests ---

    @Test
    fun testGetEnrollmentCapacityReturnsLimit() {
        val studyId = UUID.randomUUID()
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits(participantLimit = 50))

        val result = service.getEnrollmentCapacity(studyId)

        assertEquals(50, result)
    }

    @Test
    fun testGetEnrollmentCapacityReturnsDefault() {
        val studyId = UUID.randomUUID()
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits())

        val result = service.getEnrollmentCapacity(studyId)

        assertEquals(25, result)
    }

    // --- getStudyDuration tests ---

    @Test
    fun testGetStudyDurationReturnsFromCache() {
        val studyId = UUID.randomUUID()
        val duration = StudyDuration(years = 3, months = 2, days = 15)
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits(studyDuration = duration))

        val result = service.getStudyDuration(studyId)

        assertEquals(3, result.years.toInt())
        assertEquals(2, result.months.toInt())
        assertEquals(15, result.days.toInt())
    }

    // --- getDataRetentionPeriod tests ---

    @Test
    fun testGetDataRetentionPeriodReturnsFromCache() {
        val studyId = UUID.randomUUID()
        val retention = StudyDuration(days = 180)
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits(dataRetentionDuration = retention))

        val result = service.getDataRetentionPeriod(studyId)

        assertEquals(180, result.days.toInt())
    }

    // --- getStudyFeatures tests ---

    @Test
    fun testGetStudyFeaturesReturnsFromCache() {
        val studyId = UUID.randomUUID()
        val features = EnumSet.of(StudyFeature.CHRONICLE, StudyFeature.CHRONICLE_SURVEYS)
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits(features = features))

        val result = service.getStudyFeatures(studyId)

        assertTrue(result.contains(StudyFeature.CHRONICLE))
        assertTrue(result.contains(StudyFeature.CHRONICLE_SURVEYS))
        assertEquals(2, result.size)
    }

    @Test
    fun testGetStudyFeaturesReturnsDefaultFeatures() {
        val studyId = UUID.randomUUID()
        `when`(studyLimitsMap.get(studyId)).thenReturn(StudyLimits())

        val result = service.getStudyFeatures(studyId)

        assertTrue(result.contains(StudyFeature.CHRONICLE))
        assertTrue(result.contains(StudyFeature.CHRONICLE_DATA_COLLECTION))
        assertTrue(result.contains(StudyFeature.CHRONICLE_SURVEYS))
    }

    // --- initializeStudyLimits tests ---

    @Test
    fun testInitializeStudyLimitsExecutesInsert() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(participantLimit = 75)
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.initializeStudyLimits(mockConnection, studyId, limits)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setInt(2, 75)
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testInitializeStudyLimitsWithDefaultLimits() {
        val studyId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.initializeStudyLimits(mockConnection, studyId)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setInt(2, 25) // default participant limit
    }

    // --- countStudyParticipants tests ---

    @Test
    fun testCountStudyParticipantsForSingleStudy() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true, false)
        `when`(mockRs.getObject(kAnyString(), kEq(UUID::class.java))).thenReturn(studyId)
        `when`(mockRs.getLong(kAnyInt())).thenReturn(10L)
        `when`(mockRs.getLong(kAnyString())).thenReturn(10L)

        // The countStudyParticipants(UUID) method calls countStudyParticipants(Connection, Set<UUID>)
        // which processes the ResultSet
        val result = service.countStudyParticipants(mockConnection, setOf(studyId))

        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals(10L, result[studyId])
    }

    @Test
    fun testCountStudyParticipantsEmptySet() {
        `when`(mockRs.next()).thenReturn(false)

        val result = service.countStudyParticipants(mockConnection, setOf(UUID.randomUUID()))

        assertTrue(result.isEmpty())
    }

    // --- setEnrollmentCapacity tests ---

    @Test
    fun testSetEnrollmentCapacityUpdatesDb() {
        val studyId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.setEnrollmentCapacity(studyId, 100)

        verify(mockPs).setInt(1, 100)
        verify(mockPs).setObject(2, studyId)
        verify(mockPs).executeUpdate()
        verify(studyLimitsMap).loadAll(setOf(studyId), true)
    }

    // --- lockStudyForEnrollments test ---

    @Test
    fun testLockStudyForEnrollmentsExecutesLockAndReturnsCapacity() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getInt("participant_limit")).thenReturn(25)

        val capacity = service.lockStudyForEnrollments(mockConnection, studyId)

        assertEquals(25, capacity)
        verify(mockConnection).prepareStatement(kAnyString())
        verify(mockPs).setObject(1, studyId)
        verify(mockPs).executeQuery()
    }
}
