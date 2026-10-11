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

    @Test
    fun acceptedSensorOnlyDataCountsAsRecentAndroidActivity() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        val settings = mapOf<com.openlattice.chronicle.study.StudySettingType, com.openlattice.chronicle.study.StudySetting>(
            com.openlattice.chronicle.study.StudySettingType.Notifications to com.openlattice.chronicle.notifications.StudyNotificationSettings("",
                "sensor study"),
            com.openlattice.chronicle.study.StudySettingType.DataCollection to com.openlattice.chronicle.collection.AndroidDataCollectionSetting(
                modules = mapOf(com.openlattice.chronicle.collection.CollectionModuleId.USAGE_EVENTS to
                    com.openlattice.chronicle.collection.CollectionModuleSetting(enabled = false)),
            ),
        )
        pg.createConnection("").use { connection ->
            connection.prepareStatement("""INSERT INTO android_sensor_data
                (study_id, participant_id, sample_id, sensor_type, sample_timestamp, timezone, device_id)
                VALUES (?, 'sensor-participant', ?, 'accelerometer', now(), 'UTC', ?)""").use { statement ->
                statement.setObject(1, study); statement.setObject(2, UUID.randomUUID()); statement.setObject(3, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(StudyComplianceService.androidUploadsSql(mapOf(study to settings),
                    com.geekbeast.configuration.postgres.PostgresFlavor.VANILLA)).use { rows ->
                    assertTrue(rows.next())
                    org.junit.Assert.assertEquals("sensor-participant", rows.getString("participant_id"))
                    org.junit.Assert.assertFalse(rows.next())
                }
            }
        }
    }
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
        stubStudyQuery(study)
        stubBlockedStudy(study.id)
        val service = StudyComplianceService(storageResolver, auditingManager, hazelcast)

        val result = service.getNonCompliantStudies(listOf(study.id))

        assertTrue(result.isEmpty())
        verify(studies).evict(study.id)
    }

    @Test
    fun `all-study compliance scan excludes quarantined studies before participant lookup`() {
        val study = quarantinedStudy()
        stubStudyQuery(study)
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

    private fun stubStudyQuery(study: Study) {
        whenever(studies.values(any<Predicate<UUID, Study>>())).thenReturn(listOf(study))
        whenever(studies.values).thenReturn(mutableListOf(study))
    }

    private fun stubBlockedStudy(studyId: UUID) {
        whenever(guardResultSet.next()).thenReturn(true, false)
        whenever(guardResultSet.getObject("study_id", UUID::class.java)).thenReturn(studyId)
    }
}
