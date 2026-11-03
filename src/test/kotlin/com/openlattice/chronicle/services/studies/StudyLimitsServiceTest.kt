package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyDuration
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
        val limits = StudyLimits(studyDuration = StudyDuration(days = 100))
        `when`(studyLimitsMap.get(studyId)).thenReturn(limits)

        val result = service.getStudyLimits(studyId)

        assertEquals(StudyDuration(days = 100), result.studyDuration)
        verify(studyLimitsMap).get(studyId)
    }

    @Test
    fun testGetStudyLimitsDefaultValues() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits()
        `when`(studyLimitsMap.get(studyId)).thenReturn(limits)

        val result = service.getStudyLimits(studyId)

        assertEquals(null, result.studyDuration)
        assertEquals(null, result.studyEnds)
        assertEquals(StudyDuration(days = 90), result.dataRetentionDuration)
    }

    // --- setStudyLimits tests ---

    @Test
    fun testSetStudyLimitsWritesThroughTheTransaction() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(studyDuration = StudyDuration(days = 200))

        com.openlattice.chronicle.storage.PinnedPlatformConnection.committing(mockHds, mockConnection) {
            service.setStudyLimits(mockConnection, studyId, limits)
            Mockito.verify(studyLimitsMap, Mockito.never()).evict(studyId)
        }

        verify(mockPs).executeUpdate()
        Mockito.verify(studyLimitsMap, Mockito.never()).set(studyId, limits)
    }

    @Test
    fun testSetStudyLimitsWithCustomDuration() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(
            studyDuration = StudyDuration(years = 5, months = 6),
        )

        com.openlattice.chronicle.storage.PinnedPlatformConnection.committing(mockHds, mockConnection) {
            service.setStudyLimits(mockConnection, studyId, limits)
            Mockito.verify(studyLimitsMap, Mockito.never()).evict(studyId)
        }

        verify(studyLimitsMap).evict(studyId)
    }

    // --- initializeStudyLimits tests ---

    @Test
    fun testInitializeStudyLimitsExecutesInsert() {
        val studyId = UUID.randomUUID()
        val limits = StudyLimits(studyDuration = StudyDuration(days = 75))
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.initializeStudyLimits(mockConnection, studyId, limits)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setString(2, ObjectMappers.newJsonMapper().writeValueAsString(StudyDuration(days = 75)))
        verify(mockPs).executeUpdate()
    }

    @Test
    fun testInitializeStudyLimitsWithDefaultLimits() {
        val studyId = UUID.randomUUID()
        `when`(mockPs.executeUpdate()).thenReturn(1)

        service.initializeStudyLimits(mockConnection, studyId)

        verify(mockPs).setObject(1, studyId)
        verify(mockPs).setString(2, null) // no end set by the researcher
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
}
