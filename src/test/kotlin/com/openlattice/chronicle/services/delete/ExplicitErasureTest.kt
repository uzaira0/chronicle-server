package com.openlattice.chronicle.services.delete

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.jdbc.DataSourceManager
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.StudyDeletionScheduler
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyLimitsService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTask
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTaskDependencies
import com.openlattice.chronicle.study.StudyLimits
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Erasure starts only on an owner's explicit run; time never erases, and an interrupted run resumes. */
class ExplicitErasureTest : ServerSweepTestFixture() {
    private val retentionExpired = ChronicleMetrics.studiesRetentionExpired.get()
    @Before fun authenticate() = TestSecurityUtils.setupSecurityContext()
    @After fun clearAuthentication() {
        ChronicleMetrics.studiesRetentionExpired.set(retentionExpired)
        TestSecurityUtils.clearSecurityContext()
    }

    @Test fun aRequestFollowedByAnyAmountOfTimeErasesNothing() = withErasure { erasure, study, participant ->
        val participantOperation = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())
        val studyOperation = erasure.quarantineStudy(study, "fixture-researcher", UUID.randomUUID())
        ageOperations(study, Duration.ofDays(3650))

        assertEquals(0, erasure.processDueOperations())

        assertEquals("QUARANTINED", erasure.getOperation(participantOperation).status)
        assertEquals("QUARANTINED", erasure.getOperation(studyOperation).status)
        assertTrue(erasure.getOperation(participantOperation).erasable)
        assertEquals(2, telemetryRows(study))
    }

    @Test fun repeatedRequestsForOneParticipantJoinItsOpenOperation() = withErasure { erasure, study, participant ->
        val first = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())
        val repeat = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())

        assertEquals(first, repeat)
        assertEquals(listOf(first), erasure.listOperations(study, 10, 0).map { it.operationId })
    }

    @Test fun anExplicitRunErasesImmediately() = withErasure { erasure, study, participant ->
        val operation = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())

        assertEquals("COMPLETED", erasure.eraseNow(operation, study, "fixture-owner").status)

        assertEquals(1, telemetryRows(study))
        assertEquals("fixture-owner", startedBy(operation))
        assertFalse(erasure.getOperation(operation).erasable)
        assertThrows(IllegalStateException::class.java) { erasure.eraseNow(operation, study, "fixture-owner") }
    }

    @Test fun aStudyRequestWaitsForItsExplicitRun() = withErasure { erasure, study, _ ->
        val operation = erasure.quarantineStudy(study, "fixture-researcher", UUID.randomUUID())
        ageOperations(study, Duration.ofDays(30))
        assertEquals(0, erasure.processDueOperations())
        assertEquals(2, telemetryRows(study))

        assertEquals("COMPLETED", erasure.eraseNow(operation, study, "fixture-owner").status)
        assertEquals(0, telemetryRows(study))
    }

    @Test fun aCrashMidRunResumesAfterItsLeaseExpires() = withErasure { erasure, study, participant ->
        val operation = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            "fixture-researcher", UUID.randomUUID())
        // The owner's run claimed the operation and its worker died before finishing.
        sql("""
            UPDATE data_deletion_operations
            SET status = 'ERASING', started_at = now() - interval '1 hour', operation_attempt_count = 1,
                worker_lease_token = gen_random_uuid(), updated_at = now() - interval '40 minutes',
                worker_lease_expires_at = now() - interval '10 minutes'
            WHERE operation_id = '$operation'
        """)
        assertTrue(erasure.getOperation(operation).erasable)

        assertEquals(1, erasure.processDueOperations())

        assertEquals("COMPLETED", erasure.getOperation(operation).status)
        assertEquals(1, telemetryRows(study))
    }

    @Test fun aParticipantRequestBlocksProcessingKeepsTheDataAndShowsPending() = withErasure { erasure, study, participant ->
        val before = OffsetDateTime.now().minusMinutes(1)
        val operation = erasure.quarantineParticipant(study, participant, DataDeletionMode.WITHDRAW_AND_ERASE,
            participant, UUID.randomUUID())

        assertThrows(CollectionUnavailableException::class.java) {
            BatteryTelemetryUploadService(storage).upload(study, participant, UUID.randomUUID(), listOf(sample("after-request")))
        }
        assertEquals(2, telemetryRows(study))
        val pending = erasure.listOperations(study, 10, 0).single()
        assertEquals(operation, pending.operationId)
        assertEquals("QUARANTINED", pending.status)
        assertTrue(pending.requestedAt.isAfter(before))
    }

    /**
     * Nothing is deleted automatically: diagnostics, webhook deliveries (study events and their payloads) and
     * finished jobs survive every scheduled pass however old they are, and old diagnostics still export.
     */
    @Test fun noTimerAgesOutDiagnosticsWebhookDeliveriesOrFinishedJobs() = withErasure { erasure, study, participant ->
        val device = UUID.randomUUID()
        val longAgo = OffsetDateTime.now().minusDays(400)
        val old = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), longAgo.toLocalDate(), "BATTERY", "TIMEOUT", 1,
            longAgo, longAgo)
        val diagnostics = UploadDiagnosticsUploadService(storage)
        diagnostics.upload(study, participant, device, listOf(old))
        sql("UPDATE upload_diagnostics SET uploaded_at = now() - interval '400 days' WHERE study_id = '$study'")
        val webhook = UUID.randomUUID()
        val job = UUID.randomUUID()
        sql("""
            INSERT INTO webhook_registrations (webhook_id, study_id, url, secret_hash, event_types, created_by)
            VALUES ('$webhook', '$study', 'https://example.org/hook', 'hash', ARRAY['STUDY_STATUS_CHANGED'], 'fixture');
            INSERT INTO webhook_deliveries (webhook_id, study_id, event_type, payload, delivery_state, created_at, completed_at)
            VALUES ('$webhook', '$study', 'STUDY_STATUS_CHANGED', '{"status":"ARCHIVED"}', 'SUCCEEDED',
                    now() - interval '3650 days', now() - interval '3650 days'),
                   ('$webhook', '$study', 'DATA_SUBMITTED', '{"count":1}', 'FAILED',
                    now() - interval '3650 days', now() - interval '3650 days');
            INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, definition, message,
                              deleted_rows, created_at, updated_at, completed_at)
            VALUES ('$job', gen_random_uuid(), 'USER', 'fixture', 'FINISHED', '{"@type":"EmptyJobDefinition"}', '', 0,
                    now() - interval '3650 days', now() - interval '3650 days', now() - interval '3650 days')
        """)
        // A later upload must not delete old diagnostics either.
        val today = OffsetDateTime.now()
        diagnostics.upload(study, participant, device, listOf(old.copy(id = UUID.randomUUID().toString(),
            day = today.toLocalDate(), firstOccurredAt = today, lastOccurredAt = today)))

        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        // The delivery pass runs inline, so anything it would age out is gone before the assertions below.
        WebhookService(storage, ids, deliveryExecutor = MoreExecutors.newDirectExecutorService()).dispatchPendingDeliveries()
        val jobs = JobService(ids, storage, Mockito.mock(AuditingManager::class.java))
        try { jobs.tryAndAcquireTaskForExecutor() } finally { jobs.shutdown() }
        StudyDeletionScheduler(Mockito.mock(StudyLifecycleService::class.java), erasure).processScheduledDeletions()

        val exported = DataDownloadService(storage).getParticipantsUploadDiagnosticsData(study, setOf(participant),
            OffsetDateTime.MIN, OffsetDateTime.MAX).map { it["event_id"].toString() }.toSet()
        assertTrue("the 400-day-old diagnostic still exports", old.id in exported)
        assertEquals(2, exported.size)
        assertEquals(2L, count("SELECT count(*) FROM webhook_deliveries WHERE webhook_id = ? AND payload <> '{}'::jsonb", webhook))
        assertEquals(1L, count("SELECT count(*) FROM jobs WHERE job_id = ? AND status = 'FINISHED'", job))
        assertEquals(2, telemetryRows(study))
    }

    /** A passed retention date only raises the operator gauge: no erasure is requested and nothing is removed. */
    @Test fun anExpiredRetentionPeriodRequestsAndErasesNothing() = withErasure { erasure, study, _ ->
        val limits = StudyLimitsService(storage, Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS))
        postgres.createConnection("").use {
            limits.initializeStudyLimits(it, study, StudyLimits(studyEnds = OffsetDateTime.now().minusDays(400),
                studyDataExpires = OffsetDateTime.now().minusDays(300)))
        }
        assertTrue(study in limits.getStudiesExcceedingDataRetentionPeriod())
        val dependencies = StudyLimitsEnforcementTaskDependencies(storage, limits, studyService(storage))
        val task = object : StudyLimitsEnforcementTask() { override fun getDependency() = dependencies }
        repeat(2) {
            task.runTask()
            StudyDeletionScheduler(Mockito.mock(StudyLifecycleService::class.java), erasure).processScheduledDeletions()
        }

        assertEquals(0L, count("SELECT count(*) FROM data_deletion_operations WHERE study_id = ?", study))
        assertEquals(1L, count("SELECT count(*) FROM studies WHERE study_id = ?", study))
        assertEquals(2L, count("SELECT count(*) FROM study_participants WHERE study_id = ?", study))
        assertEquals(2, telemetryRows(study))
    }

    private fun count(query: String, id: UUID): Long = postgres.createConnection("").use { connection ->
        connection.prepareStatement(query).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private lateinit var storage: StorageResolver

    private fun withErasure(test: (DataDeletionOrchestrator, UUID, String) -> Unit) {
        val study = UUID.randomUUID()
        val participant = "erasure-subject"
        seed(study, participant)
        postgres.createConnection("").use { com.openlattice.chronicle.contract.seedEnrolledParticipant(it, study, "control") }
        val configs = ChronicleContractTestSchema.storageConfigurations(postgres, maximumPoolSize = 4)
        DataSourceManager(configs, HealthCheckRegistry(), MetricRegistry()).use { sources ->
            storage = StorageResolver(sources, ChronicleStorageConfiguration())
            RLSRequestContext.withSystemContext {
                val ingest = BatteryTelemetryUploadService(storage)
                ingest.upload(study, participant, UUID.randomUUID(), listOf(sample("before-request")))
                ingest.upload(study, "control", UUID.randomUUID(), listOf(sample("before-request")))
                // Ten years ahead: no clock reading may make a pending request erasable.
                val clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(3650))
                test(DataDeletionOrchestrator(storage, Mockito.mock(AuditingManager::class.java), clock), study, participant)
            }
        }
    }

    // Every clock the scheduler could read says the request is long overdue.
    private fun ageOperations(study: UUID, age: Duration) = sql("""
        UPDATE data_deletion_operations
        SET created_at = now() - interval '${age.toDays()} days', updated_at = now() - interval '${age.toDays()} days',
            next_attempt_at = now() - interval '${age.toDays()} days'
        WHERE study_id = '$study'
    """)

    private fun sql(statement: String) {
        postgres.createConnection("").use { connection -> connection.createStatement().use { it.execute(statement.trimIndent()) } }
    }

    private fun telemetryRows(study: UUID): Int = postgres.createConnection("").use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        connection.prepareStatement("SELECT count(*) FROM battery_telemetry WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun startedBy(operation: UUID): String? = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "SELECT actor FROM data_deletion_audit_outbox WHERE operation_id = ? AND event_type = 'START_DATA_DELETION'",
        ).use {
            it.setObject(1, operation)
            val rows = it.executeQuery() // closed with its statement
            if (rows.next()) rows.getString(1) else null
        }
    }

    private fun sample(id: String) = BatterySample(id, OffsetDateTime.now(), "UTC", 80,
        BatteryChargingState.NOT_CHARGING, BatteryPlugType.UNPLUGGED, 200, 4000, BatteryHealth.GOOD)
}
