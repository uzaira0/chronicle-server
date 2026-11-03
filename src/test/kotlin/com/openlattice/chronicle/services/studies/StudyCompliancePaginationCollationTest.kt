package com.openlattice.chronicle.services.studies

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.hazelcast.query.Predicate
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.openlattice.chronicle.controllers.StudyComplianceController
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.participants.ParticipantIds
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.ViolationReason
import com.openlattice.chronicle.util.validateParticipantId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** The SQL lookahead and Java selection must partition the same valid participant-id order. */
class StudyCompliancePaginationCollationTest {
    private val participantIds = listOf("a", "A", "B", "b", "_", "-", ".p", "a.b", "aa", "aA")
    private val pageSize = 2

    @Test
    fun cLocalePagesEveryValidParticipantExactlyOnceAndPreservesSparseHeaders() {
        assertPagination("--encoding=UTF8 --locale-provider=libc --locale=C", "c")
    }

    @Test
    fun englishIcuPagesEveryValidParticipantExactlyOnceAndPreservesSparseHeaders() {
        assertPagination("--encoding=UTF8 --locale-provider=icu --icu-locale=en-US", "i")
    }

    private fun assertPagination(initdbArgs: String, provider: String) {
        assertEquals(participantIds.size, participantIds.toSet().size)
        participantIds.forEach { id ->
            assertTrue("Fixture participant id must pass the product validator: $id", ParticipantIds.isValid(id))
            validateParticipantId(id)
        }
        ChronicleContractTestSchema.prodPostgresContainer("compliance_paging_$provider").apply {
            withEnv("POSTGRES_INITDB_ARGS", initdbArgs)
        }.use { postgres ->
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            val study = Study(UUID.randomUUID(), "Compliance pagination", contact = "pagination@example.invalid")
            postgres.createConnection("").use { connection ->
                seedStudy(connection, study)
                assertDatabaseOrdering(connection, study.id, provider)
            }
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 3
                minimumIdle = 0
            }).use { pool ->
                val storage = mock<StorageResolver>()
                val scoped = RLSDataSources.wrapUncachedRequestScoped(pool)
                whenever(storage.getPlatformStorage()).thenReturn(scoped)
                whenever(storage.getEventStorageWithFlavor()).thenReturn(scoped)
                val service = newService(storage, study)
                val context = RLSConnectionContext("compliance-pagination-researcher", setOf(study.id), false)
                RLSRequestContext.withContext(context) {
                    assertRuntimeScope(scoped)
                    assertEveryParticipantIsReturnedOnce(service, study.id)
                    postgres.createConnection("").use { seedRecentReceipts(it, study.id) }
                    assertSparseHeaders(service, storage, study.id)
                }
            }
        }
    }

    private fun seedStudy(connection: Connection, study: Study) {
        connection.prepareStatement("INSERT INTO studies (study_id, title, contact) VALUES (?, ?, ?)").use {
            it.setObject(1, study.id)
            it.setString(2, study.title)
            it.setString(3, study.contact)
            assertEquals(1, it.executeUpdate())
        }
        participantIds.forEach { seedEnrolledParticipant(connection, study.id, it) }
    }

    private fun assertDatabaseOrdering(connection: Connection, studyId: UUID, provider: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT current_setting('server_encoding'), datlocprovider::text
                FROM pg_database WHERE datname = current_database()""").use {
                assertTrue(it.next())
                assertEquals("UTF8", it.getString(1))
                assertEquals("The task-private database must use the requested locale provider", provider, it.getString(2))
            }
        }
        connection.prepareStatement(
            "SELECT participant_id FROM study_participants WHERE study_id = ? ORDER BY participant_id",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                val sqlOrder = buildList { while (rows.next()) add(rows.getString(1)) }
                assertEquals(participantIds.toSet(), sqlOrder.toSet())
                if (provider == "i") {
                    assertFalse("ICU fixture must exercise a default order different from Java: $sqlOrder",
                        participantIds.sorted() == sqlOrder)
                } else {
                    assertEquals(participantIds.sorted(), sqlOrder)
                }
            }
        }
    }

    private fun assertRuntimeScope(source: HikariDataSource) {
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_user, current_setting('app.is_admin'), " +
                    "current_setting('app.is_deletion_worker')").use {
                    assertTrue(it.next())
                    assertEquals("chronicle_app", it.getString(1))
                    assertEquals("false", it.getString(2))
                    assertEquals("false", it.getString(3))
                }
            }
        }
    }

    private fun newService(storage: StorageResolver, study: Study): StudyComplianceService {
        val hazelcast = mock<HazelcastInstance>()
        val studies = mock<IMap<UUID, Study>>()
        whenever(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        whenever(studies.values(any<Predicate<UUID, Study>>())).thenReturn(listOf(study))
        return StudyComplianceService(storage, mock<AuditingManager>(), hazelcast)
    }

    private fun assertEveryParticipantIsReturnedOnce(service: StudyComplianceService, studyId: UUID) {
        val returned = mutableListOf<String>()
        val offsets = mutableListOf<Int>()
        var offset = 0
        while (true) {
            assertTrue("Continuation must stop within the seeded participant count", offsets.size < participantIds.size)
            offsets.add(offset)
            val page = service.getStudyCompliancePage(studyId, pageSize, offset)
            val violations = page.violations.getValue(studyId)
            assertTrue(violations.values.flatten().all { it.reason == ViolationReason.NOT_ENROLLED })
            returned.addAll(violations.keys.sorted())
            val next = page.nextOffset ?: break
            assertEquals(offset.toLong() + pageSize, next)
            offset = Math.toIntExact(next)
        }
        assertEquals("Every enrolled participant must occur exactly once across the real service pages",
            participantIds.sorted(), returned)
        assertEquals((participantIds.indices step pageSize).toList(), offsets)
    }

    private fun seedRecentReceipts(connection: Connection, studyId: UUID) {
        participantIds.forEach { participantId ->
            connection.prepareStatement("""INSERT INTO devices
                (study_id, random_device_id, participant_id, device_type, source_device)
                VALUES (?, ?, ?, 'Android', '{}'::jsonb)""").use {
                it.setObject(1, studyId)
                it.setObject(2, UUID.randomUUID())
                it.setString(3, participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""INSERT INTO chronicle_usage_events
                (study_id, participant_id, app_package_name, interaction_type, event_type,
                 event_timestamp, timezone, username, application_label)
                VALUES (?, ?, 'org.example.pagination', 'Move to Foreground', 1, now(), 'UTC', '', 'Pagination')""").use {
                it.setObject(1, studyId)
                it.setString(2, participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun assertSparseHeaders(service: StudyComplianceService, storage: StorageResolver, studyId: UUID) {
        val authorization = mock<AuthorizationManager>()
        whenever(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
        val studyService = mock<StudyService>()
        whenever(studyService.getStudyId(studyId)).thenReturn(studyId)
        val controller = StudyComplianceController(mock<StudyLimitsManager>(), studyService, service,
            mock<StudyComplianceHazelcastTask>(), storage, mock<AuditingManager>(), authorization,
            AuditService(RecordingAuditingManager()))
        TestSecurityUtils.setupSecurityContext("compliance-pagination-researcher", admin = false)
        try {
            (participantIds.indices step pageSize).forEach { offset ->
                val response = MockHttpServletResponse()
                val violations = TestSecurityUtils.withServletRequest(MockHttpServletRequest(), response) {
                    controller.getStudyComplianceViolations(studyId, pageSize, offset)
                }
                assertTrue("Fresh research receipts must leave a sparse, empty compliance page",
                    violations.values.all { it.isEmpty() })
                val expectedNext = (offset + pageSize).takeIf { it < participantIds.size }?.toString()
                assertEquals("Continuation follows source participants even when violations are empty",
                    expectedNext, response.getHeader("X-Next-Offset"))
            }
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }
}
