package com.openlattice.chronicle.services.studies

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSConnectionSql
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.openlattice.chronicle.notifications.StudyNotificationSettings
import com.openlattice.chronicle.sources.SourceDeviceType
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
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
    fun everyParticipantReceiptStoreHasAnExplicitComplianceDecision() {
        val columns = setOf("study_id", "participant_id", "uploaded_at")
        val candidates = listOf(
            com.openlattice.chronicle.storage.ChroniclePostgresTables::class.java,
            com.openlattice.chronicle.storage.PostgresEventTables::class.java,
        ).flatMap { owner -> owner.fields.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                com.geekbeast.postgres.PostgresTableDefinition::class.java.isAssignableFrom(it.type)
        }.map { it.get(null) as com.geekbeast.postgres.PostgresTableDefinition } }
            .filter { table -> table.columns.map { it.name }.containsAll(columns) }.map { it.name }.toSet()
        val settings = mapOf<StudySettingType, StudySetting>(
            StudySettingType.Notifications to StudyNotificationSettings("", "inventory"),
        )
        val receiptTables = listOf(SourceDeviceType.Android, SourceDeviceType.Ios).flatMap { platform ->
            Regex("FROM ([a-z_]+)").findAll(StudyComplianceService.recentResearchUploadsSql(
                platform, mapOf(UUID.randomUUID() to settings))).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("Receipt inventory must include temporary ingest storage", "upload_buffer" in candidates)
        assertEquals("Every new participant receipt store needs a compliance decision", candidates, receiptTables)
    }

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
                (study_id, participant_id, sample_id, sensor_type, sample_timestamp, timezone, random_device_id)
                VALUES (?, 'sensor-participant', ?, 'accelerometer', now(), 'UTC', ?)""").use { statement ->
                statement.setObject(1, study); statement.setObject(2, UUID.randomUUID()); statement.setObject(3, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(StudyComplianceService.recentResearchUploadsSql(
                    com.openlattice.chronicle.sources.SourceDeviceType.Android, mapOf(study to settings))).use { rows ->
                    assertTrue(rows.next())
                    org.junit.Assert.assertEquals("sensor-participant", rows.getString("participant_id"))
                    org.junit.Assert.assertFalse(rows.next())
                }
            }
        }
    }

    /**
     * A phone offline for two days drains its backlog today: compliance follows when the server received
     * research rows, on every platform and research store, not when the phone captured them.
     */
    @Test
    fun researchDataReceivedTodayIsCompliantWhateverItsCaptureTime() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val inserts = listOf(
            SourceDeviceType.Android to """INSERT INTO chronicle_usage_events
                (study_id, participant_id, app_package_name, interaction_type, event_type, event_timestamp, timezone, username,
                 application_label)
                VALUES (?, ?, 'org.example.drained', 'Move to Foreground', 1, now() - interval '2 days', 'UTC', '', 'Drained')""",
            SourceDeviceType.Android to """INSERT INTO android_sensor_data
                (study_id, participant_id, sample_id, sensor_type, sample_timestamp, timezone, random_device_id)
                VALUES (?::uuid, ?, gen_random_uuid(), 'accelerometer', now() - interval '2 days', 'UTC', gen_random_uuid())""",
            SourceDeviceType.Ios to """INSERT INTO ios_screen_time_usage
                (study_id, participant_id, random_device_id, record_id, row_kind, captured_at, observation_start,
                 observation_end, timezone, app_name, duration_seconds)
                VALUES (?::uuid, ?, gen_random_uuid(), gen_random_uuid(), 'application',
                 now() - interval '2 days', now() - interval '2 days 1 hour', now() - interval '2 days', 'UTC', 'Drained', 60)""",
        ) + UploadType.entries.filter { it.countsAsResearchReceipt }.map { type ->
            // Accepted and acknowledged, not yet moved to its research store.
            SourceDeviceType.Android to bufferedUpload(type.name, "now()")
        }
        pg.createConnection("").use { connection ->
            // Rolled back: a committed buffer row would reach the movers other tests run on this shared schema.
            connection.withTransaction(rollbackOnSuccess = true) {
                inserts.forEach { (platform, insert) ->
                    val study = UUID.randomUUID()
                    connection.prepareStatement(insert).use { statement ->
                        statement.setString(1, study.toString()); statement.setString(2, "drained-today")
                        assertEquals(1, statement.executeUpdate())
                    }
                    assertEquals(insert, listOf("drained-today"), recentParticipants(connection, platform, study))
                }
                // Captured just now but received two days ago is not recent research data.
                val stale = UUID.randomUUID()
                connection.prepareStatement("""INSERT INTO chronicle_usage_events
                    (study_id, participant_id, app_package_name, interaction_type, event_type, event_timestamp, timezone, username,
                     application_label, uploaded_at)
                    VALUES (?, 'received-earlier', 'org.example.stale', 'Move to Foreground', 1, now(), 'UTC', '', 'Stale',
                     now() - interval '2 days')""").use {
                    it.setString(1, stale.toString()); it.executeUpdate()
                }
                assertEquals(emptyList<String>(), recentParticipants(connection, SourceDeviceType.Android, stale))
                // A buffered upload received two days ago, or one the sensor mover quarantined, is not recent research data.
                for (insert in listOf(bufferedUpload(UploadType.Android.name, "now() - interval '2 days'"),
                    bufferedUpload("AndroidSensorRejected", "now()"))) {
                    val study = UUID.randomUUID()
                    connection.prepareStatement(insert).use {
                        it.setString(1, study.toString()); it.setString(2, "not-recent"); assertEquals(1, it.executeUpdate())
                    }
                    assertEquals(insert, emptyList<String>(), recentParticipants(connection, SourceDeviceType.Android, study))
                }
            }
        }
    }

    @Test
    fun unknownBufferStatesAreRefusedAtTheStorageBoundary() {
        com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.withTransaction(rollbackOnSuccess = true) {
                val rejected = org.junit.Assert.assertThrows(java.sql.SQLException::class.java) {
                    connection.prepareStatement(bufferedUpload("Unknown", "now()")).use {
                        it.setString(1, UUID.randomUUID().toString()); it.setString(2, "invalid-state"); it.executeUpdate()
                    }
                }
                assertEquals("23514", rejected.sqlState)
            }
        }
    }

    @Test
    fun bufferedUploadsRespectResearcherAndScheduledComplianceContexts() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val studies = List(2) { UUID.randomUUID() }
        pg.createConnection("").use { connection ->
            connection.withTransaction(rollbackOnSuccess = true) {
                studies.forEach { study ->
                    connection.prepareStatement(bufferedUpload(UploadType.Android.name, "now()")).use {
                        it.setString(1, study.toString()); it.setString(2, "buffered"); assertEquals(1, it.executeUpdate())
                    }
                }
                RLSConnectionSql.applyContext(connection, RLSConnectionContext("researcher", setOf(studies.first()), false))
                assertEquals(listOf("buffered"), recentParticipants(connection, SourceDeviceType.Android, studies.first()))
                assertEquals(emptyList<String>(), recentParticipants(connection, SourceDeviceType.Android, studies.last()))
                RLSConnectionSql.applyContext(connection, RLSRequestContext.systemContext)
                studies.forEach { study ->
                    assertEquals(listOf("buffered"), recentParticipants(connection, SourceDeviceType.Android, study))
                }
            }
        }
    }

    @Test
    fun uncommittedAndRolledBackUploadsNeverCountAsReceived() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        pg.createConnection("").use { writer ->
            writer.withTransaction(rollbackOnSuccess = true) {
                writer.prepareStatement(bufferedUpload(UploadType.Android.name, "now()")).use {
                    it.setString(1, study.toString()); it.setString(2, "refused"); assertEquals(1, it.executeUpdate())
                }
                pg.createConnection("").use { reader ->
                    assertEquals(emptyList<String>(), recentParticipants(reader, SourceDeviceType.Android, study))
                    writer.rollback()
                    assertEquals(emptyList<String>(), recentParticipants(reader, SourceDeviceType.Android, study))
                }
            }
        }
    }

    @Test
    fun bufferedReceiptQueriesUseIndexesPastAnOldBacklog() {
        val pg = com.openlattice.chronicle.contract.ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        pg.createConnection("").use { connection ->
            connection.withTransaction(rollbackOnSuccess = true) {
                connection.prepareStatement(bufferedUpload(UploadType.Android.name, "now() - interval '30 days'", 20000)).use {
                    it.setString(1, study.toString()); it.setString(2, "old"); assertEquals(20000, it.executeUpdate())
                }
                connection.createStatement().use { it.execute("ANALYZE upload_buffer") }
                connection.prepareStatement("""INSERT INTO chronicle_usage_events
                    (study_id, participant_id, app_package_name, interaction_type, event_type, event_timestamp,
                     timezone, username, application_label, uploaded_at)
                    SELECT ?, 'old', 'org.example.index', 'Move to Foreground', 1,
                        now() - n * interval '1 second', 'UTC', '', 'Index', now() - interval '30 days'
                    FROM generate_series(1, 20000) n""").use {
                    it.setString(1, study.toString()); assertEquals(20000, it.executeUpdate())
                }
                connection.createStatement().use { it.execute("ANALYZE chronicle_usage_events") }
                val settings = mapOf<StudySettingType, StudySetting>(StudySettingType.Notifications to StudyNotificationSettings("", "indexed"))
                for (context in listOf(RLSConnectionContext("researcher", setOf(study), false), RLSRequestContext.systemContext)) {
                    RLSConnectionSql.applyContext(connection, context)
                    val plan = connection.createStatement().use { statement ->
                        statement.executeQuery("EXPLAIN " + StudyComplianceService.recentResearchUploadsSql(
                            SourceDeviceType.Android, mapOf(study to settings))).use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.joinToString("\n")
                        }
                    }
                    assertFalse(plan, Regex("Seq Scan on (?:public\\.)?upload_buffer\\b").containsMatchIn(plan))
                    assertTrue(plan, "upload_buffer_type_uploaded_at_idx" in plan || "upload_buffer_study_id_idx" in plan)
                    assertTrue(plan, "chronicle_usage_events_study_uploaded_at_idx" in plan)
                }
            }
        }
    }

    private fun bufferedUpload(uploadType: String, uploadedAt: String, copies: Int = 1) = """INSERT INTO upload_buffer
        (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
        SELECT ?::uuid, ?, '[]'::jsonb, $uploadedAt, '$uploadType', gen_random_uuid() FROM generate_series(1, $copies)"""

    private fun recentParticipants(connection: Connection, platform: SourceDeviceType, study: UUID): List<String> {
        val settings = mapOf<StudySettingType, StudySetting>(
            StudySettingType.Notifications to StudyNotificationSettings("", "receipt study"),
        )
        return connection.createStatement().use { statement ->
            statement.executeQuery(StudyComplianceService.recentResearchUploadsSql(platform, mapOf(study to settings))).use { rows ->
                generateSequence { if (rows.next()) rows.getString("participant_id") else null }.toList()
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
