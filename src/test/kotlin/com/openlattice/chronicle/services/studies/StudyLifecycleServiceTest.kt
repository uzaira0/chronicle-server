package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyCloneRequest
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.webhooks.WebhookEventType
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import com.openlattice.chronicle.controllers.kEq
import com.openlattice.chronicle.controllers.kAny
import com.openlattice.chronicle.controllers.kAnyString
import com.openlattice.chronicle.controllers.TestSecurityUtils
import org.junit.After
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.*

class StudyLifecycleServiceTest {

    private lateinit var storageResolver: StorageResolver
    private lateinit var studyService: StudyService
    private lateinit var authorizationService: AuthorizationManager
    private lateinit var idGenerationService: HazelcastIdGenerationService
    private lateinit var auditingManager: AuditingManager
    private lateinit var dataDeletionOrchestrator: DataDeletionOrchestrator
    private lateinit var webhookService: WebhookService
    private lateinit var service: StudyLifecycleService
    private lateinit var mockHds: HikariDataSource
    private lateinit var mockConnection: Connection
    private lateinit var mockPs: PreparedStatement
    private lateinit var mockRs: ResultSet

    @Before
    fun setUp() {
        // Audited transaction builders construct AuditableEvent, which reads
        // Principals.getCurrentSecurablePrincipal() as a default arg. Stand the
        // SecurityContext + Principals statics up before the service runs.
        TestSecurityUtils.setupSecurityContext()

        storageResolver = Mockito.mock(StorageResolver::class.java)
        studyService = Mockito.mock(StudyService::class.java)
        authorizationService = Mockito.mock(AuthorizationManager::class.java)
        idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        auditingManager = Mockito.mock(AuditingManager::class.java)
        dataDeletionOrchestrator = Mockito.mock(DataDeletionOrchestrator::class.java)
        webhookService = Mockito.mock(WebhookService::class.java)
        mockHds = Mockito.mock(HikariDataSource::class.java)
        mockConnection = Mockito.mock(Connection::class.java)
        mockPs = Mockito.mock(PreparedStatement::class.java)
        mockRs = Mockito.mock(ResultSet::class.java)

        `when`(storageResolver.getPlatformStorage()).thenReturn(mockHds)
        `when`(mockHds.connection).thenReturn(mockConnection)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(mockPs)
        `when`(mockPs.executeQuery()).thenReturn(mockRs)
        `when`(mockPs.executeUpdate()).thenReturn(1)
        `when`(idGenerationService.getNextId()).thenReturn(UUID.randomUUID())

        service = StudyLifecycleService(
            storageResolver, studyService, authorizationService, idGenerationService, auditingManager,
            dataDeletionOrchestrator, webhookService,
            Mockito.mock(StudyLimitsManager::class.java),
        )
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun testServiceConstructsSuccessfully() {
        assertNotNull(service)
    }

    /** "Copy of " plus a maximum-length title must fit the V1 studies_title_format CHECK, not fail with HTTP 500. */
    @Test
    fun testCloneOfAMaximumLengthTitleStaysWithinTheTitleColumnLimit() {
        val sourceStudyId = UUID.randomUUID()
        // A surrogate pair straddles the cut point, so a plain substring would leave half a code point.
        val title = "t".repeat(1991) + "😀" + "t".repeat(7)
        assertEquals(2000, title.length)
        `when`(studyService.getStudy(sourceStudyId)).thenReturn(Study(title = title, contact = "test@example.com"))

        service.cloneStudy(sourceStudyId, "test-user", StudyCloneRequest())

        val values = org.mockito.ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(mockPs, Mockito.atLeastOnce()).setString(Mockito.eq(2), values.capture())
        val cloneTitle = values.allValues.single { it.startsWith("Copy of ") }
        assertTrue(cloneTitle.length <= 2000)
        assertTrue(cloneTitle.isNotEmpty() && !cloneTitle.last().isHighSurrogate())
        assertEquals("Copy of " + "t".repeat(1991), cloneTitle)
    }

    @Test
    fun testCloneStudyCommitsOwnerAndAdminAcesBeforeCacheWarming() {
        val sourceStudyId = UUID.randomUUID()
        val clonedStudyId = UUID.randomUUID()
        val sourceStudy = Study(title = "Source", contact = "test@example.com")
        val aclKey = AclKey(clonedStudyId)
        val creator = Principal(PrincipalType.USER, "test-user")
        val allPermissions = EnumSet.allOf(Permission::class.java)
        `when`(studyService.getStudy(sourceStudyId)).thenReturn(sourceStudy)
        `when`(idGenerationService.getNextId()).thenReturn(clonedStudyId)

        val actual = service.cloneStudy(sourceStudyId, "test-user", StudyCloneRequest())

        assertEquals(clonedStudyId, actual)
        Mockito.verify(authorizationService).createUnnamedSecurableObject(
            kEq(mockConnection),
            kEq(aclKey),
            kEq(creator),
            kEq(allPermissions),
            kEq(SecurableObjectType.Study),
            kEq(OffsetDateTime.MAX),
        )
        Mockito.verify(authorizationService).createUnnamedSecurableObject(
            kEq(mockConnection),
            kEq(aclKey),
            kEq(SystemRole.adminRole),
            kEq(allPermissions),
            kEq(SecurableObjectType.Study),
            kEq(OffsetDateTime.MAX),
        )
        Mockito.verify(authorizationService, Mockito.never()).addPermission(
            kEq(aclKey),
            kEq(SystemRole.adminRole),
            kEq(allPermissions),
        )
        val ordered = Mockito.inOrder(mockConnection, authorizationService)
        ordered.verify(authorizationService).createUnnamedSecurableObject(
            kEq(mockConnection),
            kEq(aclKey),
            kEq(creator),
            kEq(allPermissions),
            kEq(SecurableObjectType.Study),
            kEq(OffsetDateTime.MAX),
        )
        ordered.verify(authorizationService).createUnnamedSecurableObject(
            kEq(mockConnection),
            kEq(aclKey),
            kEq(SystemRole.adminRole),
            kEq(allPermissions),
            kEq(SecurableObjectType.Study),
            kEq(OffsetDateTime.MAX),
        )
        ordered.verify(mockConnection).commit()
        ordered.verify(authorizationService).ensureAceIsLoaded(aclKey, creator)
        ordered.verify(authorizationService).ensureAceIsLoaded(aclKey, SystemRole.adminRole)
    }

    @Test
    fun testCloneStudyCacheWarmFailureDoesNotChangeCommittedResult() {
        val sourceStudyId = UUID.randomUUID()
        val clonedStudyId = UUID.randomUUID()
        val sourceStudy = Study(title = "Source", contact = "test@example.com")
        val aclKey = AclKey(clonedStudyId)
        val creator = Principal(PrincipalType.USER, "test-user")
        `when`(studyService.getStudy(sourceStudyId)).thenReturn(sourceStudy)
        `when`(idGenerationService.getNextId()).thenReturn(clonedStudyId)
        Mockito.doThrow(IllegalStateException("injected cache failure"))
            .`when`(authorizationService)
            .ensureAceIsLoaded(aclKey, creator)

        val actual = service.cloneStudy(sourceStudyId, "test-user", StudyCloneRequest())

        assertEquals(clonedStudyId, actual)
        Mockito.verify(mockConnection).commit()
        Mockito.verify(authorizationService).ensureAceIsLoaded(aclKey, creator)
        Mockito.verify(authorizationService).ensureAceIsLoaded(aclKey, SystemRole.adminRole)
    }

    @Test
    fun testCloneStudyCommitFailureDoesNotPublishAclCacheState() {
        val sourceStudyId = UUID.randomUUID()
        val clonedStudyId = UUID.randomUUID()
        val sourceStudy = Study(title = "Source", contact = "test@example.com")
        val aclKey = AclKey(clonedStudyId)
        `when`(studyService.getStudy(sourceStudyId)).thenReturn(sourceStudy)
        `when`(idGenerationService.getNextId()).thenReturn(clonedStudyId)
        Mockito.doThrow(SQLException("injected commit failure")).`when`(mockConnection).commit()

        assertThrows(SQLException::class.java) {
            service.cloneStudy(sourceStudyId, "test-user", StudyCloneRequest())
        }

        Mockito.verify(mockConnection).rollback()
        Mockito.verify(authorizationService, Mockito.never()).ensureAceIsLoaded(
            aclKey,
            Principal(PrincipalType.USER, "test-user"),
        )
        Mockito.verify(authorizationService, Mockito.never()).ensureAceIsLoaded(aclKey, SystemRole.adminRole)
    }

    // --- getLifecycleStatus tests ---

    @Test
    fun testGetLifecycleStatusReturnsActive() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)

        val result = service.getLifecycleStatus(UUID.randomUUID())

        assertEquals(StudyLifecycleStatus.ACTIVE, result)
    }

    @Test
    fun testGetLifecycleStatusReturnsArchived() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ARCHIVED.name)

        val result = service.getLifecycleStatus(UUID.randomUUID())

        assertEquals(StudyLifecycleStatus.ARCHIVED, result)
    }

    @Test
    fun testGetLifecycleStatusReturnsScheduledForDeletion() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name)

        val result = service.getLifecycleStatus(UUID.randomUUID())

        assertEquals(StudyLifecycleStatus.SCHEDULED_FOR_DELETION, result)
    }

    @Test
    fun testGetLifecycleStatusThrowsWhenStudyNotFound() {
        `when`(mockRs.next()).thenReturn(false)

        assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) {
            service.getLifecycleStatus(UUID.randomUUID())
        }
    }

    // --- archiveStudy tests ---

    @Test
    fun testArchiveStudyFromActive() {
        // getCurrentStatus returns ACTIVE, then updateStatus returns, then insertLifecycleEvent
        val statusRs = Mockito.mock(ResultSet::class.java)
        val updateRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)
        val updatePs = Mockito.mock(PreparedStatement::class.java)
        val insertPs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)

        `when`(updateRs.next()).thenReturn(true)
        `when`(updatePs.executeQuery()).thenReturn(updateRs)

        `when`(insertPs.executeUpdate()).thenReturn(1)

        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(statusPs)
            .thenReturn(updatePs)
            .thenReturn(insertPs)

        // autocommit must be settable
        `when`(mockConnection.autoCommit).thenReturn(true)

        val studyId = UUID.randomUUID()
        service.archiveStudy(studyId, "test-user", expectedRevision = 0)

        Mockito.verify(mockConnection).prepareStatement(
            Mockito.argThat<String> { sql -> sql.contains("FOR UPDATE") }
        )
        // Verify the lifecycle event insert was executed
        Mockito.verify(insertPs).executeUpdate()
        Mockito.verify(webhookService).enqueueEvent(
            kEq(mockConnection),
            kEq(studyId),
            kEq(WebhookEventType.STUDY_STATUS_CHANGED),
            kAny(),
        )
    }

    @Test
    fun testArchiveReplayWhenAlreadyArchivedIsANoOp() {
        val statusRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ARCHIVED.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)

        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(statusPs)
        `when`(mockConnection.autoCommit).thenReturn(true)

        // A stale revision does not matter: the archive it asked for already landed.
        service.archiveStudy(UUID.randomUUID(), "test-user", expectedRevision = 41)

        Mockito.verify(mockConnection, Mockito.times(1)).prepareStatement(kAnyString())
        Mockito.verifyNoInteractions(webhookService)
    }

    @Test
    fun testArchiveStudyFailsWhenScheduledForDeletion() {
        val statusRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)

        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(statusPs)
        `when`(mockConnection.autoCommit).thenReturn(true)

        assertThrows(IllegalStateException::class.java) {
            service.archiveStudy(UUID.randomUUID(), "test-user", expectedRevision = 0)
        }
    }

    // --- unarchiveStudy tests ---

    @Test
    fun testUnarchiveStudyFromArchived() {
        val statusRs = Mockito.mock(ResultSet::class.java)
        val updateRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)
        val updatePs = Mockito.mock(PreparedStatement::class.java)
        val insertPs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ARCHIVED.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)

        `when`(updateRs.next()).thenReturn(true)
        `when`(updatePs.executeQuery()).thenReturn(updateRs)

        `when`(insertPs.executeUpdate()).thenReturn(1)

        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(statusPs)
            .thenReturn(updatePs)
            .thenReturn(insertPs)

        `when`(mockConnection.autoCommit).thenReturn(true)

        service.unarchiveStudy(UUID.randomUUID(), "test-user", expectedRevision = 0)

        // Verify the lifecycle event insert was executed
        Mockito.verify(insertPs).executeUpdate()
    }

    @Test
    fun testUnarchiveReplayWhenActiveIsANoOp() {
        val statusRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)

        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(statusPs)
        `when`(mockConnection.autoCommit).thenReturn(true)

        service.unarchiveStudy(UUID.randomUUID(), "test-user", expectedRevision = 41)

        Mockito.verify(mockConnection, Mockito.times(1)).prepareStatement(kAnyString())
        Mockito.verifyNoInteractions(webhookService)
    }

    // --- scheduled deletion transaction-boundary tests ---

    @Test
    fun testStudyDeletionRequestWritesLifecycleStateInsideQuarantineTransaction() {
        val studyId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val idempotencyKey = UUID.nameUUIDFromBytes(
            "study-erasure:$studyId:default".toByteArray(Charsets.UTF_8)
        )
        val statusRs = Mockito.mock(ResultSet::class.java)
        val updateRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)
        val updatePs = Mockito.mock(PreparedStatement::class.java)
        val eventPs = Mockito.mock(PreparedStatement::class.java)
        val schedulePs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)
        `when`(updateRs.next()).thenReturn(true)
        `when`(updatePs.executeQuery()).thenReturn(updateRs)
        `when`(eventPs.executeUpdate()).thenReturn(1)
        `when`(schedulePs.executeUpdate()).thenReturn(1)
        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(statusPs)
            .thenReturn(updatePs)
            .thenReturn(eventPs)
            .thenReturn(schedulePs)
        Mockito.doAnswer { invocation ->
            val transaction = invocation.getArgument<(Connection, UUID, Boolean) -> Unit>(3)
            transaction(mockConnection, operationId, true)
            operationId
        }.`when`(dataDeletionOrchestrator).quarantineStudyAtomically(
            kEq(studyId),
            kEq("test-user"),
            kEq(idempotencyKey),
            kAny(),
        )

        val actual = service.requestStudyDeletion(studyId, "test-user")

        assertEquals(operationId, actual)
        Mockito.verify(mockConnection).prepareStatement(
            Mockito.argThat<String> { sql -> sql.contains("FOR UPDATE") }
        )
        Mockito.verify(updatePs).setString(1, StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name)
        Mockito.verify(schedulePs).setObject(kEq(3), kAny<OffsetDateTime>())
        Mockito.verify(schedulePs).setObject(4, operationId)
        Mockito.verify(schedulePs).setString(5, StudyLifecycleStatus.ACTIVE.name)
        Mockito.verify(webhookService).enqueueEvent(
            kEq(mockConnection),
            kEq(studyId),
            kEq(WebhookEventType.STUDY_STATUS_CHANGED),
            kAny(),
        )
    }

    @Test
    fun testRepeatedStudyDeletionRequestsUseStableOperationKeyWithoutRewritingLifecycleState() {
        val studyId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val idempotencyKey = UUID.nameUUIDFromBytes(
            "study-erasure:$studyId:default".toByteArray(Charsets.UTF_8)
        )
        val statusRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)
        `when`(statusRs.next()).thenReturn(true, true)
        `when`(statusRs.getString(kAnyString()))
            .thenReturn(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(statusPs)
        Mockito.doAnswer { invocation ->
            val transaction = invocation.getArgument<(Connection, UUID, Boolean) -> Unit>(3)
            transaction(mockConnection, operationId, false)
            operationId
        }.`when`(dataDeletionOrchestrator).quarantineStudyAtomically(
            kEq(studyId),
            kEq("test-user"),
            kEq(idempotencyKey),
            kAny(),
        )

        assertEquals(operationId, service.requestStudyDeletion(studyId, "test-user"))
        assertEquals(operationId, service.requestStudyDeletion(studyId, "test-user"))

        Mockito.verify(dataDeletionOrchestrator, Mockito.times(2)).quarantineStudyAtomically(
            kEq(studyId),
            kEq("test-user"),
            kEq(idempotencyKey),
            kAny(),
        )
        Mockito.verify(webhookService, Mockito.never()).enqueueEvent(
            kAny(),
            kAny(),
            kAny(),
            kAny(),
        )
    }

    @Test
    fun testCancelScheduledDeletionRestoresArchivedStateInsideErasureTransaction() {
        val studyId = UUID.randomUUID()
        val statusRs = Mockito.mock(ResultSet::class.java)
        val previousStatusRs = Mockito.mock(ResultSet::class.java)
        val updateRs = Mockito.mock(ResultSet::class.java)
        val statusPs = Mockito.mock(PreparedStatement::class.java)
        val previousStatusPs = Mockito.mock(PreparedStatement::class.java)
        val updatePs = Mockito.mock(PreparedStatement::class.java)
        val eventPs = Mockito.mock(PreparedStatement::class.java)
        val deleteSchedulePs = Mockito.mock(PreparedStatement::class.java)

        `when`(statusRs.next()).thenReturn(true)
        `when`(statusRs.getString(kAnyString()))
            .thenReturn(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name)
        `when`(statusPs.executeQuery()).thenReturn(statusRs)
        `when`(previousStatusRs.next()).thenReturn(true)
        `when`(previousStatusRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ARCHIVED.name)
        `when`(previousStatusPs.executeQuery()).thenReturn(previousStatusRs)
        `when`(updateRs.next()).thenReturn(true)
        `when`(updatePs.executeQuery()).thenReturn(updateRs)
        `when`(eventPs.executeUpdate()).thenReturn(1)
        `when`(deleteSchedulePs.executeUpdate()).thenReturn(1)
        `when`(mockConnection.prepareStatement(kAnyString()))
            .thenReturn(statusPs)
            .thenReturn(previousStatusPs)
            .thenReturn(updatePs)
            .thenReturn(eventPs)
            .thenReturn(deleteSchedulePs)
        Mockito.doAnswer { invocation ->
            val transaction = invocation.getArgument<(Connection, Int) -> Unit>(2)
            transaction(mockConnection, 1)
            1
        }.`when`(dataDeletionOrchestrator).cancelStudyErasureAtomically(
            kEq(studyId),
            kEq("test-user"),
            kAny(),
        )

        service.cancelScheduledDeletion(studyId, "test-user")

        Mockito.verify(mockConnection).prepareStatement(
            Mockito.argThat<String> { sql -> sql.contains("FOR UPDATE") }
        )
        Mockito.verify(updatePs).setString(1, StudyLifecycleStatus.ARCHIVED.name)
        Mockito.verify(deleteSchedulePs).executeUpdate()
        Mockito.verify(webhookService).enqueueEvent(
            kEq(mockConnection),
            kEq(studyId),
            kEq(WebhookEventType.STUDY_STATUS_CHANGED),
            kAny(),
        )
    }

    // --- autoArchiveExpiredStudies tests ---

    @Test fun expirationSnapshotCannotArchiveAStudyWhoseDeadlineWasExtended() {
        val selected = Mockito.mock(ResultSet::class.java)
        val status = Mockito.mock(ResultSet::class.java)
        val selectedStatement = Mockito.mock(PreparedStatement::class.java)
        val statusStatement = Mockito.mock(PreparedStatement::class.java)
        val deadlineStatement = Mockito.mock(PreparedStatement::class.java)
        val deadline = Mockito.mock(ResultSet::class.java)
        `when`(selected.next()).thenReturn(true, false)
        `when`(selected.getObject(kEq("study_id"), kEq(UUID::class.java))).thenReturn(UUID.randomUUID())
        `when`(status.next()).thenReturn(true)
        `when`(status.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        `when`(deadline.next()).thenReturn(true)
        `when`(deadline.getBoolean(1)).thenReturn(false)
        `when`(selectedStatement.executeQuery()).thenReturn(selected)
        `when`(statusStatement.executeQuery()).thenReturn(status)
        `when`(deadlineStatement.executeQuery()).thenReturn(deadline)
        `when`(mockConnection.prepareStatement(kAnyString())).thenReturn(selectedStatement, statusStatement, deadlineStatement)
        service.autoArchiveExpiredStudies()
        Mockito.verify(deadlineStatement, Mockito.never()).setString(1, StudyLifecycleStatus.ARCHIVED.name)
        Mockito.verify(deadlineStatement).executeQuery()
        Mockito.verifyNoInteractions(webhookService)
    }

    /**
     * The nightly auto-archive of studies past their end date: a database error on one study is counted on the
     * background-failure metric operators alert on, and the next expired study is still archived.
     */
    @Test fun oneStudyFailingToAutoArchiveIsCountedAndTheNextIsStillArchived() {
        val failing = UUID.randomUUID()
        val archived = UUID.randomUUID()
        val selected = Mockito.mock(ResultSet::class.java)
        val selectedStatement = Mockito.mock(PreparedStatement::class.java)
        `when`(selected.next()).thenReturn(true, true, false)
        `when`(selected.getObject(kEq("study_id"), kEq(UUID::class.java))).thenReturn(failing, archived)
        `when`(selectedStatement.executeQuery()).thenReturn(selected)
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)
        `when`(mockRs.getBoolean(1)).thenReturn(true)
        `when`(mockConnection.autoCommit).thenReturn(true)
        var prepared = 0
        `when`(mockConnection.prepareStatement(kAnyString())).thenAnswer {
            prepared += 1
            when (prepared) {
                1 -> selectedStatement
                2 -> throw SQLException("connection reset", "08006")
                else -> mockPs
            }
        }
        val failures = com.openlattice.chronicle.observability.ChronicleMetrics.backgroundTaskFailuresTotal
            .labels(com.openlattice.chronicle.observability.ChronicleMetrics.BackgroundTask.AUTO_ARCHIVE.label)
        val before = failures.get()

        service.autoArchiveExpiredStudies()

        assertEquals(before + 1, failures.get(), 0.0)
        Mockito.verify(webhookService).enqueueEvent(
            kEq(mockConnection), kEq(archived), kEq(WebhookEventType.STUDY_STATUS_CHANGED), kAny(),
        )
        Mockito.verify(webhookService, Mockito.never()).enqueueEvent(
            kEq(mockConnection), kEq(failing), kEq(WebhookEventType.STUDY_STATUS_CHANGED), kAny(),
        )
    }

    @Test
    fun testAutoArchiveExpiredStudiesNoExpired() {
        `when`(mockRs.next()).thenReturn(false)
        val failures = com.openlattice.chronicle.observability.ChronicleMetrics.backgroundTaskFailuresTotal
            .labels(com.openlattice.chronicle.observability.ChronicleMetrics.BackgroundTask.AUTO_ARCHIVE.label)
        val before = failures.get()

        service.autoArchiveExpiredStudies()

        // Only the expiry query ran: no status row was written, nothing was announced, nothing failed.
        Mockito.verify(mockConnection, Mockito.times(1)).prepareStatement(kAnyString())
        Mockito.verify(mockPs, Mockito.never()).executeUpdate()
        Mockito.verifyNoInteractions(webhookService, auditingManager)
        assertEquals(before, failures.get(), 0.0)
    }

    @Test
    fun testGetLifecycleStatusSetsCorrectParameter() {
        val studyId = UUID.randomUUID()
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn(StudyLifecycleStatus.ACTIVE.name)

        service.getLifecycleStatus(studyId)

        Mockito.verify(mockPs).setObject(1, studyId)
    }

    @Test
    fun testGetLifecycleStatusThrowsForInvalidStatus() {
        `when`(mockRs.next()).thenReturn(true)
        `when`(mockRs.getString(kAnyString())).thenReturn("INVALID_STATUS")

        assertThrows(IllegalArgumentException::class.java) {
            service.getLifecycleStatus(UUID.randomUUID())
        }
    }
}
