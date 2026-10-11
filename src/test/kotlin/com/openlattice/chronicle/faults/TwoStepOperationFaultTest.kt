package com.openlattice.chronicle.faults

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditedOperation
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.controllers.StudyController
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.storageResolver as fixtureStorage
import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.testing.FaultInjectingDataSource
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class TwoStepOperationFaultTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() = RestoreContinuityFixture.start()

        @AfterClass
        @JvmStatic
        fun tearDownClass() = RestoreContinuityFixture.stop()
    }

    private val fixture = RestoreContinuityFixture()
    private lateinit var faults: FaultInjectingDataSource
    private lateinit var storage: StorageResolver
    private val studyId: UUID get() = fixture.studyId
    private val participantId: String get() = fixture.participantId

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
        fixture.seedRestoredOlderState()
        faults = FaultInjectingDataSource(fixtureStorage.getPlatformStorage())
        val view = object : HikariDataSource() {
            override fun getConnection(): Connection = faults.connection.apply {
                // Bound an accidental nested study-fence lock so the regression fails promptly.
                createStatement().use { it.execute("SET statement_timeout = '5s'") }
            }
        }
        storage = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenAnswer { PinnedPlatformConnection.resolve(view) }
    }

    @After
    fun cleanUp() {
        fixture.cleanFixture()
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun `DEL-04 ledger failure rolls back status and retry succeeds`() {
        val auditing = Mockito.mock(AuditingManager::class.java)
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        @Suppress("UNCHECKED_CAST")
        val studies = Mockito.mock(IMap::class.java) as IMap<UUID, Study>
        Mockito.`when`(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(studies)
        val service = Mockito.spy(StudyService(
            storageResolver = storage,
            authorizationService = authorization,
            candidateService = mock(),
            enrollmentService = mock(),
            surveysManager = mock(),
            idGenerationService = mock(),
            studyLimitsMgr = mock(),
            auditingManager = auditing,
            hazelcast = hazelcast,
            participantStatsCache = mock(),
            webhookService = mock(),
        ))
        Mockito.doReturn(true).`when`(service).isValidStudy(studyId)
        val orchestrator = DataDeletionOrchestrator(storage, auditing)
        val controller = StudyController(
            hazelcastInstance = hazelcast,
            storageResolver = storage,
            idGenerationService = mock(),
            enrollmentService = mock(),
            studyService = service,
            sensorDataUploadService = mock(),
            androidSensorDataUploadService = mock(),
            batteryTelemetryUploadService = mock(),
            interactionEventsUploadService = mock(),
            appAudioActivityUploadService = mock(),
            ambientAudioUploadService = mock(),
            appAudioContentUploadService = mock(),
            notificationActivityUploadService = mock(),
            sleepEventsUploadService = mock(),
            activityRecognitionEventsUploadService = mock(),
            healthMetricsUploadService = mock(),
            connectivityStateEventsUploadService = mock(),
            appNetworkUsageUploadService = mock(),
            deviceSettingsUploadService = mock(),
            uploadDiagnosticsUploadService = mock(),
            encryptedPayloadUploadService = mock(),
            studyEncryptionKeyService = mock(),
            appDataUploadService = mock(),
            downloadService = mock(),
            enrollmentManager = mock(),
            authorizationManager = authorization,
            auditingManager = auditing,
            chronicleJobService = mock(),
            auditService = mock(),
            studySettingsAuditService = mock(),
            participantCollectionAcknowledgmentService = mock(),
            studySettingsNotificationService = mock(),
            apiKeyService = mock(),
            dataDeletionOrchestrator = orchestrator,
            studyLifecycleService = mock(),
            webhookService = mock(),
        )
        faults.failOnStatement(Regex("INSERT\\s+INTO\\s+data_deletion_operations", RegexOption.IGNORE_CASE))

        val error = assertThrows(SQLException::class.java) {
            controller.deleteStudyParticipants(studyId, setOf(participantId)).toList()
        }
        assertEquals("XX999", error.sqlState)
        assertEquals(1, faults.injectedFailures)
        assertEquals("ENROLLED", status())
        assertEquals(0L, count("data_deletion_operations"))
        assertEquals(0L, count("data_deletion_audit_outbox"))

        faults.clearFault()
        assertEquals(1, controller.deleteStudyParticipants(studyId, setOf(participantId)).count())
        assertEquals("NOT_ENROLLED", status())
        assertEquals(1L, count("data_deletion_operations"))
        assertEquals(1L, count("data_deletion_audit_outbox", " AND event_type = 'UPDATE_PARTICIPATION_STATUS'"))
        Mockito.verify(service, Mockito.never()).updateParticipationStatus(
            studyId, participantId, com.openlattice.chronicle.data.ParticipationStatus.NOT_ENROLLED,
        )
    }

    @Test
    fun `containment failure rolls back receipt and revocation and retains checkpoint for retry`() {
        fixture.seedContinuityCheckpoint()
        // This is after the receipt INSERT and both key UPDATEs, so rollback must undo real writes.
        faults.failOnStatement(Regex("UPDATE\\s+study_participants\\s+target", RegexOption.IGNORE_CASE))
        val reconciler = RestoreContinuityReconciler(storage, DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java)))

        val error = assertThrows(SQLException::class.java) { reconciler.reconcile() }
        assertEquals("XX999", error.sqlState)
        assertEquals(1, faults.injectedFailures)
        assertEquals("ENROLLED", status())
        assertEquals(0L, count("mobile_withdrawal_requests"))
        assertEquals(0L, count("api_keys", " AND revoked"))
        assertTrue(fixture.schemaExists("chronicle_restore_continuity"))
        assertEquals(0L, count("restore_continuity_reconciliations"))

        faults.clearFault()
        assertNotNull(reconciler.reconcile())
        assertTrue(!fixture.schemaExists("chronicle_restore_continuity"))
        assertEquals(1L, count("mobile_withdrawal_requests"))
        assertEquals(0L, count("study_participants"))
    }

    @Test
    fun `failed domain commit rolls back its success audit`() {
        val auditResolver = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(auditResolver.getAuditStorage()).thenReturn(
            PostgresFlavor.VANILLA to fixtureStorage.getPlatformStorage(),
        )
        val event = AuditableEvent(
            aclKey = AclKey(studyId), eventType = AuditEventType.UPDATE_PARTICIPATION_STATUS,
            study = studyId, description = "fault-characterisation-${UUID.randomUUID()}",
        )
        faults.failOnCommit(1)
        faults.connection.use { connection ->
            val operation = AuditedOperation(
                connection,
                { tx ->
                    tx.prepareStatement(StudyService.SET_PARTICIPATION_STATUS_SQL).use {
                        it.setString(1, "NOT_ENROLLED")
                        it.setObject(2, studyId)
                        it.setString(3, participantId)
                        it.executeUpdate()
                    }
                },
                { listOf(event) },
                PostgresAuditingManager(auditResolver),
            )
            val error = assertThrows(SQLException::class.java) { operation.execute() }
            assertEquals("XX999", error.sqlState)
        }
        assertEquals(1, faults.injectedFailures)
        assertEquals("ENROLLED", status())
        // Success audits share the domain transaction and cannot survive its failed commit.
        fixtureStorage.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM audit WHERE description = ?").use {
                it.setString(1, event.description)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0L, rows.getLong(1))
                }
            }
        }
    }

    private fun status(): String = fixtureStorage.getPlatformStorage().connection.use(fixture::participationStatus)

    private fun count(table: String, extra: String = ""): Long = fixtureStorage.getPlatformStorage().connection.use { connection ->
        if (table == "restore_continuity_reconciliations") fixture.count(connection, table, "checkpoint_id", fixture.checkpointId, extra)
        else fixture.count(connection, table, "study_id", studyId, extra)
    }
}
