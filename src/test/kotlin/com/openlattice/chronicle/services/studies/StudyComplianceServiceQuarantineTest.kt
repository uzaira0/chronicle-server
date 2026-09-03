package com.openlattice.chronicle.services.studies

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class StudyComplianceServiceQuarantineTest {
    private val storageResolver = mock<StorageResolver>()
    private val auditingManager = mock<AuditingManager>()
    private val hazelcast = mock<HazelcastInstance>()
    private val studies = mock<IMap<UUID, Study>>()
    private val dataSource = mock<HikariDataSource>()
    private val connection = mock<Connection>()
    private val uuidSqlArray = mock<java.sql.Array>()
    private val guardStatement = mock<PreparedStatement>()
    private val guardResultSet = mock<ResultSet>()

    @Before
    fun setUp() {
        whenever(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        whenever(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.createArrayOf(org.mockito.kotlin.eq("UUID"), any())).thenReturn(uuidSqlArray)
        whenever(connection.prepareStatement(StudyDeletionGuard.FIND_BLOCKED_STUDIES_SQL))
            .thenReturn(guardStatement)
        whenever(guardStatement.executeQuery()).thenReturn(guardResultSet)
    }

    @Test
    fun `requested compliance scan excludes quarantined studies before participant lookup`() {
        val study = quarantinedStudy()
        stubNotificationStudyQuery(study)
        stubBlockedStudy(study.id)
        val service = StudyComplianceService(storageResolver, auditingManager, hazelcast)

        val result = service.getNonCompliantStudies(listOf(study.id))

        assertTrue(result.isEmpty())
        verify(studies).evict(study.id)
    }

    @Test
    fun `all-study compliance scan excludes quarantined studies before participant lookup`() {
        val study = quarantinedStudy()
        stubNotificationStudyQuery(study)
        stubBlockedStudy(study.id)
        val service = StudyComplianceService(storageResolver, auditingManager, hazelcast)

        val result = service.getAllNonCompliantStudies()

        assertTrue(result.isEmpty())
        verify(studies).evict(study.id)
    }

    private fun quarantinedStudy(): Study =
        Study(
            studyId = UUID.randomUUID(),
            title = "quarantined",
            contact = "test@example.com",
        )

    private fun stubNotificationStudyQuery(study: Study) {
        whenever(studies.values(any<Predicate<UUID, Study>>())).thenReturn(listOf(study))
    }

    private fun stubBlockedStudy(studyId: UUID) {
        whenever(guardResultSet.next()).thenReturn(true, false)
        whenever(guardResultSet.getObject("study_id", UUID::class.java)).thenReturn(studyId)
    }
}
