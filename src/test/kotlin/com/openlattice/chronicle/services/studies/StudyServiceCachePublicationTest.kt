package com.openlattice.chronicle.services.studies

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.services.candidates.CandidateManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.UUID

class StudyServiceCachePublicationTest {
    private val storageResolver = mock<StorageResolver>()
    private val authorizationService = mock<AuthorizationManager>()
    private val idGenerationService = mock<HazelcastIdGenerationService>()
    private val auditingManager = mock<AuditingManager>()
    private val hazelcast = mock<HazelcastInstance>()
    private val studies = mock<IMap<UUID, Study>>()
    private val dataSource = mock<HikariDataSource>()
    private val connection = mock<Connection>()
    private val uuidSqlArray = mock<java.sql.Array>()

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
        whenever(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        whenever(storageResolver.getDefaultPlatformStorage())
            .thenReturn(PostgresFlavor.VANILLA to dataSource)
        whenever(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.createArrayOf(eq("UUID"), org.mockito.kotlin.any())).thenReturn(uuidSqlArray)
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun `create commits before warming owner and admin ACEs`() {
        val studyId = UUID.randomUUID()
        val study = Study(title = "cache publication", contact = "test@example.com")
        whenever(idGenerationService.getNextId()).thenReturn(studyId)
        val service = spy(newService())
        doNothing().whenever(service).createStudy(same(connection), same(study))

        val actualId = service.createStudy(study)

        assertEquals(studyId, actualId)
        verify(authorizationService).ensureAceIsLoaded(
            AclKey(studyId),
            Principal(PrincipalType.USER, "test-user"),
        )
        verify(authorizationService).ensureAceIsLoaded(AclKey(studyId), SystemRole.adminRole)
        val ordered = inOrder(connection, authorizationService)
        ordered.verify(connection).commit()
        ordered.verify(authorizationService).ensureAceIsLoaded(
            AclKey(studyId),
            Principal(PrincipalType.USER, "test-user"),
        )
        ordered.verify(authorizationService).ensureAceIsLoaded(AclKey(studyId), SystemRole.adminRole)
        verify(studies, never()).evict(studyId)
    }

    @Test
    fun `post-commit cache warm failure cannot turn durable create into a failed response`() {
        val studyId = UUID.randomUUID()
        val study = Study(title = "durable success", contact = "test@example.com")
        val owner = Principal(PrincipalType.USER, "test-user")
        whenever(idGenerationService.getNextId()).thenReturn(studyId)
        doThrow(IllegalStateException("injected cache failure"))
            .whenever(authorizationService)
            .ensureAceIsLoaded(AclKey(studyId), owner)
        val service = spy(newService())
        doNothing().whenever(service).createStudy(same(connection), same(study))

        val actualId = service.createStudy(study)

        assertEquals(studyId, actualId)
        verify(connection).commit()
        verify(authorizationService).ensureAceIsLoaded(AclKey(studyId), owner)
        verify(authorizationService).ensureAceIsLoaded(AclKey(studyId), SystemRole.adminRole)
    }

    @Test
    fun `single study lookup uses synchronous read-through get`() {
        val studyId = UUID.randomUUID()
        val study = Study(studyId = studyId, title = "read-through", contact = "test@example.com")
        whenever(studies[studyId]).thenReturn(study)
        stubBlockedStudy()
        val service = newService()

        val actual = service.getStudy(studyId)

        assertSame(study, actual)
        verify(studies)[studyId]
        verify(studies, never()).getAll(setOf(studyId))
    }

    @Test
    fun `quarantined study lookup evicts cached value and returns not found`() {
        val studyId = UUID.randomUUID()
        val cachedStudy = Study(
            studyId = studyId,
            title = "quarantined",
            contact = "test@example.com",
        )
        whenever(studies[studyId]).thenReturn(cachedStudy)
        val guardStatement = stubBlockedStudy(studyId)
        val service = newService()

        assertThrows(NoSuchElementException::class.java) {
            service.getStudy(studyId)
        }

        verify(studies).get(studyId)
        verify(studies).evict(studyId)
        verify(guardStatement).executeQuery()
    }

    @Test
    fun `study cache eviction failure cannot make quarantined study readable`() {
        val studyId = UUID.randomUUID()
        val cachedStudy = Study(
            studyId = studyId,
            title = "quarantined",
            contact = "test@example.com",
        )
        whenever(studies[studyId]).thenReturn(cachedStudy)
        stubBlockedStudy(studyId)
        doThrow(IllegalStateException("injected cache failure")).whenever(studies).evict(studyId)
        val service = newService()

        assertThrows(NoSuchElementException::class.java) {
            service.getStudy(studyId)
        }

        verify(studies).evict(studyId)
    }

    @Test
    fun `bulk study lookup filters quarantined cached values and keeps visible studies`() {
        val visibleStudyId = UUID.randomUUID()
        val blockedStudyId = UUID.randomUUID()
        val visibleStudy = Study(
            studyId = visibleStudyId,
            title = "visible",
            contact = "test@example.com",
        )
        val blockedStudy = Study(
            studyId = blockedStudyId,
            title = "quarantined",
            contact = "test@example.com",
        )
        val requestedStudyIds = linkedSetOf(visibleStudyId, blockedStudyId)
        whenever(studies.getAll(requestedStudyIds)).thenReturn(
            linkedMapOf(
                visibleStudyId to visibleStudy,
                blockedStudyId to blockedStudy,
            ),
        )
        stubBlockedStudy(blockedStudyId)
        val service = newService()

        val actual = service.getStudies(requestedStudyIds).toList()

        assertEquals(listOf(visibleStudy), actual)
        verify(studies).evict(blockedStudyId)
        verify(studies, never()).evict(visibleStudyId)
    }

    @Test
    fun `single study settings fail closed when quarantine is active`() {
        val studyId = UUID.randomUUID()
        val settingsStatement = mock<PreparedStatement>()
        val settingsResultSet = mock<ResultSet>()
        whenever(connection.prepareStatement(anyString())).thenReturn(settingsStatement)
        whenever(settingsStatement.connection).thenReturn(connection)
        whenever(settingsStatement.executeQuery()).thenReturn(settingsResultSet)
        whenever(settingsResultSet.next()).thenReturn(true)
        whenever(settingsResultSet.getString("settings")).thenReturn("{}")
        stubBlockedStudy(studyId)
        val service = newService()

        assertThrows(NoSuchElementException::class.java) {
            service.getStudySettings(studyId)
        }

        verify(studies).evict(studyId)
    }

    @Test
    fun `bulk study settings omit quarantined studies`() {
        val visibleStudyId = UUID.randomUUID()
        val blockedStudyId = UUID.randomUUID()
        val settingsStatement = mock<PreparedStatement>()
        val settingsResultSet = mock<ResultSet>()
        whenever(connection.prepareStatement(anyString())).thenReturn(settingsStatement)
        whenever(settingsStatement.connection).thenReturn(connection)
        whenever(settingsStatement.executeQuery()).thenReturn(settingsResultSet)
        whenever(settingsResultSet.next()).thenReturn(true, true, false)
        whenever(settingsResultSet.getObject("study_id", UUID::class.java))
            .thenReturn(visibleStudyId, blockedStudyId)
        whenever(settingsResultSet.getString("settings")).thenReturn("{}")
        stubBlockedStudy(blockedStudyId)
        val service = newService()

        val actual = service.getStudySettings(listOf(visibleStudyId, blockedStudyId))

        assertEquals(setOf(visibleStudyId), actual.keys)
        verify(studies).evict(blockedStudyId)
    }

    @Test
    fun `all study id enumeration omits quarantined studies`() {
        val visibleStudyId = UUID.randomUUID()
        val blockedStudyId = UUID.randomUUID()
        val statement = mock<Statement>()
        val resultSet = mock<ResultSet>()
        whenever(connection.createStatement()).thenReturn(statement)
        whenever(statement.executeQuery(anyString())).thenReturn(resultSet)
        whenever(resultSet.next()).thenReturn(true, true, false)
        whenever(resultSet.getObject("study_id", UUID::class.java))
            .thenReturn(visibleStudyId, blockedStudyId)
        stubBlockedStudy(blockedStudyId)
        val service = newService()

        val actual = service.getAllStudyIds().toList()

        assertEquals(listOf(visibleStudyId), actual)
        verify(studies).evict(blockedStudyId)
    }

    @Test
    fun `legacy study id resolution returns null when resolved study is quarantined`() {
        val legacyStudyId = UUID.randomUUID()
        val resolvedStudyId = UUID.randomUUID()
        val legacyStatement = mock<PreparedStatement>()
        val legacyResultSet = mock<ResultSet>()
        whenever(connection.prepareStatement(anyString())).thenReturn(legacyStatement)
        whenever(legacyStatement.executeQuery()).thenReturn(legacyResultSet)
        whenever(legacyResultSet.next()).thenReturn(true)
        whenever(legacyResultSet.getObject("study_id", UUID::class.java)).thenReturn(resolvedStudyId)
        stubBlockedStudy(resolvedStudyId)
        val service = newService()

        val actual = service.getStudyId(legacyStudyId)

        assertEquals(null, actual)
        verify(studies).evict(resolvedStudyId)
    }

    @Test
    fun `transactional create writes creator and admin ACEs through caller connection`() {
        val studyId = UUID.randomUUID()
        val study = Study(
            studyId = studyId,
            title = "atomic ACL publication",
            contact = "test@example.com",
        )
        val statement = mock<PreparedStatement>()
        whenever(connection.prepareStatement(anyString())).thenReturn(statement)
        whenever(statement.executeBatch()).thenReturn(intArrayOf())
        val service = newService()
        val allPermissions = EnumSet.allOf(Permission::class.java)

        service.createStudy(connection, study)

        verify(authorizationService).createUnnamedSecurableObject(
            same(connection),
            eq(AclKey(studyId)),
            eq(Principal(PrincipalType.USER, "test-user")),
            eq(allPermissions),
            eq(SecurableObjectType.Study),
            eq(OffsetDateTime.MAX),
        )
        verify(authorizationService).createUnnamedSecurableObject(
            same(connection),
            eq(AclKey(studyId)),
            eq(SystemRole.adminRole),
            eq(allPermissions),
            eq(SecurableObjectType.Study),
            eq(OffsetDateTime.MAX),
        )
        verify(authorizationService, never()).addPermission(
            eq(AclKey(studyId)),
            eq(SystemRole.adminRole),
            eq(allPermissions),
        )
    }

    @Test
    fun `failed commit publishes neither study nor ACL cache state`() {
        val studyId = UUID.randomUUID()
        val study = Study(title = "failed commit", contact = "test@example.com")
        whenever(idGenerationService.getNextId()).thenReturn(studyId)
        val service = spy(newService())
        doNothing().whenever(service).createStudy(same(connection), same(study))
        doThrow(SQLException("injected commit failure")).whenever(connection).commit()

        assertThrows(SQLException::class.java) {
            service.createStudy(study)
        }

        verify(connection).rollback()
        verify(studies, never()).evict(studyId)
        verify(studies, never()).get(studyId)
        verify(authorizationService, never()).ensureAceIsLoaded(
            AclKey(studyId),
            Principal(PrincipalType.USER, "test-user"),
        )
        verify(authorizationService, never()).ensureAceIsLoaded(AclKey(studyId), SystemRole.adminRole)
    }

    private fun newService(): StudyService =
        StudyService(
            storageResolver = storageResolver,
            authorizationService = authorizationService,
            candidateService = mock<CandidateManager>(),
            enrollmentService = mock<EnrollmentManager>(),
            surveysManager = mock<SurveysManager>(),
            idGenerationService = idGenerationService,
            studyLimitsMgr = mock<StudyLimitsManager>(),
            auditingManager = auditingManager,
            hazelcast = hazelcast,
            participantStatsCache = mock<ParticipantStatsCache>(),
            webhookService = mock<WebhookService>(),
        )

    private fun stubBlockedStudy(blockedStudyId: UUID? = null): PreparedStatement {
        val statement = mock<PreparedStatement>()
        val resultSet = mock<ResultSet>()
        whenever(connection.prepareStatement(StudyDeletionGuard.FIND_BLOCKED_STUDIES_SQL)).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(resultSet)
        if (blockedStudyId == null) {
            whenever(resultSet.next()).thenReturn(false)
        } else {
            whenever(resultSet.next()).thenReturn(true, false)
            whenever(resultSet.getObject("study_id", UUID::class.java)).thenReturn(blockedStudyId)
        }
        return statement
    }
}
