package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.seedCandidate

import com.openlattice.chronicle.authorization.JwtBlocklist
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import com.zaxxer.hikari.HikariDataSource
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.util.SqlFailures
import com.openlattice.chronicle.survey.SurveySettings
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.AuditObservation
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.services.studies.loadLockedStudySettings
import com.openlattice.chronicle.services.studies.mergeStudySetting
import com.openlattice.chronicle.services.studies.stampDataCollectionSettingsVersion
import com.openlattice.chronicle.services.studies.mergeAndStampDataCollectionSettingsVersionLocked
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.quarantineStudy
import com.openlattice.chronicle.services.delete.MobileSelfWithdrawalResult
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.services.delete.bindErasureStudyId
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.loadLockedStudy
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.ChronicleJobRunner
import com.openlattice.chronicle.services.jobs.EmptyJobDefinition
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.StudySnapshotRepository
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsQueryService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_DEVICE_SENSOR_AVAILABILITY
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ANDROID_SENSOR_DATA
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.CANDIDATES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.FILTERED_APPS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.INTERACTION_EVENTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATIONS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.ORGANIZATIONS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_COLLECTION_ACKNOWLEDGMENT
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_STATS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.QUESTIONNAIRES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_SETTINGS_AUDIT
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.ScreenTimeUsageCaptures
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSConnectionSql
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.study.WithdrawalMode
import com.openlattice.chronicle.services.webhooks.ParticipantRef
import com.openlattice.chronicle.services.webhooks.WebhookPayload
import com.openlattice.chronicle.webhooks.WebhookEventType
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.flywaydb.core.api.output.MigrateResult
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.BeforeClass
import org.postgresql.core.BaseConnection
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Fresh-install migration gate: framework bootstrap + the FULL production Flyway corpus,
 * executed by real Flyway (same engine, same classpath location as FlywayMigrationService),
 * against the production database image — then postcondition assertions over the end state
 * (RLS, policies, append-only grants, constraints, deletion ledger, re-issued orphan objects)
 * plus behavioral checks that exercise the migrated schema through real services.
 *
 * Replaces MigrationRoundtripTest, which drove the (deleted) per-class SqlMigrationUpgrade
 * system. Legacy one-time data conversions live only in prod history now; what this suite
 * proves is that a fresh database migrated by Flyway matches what production converged to
 * (see docs/db/MIGRATION-LEDGER-AUDIT.md).
 */
class FlywayMigrationCorpusTest {
    private var previousFixtureContext: RLSConnectionContext? = null

    @Before
    fun installFixtureContext() {
        previousFixtureContext = RLSRequestContext.current()
        RLSRequestContext.set(RLSConnectionContext("migration-fixture", emptySet(), isAdmin = true))
    }

    @After
    fun restoreFixtureContext() {
        previousFixtureContext?.let(RLSRequestContext::set) ?: RLSRequestContext.clear()
    }

    private val studyFixture get() = MigrationStudyFixture(storageResolver)
    @Test
    fun testUploadDiagnosticsAreIdempotentIdentityScopedAndStudyRetained() {
        val studyId = UUID.randomUUID()
        val participantId = "diagnostic-participant-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val randomDeviceId = UUID.randomUUID()
        val service = UploadDiagnosticsUploadService(storageResolver)
        val eventId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        fun diagnostic(count: Int, occurredAt: OffsetDateTime) =
            AndroidUploadDiagnosticEvent(
                id = eventId,
                day = occurredAt.toLocalDate(),
                moduleFamily = "USAGE_LIFECYCLE",
                issueCode = "CONNECTION_FAILURE",
                count = count,
                firstOccurredAt = occurredAt,
                lastOccurredAt = occurredAt,
                errorType = "ConnectException",
            )

        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "diagnostic-test",
                authorizedStudyIds = setOf(studyId),
                isAdmin = false,
            ),
        )
        try {
            assertEquals(setOf(UUID.fromString(eventId)),
                service.upload(studyId, participantId, randomDeviceId, listOf(diagnostic(1, now))).acceptedIds)
            assertEquals(setOf(UUID.fromString(eventId)),
                service.upload(studyId, participantId, randomDeviceId, listOf(diagnostic(4, now))).acceptedIds)

            assertDiagnosticIdentityAndCount(eventId, randomDeviceId, expectedCount = 4)

            val oldDiagnostic = diagnostic(1, now.minusDays(365))
                .copy(id = UUID.randomUUID().toString())
            getConnection().use { connection ->
                connection.prepareStatement(
                    "UPDATE upload_diagnostics SET uploaded_at = ? WHERE event_id = ?",
                ).use { statement ->
                    statement.setObject(1, now.minusDays(365))
                    statement.setString(2, eventId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            service.upload(studyId, participantId, randomDeviceId, listOf(oldDiagnostic))
            assertDiagnosticsRetained(eventId, oldDiagnostic.id)
        } finally {
            RLSRequestContext.clear()
        }
    }

    private fun assertDiagnosticIdentityAndCount(eventId: String, randomDeviceId: UUID, expectedCount: Int) {
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT occurrence_count, random_device_id FROM upload_diagnostics WHERE event_id = ?",
            ).use { statement ->
                statement.setString(1, eventId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(expectedCount, resultSet.getInt("occurrence_count"))
                    assertEquals(randomDeviceId, resultSet.getObject("random_device_id", UUID::class.java))
                }
            }
        }
    }

    private fun assertDiagnosticsRetained(eventId: String, oldEventId: String) {
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM upload_diagnostics WHERE event_id IN (?, ?)",
            ).use { statement ->
                statement.setString(1, eventId)
                statement.setString(2, oldEventId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(2, resultSet.getInt(1))
                }
            }
        }
    }

    @Test
    fun testV107AcceptsEveryDiagnosticFamilyAndIssueCodePair() {
        val definitions = getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = 'upload_diagnostics'::regclass AND contype = 'c'
                """.trimIndent()).use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
            }
        }
        fun allowedValues(name: String): List<String> = Regex("'([A-Z_]+)'")
            .findAll(definitions.getValue(name)).map { it.groupValues[1] }.toList().also { assertTrue(it.isNotEmpty()) }
        val families = allowedValues("upload_diagnostics_module_family_check")
        val codes = allowedValues("upload_diagnostics_issue_code_check")
        val studyId = UUID.randomUUID()
        val randomDeviceId = UUID.randomUUID()
        val participantId = "catalog-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val day = LocalDate.parse("2026-09-25")
        val occurredAt = day.atStartOfDay().atOffset(ZoneOffset.UTC)
        val events = families.flatMap { family ->
            codes.map { code ->
                AndroidUploadDiagnosticEvent(
                    id = UUID.randomUUID().toString(),
                    day = day,
                    moduleFamily = family,
                    issueCode = code,
                    count = 1,
                    firstOccurredAt = occurredAt,
                    lastOccurredAt = occurredAt,
                )
            }
        }
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "diagnostic-catalog-test",
                authorizedStudyIds = setOf(studyId),
                isAdmin = false,
            ),
        )
        try {
            val service = UploadDiagnosticsUploadService(storageResolver)
            events.chunked(500).forEach { batch ->
                assertEquals(batch.size, service.upload(studyId, participantId, randomDeviceId, batch).acceptedIds.size)
            }
            getConnection().use { connection ->
                connection.prepareStatement("SELECT count(*) FROM upload_diagnostics WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(events.size, resultSet.getInt(1))
                    }
                }
            }
        } finally {
            RLSRequestContext.clear()
        }
    }

    @Test
    fun testDiagnosticsFromBeforeACompletedPurgeAreAcknowledgedButNotStoredAgain() {
        val studyId = UUID.randomUUID()
        val participantId = "purged-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val randomDeviceId = UUID.randomUUID()
        val purgeStarted = OffsetDateTime.parse("2026-09-20T12:00:00Z")
        getConnection().use { connection ->
            // A completed purge as the orchestrator leaves it: participant_id cleared, block token kept.
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_id, participant_block_token, mode, status,
                    requested_by, idempotency_key, registry_version, started_at, completed_at
                ) VALUES (?, ?, NULL, chronicle_participant_block_token(?::uuid, ?), 'COLLECTED_DATA_PURGE', 'COMPLETED',
                          'migration-test', ?, 1, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.setString(3, studyId.toString())
                statement.setString(4, participantId)
                statement.setObject(5, UUID.randomUUID())
                statement.setObject(6, purgeStarted)
                statement.setObject(7, purgeStarted.plusMinutes(5))
                assertEquals(1, statement.executeUpdate())
            }
        }
        fun event(firstOccurredAt: OffsetDateTime) = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(),
            day = firstOccurredAt.toLocalDate(),
            moduleFamily = "LOCAL_STORE",
            issueCode = "LOCAL_WRITE_FAILED",
            count = 1,
            firstOccurredAt = firstOccurredAt,
            lastOccurredAt = firstOccurredAt,
        )
        val erased = event(purgeStarted.minusHours(3))
        val later = event(purgeStarted.plusHours(3))

        RLSRequestContext.set(
            RLSConnectionContext(principalId = "purge-replay", authorizedStudyIds = setOf(studyId), isAdmin = false),
        )
        try {
            val receipt = UploadDiagnosticsUploadService(storageResolver).upload(studyId, participantId, randomDeviceId, listOf(erased, later))
            assertEquals(setOf(UUID.fromString(later.id)), receipt.acceptedIds)
            assertEquals(setOf(UUID.fromString(erased.id)), receipt.discardedIds)
        } finally {
            RLSRequestContext.clear()
        }

        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT event_id FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows ->
                    val stored = generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    assertEquals(listOf(later.id), stored)
                }
            }
        }
    }

    @Test
    fun testErasedDiagnosticReplayWithAheadClockCannotResurrectAfterPurge() {
        val studyId = UUID.randomUUID()
        val participantId = "ahead-clock-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        studyFixture.ensureStudyParticipant(studyId, "$participantId-other")
        val randomDeviceId = UUID.randomUUID()
        val cutoff = OffsetDateTime.parse("2026-09-20T12:00:00Z")
        val erased = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(),
            day = cutoff.toLocalDate(),
            moduleFamily = "LOCAL_STORE",
            issueCode = "LOCAL_WRITE_FAILED",
            count = 1,
            firstOccurredAt = cutoff.plusHours(1),
            lastOccurredAt = cutoff.plusHours(1),
        )
        val later = erased.copy(id = UUID.randomUUID().toString())
        RLSRequestContext.set(RLSConnectionContext("ahead-clock-replay", setOf(studyId), isAdmin = false))
        try {
            val service = UploadDiagnosticsUploadService(storageResolver)
            service.upload(studyId, participantId, randomDeviceId, listOf(erased))
        } finally {
            RLSRequestContext.clear()
        }
        getConnection().use { deletion ->
            deletion.autoCommit = false
            deletion.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().close()
            }
            deletion.prepareStatement(
                "DELETE FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                assertEquals(1, statement.executeUpdate())
            }
            deletion.prepareStatement(
                """INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_id, participant_block_token, mode, status,
                    requested_by, idempotency_key, registry_version, started_at, completed_at
                ) VALUES (?, ?, NULL, chronicle_participant_block_token(?::uuid, ?), 'COLLECTED_DATA_PURGE', 'COMPLETED',
                          'migration-test', ?, 1, ?, ?)""".trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.setString(3, studyId.toString())
                statement.setString(4, participantId)
                statement.setObject(5, UUID.randomUUID())
                statement.setObject(6, cutoff)
                statement.setObject(7, cutoff.plusMinutes(5))
                assertEquals(1, statement.executeUpdate())
            }
            deletion.commit()
        }
        RLSRequestContext.set(RLSConnectionContext("ahead-clock-replay", setOf(studyId), isAdmin = false))
        try {
            val service = UploadDiagnosticsUploadService(storageResolver)
            val replay = erased.copy(firstOccurredAt = cutoff.plusHours(2), lastOccurredAt = cutoff.plusHours(2))
            val receipt = service.upload(studyId, participantId, randomDeviceId, listOf(replay, later))
            assertEquals(setOf(UUID.fromString(later.id)), receipt.acceptedIds)
            assertEquals(setOf(UUID.fromString(erased.id)), receipt.discardedIds)
            // Re-enrollment on another device must not bypass the participant's erasure fence.
            assertEquals(setOf(UUID.fromString(erased.id)),
                service.upload(studyId, participantId, UUID.randomUUID(), listOf(replay)).discardedIds)
            // The same opaque ID in another participant's scope remains independent.
            service.upload(studyId, "$participantId-other", randomDeviceId, listOf(replay))
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT event_id FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, participantId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(later.id, rows.getString(1))
                        assertFalse(rows.next())
                    }
                    statement.setString(2, "$participantId-other")
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(erased.id, rows.getString(1))
                    }
                }
            }
        } finally {
            RLSRequestContext.clear()
        }
    }

    @Test
    fun testDiagnosticErasureTombstonesSupportNonBypassOwnerAndTransactionalRollback() {
        val studyId = UUID.randomUUID()
        val participantId = "erasure-owner-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val owner = "diagnostic_owner_${UUID.randomUUID().toString().replace("-", "")}"
        val occurredAt = OffsetDateTime.now(ZoneOffset.UTC)
        val event = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(), day = occurredAt.toLocalDate(),
            moduleFamily = "LOCAL_STORE", issueCode = "LOCAL_WRITE_FAILED", count = 1,
            firstOccurredAt = occurredAt, lastOccurredAt = occurredAt,
        )
        RLSRequestContext.set(RLSConnectionContext("erasure-owner", setOf(studyId), isAdmin = false))
        try {
            UploadDiagnosticsUploadService(storageResolver).upload(studyId, participantId, UUID.randomUUID(), listOf(event))
        } finally {
            RLSRequestContext.clear()
        }
        getConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("CREATE ROLE $owner NOLOGIN NOSUPERUSER NOBYPASSRLS")
                    statement.execute("GRANT USAGE ON SCHEMA public TO $owner")
                    statement.execute("GRANT EXECUTE ON FUNCTION chronicle_participant_block_token(uuid, text) TO $owner")
                    statement.execute("ALTER TABLE upload_diagnostic_erasures OWNER TO $owner")
                    statement.execute("ALTER FUNCTION chronicle_record_erased_diagnostic_ids() OWNER TO $owner")
                }
                connection.prepareStatement("DELETE FROM upload_diagnostics WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "SELECT event_id FROM upload_diagnostic_erasures WHERE study_id = ?",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(UUID.fromString(event.id), rows.getObject(1, UUID::class.java))
                        assertFalse(rows.next())
                    }
                }
            } finally {
                connection.rollback()
            }
        }
        getConnection().use { connection ->
            listOf("upload_diagnostics" to 1, "upload_diagnostic_erasures" to 0).forEach { (table, count) ->
                connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("Erasure and tombstones must roll back together", count, rows.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun testStudyErasureRemovesDiagnosticsAndTombstonesWithNonBypassRoles() {
        assertStudyDiagnosticErasure(resumeVerifiedDiagnostics = false)
        assertStudyDiagnosticErasure(resumeVerifiedDiagnostics = true)
    }

    private fun assertStudyDiagnosticErasure(resumeVerifiedDiagnostics: Boolean) {
        val studyId = UUID.randomUUID()
        val participantId = "study-diagnostics-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val owner = "study_diagnostic_owner_${UUID.randomUUID().toString().replace("-", "")}"
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val events = (1..2).map {
            AndroidUploadDiagnosticEvent(
                id = UUID.randomUUID().toString(), day = now.toLocalDate(),
                moduleFamily = "LOCAL_STORE", issueCode = "LOCAL_WRITE_FAILED", count = 1,
                firstOccurredAt = now, lastOccurredAt = now,
            )
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        var operationId: UUID? = null
        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $owner NOLOGIN NOSUPERUSER NOBYPASSRLS")
                statement.execute("GRANT USAGE ON SCHEMA public TO $owner")
                statement.execute("GRANT SELECT, DELETE ON ALL TABLES IN SCHEMA public TO $owner")
                statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $owner")
                statement.execute("ALTER TABLE upload_diagnostic_erasures OWNER TO $owner")
                statement.execute("ALTER FUNCTION chronicle_record_erased_diagnostic_ids() OWNER TO $owner")
                statement.execute("ALTER FUNCTION chronicle_delete_study_rows(TEXT, UUID) OWNER TO $owner")
                statement.execute("ALTER FUNCTION chronicle_count_study_rows_for_erasure(TEXT, UUID) OWNER TO $owner")
            }
        }
        try {
            RLSRequestContext.set(RLSConnectionContext("diagnostic-erasure-seed", setOf(studyId), isAdmin = false))
            try {
                UploadDiagnosticsUploadService(storageResolver).upload(studyId, participantId, UUID.randomUUID(), events)
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.prepareStatement("DELETE FROM upload_diagnostics WHERE study_id = ? AND event_id = ?").use {
                        it.setObject(1, studyId)
                        it.setString(2, events.first().id)
                        assertEquals(1, it.executeUpdate())
                    }
                    connection.prepareStatement("SELECT count(*) FROM upload_diagnostic_erasures WHERE study_id = ?").use {
                        it.setObject(1, studyId)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("Participant deletion still creates a tombstone", 1, rows.getInt(1))
                        }
                    }
                }
                getConnection().use { connection ->
                    try {
                        RLSConnectionSql.applyContext(connection, requireNotNull(RLSRequestContext.current()))
                        connection.createStatement().use { it.execute("SET ROLE $owner") }
                        connection.prepareStatement("DELETE FROM upload_diagnostic_erasures WHERE study_id = ?").use {
                            it.setObject(1, studyId)
                            assertEquals("Study access alone must not authorize tombstone deletion", 0, it.executeUpdate())
                        }
                    } finally {
                        RLSConnectionSql.clear(connection)
                    }
                }
            } finally {
                RLSRequestContext.clear()
            }
            RLSRequestContext.withDeletionWorkerContext {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user",
                        ).use { rows ->
                            assertTrue(rows.next())
                            assertEquals("chronicle_app", rows.getString(1))
                            assertFalse(rows.getBoolean(2))
                            assertFalse(rows.getBoolean(3))
                        }
                    }
                }
                operationId = orchestrator.quarantineStudy(studyId, "migration-test", UUID.randomUUID())
            }
            if (resumeVerifiedDiagnostics) {
                // Resuming must physically sweep even a previously verified asset before committing proof.
                getConnection().use { connection ->
                    connection.prepareStatement(
                        "UPDATE data_deletion_steps SET status = 'VERIFIED' WHERE operation_id = ? AND asset_id = ?",
                    ).use {
                        it.setObject(1, operationId)
                        it.setString(2, "study-table:upload_diagnostics")
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            processDeletion(orchestrator, requireNotNull(operationId))
            getConnection().use { connection ->
                listOf("upload_diagnostics", "upload_diagnostic_erasures").forEach { table ->
                    connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id = ?").use {
                        it.setObject(1, studyId)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals("Study erasure must leave no $table rows", 0, rows.getInt(1))
                        }
                    }
                }
            }
        } finally {
            RLSRequestContext.clear()
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE upload_diagnostic_erasures OWNER TO ${postgres.username}")
                    statement.execute("ALTER FUNCTION chronicle_record_erased_diagnostic_ids() OWNER TO ${postgres.username}")
                    statement.execute("ALTER FUNCTION chronicle_delete_study_rows(TEXT, UUID) OWNER TO ${postgres.username}")
                    statement.execute("ALTER FUNCTION chronicle_count_study_rows_for_erasure(TEXT, UUID) OWNER TO ${postgres.username}")
                    statement.execute("DROP OWNED BY $owner")
                    statement.execute("DROP ROLE $owner")
                }
                operationId?.let { id ->
                    deleteById(connection, "data_deletion_tombstones", "operation_id", id)
                    deleteById(connection, "data_deletion_audit_outbox", "operation_id", id)
                    deleteById(connection, "data_deletion_operations", "operation_id", id)
                }
                listOf("upload_diagnostics", "upload_diagnostic_erasures", "studies").forEach { table ->
                    deleteById(connection, table, "study_id", studyId)
                }
            }
        }
    }

    

    @Test
    fun testDiagnosticUploadWaitsForDeletionBeforeReadingErasureCutoff() {
        val studyId = UUID.randomUUID()
        val participantId = "concurrent-purge-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(studyId, participantId)
        val randomDeviceId = UUID.randomUUID()
        val purgeStarted = OffsetDateTime.parse("2026-09-20T12:00:00Z")
        fun event(at: OffsetDateTime) = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(),
            day = at.toLocalDate(),
            moduleFamily = "LOCAL_STORE",
            issueCode = "LOCAL_WRITE_FAILED",
            count = 1,
            firstOccurredAt = at,
            lastOccurredAt = at,
        )
        val existing = event(purgeStarted.minusHours(2))
        val erased = event(purgeStarted.minusHours(1))
        val later = event(purgeStarted.plusHours(1))
        RLSRequestContext.set(RLSConnectionContext("diagnostic-seed", setOf(studyId), isAdmin = false))
        try {
            UploadDiagnosticsUploadService(storageResolver).upload(studyId, participantId, randomDeviceId, listOf(existing))
        } finally {
            RLSRequestContext.clear()
        }
        val executor = Executors.newSingleThreadExecutor()
        getConnection().use { deletion ->
            deletion.autoCommit = false
            try {
                deletion.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().close()
                }
                val upload = executor.submit<UploadReceipt> {
                    RLSRequestContext.set(
                        RLSConnectionContext("diagnostic-race", setOf(studyId), isAdmin = false),
                    )
                    try {
                        UploadDiagnosticsUploadService(storageResolver).upload(
                            studyId, participantId, randomDeviceId, listOf(erased, later),
                        )
                    } finally {
                        RLSRequestContext.clear()
                    }
                }

                // The waiting SQL must be the study lock, before the cutoff SELECT or INSERT.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waitingForStudyLock = false
                while (!waitingForStudyLock && System.nanoTime() < deadline) {
                    getConnection().use { monitor ->
                        monitor.prepareStatement(
                            """SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE pid <> pg_backend_pid()
                                  AND wait_event_type = 'Lock' AND wait_event = 'advisory'
                                  AND query LIKE '%SELECT pg_advisory_xact_lock_shared%'
                            )""".trimIndent(),
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                waitingForStudyLock = rows.getBoolean(1)
                            }
                        }
                    }
                    if (!waitingForStudyLock) TimeUnit.MILLISECONDS.sleep(25)
                }
                assertTrue("Upload must wait on the shared study lock before reading the cutoff", waitingForStudyLock)
                assertFalse(upload.isDone)

                deletion.prepareStatement(
                    "DELETE FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, participantId)
                    assertEquals(1, statement.executeUpdate())
                }
                deletion.prepareStatement(
                    """INSERT INTO data_deletion_operations (
                        operation_id, study_id, participant_id, participant_block_token, mode, status,
                        requested_by, idempotency_key, registry_version, started_at, completed_at
                    ) VALUES (?, ?, NULL, chronicle_participant_block_token(?::uuid, ?), 'COLLECTED_DATA_PURGE', 'COMPLETED',
                              'migration-test', ?, 1, ?, ?)""".trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.setString(3, studyId.toString())
                    statement.setString(4, participantId)
                    statement.setObject(5, UUID.randomUUID())
                    statement.setObject(6, purgeStarted)
                    statement.setObject(7, purgeStarted.plusMinutes(5))
                    assertEquals(1, statement.executeUpdate())
                }
                deletion.commit()

                val receipt = upload.get(10, TimeUnit.SECONDS)
                assertEquals(setOf(UUID.fromString(later.id)), receipt.acceptedIds)
                assertEquals(setOf(UUID.fromString(erased.id)), receipt.discardedIds)
                getConnection().use { connection ->
                    connection.prepareStatement(
                        "SELECT event_id FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?",
                    ).use { statement ->
                        statement.setObject(1, studyId)
                        statement.setString(2, participantId)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(later.id, rows.getString(1))
                            assertFalse(rows.next())
                        }
                    }
                }
            } finally {
                deletion.rollback()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun testAndroidDiagnosticsHistoryPaginatesAndRespectsStudyRls() {
        val visibleStudyId = UUID.randomUUID()
        val hiddenStudyId = UUID.randomUUID()
        val randomDeviceIds = listOf(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            UUID.fromString("00000000-0000-4000-8000-000000000002"),
        )
        val participantId = "history-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(visibleStudyId, participantId)
        studyFixture.ensureStudyParticipant(hiddenStudyId, participantId)
        val service = UploadDiagnosticsUploadService(storageResolver)
        val baseDay = LocalDate.parse("2026-09-20")

        fun event(day: LocalDate, deviceIndex: Int, issueCode: String) =
            Triple(randomDeviceIds[deviceIndex], day, AndroidUploadDiagnosticEvent(
                id = UUID.randomUUID().toString(),
                day = day,
                moduleFamily = "LOCAL_STORE",
                issueCode = issueCode,
                count = 2,
                firstOccurredAt = day.atStartOfDay().atOffset(ZoneOffset.UTC),
                lastOccurredAt = day.atStartOfDay().atOffset(ZoneOffset.UTC),
            ))

        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "diagnostic-history-seed",
                authorizedStudyIds = setOf(visibleStudyId, hiddenStudyId),
                isAdmin = false,
            ),
        )
        try {
            listOf(
                event(baseDay, 0, "LOCAL_BUFFER_OVERFLOW"),
                event(baseDay.plusDays(1), 0, "COLLECTION_PAUSED_STORAGE"),
                event(baseDay.plusDays(1), 1, "SAMPLE_QUARANTINED"),
            ).forEach { (randomDeviceId, _, diagnostic) ->
                service.upload(visibleStudyId, participantId, randomDeviceId, listOf(diagnostic))
            }
            service.upload(
                hiddenStudyId,
                participantId,
                randomDeviceIds[0],
                listOf(event(baseDay, 0, "UPLOAD_FAILURE").third),
            )

            RLSRequestContext.set(
                RLSConnectionContext(
                    principalId = "diagnostic-history-reader",
                    authorizedStudyIds = setOf(visibleStudyId),
                    isAdmin = false,
                ),
            )
            val exportedDay = DataDownloadService(storageResolver).getParticipantsUploadDiagnosticsData(
                visibleStudyId,
                setOf(participantId),
                baseDay.atStartOfDay().atOffset(ZoneOffset.ofHours(-3)),
                baseDay.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(-3)),
            ).toList()
            assertEquals(1, exportedDay.size)
            assertEquals(baseDay.toString(), exportedDay.single()["diagnostic_day"].toString())
            val earlyHourExport = DataDownloadService(storageResolver).getParticipantsUploadDiagnosticsData(
                visibleStudyId, setOf(participantId),
                baseDay.atStartOfDay().atOffset(ZoneOffset.UTC),
                baseDay.atStartOfDay().atOffset(ZoneOffset.UTC).plusMinutes(30),
            ).toList()
            assertEquals("A non-midnight end must include diagnostics from its day", 1, earlyHourExport.size)
            val queryService = UploadDiagnosticsQueryService(storageResolver)
            val first = queryService.getPage(visibleStudyId, participantId = participantId, limit = 1)
            assertEquals(1, first.items.size)
            assertEquals(baseDay.plusDays(1), first.items.single().day)
            assertEquals(1, first.items.single().codes.size)
            assertEquals("COLLECTION_PAUSED_STORAGE", first.items.single().codes.single().issueCode)
            val second = queryService.getPage(
                visibleStudyId,
                participantId = participantId,
                cursor = first.nextCursor,
                limit = 1,
            )
            assertEquals(1, second.items.size)
            assertEquals(baseDay.plusDays(1), second.items.single().day)
            assertNotEquals(first.items.single().randomDeviceId, second.items.single().randomDeviceId)
            val third = queryService.getPage(
                visibleStudyId,
                participantId = participantId,
                cursor = second.nextCursor,
                limit = 1,
            )
            assertEquals(baseDay, third.items.single().day)
            assertTrue(third.nextCursor == null)
            assertTrue(queryService.getPage(hiddenStudyId, limit = 10).items.isEmpty())
        } finally {
            RLSRequestContext.clear()
        }
    }

    /**
     * Walking every page of the diagnostics history, for each filter and several combinations, returns
     * exactly the groups an unpaged filter of the seeded rows selects, once each and in the documented order (newest day,
     * then participant, then device; codes by family, code and event). A row written between pages lands only if it sorts
     * after the cursor.
     */
    @Test
    fun testDiagnosticFilteredPagesEqualUnpagedOracle() {
        val history = DiagnosticHistory(UUID.randomUUID().toString().take(8))
        val participants = history.participants
        val baseDay = history.baseDay
        try {
            history.seed()
            assertTrue("more than one full page of groups", history.seeded.size > 200)
            val filters = listOf(
                DiagnosticFilter(),
                DiagnosticFilter(participantId = participants[2]),
                DiagnosticFilter(deviceId = history.device(3, 1)),
                DiagnosticFilter(fromDay = baseDay.plusDays(3)),
                DiagnosticFilter(toDay = baseDay.plusDays(9)),
                DiagnosticFilter(fromDay = baseDay.plusDays(4), toDay = baseDay.plusDays(4)),
                DiagnosticFilter(family = "LOCAL_STORE"),
                DiagnosticFilter(issue = "CONNECTION_FAILURE"),
                DiagnosticFilter(family = "LOCAL_STORE", issue = "SAMPLE_QUARANTINED"),
                DiagnosticFilter(participantId = participants[4], family = "BATTERY"),
                DiagnosticFilter(deviceId = history.device(1, 2), fromDay = baseDay.plusDays(2), toDay = baseDay.plusDays(11),
                    issue = "LOCAL_BUFFER_OVERFLOW"),
                DiagnosticFilter(participantId = "dq-nobody-${history.run}"),
            )
            for (filter in filters) for (limit in listOf(1, 13, 200)) {
                assertEquals("$filter at limit $limit", filter.expected(history.seeded), history.walk(filter, limit))
            }
            assertKeysetPagingAcrossInserts(history)
            assertMalformedPagesRejected(history)
        } finally {
            RLSRequestContext.clear()
        }
    }

    /** Keyset paging: after the first page, a group newer than the cursor is not shown; an older one is. */
    private fun assertKeysetPagingAcrossInserts(history: DiagnosticHistory) {
        val newer = DiagnosticGroup(history.participants[5], history.device(5, 0), history.baseDay.plusDays(20), emptyList())
        val older = DiagnosticGroup(history.participants[0], history.device(0, 1), history.baseDay.minusDays(1), emptyList())
        val afterInserts = mutableListOf<DiagnosticGroup>()
        val walked = history.walk(DiagnosticFilter(), 50) { pages ->
            if (pages == 1) {
                history.asSeeder(setOf(history.studyId))
                afterInserts += history.upload(history.studyId, newer.participantId, newer.deviceId, newer.day, DIAGNOSTIC_CODES.take(1))
                afterInserts += history.upload(history.studyId, older.participantId, older.deviceId, older.day, DIAGNOSTIC_CODES.take(1))
                history.asReader()
            }
        }
        assertEquals(DiagnosticFilter().expected(history.seeded + afterInserts.last()), walked)
        assertEquals(DiagnosticFilter().expected(history.seeded + afterInserts), history.walk(DiagnosticFilter(), 200))
    }

    private fun assertMalformedPagesRejected(history: DiagnosticHistory) {
        for (limit in listOf(0, 201, -1)) {
            assertThrows("limit $limit", IllegalArgumentException::class.java) { history.page(DiagnosticFilter(), null, limit) }
        }
        for (cursor in listOf("garbage", "2026-08-01~ZHEw~x~2~_", "not-a-day~ZHEw~x~0~_", "2026-08-01~!!~x~0~_",
                "2026-08-01~ZHEw~x~zero~_", "x".repeat(1025))) {
            assertThrows(cursor, IllegalArgumentException::class.java) { history.page(DiagnosticFilter(), cursor, 10) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            history.page(DiagnosticFilter(fromDay = history.baseDay.plusDays(2), toDay = history.baseDay.plusDays(1)), null, 10)
        }
    }

    private data class DiagnosticCode(val family: String, val issue: String, val eventId: String)

    private data class DiagnosticGroup(val participantId: String, val deviceId: UUID, val day: LocalDate, val codes: List<DiagnosticCode>)

    private data class DiagnosticFilter(
        val participantId: String? = null, val deviceId: UUID? = null, val fromDay: LocalDate? = null,
        val toDay: LocalDate? = null, val family: String? = null, val issue: String? = null,
    ) {
        private fun selects(group: DiagnosticGroup) = (participantId == null || group.participantId == participantId) &&
            (deviceId == null || group.deviceId == deviceId) &&
            (fromDay == null || group.day >= fromDay) &&
            (toDay == null || group.day <= toDay)

        private fun keeps(code: DiagnosticCode) = (family == null || code.family == family) && (issue == null || code.issue == issue)

        /** The unpaged oracle: the groups of [groups] this filter selects, in the documented order. */
        fun expected(groups: List<DiagnosticGroup>): List<DiagnosticGroup> = groups.filter(::selects)
            .map { group -> group.copy(codes = group.codes.filter(::keeps).sortedWith(compareBy({ it.family }, { it.issue }, { it.eventId }))) }
            .filter { it.codes.isNotEmpty() }
            .sortedWith(compareByDescending<DiagnosticGroup> { it.day }.thenBy { it.participantId }.thenBy { it.deviceId.toString() })
    }

    /** Six participants with three devices each over fifteen days, plus one row of another study. */
    private inner class DiagnosticHistory(val run: String) {
        val studyId: UUID = UUID.randomUUID()
        private val otherStudyId = UUID.randomUUID()
        val participants = (0 until 6).map { "dq$it-$run" }
        val baseDay: LocalDate = LocalDate.parse("2026-08-01")
        val seeded = mutableListOf<DiagnosticGroup>()
        private val service = UploadDiagnosticsUploadService(storageResolver)
        private val history = UploadDiagnosticsQueryService(storageResolver)

        fun device(participant: Int, index: Int): UUID = UUID.fromString("00000000-0000-4000-8000-0000000%02d%03d".format(participant, index))

        fun asSeeder(studies: Set<UUID>) =
            RLSRequestContext.set(RLSConnectionContext("diagnostic-oracle-seed", studies, isAdmin = false))

        fun asReader() = RLSRequestContext.set(RLSConnectionContext("diagnostic-oracle-reader", setOf(studyId), isAdmin = false))

        fun upload(study: UUID, participant: String, deviceId: UUID, day: LocalDate, chosen: List<Pair<String, String>>): DiagnosticGroup {
            val events = chosen.map { (family, issue) ->
                val noon = day.atTime(12, 0).atOffset(ZoneOffset.UTC)
                AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), day, family, issue, 2, noon, noon)
            }
            service.upload(study, participant, deviceId, events)
            return DiagnosticGroup(participant, deviceId, day, events.map { DiagnosticCode(it.moduleFamily, it.issueCode, it.id) })
        }

        /** Seeds the groups and leaves the request context reading this study only. */
        fun seed() {
            participants.forEach { studyFixture.ensureStudyParticipant(studyId, it) }
            studyFixture.ensureStudyParticipant(otherStudyId, participants.first())
            asSeeder(setOf(studyId, otherStudyId))
            participants.forEachIndexed { p, participant ->
                for (d in 0 until 3) for (offset in 0 until 15) {
                    // About one group in five is absent; the rest carry two or three of the four codes.
                    if ((p + d + offset) % 5 == 0) continue
                    val chosen = DIAGNOSTIC_CODES.filterIndexed { k, _ -> (k + p * 7 + d * 3 + offset) % 3 != 0 }
                    seeded += upload(studyId, participant, device(p, d), baseDay.plusDays(offset.toLong()), chosen)
                }
            }
            upload(otherStudyId, participants.first(), device(0, 0), baseDay, DIAGNOSTIC_CODES)
            asReader()
        }

        fun page(filter: DiagnosticFilter, cursor: String?, limit: Int) = history.getPage(studyId, filter.participantId,
            filter.deviceId, filter.fromDay, filter.toDay, filter.family, filter.issue, cursor, limit)

        fun walk(filter: DiagnosticFilter, limit: Int, between: (Int) -> Unit = {}): List<DiagnosticGroup> {
            val walked = mutableListOf<DiagnosticGroup>()
            var cursor: String? = null
            var pages = 0
            do {
                val current = page(filter, cursor, limit)
                assertTrue("a page never exceeds its limit", current.items.size <= limit)
                walked += current.items.map { row ->
                    DiagnosticGroup(row.participantId, checkNotNull(row.randomDeviceId), row.day,
                        row.codes.map { DiagnosticCode(it.moduleFamily, it.issueCode, it.eventId) })
                }
                cursor = current.nextCursor
                between(++pages)
            } while (cursor != null)
            return walked
        }
    }

    @Test
    fun testEnrollmentLockedStudyProjectionIncludesOrganizationIds() {
        val studyId = UUID.randomUUID()
        getConnection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title, description) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "enrollment-locked-study")
                statement.setString(3, "Enrollment projection regression fixture")
                assertEquals(1, statement.executeUpdate())
            }

            val study = loadLockedStudy(connection, studyId)

            assertEquals(studyId, study.id)
            assertTrue(study.organizationIds.isEmpty())
            connection.prepareStatement("UPDATE studies SET description = NULL WHERE study_id = ?").use {
                it.setObject(1, studyId)
                assertEquals(1, it.executeUpdate())
            }
            assertEquals("", loadLockedStudy(connection, studyId).description)
        }
    }

    @Test
    fun testSweepPreviewUnderstandsArrayScopedJobs() {
        val fixture = ConcurrentWithdrawalFixture()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            getConnection().use { connection ->
                connection.prepareStatement("""
                    INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id,
                                      status, definition, message, deleted_rows)
                    VALUES (?, ?, 'USER', 'sweep', 'PENDING', ?::jsonb, '', 0)
                """.trimIndent()).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setString(3, """{"@type":"EmptyJobDefinition","studyId":"${fixture.studyId}","participantIds":["${fixture.participantId}"]}""")
                    statement.executeUpdate()
                }
            }
            val preview = createParticipantPurgeService(Mockito.mock(AuditingManager::class.java))
                .previewPurge(fixture.studyId, fixture.participantId)
            assertEquals(1L, preview.assetCounts.getValue("jobs"))
            assertTrue(preview.confirmationToken.isNotBlank())
        } finally {
            sweepCleanupRows(fixture, listOf("jobs"))
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testSweepStatusOnlyNotEnrolledStillCreatesErasure() = sweepWithdrawalStatus("NOT_ENROLLED")

    @Test
    fun testSweepPausedEnrollmentCanWithdraw() = sweepWithdrawalStatus("PAUSED")

    @Test
    fun testSweepCompletedEnrollmentCanWithdraw() = sweepWithdrawalStatus("COLLECTION_COMPLETED")

    private fun sweepWithdrawalStatus(status: String) {
        val fixture = ConcurrentWithdrawalFixture()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            getConnection().use { connection ->
                connection.prepareStatement("UPDATE study_participants SET participation_status = ? WHERE study_id = ?").use {
                    it.setString(1, status)
                    it.setObject(2, fixture.studyId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val device = fixture.devices.first()
            val result = createParticipantPurgeService(Mockito.mock(AuditingManager::class.java)).executeSelfWithdrawal(
                fixture.studyId, fixture.participantId, device.randomDeviceId, device.keyId, device.requestId,
            )
            assertFalse(result.alreadyWithdrawn)
            assertNotNull(result.deletionOperationId)
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testSweepSecondDeviceAcknowledgesWithdrawalDuringQuarantineAndAfterErasure() {
        for (complete in listOf(false, true)) {
            val fixture = ConcurrentWithdrawalFixture()
            TestSecurityUtils.setupSecurityContext()
            try {
                seedConcurrentWithdrawalFixture(fixture)
                val service = createParticipantPurgeService(Mockito.mock(AuditingManager::class.java))
                val first = fixture.devices.first()
                val secondKey = installRawDeviceKey(fixture.devices.last().keyId)
                val owner = service.executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                    first.randomDeviceId, first.keyId, first.requestId)
                if (complete) {
                    processDeletion(DataDeletionOrchestrator(storageResolver,
                        Mockito.mock(AuditingManager::class.java)), requireNotNull(owner.deletionOperationId))
                    // Completion deleted the second device's key; its tombstone carries the acknowledgment
                    // (MobileEnrollmentController answers an erased key as already withdrawn).
                    assertNull(apiKeys().authenticateWithdrawalApiKey(secondKey, fixture.devices.last().requestId))
                    assertTrue(apiKeys().isErasedDeviceKey(secondKey))
                    continue
                }
                val second = fixture.devices.last()
                asDeviceApplication(fixture, second.keyId) { resolver ->
                    val audit = Mockito.mock(AuditingManager::class.java)
                    val scoped = ParticipantPurgeService(resolver, DataDeletionOrchestrator(resolver, audit),
                        ApiKeyService(resolver, Mockito.mock(HazelcastIdGenerationService::class.java), audit), audit)
                    val result = scoped.executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                        second.randomDeviceId, second.keyId, second.requestId)
                    assertTrue(result.alreadyWithdrawn)
                    assertEquals(result, scoped.executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                        second.randomDeviceId, second.keyId, second.requestId))
                }
            } finally {
                deleteTombstones(fixture)
                cleanupConcurrentWithdrawalFixture(fixture)
                TestSecurityUtils.clearSecurityContext()
            }
        }
    }

    @Test
    fun testResearcherDeletedParticipantDeviceLearnsNotEnrolledAndKeyIsTombstonedAtCompletion() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            val otherKey = installRawDeviceKey(fixture.devices.last().keyId)
            fun authenticate() = asDeviceApplication(fixture, device.keyId) { apiKeys(it).authenticateApiKey(rawKey) }

            assertTrue("a live enrollment slides its device key",
                requireNotNull(authenticate()).expiresAt.isAfter(OffsetDateTime.now().plusDays(300)))
            assertFalse("a live key has no tombstone", apiKeys().isErasedDeviceKey(rawKey))
            resetDeviceKeyExpiry(device.keyId)

            // The two steps of StudyController.deleteStudyParticipants.
            getConnection().use { connection ->
                executeSingleUpdate(connection,
                    "UPDATE study_participants SET participation_status = 'NOT_ENROLLED' WHERE study_id = ? AND participant_id = ?",
                ) {
                    it.setObject(1, fixture.studyId)
                    it.setString(2, fixture.participantId)
                }
            }
            val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
            val operationId = orchestrator.quarantineParticipant(
                fixture.studyId, fixture.participantId, DataDeletionMode.WITHDRAW_AND_ERASE,
                "migration-test", UUID.randomUUID(),
            )

            assertDeletedForDevice(fixture, device.keyId)
            val quarantined = requireNotNull(authenticate()) { "the phone keeps its key through the quarantine" }
            assertTrue("a deleted participant's key must not slide",
                quarantined.expiresAt.isBefore(OffsetDateTime.now().plusDays(2)))
            assertEquals("keys are kept through the quarantine", fixture.devices.size, deviceKeyRows(fixture))
            assertNull("nothing is tombstoned at quarantine", tombstoneKind(rawKey))

            processDeletion(orchestrator, operationId)

            assertDeletedForDevice(fixture, device.keyId)
            assertEquals("completion deletes every device key", 0, deviceKeyRows(fixture))
            assertEquals("WITHDRAW_AND_ERASE", tombstoneKind(rawKey))
            assertEquals("WITHDRAW_AND_ERASE", tombstoneKind(otherKey))
            assertNull("an erased key fails ordinary authentication", authenticate())
            assertNull("an erased key fails the withdrawal lookup", asDeviceApplication(fixture, device.keyId) {
                apiKeys(it).authenticateWithdrawalApiKey(rawKey, UUID.randomUUID())
            })
            assertTrue("the tombstone still admits the status read and withdrawal acknowledgment",
                apiKeys().isErasedDeviceKey(rawKey))
            assertTombstonesCarryNoIdentifiers()
        } finally {
            deleteTombstones(fixture)
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testStudyErasureTellsDevicesErasedStopsKeySlidingAndTombstonesKeys() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
            val operationId = orchestrator.quarantineStudy(fixture.studyId, "migration-test", UUID.randomUUID())

            // Study erasure leaves the row ENROLLED but the device reads NOT_ENROLLED.
            assertDeletedForDevice(fixture, device.keyId)
            for (key in listOf(
                asDeviceApplication(fixture, device.keyId) { apiKeys(it).authenticateApiKey(rawKey) },
                apiKeys().authenticateApiKey(rawKey), // the pool owner, as before RLSContextFilter runs
            )) {
                assertTrue("a key under study erasure must not slide",
                    requireNotNull(key).expiresAt.isBefore(OffsetDateTime.now().plusDays(2)))
            }

            processDeletion(orchestrator, operationId)

            assertEquals("study erasure deletes every device key", 0, deviceKeyRows(fixture))
            assertEquals("STUDY_ERASURE", tombstoneKind(rawKey))
            assertNull(apiKeys().authenticateApiKey(rawKey))
            assertTrue(apiKeys().isErasedDeviceKey(rawKey))
            assertTombstonesCarryNoIdentifiers()
        } finally {
            deleteTombstones(fixture)
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testCollectedDataPurgeKeepsTheDeviceEnrolledAndItsKeySliding() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java)).quarantineParticipant(
                fixture.studyId, fixture.participantId, DataDeletionMode.COLLECTED_DATA_PURGE,
                "migration-test", UUID.randomUUID(),
            )

            asDeviceApplication(fixture, device.keyId) { resolver ->
                val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(
                    resolver,
                    Mockito.mock(HazelcastIdGenerationService::class.java),
                    Mockito.mock(com.openlattice.chronicle.services.candidates.CandidateManager::class.java),
                )
                assertFalse("the purge quarantine hides the row from the device context",
                    enrollment.isKnownParticipant(fixture.studyId, fixture.participantId))
                assertEquals("a purge keeps the enrollment, so the phone keeps collecting",
                    com.openlattice.chronicle.data.ParticipationStatus.ENROLLED,
                    enrollment.getDeviceParticipationStatus(fixture.studyId, fixture.participantId))
            }
            assertTrue("a purged participant's key keeps sliding", requireNotNull(
                asDeviceApplication(fixture, device.keyId) { apiKeys(it).authenticateApiKey(rawKey) },
            ).expiresAt.isAfter(OffsetDateTime.now().plusDays(300)))
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testManuallyRevokedKeyOfLiveParticipantGetsNoErasureAnswer() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            getConnection().use { connection ->
                executeSingleUpdate(connection, "UPDATE api_keys SET revoked = true WHERE key_id = ?") {
                    it.setObject(1, device.keyId)
                }
            }
            assertNull(apiKeys().authenticateApiKey(rawKey))
            assertFalse("a revoked key of a live participant must stay 401 on the status read",
                apiKeys().isErasedDeviceKey(rawKey))
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    @Test
    fun testDeviceStatusOfMissingEnrollmentWithoutErasureEvidenceThrows() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            getConnection().use { connection ->
                executeSingleUpdate(connection, "DELETE FROM study_participants WHERE study_id = ?") {
                    it.setObject(1, fixture.studyId)
                }
            }
            asDeviceApplication(fixture, device.keyId) { resolver ->
                val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(
                    resolver,
                    Mockito.mock(HazelcastIdGenerationService::class.java),
                    Mockito.mock(com.openlattice.chronicle.services.candidates.CandidateManager::class.java),
                )
                assertThrows("a missing row without erasure evidence must not read as NOT_ENROLLED",
                    IllegalStateException::class.java) {
                    enrollment.getDeviceParticipationStatus(fixture.studyId, fixture.participantId)
                }
            }
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    @Test
    fun testExpiredErasedKeyTombstonesAreDeletedOnLookupAndByTheDeletionWorker() {
        val onLookup = "expired-lookup-${UUID.randomUUID()}"
        val byWorker = "expired-worker-${UUID.randomUUID()}"
        val live = "live-${UUID.randomUUID()}"
        fun insertTombstone(hash: String, expiresIn: String) = getConnection().use { connection ->
            executeSingleUpdate(connection,
                "INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at) VALUES (?, 'STUDY_ERASURE', now() + ?::interval)",
            ) {
                it.setString(1, hash)
                it.setString(2, expiresIn)
            }
        }
        fun exists(hash: String) = getConnection().use { connection ->
            connection.prepareStatement("SELECT count(*) FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, hash)
                it.executeQuery().use { result -> check(result.next()); result.getInt(1) == 1 }
            }
        }
        try {
            insertTombstone(onLookup, "-1 second")
            insertTombstone(live, "1 day")
            assertFalse(apiKeys().isErasedDeviceKey("ck_unknown_${UUID.randomUUID()}"))
            assertFalse("a lookup deletes expired tombstones", exists(onLookup))
            assertTrue("a lookup keeps unexpired tombstones", exists(live))

            insertTombstone(byWorker, "-1 second")
            DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
                .processDueOperations(limit = 1)
            assertFalse("the deletion worker deletes expired tombstones", exists(byWorker))
            assertTrue(exists(live))
        } finally {
            getConnection().use { connection ->
                executeSingleUpdate(connection, "DELETE FROM erased_device_key_tombstones WHERE key_hash IN (?, ?, ?)",
                    expected = null) {
                    it.setString(1, onLookup)
                    it.setString(2, byWorker)
                    it.setString(3, live)
                }
            }
        }
    }

    @Test
    fun testErasedKeyTombstoneNeverAnswersForAHashAnApiKeyRowStillOwns() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            getConnection().use { connection ->
                executeSingleUpdate(connection,
                    "INSERT INTO erased_device_key_tombstones (key_hash, kind, expires_at) VALUES (?, 'WITHDRAW_AND_ERASE', now() + interval " +
                        "'1 day')",
                ) { it.setString(1, sha256Hex(rawKey)) }
                executeSingleUpdate(connection, "UPDATE api_keys SET revoked = true WHERE key_id = ?") { it.setObject(1, device.keyId) }
            }
            assertFalse("a revoked row still owns the hash", apiKeys().isErasedDeviceKey(rawKey))
            getConnection().use { connection ->
                executeSingleUpdate(connection, "DELETE FROM api_keys WHERE key_id = ?") { it.setObject(1, device.keyId) }
            }
            assertTrue("once no row owns the hash the tombstone answers", apiKeys().isErasedDeviceKey(rawKey))
        } finally {
            deleteTombstones(fixture)
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testDeviceKeyThatExpiresDuringTheQuarantineIsStillTombstonedForAYear() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(device.keyId)
            val (orchestrator, operationId) = deleteParticipantForDevices(fixture)
            // The phone stays offline past its key's own expiry while the quarantine runs.
            getConnection().use { connection ->
                executeSingleUpdate(connection, "UPDATE api_keys SET expires_at = now() - interval '1 minute' WHERE study_id = ?",
                    expected = null) { it.setObject(1, fixture.studyId) }
            }
            assertNull(apiKeys().authenticateApiKey(rawKey))
            assertFalse("nothing is tombstoned before completion", apiKeys().isErasedDeviceKey(rawKey))

            processDeletion(orchestrator, operationId)

            assertEquals(0, deviceKeyRows(fixture))
            assertEquals("the expired key is tombstoned too", "WITHDRAW_AND_ERASE", tombstoneKind(rawKey))
            assertTrue("the tombstone lives a year from erasure, not from the key's expiry",
                requireNotNull(tombstoneExpiry(rawKey)).isAfter(OffsetDateTime.now().plusDays(364)))
            assertTrue(apiKeys().isErasedDeviceKey(rawKey))
            assertDeletedForDevice(fixture, device.keyId)
        } finally {
            deleteTombstones(fixture)
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testTombstonedOrRevokedDeviceKeyMaterialIsNeverInstalledAgain() {
        val erased = ConcurrentWithdrawalFixture()
        val live = ConcurrentWithdrawalFixture()
        val liveDevice = live.devices.first().randomDeviceId
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val installer = ApiKeyService(storageResolver, ids, Mockito.mock(AuditingManager::class.java))
        fun proposedKey() = UUID.randomUUID().toString().replace("-", "").let { "ck_${it.take(8)}_$it" }
        fun install(rawKey: String): Boolean {
            val attemptId = UUID.randomUUID()
            bindEnrollmentAttempt(live, liveDevice, attemptId, sha256Hex(rawKey))
            return try {
                installer.installMobileApiKey(live.studyId, live.participantId, liveDevice, attemptId, rawKey)
                true
            } catch (_: com.openlattice.chronicle.services.apikeys.InvalidEnrollmentAttemptException) {
                false
            }
        }
        val erasedKeys = erased.devices.map { proposedKey() }
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(erased)
            seedConcurrentWithdrawalFixture(live)
            getConnection().use { connection ->
                erased.devices.zip(erasedKeys).forEach { (device, rawKey) ->
                    executeSingleUpdate(connection, "UPDATE api_keys SET key_hash = ? WHERE key_id = ?") {
                        it.setString(1, sha256Hex(rawKey))
                        it.setObject(2, device.keyId)
                    }
                }
                executeSingleUpdate(connection, "UPDATE api_keys SET revoked = true WHERE key_id = ?") {
                    it.setObject(1, erased.devices.last().keyId)
                }
            }
            assertFalse("a revoked key's material is never installed again", install(erasedKeys.last()))

            val (orchestrator, operationId) = deleteParticipantForDevices(erased)
            processDeletion(orchestrator, operationId)
            erasedKeys.forEach { assertEquals("WITHDRAW_AND_ERASE", tombstoneKind(it)) }
            assertFalse("a tombstoned key is never installed for another participant", install(erasedKeys.first()))

            val fresh = proposedKey()
            assertTrue(install(fresh))
            assertEquals(live.participantId, requireNotNull(apiKeys().authenticateApiKey(fresh)).participantId)
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM api_keys JOIN erased_device_key_tombstones USING (key_hash)",
                    ).use { assertTrue(it.next()); assertEquals("no key shares its hash with a tombstone", 0, it.getInt(1)) }
                }
                executeSingleUpdate(connection, "UPDATE api_keys SET revoked = true WHERE key_hash = ?") {
                    it.setString(1, sha256Hex(fresh))
                }
            }
            assertNull(apiKeys().authenticateApiKey(fresh))
            assertFalse("a manually revoked live key never reaches the tombstone path", apiKeys().isErasedDeviceKey(fresh))
        } finally {
            getConnection().use { connection ->
                erasedKeys.forEach { rawKey ->
                    executeSingleUpdate(connection, "DELETE FROM erased_device_key_tombstones WHERE key_hash = ?",
                        expected = null) { it.setString(1, sha256Hex(rawKey)) }
                }
            }
            cleanupConcurrentWithdrawalFixture(erased)
            cleanupConcurrentWithdrawalFixture(live)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testInstallingKeyMaterialWaitsForAnotherDeviceThatOwnsTheSameHash() {
        val other = ConcurrentWithdrawalFixture()
        val live = ConcurrentWithdrawalFixture()
        val liveDevice = live.devices.first().randomDeviceId
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val installer = ApiKeyService(storageResolver, ids, Mockito.mock(AuditingManager::class.java))
        val rawKey = UUID.randomUUID().toString().replace("-", "").let { "ck_${it.take(8)}_$it" }
        val hash = sha256Hex(rawKey)
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(other)
            seedConcurrentWithdrawalFixture(live)
            val attemptId = UUID.randomUUID()
            bindEnrollmentAttempt(live, liveDevice, attemptId, hash)
            getConnection().use { owner ->
                owner.autoCommit = false
                // Another device's installation of the same material, paused after its used-material check.
                owner.prepareStatement(ApiKeyService.LOCK_KEY_MATERIAL_SQL).use { it.setString(1, hash); it.execute() }
                val install = java.util.concurrent.CompletableFuture.supplyAsync {
                    try {
                        installer.installMobileApiKey(live.studyId, live.participantId, liveDevice, attemptId, rawKey)
                        true
                    } catch (_: com.openlattice.chronicle.services.apikeys.InvalidEnrollmentAttemptException) {
                        false
                    }
                }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                while (!install.isDone && waitingAdvisoryLocks() == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
                assertFalse("the second installation waits for the hash lock", install.isDone)
                executeSingleUpdate(owner, "UPDATE api_keys SET key_hash = ? WHERE key_id = ?") {
                    it.setString(1, hash)
                    it.setObject(2, other.devices.first().keyId)
                }
                owner.commit()
                assertFalse("material another device owns is never installed", install.get(20, java.util.concurrent.TimeUnit.SECONDS))
            }
            getConnection().use { connection ->
                connection.prepareStatement("SELECT count(*) FROM api_keys WHERE key_hash = ?").use { statement ->
                    statement.setString(1, hash)
                    statement.executeQuery().use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
                }
            }
        } finally {
            cleanupConcurrentWithdrawalFixture(other)
            cleanupConcurrentWithdrawalFixture(live)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testErasureTombstoningWaitsForAnInstallationHoldingTheHash() {
        val fixture = ConcurrentWithdrawalFixture()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val rawKey = installRawDeviceKey(fixture.devices.first().keyId)
            val (orchestrator, operationId) = deleteParticipantForDevices(fixture)
            getConnection().use { installer ->
                installer.autoCommit = false
                installer.prepareStatement(ApiKeyService.LOCK_KEY_MATERIAL_SQL).use {
                    it.setString(1, sha256Hex(rawKey))
                    it.execute()
                }
                val erasure = java.util.concurrent.CompletableFuture.supplyAsync {
                    orchestrator.eraseNow(operationId, fixture.studyId, "migration-test"); 1
                }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                while (!erasure.isDone && waitingAdvisoryLocks() == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
                assertFalse("tombstoning waits for the installation holding the hash", erasure.isDone)
                installer.commit()
                assertEquals(1, erasure.get(60, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertEquals("WITHDRAW_AND_ERASE", tombstoneKind(rawKey))
        } finally {
            deleteTombstones(fixture)
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    private fun waitingAdvisoryLocks(): Int = getConnection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted").use {
                check(it.next())
                it.getInt(1)
            }
        }
    }

    /** The two steps of StudyController.deleteStudyParticipants: NOT_ENROLLED, then a WITHDRAW_AND_ERASE quarantine. */
    private fun deleteParticipantForDevices(fixture: ConcurrentWithdrawalFixture): Pair<DataDeletionOrchestrator, UUID> {
        getConnection().use { connection ->
            executeSingleUpdate(connection,
                "UPDATE study_participants SET participation_status = 'NOT_ENROLLED' WHERE study_id = ? AND participant_id = ?",
            ) {
                it.setObject(1, fixture.studyId)
                it.setString(2, fixture.participantId)
            }
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        return orchestrator to orchestrator.quarantineParticipant(
            fixture.studyId, fixture.participantId, DataDeletionMode.WITHDRAW_AND_ERASE,
            "migration-test", UUID.randomUUID(),
        )
    }

    private fun tombstoneExpiry(rawKey: String): OffsetDateTime? = getConnection().use { connection ->
        connection.prepareStatement("SELECT expires_at FROM erased_device_key_tombstones WHERE key_hash = ?").use { statement ->
            statement.setString(1, sha256Hex(rawKey))
            statement.executeQuery().use { if (it.next()) it.getObject(1, OffsetDateTime::class.java) else null }
        }
    }

    /** A bound enrollment invitation that authorizes installing [keyHash] for this device and attempt. */
    private fun bindEnrollmentAttempt(fixture: ConcurrentWithdrawalFixture, randomDeviceId: UUID, attemptId: UUID, keyHash: String) =
        getConnection().use { connection ->
            executeSingleUpdate(
                connection,
                """
                INSERT INTO participant_form_access_codes (
                    access_code_id, token_hash, study_id, participant_id, form_kind, issuer_type, issued_by,
                    expires_at, exchanged_at, enrollment_attempt_id, enrollment_source_device_hash,
                    enrollment_random_device_id, enrollment_manifest_digest, enrollment_request_hash,
                    enrollment_proposed_key_hash, enrollment_replay_expires_at, enrollment_settings_version,
                    enrollment_disclosure_version, enrollment_enabled_modules, enrollment_required_modules
                ) VALUES (?, decode(md5(?::text), 'hex'), ?, ?, 'ENROLLMENT', 'RESEARCHER', 'migration-test',
                          now() + interval '1 day', now(), ?, repeat('1', 64), ?, repeat('2', 64), repeat('3', 64),
                          ?, now() + interval '1 hour', 1, 'disclosure-v1', '[]'::jsonb, '[]'::jsonb)
                """.trimIndent(),
            ) {
                it.setObject(1, attemptId)
                it.setObject(2, attemptId)
                it.setObject(3, fixture.studyId)
                it.setString(4, fixture.participantId)
                it.setObject(5, attemptId)
                it.setObject(6, randomDeviceId)
                it.setString(7, keyHash)
            }
        }

    private fun apiKeys(resolver: StorageResolver = storageResolver) =
        ApiKeyService(resolver, Mockito.mock(HazelcastIdGenerationService::class.java), Mockito.mock(AuditingManager::class.java))

    private fun deviceKeyRows(fixture: ConcurrentWithdrawalFixture): Int = getConnection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM api_keys WHERE study_id = ?").use { statement ->
            statement.setObject(1, fixture.studyId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }
    }

    private fun tombstoneKind(rawKey: String): String? = getConnection().use { connection ->
        connection.prepareStatement("SELECT kind FROM erased_device_key_tombstones WHERE key_hash = ?").use { statement ->
            statement.setString(1, sha256Hex(rawKey))
            statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString(1) else null }
        }
    }

    /** The tombstone table holds the key hash, the erasure kind and the expiry: no study, participant or device. */
    private fun assertTombstonesCarryNoIdentifiers() = getConnection().use { connection ->
        connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'erased_device_key_tombstones' ORDER BY column_name",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                val columns = buildList { while (resultSet.next()) add(resultSet.getString(1)) }
                assertEquals(listOf("expires_at", "key_hash", "kind"), columns)
            }
        }
    }

    private fun deleteTombstones(fixture: ConcurrentWithdrawalFixture) = getConnection().use { connection ->
        connection.prepareStatement("DELETE FROM erased_device_key_tombstones WHERE key_hash = ?").use { statement ->
            fixture.devices.forEach { device ->
                statement.setString(1, sha256Hex("ck_status__${device.keyId.toString().replace("-", "")}"))
                statement.executeUpdate()
            }
        }
    }

    private fun sha256Hex(rawKey: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(rawKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Device status preserves erasure; form-access-code minting finds no participant. */
    private fun assertDeletedForDevice(fixture: ConcurrentWithdrawalFixture, keyId: UUID) {
        asDeviceApplication(fixture, keyId) { resolver ->
            val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(
                resolver,
                Mockito.mock(HazelcastIdGenerationService::class.java),
                Mockito.mock(com.openlattice.chronicle.services.candidates.CandidateManager::class.java),
            )
            assertEquals(
                com.openlattice.chronicle.data.ParticipationStatus.ERASED,
                enrollment.getDeviceParticipationStatus(fixture.studyId, fixture.participantId),
            )
            assertFalse(enrollment.isKnownParticipant(fixture.studyId, fixture.participantId))
        }
    }

    private fun installRawDeviceKey(keyId: UUID): String {
        val rawKey = "ck_status__${keyId.toString().replace("-", "")}"
        val hash = sha256Hex(rawKey)
        getConnection().use { connection ->
            executeSingleUpdate(connection, "UPDATE api_keys SET key_hash = ? WHERE key_id = ?") {
                it.setString(1, hash)
                it.setObject(2, keyId)
            }
        }
        return rawKey
    }

    private fun resetDeviceKeyExpiry(keyId: UUID) = getConnection().use { connection ->
        executeSingleUpdate(connection, "UPDATE api_keys SET expires_at = now() + interval '1 day' WHERE key_id = ?") {
            it.setObject(1, keyId)
        }
    }

    private fun <T> withDeviceRequestContext(
        fixture: ConcurrentWithdrawalFixture,
        device: ConcurrentWithdrawalDevice,
        action: () -> T,
    ): T {
        val previous = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val authentication = com.openlattice.chronicle.filters.ApiKeyAuthenticationToken(
            principal = "apikey:${device.keyId}", keyId = device.keyId, studyId = fixture.studyId,
            participantId = fixture.participantId, randomDeviceId = device.randomDeviceId,
            scope = com.openlattice.chronicle.apikey.ApiKeyScope.WRITE,
            authorities = listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_API_KEY")),
        )
        val security = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext()
        security.authentication = authentication
        org.springframework.security.core.context.SecurityContextHolder.setContext(security)
        return try {
            val context = com.openlattice.chronicle.storage.rls.RLSContextManager(Mockito.mock(AuthorizationManager::class.java))
                .getCurrentUserContext()
            assertEquals(setOf(fixture.studyId), context.authorizedStudyIds)
            assertFalse(context.isAdmin)
            assertFalse(context.isDeletionWorker)
            RLSRequestContext.withContext(context, action)
        } finally { org.springframework.security.core.context.SecurityContextHolder.setContext(previous) }
    }

    /** Runs as the application role with the mobile RLS context RLSContextManager gives a device key. */
    private fun <T> asDeviceApplication(
        fixture: ConcurrentWithdrawalFixture,
        keyId: UUID,
        block: (StorageResolver) -> T,
    ): T = getConnection().use { connection ->
        connection.createStatement().use { it.execute("SET ROLE chronicle_app") }
        try {
            val nonclosing = java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader,
                arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "close") null else try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            } as Connection
            val borrowed = Mockito.mock(com.zaxxer.hikari.HikariDataSource::class.java)
            Mockito.`when`(borrowed.connection).thenAnswer {
                RLSConnectionSql.applyContext(connection, requireNotNull(RLSRequestContext.current()))
                nonclosing
            }
            val resolver = Mockito.mock(StorageResolver::class.java)
            Mockito.`when`(resolver.getPlatformStorage()).thenReturn(borrowed)
            val device = fixture.devices.single { it.keyId == keyId }
            withDeviceRequestContext(fixture, device) { block(resolver) }
        } finally {
            connection.createStatement().use { statement ->
                statement.execute("RESET ROLE")
                statement.execute(com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.CONNECTION_INIT_SQL)
            }
        }
    }

    @Test
    fun testSweepParticipantErasureReconcilesPersistedSteps() = sweepParticipantAssetErasure("legacy")

    @Test
    fun testSweepWithdrawalErasesDeviceMetadata() = sweepParticipantAssetErasure("devices")

    @Test
    fun testSweepDeviceQuarantineAndErasureRespectCollectedPurgeVersusWithdrawal() {
        for (mode in listOf(DataDeletionMode.COLLECTED_DATA_PURGE, DataDeletionMode.WITHDRAW_AND_ERASE)) {
            val fixture = ConcurrentWithdrawalFixture()
            val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
            try {
                seedConcurrentWithdrawalFixture(fixture)
                getConnection().use { connection ->
                    connection.createStatement().use { it.execute("""INSERT INTO devices
                        (study_id, random_device_id, participant_id, device_type, source_device)
                        VALUES ('${fixture.studyId}', '${fixture.devices.first().randomDeviceId}', '${fixture.participantId}', 'Android', '{}'::jsonb)""") }
                }
                val operation = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId, mode,
                    "sweep", UUID.randomUUID())
                postgres.createConnection("").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET ROLE chronicle_app")
                        statement.execute("SELECT set_config('app.is_admin', 'true', false)")
                        statement.executeQuery("SELECT count(*) FROM devices WHERE study_id = '${fixture.studyId}'").use {
                            assertTrue(it.next())
                            assertEquals(if (mode == DataDeletionMode.COLLECTED_DATA_PURGE) 1L else 0L, it.getLong(1))
                        }
                    }
                }
                if (mode == DataDeletionMode.WITHDRAW_AND_ERASE) assertThrows(SQLException::class.java) {
                    getConnection().use { connection -> connection.createStatement().use {
                        it.execute("UPDATE devices SET source_device = '{}'::jsonb WHERE study_id = '${fixture.studyId}'")
                    } }
                }
                processDeletion(orchestrator, operation)
                assertEquals(if (mode == DataDeletionMode.COLLECTED_DATA_PURGE) 1L else 0L, sweepCount(fixture, "devices"))
            } finally {
                sweepCleanupRows(fixture, listOf("devices"))
                cleanupConcurrentWithdrawalFixture(fixture)
            }
        }
    }

    @Test
    fun testCollectedPurgeKeepsExchangedEnrollmentCodeThatWithdrawalErases() {
        for (mode in listOf(DataDeletionMode.COLLECTED_DATA_PURGE, DataDeletionMode.WITHDRAW_AND_ERASE)) {
            val fixture = ConcurrentWithdrawalFixture()
            val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
            val enrollmentCode = UUID.randomUUID()
            val questionnaireCode = UUID.randomUUID()
            try {
                seedConcurrentWithdrawalFixture(fixture)
                getConnection().use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO participant_form_access_codes (
                            access_code_id, token_hash, study_id, participant_id, form_kind,
                            issuer_type, issued_by, expires_at, exchanged_at
                        ) VALUES (?, ?, ?, ?, ?, 'RESEARCHER', 'sweep', now() + interval '1 day', ?)
                        """.trimIndent(),
                    ).use { statement ->
                        for ((codeId, kind) in listOf(enrollmentCode to "ENROLLMENT", questionnaireCode to "QUESTIONNAIRE")) {
                            statement.setObject(1, codeId)
                            statement.setBytes(2, codeId.toString().toByteArray())
                            statement.setObject(3, fixture.studyId)
                            statement.setString(4, fixture.participantId)
                            statement.setString(5, kind)
                            statement.setObject(6, OffsetDateTime.now())
                            statement.executeUpdate()
                        }
                    }
                }
                val operation = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId, mode,
                    "sweep", UUID.randomUUID())
                val activeCodes = { kind: String ->
                    getConnection().use { connection ->
                        connection.prepareStatement(
                            "SELECT count(*) FROM participant_form_access_codes WHERE study_id = ? AND form_kind = ? AND revoked_at IS NULL",
                        ).use {
                            it.setObject(1, fixture.studyId); it.setString(2, kind)
                            it.executeQuery().use { result -> check(result.next()); result.getLong(1) }
                        }
                    }
                }
                val keepsEvidence = if (mode == DataDeletionMode.COLLECTED_DATA_PURGE) 1L else 0L
                // Collection acknowledgments require the exchanged enrollment code unrevoked.
                assertEquals(keepsEvidence, activeCodes("ENROLLMENT"))
                assertEquals(0L, activeCodes("QUESTIONNAIRE"))
                processDeletion(orchestrator, operation)
                assertEquals(keepsEvidence, activeCodes("ENROLLMENT"))
                assertEquals(keepsEvidence, sweepCount(fixture, "participant_form_access_codes"))
            } finally {
                sweepCleanupRows(fixture, listOf("participant_form_access_codes"))
                cleanupConcurrentWithdrawalFixture(fixture)
            }
        }
    }

    @Test
    fun testSweepParticipantErasureDeletesWebhookPayloads() = sweepParticipantAssetErasure("webhook")

    private fun sweepParticipantAssetErasure(finding: String) {
        val fixture = ConcurrentWithdrawalFixture()
        val webhookId = UUID.randomUUID()
        val audit = Mockito.mock(AuditingManager::class.java)
        val orchestrator = DataDeletionOrchestrator(storageResolver, audit)
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("""INSERT INTO devices (study_id, random_device_id, participant_id, device_type, source_device)
                        VALUES ('${fixture.studyId}', '${fixture.devices.first().randomDeviceId}', '${fixture.participantId}', 'Android', '{}'::jsonb)""")
                    statement.execute("""INSERT INTO webhook_registrations (webhook_id, study_id, url, secret_hash, event_types, created_by)
                        VALUES ('$webhookId', '${fixture.studyId}', 'https://example.org/hook', 'hash', ARRAY['PARTICIPANT_ENROLLED'], 'sweep')""")
                    statement.execute("""INSERT INTO webhook_deliveries (delivery_id, webhook_id, event_type, participant_id, payload, delivery_state)
                        VALUES ('${UUID.randomUUID()}', '$webhookId', 'PARTICIPANT_ENROLLED', '${fixture.participantId}',
                        '{"studyId":"${fixture.studyId}","data":{"participantId":"${fixture.participantId}"}}'::jsonb, 'PENDING')""")
                }
            }
            val operationId = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                DataDeletionMode.WITHDRAW_AND_ERASE, "sweep", UUID.randomUUID())
            if (finding == "legacy") getConnection().use { connection ->
                connection.createStatement().use { it.execute(
                    "DELETE FROM data_deletion_steps WHERE operation_id = '$operationId' AND asset_id IN ('notifications', " +
                    "'participant-pseudonyms')") }
            }
            processDeletion(orchestrator, operationId)
            if (finding == "devices") assertEquals(0L, sweepCount(fixture, "devices"))
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM webhook_deliveries WHERE webhook_id = '$webhookId'").use {
                        assertTrue(it.next()); if (finding == "webhook") assertEquals(0L, it.getLong(1))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM webhook_registrations WHERE webhook_id = '$webhookId'")
                }
            }
            sweepCleanupRows(fixture, listOf("devices"))
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testSweepCompletedPurgeAcknowledgesOldSensorRetryAndKeepsFreshRecords() {
        val fixture = ConcurrentWithdrawalFixture()
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val upload = AndroidSensorDataUploadService(storageResolver)
            val old = AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.accelerometer,
                OffsetDateTime.now().minusDays(1), "UTC", values = listOf(1f, 2f, 3f))
            val device = fixture.devices.first().randomDeviceId
            assertEquals(1, upload.upload(fixture.studyId, fixture.participantId, device, listOf(old)))
            val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
            val operation = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE, "sweep", UUID.randomUUID())
            processDeletion(orchestrator, operation)
            val fresh = old.copy(id = UUID.randomUUID(), timestamp = OffsetDateTime.now().plusSeconds(1))
            assertEquals(1, upload.upload(fixture.studyId, fixture.participantId, device, listOf(old)))
            assertEquals(0L, sweepCount(fixture, "android_sensor_data"))
            assertEquals(2, upload.upload(fixture.studyId, fixture.participantId, device, listOf(old, fresh)))
            assertEquals(1L, sweepCount(fixture, "android_sensor_data"))
        } finally {
            sweepCleanupRows(fixture, listOf("android_sensor_data"))
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testSweepFinalVerificationDoesNotInvertStudyRevisionWriters() {
        val targetStudy = UUID.randomUUID()
        val controlStudy = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        val executor = Executors.newSingleThreadExecutor()
        var operationId: UUID? = null
        fun setting(version: Int) = ObjectMappers.newJsonMapper().writeValueAsString(StudySettings(mapOf(
            StudySettingType.DataCollection to AndroidDataCollectionSetting(
                modules = mapOf(CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true, required = false)),
                settingsVersion = version,
            ),
        )))
        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO studies (study_id, title, settings) VALUES (?, 'finalization-target', ?::jsonb), (?, 'revision-control', " +
                    "?::jsonb)").use {
                    it.setObject(1, targetStudy); it.setString(2, setting(1))
                    it.setObject(3, controlStudy); it.setString(4, setting(1)); it.executeUpdate()
                }
            }
            operationId = orchestrator.quarantineStudy(targetStudy, "sweep", UUID.randomUUID())
            getConnection().use { writer ->
                writer.autoCommit = false
                writer.createStatement().use {
                    it.execute("SET LOCAL statement_timeout = '2s'")
                    it.execute("SET LOCAL deadlock_timeout = '10s'")
                    it.execute("UPDATE studies SET title = title WHERE study_id = '$controlStudy'")
                }
                val deletion = executor.submit<Int> { orchestrator.eraseNow(checkNotNull(operationId), targetStudy, "sweep"); 1 }
                try {
                    assertEquals("Other studies' writers must not delay final verification", 1, deletion.get(15, TimeUnit.SECONDS))
                    writer.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use {
                        it.setString(1, setting(2)); it.setObject(2, controlStudy); assertEquals(1, it.executeUpdate())
                    }
                    writer.commit()
                    assertEquals(1, deletion.get(15, TimeUnit.SECONDS))
                    assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
                } finally {
                    writer.rollback()
                    deletion.get(15, TimeUnit.SECONDS)
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            getConnection().use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM data_deletion_audit_outbox WHERE study_id = '$targetStudy'")
                    it.execute("DELETE FROM data_deletion_operations WHERE study_id = '$targetStudy'")
                    it.execute("DELETE FROM studies WHERE study_id IN ('$targetStudy', '$controlStudy')")
                }
            }
        }
    }

    @Test
    fun testSweepSelfWithdrawalSurvivesCancellationOfStudyQuarantine() {
        val fixture = ConcurrentWithdrawalFixture()
        val audit = Mockito.mock(AuditingManager::class.java)
        val orchestrator = DataDeletionOrchestrator(storageResolver, audit)
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            orchestrator.quarantineStudy(fixture.studyId, "sweep", UUID.randomUUID())
            val device = fixture.devices.first()
            val result = createParticipantPurgeService(audit).executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                device.randomDeviceId, device.keyId, device.requestId)
            assertFalse(result.alreadyWithdrawn)
            val withdrawal = requireNotNull(result.deletionOperationId)
            assertEquals(1, orchestrator.cancelStudyErasure(fixture.studyId, "sweep"))
            processDeletion(orchestrator, withdrawal)
            assertEquals("COMPLETED", orchestrator.getOperation(withdrawal).status)
            assertEquals(0L, sweepCount(fixture, "study_participants"))
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testSweepOrdinaryDrainAndScreenTimeRewriteDoNotBlockReinsertion() {
        val fixture = ConcurrentWithdrawalFixture()
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val buffer = """INSERT INTO upload_buffer (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
                VALUES ('${fixture.studyId}', '${fixture.participantId}', '[]'::jsonb, now(), 'Android', '${fixture.devices.first().randomDeviceId}')"""
            assertEquals(1, update(buffer))
            queryRow(ChroniclePostgresTables.getMoveSql(128, UploadType.Android, listOf(fixture.studyId))) { assertFalse(it.next()) }
            assertEquals(1, update(buffer))
            val screenTime = screenTimeRow(fixture)
            assertEquals(1, update(screenTime))
            assertEquals(1, update("DELETE FROM ios_screen_time_usage WHERE study_id = ?", fixture.studyId))
            assertEquals(1, update(screenTime))
        } finally {
            sweepCleanupRows(fixture, listOf("upload_buffer", "ios_screen_time_usage"))
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    @Test
    fun testSweepRepeatedPurgeCutoffIsDurableAndLeavesWithWithdrawal() {
        val fixture = ConcurrentWithdrawalFixture()
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        try {
            seedConcurrentWithdrawalFixture(fixture)
            var previous: OffsetDateTime? = null
            repeat(2) {
                val operation = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                    DataDeletionMode.COLLECTED_DATA_PURGE, "sweep", UUID.randomUUID())
                processDeletion(orchestrator, operation)
                getConnection().use { connection ->
                    connection.prepareStatement("""
                        SELECT cutoff, started_at FROM participant_purge_cutoffs cutoff
                        JOIN data_deletion_operations operation USING (study_id)
                        WHERE operation.operation_id = ?
                    """.trimIndent()).use { statement ->
                        statement.setObject(1, operation)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            val cutoff = rows.getObject(1, OffsetDateTime::class.java)
                            assertEquals(rows.getObject(2, OffsetDateTime::class.java), cutoff)
                            previous?.let { assertFalse(cutoff.isBefore(it)) }
                            previous = cutoff
                        }
                    }
                }
            }
            val withdrawal = orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                DataDeletionMode.WITHDRAW_AND_ERASE, "sweep", UUID.randomUUID())
            processDeletion(orchestrator, withdrawal)
            getConnection().use { connection ->
                connection.prepareStatement("SELECT count(*) FROM participant_purge_cutoffs WHERE study_id = ?").use {
                    it.setObject(1, fixture.studyId)
                    it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0L, rows.getLong(1)) }
                }
            }
        } finally { cleanupConcurrentWithdrawalFixture(fixture) }
    }

    private fun screenTimeRow(fixture: ConcurrentWithdrawalFixture): String = """INSERT INTO ios_screen_time_usage
        (study_id, participant_id, random_device_id, record_id, row_kind, captured_at,
            observation_start, observation_end, timezone, app_name, duration_seconds)
        VALUES ('${fixture.studyId}', '${fixture.participantId}', '${fixture.devices.first().randomDeviceId}', '${UUID.randomUUID()}',
            'application', '2026-01-01T02:05:00Z',
            '2026-01-01T01:00:00Z', '2026-01-01T02:00:00Z', 'UTC', 'Fixture', 60)"""

    @Test
    fun testSweepQueuedWebhookIsNotSentDuringParticipantQuarantine() = sweepWebhookErasure(inFlight = false)

    @Test
    fun testSweepQuarantineWaitsForInFlightWebhookAndBlocksTheNextDelivery() = sweepWebhookErasure(inFlight = true)

    private fun sweepWebhookErasure(inFlight: Boolean) {
        val fixture = ConcurrentWithdrawalFixture()
        val webhookId = UUID.randomUUID()
        val requests = AtomicInteger()
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tasks = mutableListOf<Runnable>()
        val executor = Mockito.mock(ExecutorService::class.java)
        Mockito.doAnswer { invocation -> tasks += invocation.getArgument<Runnable>(0); null }
            .`when`(executor).execute(Mockito.any(Runnable::class.java))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            sending.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204)
                .message("No Content").body(ByteArray(0).toResponseBody(null)).build()
        }.build()
        val service = WebhookService(storageResolver, Mockito.mock(HazelcastIdGenerationService::class.java),
            deliveryExecutor = executor, httpClientTemplate = client,
            hostResolver = { arrayOf(java.net.InetAddress.getByName("93.184.216.34")) })
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        val workers = Executors.newFixedThreadPool(2)
        try {
            seedConcurrentWithdrawalFixture(fixture)
            getConnection().use { connection ->
                connection.createStatement().use { it.execute("""INSERT INTO webhook_registrations
                    (webhook_id, study_id, url, secret_hash, event_types, created_by)
                    VALUES ('$webhookId', '${fixture.studyId}', 'https://example.org/hook', 'hash', ARRAY['DATA_SUBMITTED'], 'sweep')""") }
                assertEquals(1, service.enqueueEvent(connection, fixture.studyId, WebhookEventType.DATA_SUBMITTED,
                    WebhookPayload.DataSubmitted(ParticipantRef.fromParticipantId(fixture.participantId), ParticipantDataType.UsageEvents, 1)))
                if (inFlight) assertEquals(1, service.enqueueEvent(connection, fixture.studyId, WebhookEventType.DATA_SUBMITTED,
                    WebhookPayload.DataSubmitted(ParticipantRef.fromParticipantId(fixture.participantId), ParticipantDataType.UsageEvents, 2)))
            }
            service.dispatchPendingDeliveries()
            if (inFlight) {
                val delivery = workers.submit { tasks.removeAt(0).run() }
                assertTrue(sending.await(5, TimeUnit.SECONDS))
                val quarantine = workers.submit<UUID> {
                    RLSRequestContext.withSystemContext {
                        orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                            DataDeletionMode.WITHDRAW_AND_ERASE, "sweep", UUID.randomUUID())
                    }
                }
                assertThrows(java.util.concurrent.TimeoutException::class.java) { quarantine.get(300, TimeUnit.MILLISECONDS) }
                release.countDown()
                delivery.get(10, TimeUnit.SECONDS)
                quarantine.get(10, TimeUnit.SECONDS)
            } else {
                orchestrator.quarantineParticipant(fixture.studyId, fixture.participantId,
                    DataDeletionMode.WITHDRAW_AND_ERASE, "sweep", UUID.randomUUID())
                release.countDown()
            }
            tasks.forEach { it.run() }
            assertEquals(if (inFlight) 1 else 0, requests.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            getConnection().use { connection ->
                connection.createStatement().use { it.execute("DELETE FROM webhook_registrations WHERE webhook_id = '$webhookId'") }
            }
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    private fun sweepCount(fixture: ConcurrentWithdrawalFixture, table: String): Long =
        getConnection().use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id::text = ? AND participant_id = ?").use {
                it.setString(1, fixture.studyId.toString()); it.setString(2, fixture.participantId)
                it.executeQuery().use { result -> check(result.next()); result.getLong(1) }
            }
        }

    private fun studyRowCount(studyId: UUID, table: String): Long =
        getConnection().use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE study_id::text = ?").use {
                it.setString(1, studyId.toString())
                it.executeQuery().use { result -> check(result.next()); result.getLong(1) }
            }
        }

    private fun sweepCleanupRows(fixture: ConcurrentWithdrawalFixture, tables: List<String>) {
        getConnection().use { connection ->
            tables.forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE study_id::text = ?").use {
                    it.setString(1, fixture.studyId.toString()); it.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testStatsSqlFailuresDoNotRenderBoundParticipantLabels() {
        val key = ParticipantKey(UUID.randomUUID(), STATS_SENTINEL)
        val logger = LogManager.getLogger(ParticipantStatsMapstore::class.java)
        val priorLevel = logger.level
        Configurator.setLevel(logger.name, Level.DEBUG)
        try {
            getConnection().use { connection ->
                assertFalse("JDBC must omit server error details", connection.unwrap(BaseConnection::class.java).logServerErrorDetail)
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TEMP TABLE participant_stats (LIKE public.participant_stats INCLUDING ALL) ON COMMIT DROP")
                    statement.execute("ALTER TABLE pg_temp.participant_stats ADD CHECK (participant_id <> '$STATS_SENTINEL')")
                }
                val datasource = Mockito.mock(HikariDataSource::class.java)
                Mockito.`when`(datasource.connection).thenReturn(connection)
                ScopedLogCapture(ParticipantStatsMapstore::class.java).use { logs ->
                    val failure = assertThrows(IllegalStateException::class.java) {
                        ParticipantStatsMapstore(datasource).storeAll(mapOf(key to ParticipantStats(key.studyId, key.participantId)))
                    }
                    logger.error("Hazelcast write-behind retry", failure)
                    assertTrue("The real failure must be captured", logs.messages.isNotEmpty())
                    assertTrue("Rendered logs include the throwable", logs.messages.any { it.contains("Hazelcast write-behind retry") })
                    assertFalse("Rendered SQL failure must not include the label", logs.messages.joinToString("\n").contains(STATS_SENTINEL))
                    assertNull("No database cause may escape to Hazelcast", failure.cause)
                }
            }
        } finally {
            Configurator.setLevel(logger.name, priorLevel)
        }
    }

    @Test
    fun testParticipantTokenFunctionUsesAnUnprivilegedOwnerWithExplicitReadPermission() {
        val ownerPostgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_token_owner")
        try {
            ownerPostgres.start()
            ChronicleContractTestSchema.waitForQueryReady(ownerPostgres)
            createSecondaryTokenOwner(ownerPostgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(ownerPostgres)
            roundTripParticipantTokenKey(ownerPostgres)
            ownerPostgres.createConnection("").use(::assertParticipantTokenOwnerPrivileges)
        } finally {
            ownerPostgres.stop()
        }
    }

    private fun createSecondaryTokenOwner(container: PostgreSQLContainer<*>) {
        // PostgreSQL's OID-10 bootstrap superuser cannot be demoted; test the actual function owner.
        container.createConnection("").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE ROLE chronicle LOGIN SUPERUSER PASSWORD '${ChronicleContractTestSchema.TEST_PASSWORD}'")
            }
        }
        container.withUsername("chronicle")
    }

    private fun assertParticipantTokenOwnerPrivileges(connection: Connection) {
        connection.createStatement().use { statement ->
            // Membership permits the test to enter the request role; it grants no key-table rights.
            statement.execute("GRANT chronicle_app TO chronicle")
            statement.execute("ALTER ROLE chronicle NOSUPERUSER")
            statement.executeQuery("SELECT current_user = 'chronicle' AND NOT rolsuper FROM pg_roles WHERE rolname = current_user").use {
                assertTrue(it.next()); assertTrue(it.getBoolean(1))
            }
            // The full migration ran as chronicle, including its REVOKE from this actual owner.
            statement.executeQuery("SELECT length(chronicle_participant_block_token(gen_random_uuid(), 'P1')) = 64").use {
                assertTrue(it.next()); assertTrue(it.getBoolean(1))
            }
            statement.execute("SET ROLE chronicle_app")
            statement.executeQuery("SELECT length(chronicle_participant_block_token(gen_random_uuid(), 'P1')) = 64").use {
                assertTrue(it.next()); assertTrue(it.getBoolean(1))
            }
            assertThrows(SQLException::class.java) {
                statement.execute("CREATE TABLE public.token_authority_probe (value text)")
            }
            assertThrows(SQLException::class.java) {
                statement.executeQuery("SELECT key_bytes FROM public.chronicle_participant_token_key")
            }
        }
    }

    @Test
    fun testParticipantBlockTokensRejectUnkeyedDictionaries() {
        val studyId = UUID.randomUUID()
        val tokens = getConnection().use { connection ->
            connection.prepareStatement("SELECT chronicle_participant_block_token(?::uuid, ?::text)").use { statement ->
                (1..50).map { number ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "P$number")
                    statement.executeQuery().use { result -> assertTrue(result.next()); result.getString(1) }
                }
            }
        }
        val dictionary = (1..500).flatMap { number ->
            listOf("MD5", "SHA-256").map { algorithm ->
                MessageDigest.getInstance(algorithm).digest("$studyId:P$number".toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
        }.toSet()
        assertTrue("Persisted tokens must resist unkeyed label dictionaries", tokens.none(dictionary::contains))
        assertEquals(50, tokens.toSet().size)
        roundTripParticipantTokenKey(postgres)
        getConnection().use { connection ->
            connection.prepareStatement("SELECT chronicle_participant_block_token(?::uuid, 'P1')").use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { result -> assertTrue(result.next()); assertEquals(tokens.first(), result.getString(1)) }
            }
            connection.createStatement().use { statement ->
                statement.execute("SET ROLE chronicle_app")
                assertThrows(SQLException::class.java) { statement.executeQuery("SELECT key_bytes FROM chronicle_participant_token_key") }
                statement.execute("RESET ROLE")
            }
        }
    }

    private fun roundTripParticipantTokenKey(container: PostgreSQLContainer<*>) {
        val dump = container.execInContainer("pg_dump", "-U", container.username, "-d", container.databaseName,
            "--no-owner", "--inserts", "--encoding=UTF8", "--table=public.chronicle_participant_token_key")
        assertEquals("The database backup must include the protected token key", 0, dump.exitCode)
        container.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET search_path TO ''") }
                val privileges = databaseObjectPrivileges(connection)
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE public.chronicle_participant_token_key")
                    statement.execute(dump.stdout.lineSequence().filterNot { it.startsWith("\\") }.joinToString("\n"))
                }
                connection.commit()
                connection.autoCommit = true
                connection.createStatement().use { it.execute("SET search_path TO ''") }
                ChronicleContractTestSchema.bootstrapApplicationRoles(connection)
                assertEquals("Role bootstrap must preserve every migrated object and column ACL",
                    privileges, databaseObjectPrivileges(connection))
            } catch (failure: SQLException) {
                if (!connection.isClosed && !connection.autoCommit) connection.rollback()
                throw SqlFailures.sanitize(failure)
            }
        }
    }

    private fun databaseObjectPrivileges(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                checkNotNull(javaClass.getResourceAsStream("/database-object-privileges.sql"))
                    .bufferedReader().use { it.readText() },
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    @Test
    fun testParticipantTokensFailClosedWithoutTheirKeyAuthority() {
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM public.chronicle_participant_token_key")
                    assertThrows(SQLException::class.java) {
                        statement.executeQuery("SELECT chronicle_participant_block_token(gen_random_uuid(), 'P1')")
                    }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    companion object {
        private val DIAGNOSTIC_CODES = listOf("LOCAL_STORE" to "LOCAL_BUFFER_OVERFLOW", "LOCAL_STORE" to "SAMPLE_QUARANTINED",
            "USAGE_LIFECYCLE" to "CONNECTION_FAILURE", "BATTERY" to "UPLOAD_FAILURE")
        private const val STATS_SENTINEL = "SENTINEL-STATS-7f3a"
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var storageResolver: StorageResolver
        private lateinit var migrateResult: MigrateResult

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_migration_test")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)

            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
            val dsm = DataSourceManager(
                ChronicleContractTestSchema.storageConfigurations(postgres, maximumPoolSize = 5),
                HealthCheckRegistry(), MetricRegistry(),
            )
            storageResolver = StorageResolver(dsm, ChronicleStorageConfiguration())

            migrateResult = ChronicleContractTestSchema.migrate(postgres)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun getConnection(): Connection = storageResolver.getBootstrapStorage().connection

    /** Runs one bound statement on its own bootstrap connection; returns the update count. */
    private fun update(sql: String, vararg params: Any): Int = getConnection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    /** Asserts [sql] returns a row and runs [check] on the first one. */
    private fun queryRow(sql: String, vararg params: Any, check: (java.sql.ResultSet) -> Unit) = getConnection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            val resultSet = statement.executeQuery() // closed with its statement
            assertTrue(resultSet.next())
            check(resultSet)
        }
    }

    private fun tableExists(tableName: String): Boolean {
        getConnection().use { conn ->
            conn.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { rs -> return rs.next() }
        }
    }

    private fun columnExists(tableName: String, columnName: String): Boolean {
        getConnection().use { conn ->
            conn.metaData.getColumns(null, null, tableName, columnName).use { rs -> return rs.next() }
        }
    }

    private fun getColumnNames(tableName: String): Set<String> {
        getConnection().use { conn ->
            conn.metaData.getColumns(null, null, tableName, null).use { rs ->
                val cols = mutableSetOf<String>()
                while (rs.next()) cols.add(rs.getString("COLUMN_NAME"))
                return cols
            }
        }
    }

    private fun roleHasPrivilege(grantee: String, tableName: String, privilege: String): Boolean {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT 1 FROM information_schema.role_table_grants
                WHERE grantee = ? AND table_name = ? AND privilege_type = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, grantee)
                ps.setString(2, tableName)
                ps.setString(3, privilege)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private fun rlsState(tableName: String): Pair<Boolean, Boolean> {
        getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?"
            ).use { ps ->
                ps.setString(1, tableName)
                ps.executeQuery().use { rs ->
                    assertTrue("$tableName should exist in pg_class", rs.next())
                    return rs.getBoolean("relrowsecurity") to rs.getBoolean("relforcerowsecurity")
                }
            }
        }
    }

    private fun policyCount(tableName: String): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM pg_policies WHERE tablename = ?").use { ps ->
                ps.setString(1, tableName)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun functionExists(name: String): Boolean {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM pg_proc WHERE proname = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private data class PersistedJobState(
        val status: String,
        val completedAtIsFinite: Boolean,
        val leaseToken: UUID?,
        val message: String?,
    )

    private fun readJobState(jobId: UUID): PersistedJobState? {
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT status, isfinite(completed_at) AS completed_at_is_finite, lease_token, message
                FROM jobs
                WHERE job_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, jobId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return null
                    return PersistedJobState(
                        resultSet.getString("status"),
                        resultSet.getBoolean("completed_at_is_finite"),
                        resultSet.getObject("lease_token", UUID::class.java),
                        resultSet.getString("message"),
                    )
                }
            }
        }
    }

    private fun awaitJobStatus(jobId: UUID, expectedStatus: String): PersistedJobState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastState: PersistedJobState? = null
        while (System.nanoTime() < deadline) {
            lastState = readJobState(jobId)
            if (lastState?.status == expectedStatus) return lastState
            Thread.sleep(50)
        }
        throw AssertionError("Job $jobId did not reach $expectedStatus; last state was $lastState")
    }

    // =========================================================================
    // Corpus application + ledger
    // =========================================================================
    @Test
    fun testCorpusAppliedCleanly() {
        assertTrue("Flyway migrate must succeed", migrateResult.success)
        // Every V*.sql on the classpath applied exactly once, in order.
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND type = 'SQL'"
                ).use { rs ->
                    rs.next()
                    assertEquals(
                        "Applied migration count must match the migrate result",
                        migrateResult.migrationsExecuted,
                        rs.getInt(1),
                    )
                }
            }
        }
        assertEquals("Every shipped migration is applied once", shippedMigrationCount, migrateResult.migrationsExecuted)
    }

    @Test
    fun testFlywayCreatesServiceTablesWithResearcherRlsBoundaries() {
        assertTrue(roleHasPrivilege("chronicle_app", "studies", "SELECT"))
        assertFalse(roleHasPrivilege("chronicle_app", "audit", "SELECT"))
        assertFalse(tableExists("researcher_phone_numbers"))
        assertFalse(tableExists("researcher_notification_settings"))
    }

    @Test
    fun testStudyParticipantRowsAreHiddenFromApplicationRoleDuringStudyQuarantine() {
        val blockedStudyId = UUID.randomUUID()
        val visibleStudyId = UUID.randomUUID()
        val blockedParticipantId = "blocked-study-participant-${UUID.randomUUID()}"
        val visibleParticipantId = "visible-study-participant-${UUID.randomUUID()}"
        val operationId = UUID.randomUUID()

        getConnection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title) VALUES (?, ?), (?, ?)",
            ).use { statement ->
                statement.setObject(1, blockedStudyId)
                statement.setString(2, "blocked-study-participants")
                statement.setObject(3, visibleStudyId)
                statement.setString(4, "visible-study-participants")
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED'), (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, blockedStudyId)
                statement.setString(2, blockedParticipantId)
                statement.setObject(3, seedCandidate(connection))
                statement.setObject(4, visibleStudyId)
                statement.setString(5, visibleParticipantId)
                statement.setObject(6, seedCandidate(connection))
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, mode, status, requested_by,
                    idempotency_key, registry_version
                ) VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'migration-test', ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setObject(2, blockedStudyId)
                statement.setObject(3, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
        }

        try {
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET ROLE chronicle_app")
                    statement.execute("SELECT set_config('app.current_user_id', 'study-participant-policy-test', false)")
                    statement.execute(
                        "SELECT set_config('app.authorized_studies', '$blockedStudyId,$visibleStudyId', false)",
                    )
                    statement.execute("SELECT set_config('app.is_admin', 'false', false)")
                }
                try {
                    connection.prepareStatement(
                        """
                        SELECT study_id, participant_id
                        FROM study_participants
                        WHERE study_id IN (?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, blockedStudyId)
                        statement.setObject(2, visibleStudyId)
                        statement.executeQuery().use { resultSet ->
                            val visibleParticipants = buildSet {
                                while (resultSet.next()) {
                                    add(
                                        resultSet.getObject("study_id", UUID::class.java) to
                                            resultSet.getString("participant_id"),
                                    )
                                }
                            }
                            assertEquals(
                                "Study quarantine must hide participant identities from the application role",
                                setOf(visibleStudyId to visibleParticipantId),
                                visibleParticipants,
                            )
                        }
                    }
                } finally {
                    connection.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use { statement ->
                    statement.setObject(1, operationId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM study_participants WHERE study_id IN (?, ?)").use { statement ->
                    statement.setObject(1, blockedStudyId)
                    statement.setObject(2, visibleStudyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id IN (?, ?)").use { statement ->
                    statement.setObject(1, blockedStudyId)
                    statement.setObject(2, visibleStudyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testMigrateIsNoOpWhenCurrent() {
        val second = ChronicleContractTestSchema.migrate(postgres)
        assertTrue(second.success)
        assertEquals("Re-running migrate must apply nothing", 0, second.migrationsExecuted)
    }

    @Test
    fun testUploadBufferDrainExcludesDeletionBlockedRowsEvenWithoutRls() {
        val blockedStudyId = UUID.randomUUID()
        val completedErasureStudyId = UUID.randomUUID()
        val allowedStudyId = UUID.randomUUID()
        val blockedParticipantId = "blocked-buffer-${UUID.randomUUID()}"
        val completedErasureParticipantId = "completed-buffer-${UUID.randomUUID()}"
        val allowedParticipantId = "allowed-buffer-${UUID.randomUUID()}"

        // Use the raw container connection intentionally: the claim predicate must protect
        // background/raw data sources even when the table's RLS policy is bypassed.
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO upload_buffer
                        (study_id, participant_id, data, uploaded_at, upload_type, random_device_id)
                    VALUES
                        (?, ?, '[]'::jsonb, now(), 'Android', ?),
                        (?, ?, '[]'::jsonb, now(), 'Android', ?),
                        (?, ?, '[]'::jsonb, now(), 'Android', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, blockedStudyId)
                    statement.setString(2, blockedParticipantId)
                    statement.setObject(3, UUID.randomUUID())
                    statement.setObject(4, completedErasureStudyId)
                    statement.setString(5, completedErasureParticipantId)
                    statement.setObject(6, UUID.randomUUID())
                    statement.setObject(7, allowedStudyId)
                    statement.setString(8, allowedParticipantId)
                    statement.setObject(9, UUID.randomUUID())
                    assertEquals(3, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO data_deletion_operations (
                        operation_id, study_id, mode, status, requested_by,
                        idempotency_key, registry_version
                    ) VALUES (
                        ?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'migration-test',
                        ?, 1
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, blockedStudyId)
                    statement.setObject(3, UUID.randomUUID())
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO data_deletion_operations (
                        operation_id, study_id, participant_ref, participant_id,
                        participant_block_token, mode, status, requested_by,
                        idempotency_key, registry_version
                    ) VALUES (
                        ?, ?, 'completed-participant-ref', NULL, chronicle_participant_block_token(?::uuid, ?),
                        'WITHDRAW_AND_ERASE', 'COMPLETED', 'migration-test',
                        ?, 1
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, completedErasureStudyId)
                    statement.setObject(3, completedErasureStudyId)
                    statement.setString(4, completedErasureParticipantId)
                    statement.setObject(5, UUID.randomUUID())
                    assertEquals(1, statement.executeUpdate())
                }

                val claimedSubjects = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        ChroniclePostgresTables.getMoveSql(128, UploadType.Android),
                    ).use { resultSet ->
                        buildSet {
                            while (resultSet.next()) {
                                add(
                                    resultSet.getObject("study_id", UUID::class.java) to
                                        resultSet.getString("participant_id"),
                                )
                            }
                        }
                    }
                }

                assertTrue(allowedStudyId to allowedParticipantId in claimedSubjects)
                assertFalse(
                    "Deletion-blocked rows must never enter a drain batch",
                    blockedStudyId to blockedParticipantId in claimedSubjects,
                )
                assertFalse(
                    "A redacted COMPLETED erasure must remain blocked by its durable token",
                    completedErasureStudyId to completedErasureParticipantId in claimedSubjects,
                )
                connection.prepareStatement(
                    """
                    SELECT count(*)
                    FROM upload_buffer
                    WHERE (study_id = ? AND participant_id = ?)
                       OR (study_id = ? AND participant_id = ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, blockedStudyId)
                    statement.setString(2, blockedParticipantId)
                    statement.setObject(3, completedErasureStudyId)
                    statement.setString(4, completedErasureParticipantId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(
                            "Quarantined and permanently erased buffer rows must remain unclaimed",
                            2,
                            resultSet.getInt(1),
                        )
                    }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun testExpiredClaimEventuallyCancelsAnAbandonedStoppingJob() {
        val jobId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val jobService = JobService(
            Mockito.mock(HazelcastIdGenerationService::class.java),
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO jobs (
                        job_id, securable_principal_id, principal_type, principal_id,
                        status, definition, message, deleted_rows, updated_at,
                        completed_at, lease_token, lease_expires_at
                    ) VALUES (
                        ?, ?, 'USER', 'stopping-expired-claim-test', 'RUNNING',
                        '{"@type":"EmptyJobDefinition"}'::jsonb, '', 0,
                        now() - interval '10 minutes', 'infinity', ?, now() - interval '1 minute'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, principalId)
                    statement.setObject(3, UUID.randomUUID())
                    assertEquals(1, statement.executeUpdate())
                }
            }

            jobService.tryAndAcquireTaskForExecutor()
            val stopping = awaitJobStatus(jobId, JobStatus.STOPPING.name)
            assertFalse(stopping.completedAtIsFinite)
            getConnection().use { connection ->
                connection.prepareStatement("UPDATE jobs SET updated_at = now() - interval '6 minutes' WHERE job_id = ?")
                    .use { statement ->
                        statement.setObject(1, jobId)
                        assertEquals(1, statement.executeUpdate())
                    }
            }

            jobService.tryAndAcquireTaskForExecutor()
            val canceled = awaitJobStatus(jobId, JobStatus.CANCELED.name)
            assertTrue(canceled.completedAtIsFinite)
            assertNull(canceled.leaseToken)
            assertTrue(canceled.message.orEmpty().contains("reconciliation window"))
        } finally {
            jobService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testAbandonedUncertainStoppingJobReachesTerminalState() {
        val jobId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val jobService = JobService(
            Mockito.mock(HazelcastIdGenerationService::class.java),
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO jobs (
                        job_id, securable_principal_id, principal_type, principal_id,
                        status, definition, message, deleted_rows, updated_at,
                        completed_at, lease_token, lease_expires_at
                    ) VALUES (
                        ?, ?, 'USER', 'stopping-uncertain-test', 'STOPPING',
                        '{"@type":"EmptyJobDefinition"}'::jsonb, 'Execution outcome requires reconciliation', 0,
                        now() - interval '6 minutes', 'infinity', NULL, NULL
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, principalId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            jobService.tryAndAcquireTaskForExecutor()
            val canceled = awaitJobStatus(jobId, JobStatus.CANCELED.name)
            assertTrue(canceled.completedAtIsFinite)
            assertNull(canceled.leaseToken)
            assertTrue(canceled.message.orEmpty().contains("Execution outcome requires reconciliation"))
        } finally {
            jobService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testActiveJobHoldsLeaseFenceUntilTerminalCommit() {
        val jobId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val runnerEntered = CountDownLatch(1)
        val releaseRunner = CountDownLatch(1)
        val jobService = JobService(
            Mockito.mock(HazelcastIdGenerationService::class.java),
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        jobService.registerJobHandlers(
            setOf(
                object : ChronicleJobRunner<EmptyJobDefinition> {
                    override fun run(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
                        runnerEntered.countDown()
                        check(releaseRunner.await(10, TimeUnit.SECONDS)) {
                            "Timed out waiting to release the fenced job runner"
                        }
                        return emptyList()
                    }

                    override fun accepts(): Class<EmptyJobDefinition> = EmptyJobDefinition::class.java
                },
            ),
        )

        try {
            getConnection().use { connection ->
                jobService.createJob(
                    connection,
                    ChronicleJob(
                        id = jobId,
                        securablePrincipalId = principalId,
                        principal = Principal(PrincipalType.USER, "job-fence-test"),
                        definition = EmptyJobDefinition(),
                    ),
                )
            }

            jobService.tryAndAcquireTaskForExecutor()
            assertTrue("runner must begin while owning the durable job row", runnerEntered.await(10, TimeUnit.SECONDS))

            getConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    connection.autoCommit = false
                    connection.prepareStatement(
                        """
                        SELECT job_id
                        FROM jobs
                        WHERE job_id = ?
                        FOR UPDATE SKIP LOCKED
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, jobId)
                        statement.executeQuery().use { resultSet ->
                            assertFalse(
                                "the executing worker must hold the job row lock across runner side effects",
                                resultSet.next(),
                            )
                        }
                    }
                    connection.rollback()
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }

            assertEquals(JobStatus.RUNNING, jobService.getJob(jobId).status)
            releaseRunner.countDown()
            val terminalState = awaitJobStatus(jobId, JobStatus.FINISHED.name)
            assertTrue(terminalState.completedAtIsFinite)
            assertNull(terminalState.leaseToken)
        } finally {
            releaseRunner.countDown()
            jobService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testJobReadsNeverOverlayUncommittedRunnerState() {
        val jobId = UUID.randomUUID()
        val principalId = UUID.randomUUID()
        val jobService = JobService(
            Mockito.mock(HazelcastIdGenerationService::class.java),
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        try {
            getConnection().use { connection ->
                jobService.createJob(
                    connection,
                    ChronicleJob(
                        id = jobId,
                        securablePrincipalId = principalId,
                        principal = Principal(PrincipalType.USER, "job-read-test"),
                        definition = EmptyJobDefinition(),
                    ),
                )
            }
            val claimed = getConnection().use { connection ->
                requireNotNull(jobService.lockAndGetNextJob(connection))
            }
            claimed.status = JobStatus.FINISHED
            claimed.completedAt = OffsetDateTime.now(ZoneOffset.UTC)

            val persisted = jobService.getJob(jobId)
            assertEquals(
                "API reads must reflect the committed database row, not mutable runner memory",
                JobStatus.RUNNING,
                persisted.status,
            )
        } finally {
            jobService.unlockJob(jobId)
            jobService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET status = 'CANCELED', completed_at = now(), updated_at = now(),
                        message = 'test cleanup', lease_token = NULL, lease_expires_at = NULL
                    WHERE job_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
        }
    }

    

    

    @Test
    fun testStudyQuarantineAndLifecycleMutationRollBackTogether() {
        val studyId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )

        try {
            getConnection().use { connection ->
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "atomic-quarantine-$studyId")
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertThrows(IllegalStateException::class.java) {
                orchestrator.quarantineStudyAtomically(
                    studyId = studyId,
                    requestedBy = "migration-test",
                    idempotencyKey = idempotencyKey,
                ) { connection, _, _ ->
                    connection.prepareStatement(
                        "UPDATE studies SET lifecycle_status = 'SCHEDULED_FOR_DELETION' WHERE study_id = ?",
                    ).use { statement ->
                        statement.setObject(1, studyId)
                        assertEquals(1, statement.executeUpdate())
                    }
                    error("injected lifecycle failure")
                }
            }

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT lifecycle_status,
                           (SELECT COUNT(*) FROM data_deletion_operations WHERE idempotency_key = ?) AS operations
                    FROM studies
                    WHERE study_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, idempotencyKey)
                    statement.setObject(2, studyId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("ACTIVE", resultSet.getString("lifecycle_status"))
                        assertEquals("quarantine must roll back with lifecycle state", 0, resultSet.getInt("operations"))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM data_deletion_operations WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testStudyCancellationAndLifecycleMutationRollBackTogether() {
        val studyId = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )

        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO studies (study_id, title, lifecycle_status) VALUES (?, ?, 'SCHEDULED_FOR_DELETION')",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "atomic-cancellation-$studyId")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val operationId = orchestrator.quarantineStudy(
                studyId,
                "migration-test",
                UUID.randomUUID(),
            )

            assertThrows(IllegalStateException::class.java) {
                orchestrator.cancelStudyErasureAtomically(studyId, "migration-test") { connection, cancelled ->
                    assertEquals(1, cancelled)
                    connection.prepareStatement(
                        "UPDATE studies SET lifecycle_status = 'ACTIVE' WHERE study_id = ?",
                    ).use { statement ->
                        statement.setObject(1, studyId)
                        assertEquals(1, statement.executeUpdate())
                    }
                    error("injected lifecycle failure")
                }
            }

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT study.lifecycle_status, operation.status,
                           (SELECT COUNT(*) FROM data_deletion_audit_outbox WHERE study_id = ?) AS audit_events
                    FROM studies study
                    JOIN data_deletion_operations operation ON operation.study_id = study.study_id
                    WHERE study.study_id = ? AND operation.operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setObject(2, studyId)
                    statement.setObject(3, operationId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("SCHEDULED_FOR_DELETION", resultSet.getString("lifecycle_status"))
                        assertEquals("QUARANTINED", resultSet.getString("status"))
                        assertEquals("cancellation audit outbox must roll back", 0, resultSet.getInt("audit_events"))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM data_deletion_audit_outbox WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM data_deletion_operations WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testLifecycleServiceRequestsAndCancelsOneAtomicArchivedStudyErasure() {
        val studyId = UUID.randomUUID()
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(idGenerationService.getNextId()).thenAnswer { UUID.randomUUID() }
        val lifecycleService = StudyLifecycleService(
            storageResolver = storageResolver,
            studyService = Mockito.mock(StudyService::class.java),
            authorizationService = Mockito.mock(AuthorizationManager::class.java),
            idGenerationService = idGenerationService,
            auditingManager = auditingManager,
            dataDeletionOrchestrator = DataDeletionOrchestrator(storageResolver, auditingManager),
            webhookService = Mockito.mock(WebhookService::class.java),
            studyLimitsMgr = Mockito.mock(com.openlattice.chronicle.services.studies.StudyLimitsManager::class.java),
        )
        TestSecurityUtils.setupSecurityContext("lifecycle-migration-test")
        try {
            assertEquals(1, update(
                "INSERT INTO studies (study_id, title, lifecycle_status) VALUES (?, ?, 'ARCHIVED')", studyId, "atomic-lifecycle-$studyId"))
            val operationId = lifecycleService.requestStudyDeletion(studyId, "lifecycle-migration-test")
            queryRow(
                """
                SELECT study.lifecycle_status, schedule.delete_after, operation.status,
                       schedule.operation_id, schedule.previous_status
                FROM studies study
                JOIN study_deletion_schedule schedule ON schedule.study_id = study.study_id
                JOIN data_deletion_operations operation ON operation.study_id = study.study_id
                WHERE study.study_id = ? AND operation.operation_id = ?
                """.trimIndent(),
                studyId, operationId,
            ) { resultSet ->
                assertEquals(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name, resultSet.getString(1))
                // delete_after records the request time; erasure still waits for an owner.
                assertFalse(resultSet.getObject(2, OffsetDateTime::class.java).isAfter(OffsetDateTime.now()))
                assertEquals("QUARANTINED", resultSet.getString(3))
                assertEquals(operationId, resultSet.getObject("operation_id", UUID::class.java))
                assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString("previous_status"))
            }
            assertEquals("a retried request must resolve the original operation",
                operationId, lifecycleService.requestStudyDeletion(studyId, "lifecycle-migration-test"))
            lifecycleService.cancelScheduledDeletion(studyId, "lifecycle-migration-test")
            assertCancelledBackToArchived(studyId, operationId)
            val rescheduledOperationId = lifecycleService.requestStudyDeletion(studyId, "lifecycle-migration-test")
            assertTrue("a new request after explicit cancellation must use a new operation", rescheduledOperationId != operationId)
            assertEquals("an uncertain retry after rescheduling must resolve the new operation",
                rescheduledOperationId, lifecycleService.requestStudyDeletion(studyId, "lifecycle-migration-test"))
            assertRescheduledAfterCancellation(studyId, rescheduledOperationId)
            lifecycleService.cancelScheduledDeletion(studyId, "lifecycle-migration-test")
        } finally {
            TestSecurityUtils.clearSecurityContext()
            listOf("data_deletion_audit_outbox", "data_deletion_operations", "studies").forEach {
                update("DELETE FROM $it WHERE study_id = ?", studyId)
            }
        }
    }

    private fun assertCancelledBackToArchived(studyId: UUID, operationId: UUID) = queryRow(
        """
        SELECT study.lifecycle_status, operation.status,
               (SELECT COUNT(*) FROM study_deletion_schedule WHERE study_id = ?) AS schedules
        FROM studies study
        JOIN data_deletion_operations operation ON operation.study_id = study.study_id
        WHERE study.study_id = ? AND operation.operation_id = ?
        """.trimIndent(),
        studyId, studyId, operationId,
    ) { resultSet ->
        assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString(1))
        assertEquals("CANCELLED", resultSet.getString(2))
        assertEquals(0, resultSet.getInt("schedules"))
    }

    private fun assertRescheduledAfterCancellation(studyId: UUID, rescheduledOperationId: UUID) = queryRow(
        """
        SELECT study.lifecycle_status, schedule.operation_id, schedule.previous_status,
               (SELECT COUNT(*) FROM study_deletion_schedule WHERE study_id = ?) AS schedules,
               (SELECT COUNT(*) FROM data_deletion_operations WHERE study_id = ?) AS operations,
               (SELECT COUNT(*) FROM data_deletion_operations
                 WHERE study_id = ? AND status = 'CANCELLED') AS cancelled_operations,
               (SELECT COUNT(*) FROM data_deletion_operations
                 WHERE study_id = ? AND status = 'QUARANTINED') AS quarantined_operations
        FROM studies AS study
        JOIN study_deletion_schedule AS schedule ON schedule.study_id = study.study_id
        WHERE study.study_id = ?
        """.trimIndent(),
        studyId, studyId, studyId, studyId, studyId,
    ) { resultSet ->
        assertEquals(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name, resultSet.getString("lifecycle_status"))
        assertEquals(rescheduledOperationId, resultSet.getObject("operation_id", UUID::class.java))
        assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString("previous_status"))
        assertEquals("the replacement request must own exactly one schedule", 1, resultSet.getInt("schedules"))
        assertEquals("cancellation history and the replacement request must both remain durable",
            2, resultSet.getInt("operations"))
        assertEquals(1, resultSet.getInt("cancelled_operations"))
        assertEquals(1, resultSet.getInt("quarantined_operations"))
    }

    @Test
    fun testStudyErasureCancellationRestoresOnlyWorkflowRevocations() {
        val studyId = UUID.randomUUID()
        val participantId = "cancel-access-${UUID.randomUUID()}"
        val activeCodeId = UUID.randomUUID()
        val previouslyRevokedCodeId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val originalRevokedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).withNano(0)

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "cancel-access-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_participants (
                    study_id, participant_id, candidate_id, participation_status
                ) VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.setObject(3, seedCandidate(connection))
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO participant_form_access_codes (
                    access_code_id, token_hash, study_id, participant_id, form_kind,
                    issuer_type, issued_by, expires_at, revoked_at
                ) VALUES
                    (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'migration-test', now() + interval '1 day', NULL),
                    (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'migration-test', now() + interval '1 day', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, activeCodeId)
                statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
                statement.setObject(3, studyId)
                statement.setString(4, participantId)
                statement.setObject(5, previouslyRevokedCodeId)
                statement.setBytes(6, UUID.randomUUID().toString().toByteArray())
                statement.setObject(7, studyId)
                statement.setString(8, participantId)
                statement.setObject(9, originalRevokedAt)
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO participant_form_sessions (
                    session_id, session_hash, access_code_id, study_id, participant_id,
                    form_kind, csrf_hash, idle_expires_at, absolute_expires_at
                ) VALUES (?, ?, ?, ?, ?, 'PORTAL', ?, now() + interval '1 hour', now() + interval '1 day')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
                statement.setObject(3, activeCodeId)
                statement.setObject(4, studyId)
                statement.setString(5, participantId)
                statement.setBytes(6, UUID.randomUUID().toString().toByteArray())
                assertEquals(1, statement.executeUpdate())
            }
        }

        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        val firstOperation = orchestrator.quarantineStudy(
            studyId,
            "migration-test",
            UUID.randomUUID(),
        )
        val secondOperation = orchestrator.quarantineStudy(
            studyId,
            "migration-test",
            UUID.randomUUID(),
        )

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM data_deletion_form_access_revocations
                WHERE operation_id IN (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, firstOperation)
                statement.setObject(2, secondOperation)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(6, resultSet.getInt(1))
                }
            }
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM participant_form_access_codes
                WHERE study_id = ? AND revoked_at IS NOT NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(2, resultSet.getInt(1))
                }
            }
        }

        assertEquals(2, orchestrator.cancelStudyErasure(studyId, "migration-test"))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT access_code_id, revoked_at
                FROM participant_form_access_codes
                WHERE access_code_id IN (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, activeCodeId)
                statement.setObject(2, previouslyRevokedCodeId)
                statement.executeQuery().use { resultSet ->
                    val revokedAtByCode = buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getObject("access_code_id", UUID::class.java),
                                resultSet.getObject("revoked_at", OffsetDateTime::class.java),
                            )
                        }
                    }
                    assertTrue(revokedAtByCode.containsKey(activeCodeId))
                    assertNull(revokedAtByCode[activeCodeId])
                    assertEquals(originalRevokedAt, revokedAtByCode[previouslyRevokedCodeId])
                }
            }
            connection.prepareStatement(
                "SELECT revoked_at FROM participant_form_sessions WHERE session_id = ?",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull(resultSet.getObject("revoked_at"))
                }
            }
            connection.prepareStatement(
                "SELECT COUNT(*) FROM data_deletion_form_access_revocations WHERE study_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(0, resultSet.getInt(1))
                }
            }

            connection.prepareStatement(
                "DELETE FROM data_deletion_audit_outbox WHERE study_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM data_deletion_operations WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM participant_form_sessions WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM participant_form_access_codes WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM study_participants WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun testDeletionAccountabilityIsAtomicDurableAndRetrySafe() {
        val studyId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val actor = "deletion-audit-${UUID.randomUUID()}"

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "deletion-audit-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, mode, status, requested_by,
                    idempotency_key, registry_version
                ) VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', ?, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setObject(2, studyId)
                statement.setString(3, actor)
                statement.setObject(4, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
        }

        val unavailableAuditingManager = object : AuditingManager {
            override fun recordEvents(events: List<AuditableEvent>): Int {
                error("injected audit outage")
            }
        }
        val unavailableAuditOrchestrator = DataDeletionOrchestrator(
            storageResolver,
            unavailableAuditingManager,
        )

        assertEquals(1, unavailableAuditOrchestrator.cancelStudyErasure(studyId, actor))
        assertEquals(0, unavailableAuditOrchestrator.cancelStudyErasure(studyId, actor))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT cancelled_by, cancelled_at IS NOT NULL AS has_cancelled_at
                FROM data_deletion_operations
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(actor, resultSet.getString("cancelled_by"))
                    assertTrue(resultSet.getBoolean("has_cancelled_at"))
                }
            }
            connection.prepareStatement(
                """
                SELECT event_type, COUNT(*) AS event_count,
                       bool_and(published_at IS NULL) AS all_pending
                FROM data_deletion_audit_outbox
                WHERE operation_id = ?
                GROUP BY event_type
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.executeQuery().use { resultSet ->
                    val pendingEvents = buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getString("event_type"),
                                resultSet.getInt("event_count") to resultSet.getBoolean("all_pending"),
                            )
                        }
                    }
                    assertEquals(
                        setOf(AuditEventType.CANCEL_DATA_DELETION.name),
                        pendingEvents.keys,
                    )
                    pendingEvents.values.forEach { (count, allPending) ->
                        assertEquals(1, count)
                        assertTrue(allPending)
                    }
                }
            }
            // Make the intentionally failed immediate attempts due for the
            // deterministic recovery pass below.
            connection.prepareStatement(
                "UPDATE data_deletion_audit_outbox SET available_at = now() WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                UPDATE data_deletion_audit_outbox
                SET lease_token = ?, lease_expires_at = now() - interval '1 minute'
                WHERE operation_id = ? AND event_type = 'CANCEL_DATA_DELETION'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val recoveryOrchestrator = DataDeletionOrchestrator(
            storageResolver,
            PostgresAuditingManager(storageResolver),
        )
        assertEquals(1, recoveryOrchestrator.publishPendingDeletionAuditEvents())
        assertEquals(0, recoveryOrchestrator.publishPendingDeletionAuditEvents())

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT audit_event_type, principal_id, data::jsonb ->> 'actor' AS actor
                FROM audit
                WHERE study_id = ?
                  AND audit_event_type = 'CANCEL_DATA_DELETION'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, studyId.toString())
                statement.executeQuery().use { resultSet ->
                    val publishedEvents = buildMap {
                        while (resultSet.next()) {
                            assertEquals("Chronicle", resultSet.getString("principal_id"))
                            assertEquals(actor, resultSet.getString("actor"))
                            put(resultSet.getString("audit_event_type"), true)
                        }
                    }
                    assertEquals(
                        setOf(AuditEventType.CANCEL_DATA_DELETION.name),
                        publishedEvents.keys,
                    )
                }
            }
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM data_deletion_audit_outbox
                WHERE operation_id = ? AND published_at IS NOT NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1, resultSet.getInt(1))
                }
            }

            connection.prepareStatement(
                "DELETE FROM data_deletion_audit_outbox WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    @Test
    fun testStartedStudyErasureCannotBeCancelledAsIfReversible() {
        val studyId = UUID.randomUUID()
        val participantId = "started-erasure-${UUID.randomUUID()}"
        val accessCodeId = UUID.randomUUID()
        assertEquals(1, update("INSERT INTO studies (study_id, title) VALUES (?, ?)", studyId, "started-erasure-$studyId"))
        assertEquals(1, update(
            "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, 'ENROLLED')",
            studyId, participantId, getConnection().use { seedCandidate(it) },
        ))
        assertEquals(1, update(
            """
            INSERT INTO participant_form_access_codes (
                access_code_id, token_hash, study_id, participant_id, form_kind,
                issuer_type, issued_by, expires_at
            ) VALUES (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'migration-test', now() + interval '1 day')
            """.trimIndent(),
            accessCodeId, UUID.randomUUID().toString().toByteArray(), studyId, participantId,
        ))
        val orchestrator = DataDeletionOrchestrator(storageResolver, Mockito.mock(AuditingManager::class.java))
        val operationId = orchestrator.quarantineStudy(studyId, "migration-test", UUID.randomUUID())
        assertEquals(1, update(
            """
            UPDATE data_deletion_operations
            SET status = 'FAILED', started_at = now(), operation_attempt_count = 1,
                failure_code = 'InjectedPartialFailure', next_attempt_at = now()
            WHERE operation_id = ?
            """.trimIndent(),
            operationId,
        ))

        assertEquals(0, orchestrator.cancelStudyErasure(studyId, "migration-test"))
        var lifecycleCallbackRan = false
        assertThrows(IllegalStateException::class.java) {
            orchestrator.cancelStudyErasureAtomically(studyId, "migration-test") { _, _ ->
                lifecycleCallbackRan = true
            }
        }
        assertFalse("started erasure must block lifecycle restoration", lifecycleCallbackRan)
        queryRow("SELECT revoked_at FROM participant_form_access_codes WHERE access_code_id = ?", accessCodeId) {
            assertNotNull("started erasure must keep form access revoked", it.getObject(1))
        }
        assertEquals("FAILED", orchestrator.getOperation(operationId).status)

        assertEquals(1, update(
            "UPDATE data_deletion_operations SET worker_lease_token = NULL, worker_lease_expires_at = NULL WHERE operation_id = ?",
            operationId,
        ))
        assertEquals(1, update("DELETE FROM data_deletion_operations WHERE operation_id = ?", operationId))
        listOf("participant_form_access_codes", "study_participants", "studies").forEach {
            update("DELETE FROM $it WHERE study_id = ?", studyId)
        }
    }

    // =========================================================================
    // RLS postconditions (V1, V14, V37–V43 lineage)
    // =========================================================================
    @Test
    fun testRowLevelSecurityPostconditions() {
        for (table in listOf(STUDY_PARTICIPANTS.name, CANDIDATES.name)) {
            val (enabled, forced) = rlsState(table)
            assertTrue("RLS should be enabled on $table", enabled)
            assertTrue("RLS should be forced on $table", forced)
        }
        assertTrue(functionExists("chronicle_authorized_study_ids"))
        assertTrue(functionExists("chronicle_has_candidate_access"))
        for (table in listOf(
            STUDY_PARTICIPANTS.name, NOTIFICATIONS.name, QUESTIONNAIRES.name,
            PARTICIPANT_STATS.name, FILTERED_APPS.name, CANDIDATES.name,
        )) {
            assertTrue("RLS policy should exist on $table", policyCount(table) > 0)
        }
    }

    // =========================================================================
    // Append-only audit trails (V25/V26/V44/V49 lineage)
    // =========================================================================
    @Test
    fun testAuditTrailsAreAppendOnly() {
        for (table in listOf(STUDY_SETTINGS_AUDIT.name, PARTICIPANT_COLLECTION_ACKNOWLEDGMENT.name)) {
            // Positive half first: V25/V26 GRANT INSERT, SELECT to `chronicle`. Without these,
            // the assertFalse checks below are vacuous (false for "revoked" AND "never granted"),
            // and a corpus edit that dropped the whole GRANT/REVOKE block would still pass.
            assertTrue("INSERT must remain for chronicle on $table", roleHasPrivilege("chronicle", table, "INSERT"))
            assertTrue("SELECT must remain for chronicle on $table", roleHasPrivilege("chronicle", table, "SELECT"))
            assertFalse("DELETE must be revoked from chronicle on $table", roleHasPrivilege("chronicle", table, "DELETE"))
            assertFalse("UPDATE must be revoked from chronicle on $table", roleHasPrivilege("chronicle", table, "UPDATE"))
            // V44: REVOKE-only immutability is GRANT-defeatable; RLS must also be on+forced.
            val (enabled, forced) = rlsState(table)
            assertTrue("RLS should be enabled on $table", enabled)
            assertTrue("RLS should be forced on $table", forced)
        }
    }

    // =========================================================================
    // Participation-status check constraint (behavioral)
    // =========================================================================
    @Test
    fun testParticipationStatusConstraint() {
        getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM pg_constraint WHERE conname = 'study_participants_participation_status_check'"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue("participation_status check constraint should exist", rs.next())
                }
            }
            val studyId = UUID.randomUUID()
            conn.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'status fixture')").use { statement ->
                statement.setObject(1, studyId)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, studyId)
                ps.setString(2, "valid-status-${UUID.randomUUID()}")
                ps.setObject(3, seedCandidate(conn))
                ps.executeUpdate()
            }
            assertThrows(SQLException::class.java) {
                conn.prepareStatement(
                    """
                    INSERT INTO study_participants
                        (study_id, participant_id, candidate_id, participation_status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, studyId)
                    ps.setString(2, "invalid-status-${UUID.randomUUID()}")
                    ps.setObject(3, seedCandidate(conn))
                    ps.executeUpdate()
                }
            }
        }
    }

    // =========================================================================
    // iOS Screen Time: one row per app or domain capture, keyed by capture
    // =========================================================================
    @Test
    fun testIosScreenTimeUsageTableShape() {
        assertEquals(ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.columns.map { it.name }.toSet() +
            ScreenTimeUsageCaptures.LABELS_HASH_COLUMN,
            getColumnNames(ChroniclePostgresTables.IOS_SCREEN_TIME_USAGE.name).toSet())
        getConnection().use { connection ->
            connection.prepareStatement("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'public.ios_screen_time_usage'::regclass AND conname = 'ios_screen_time_usage_capture_key'
            """.trimIndent()).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getString(1).startsWith("UNIQUE NULLS NOT DISTINCT"))
                }
            }
        }
    }

    // =========================================================================
    // Append-only Screen Time snapshots and UTC-safe delta projection
    // =========================================================================
    @Test
    fun testIosScreenTimeDeltaProjectionAcrossDstAndMidnight() {
        val studyId = UUID.randomUUID()
        val participantId = "screen-time-delta-${UUID.randomUUID()}"
        getConnection().use { connection -> ScreenTimeDeltaFixture(connection).verify(studyId, participantId) }
        assertTrue(
            "chronicle_app must be able to select the RLS-invoker view",
            roleHasPrivilege("chronicle_app", "screen_time_usage_deltas", "SELECT"),
        )
    }

    // =========================================================================
    // V21–V23 / inline-upgrade end states that fresh installs must still reach
    // =========================================================================
    @Test
    fun testFrameworkConvergedColumns() {
        assertTrue(columnExists(PostgresEventTables.CHRONICLE_USAGE_EVENTS.name, "uploaded_at"))
        assertTrue(columnExists(PARTICIPANT_STATS.name, "android_last_ping"))
        assertTrue(columnExists(PARTICIPANT_STATS.name, "ios_last_ping"))
        assertTrue(columnExists(STUDIES.name, "modules"))
        // Candidate PII must never exist on a fresh install (CandidatePiiRemovalUpgrade
        // was prod-only history; the framework definition is PII-free).
        for (pii in listOf("first_name", "last_name", "name", "dob", "email", "phone_number")) {
            assertFalse("candidates must not have PII column $pii", columnExists(CANDIDATES.name, pii))
        }
    }

    // =========================================================================
    // Interaction position provenance columns
    // =========================================================================
    @Test
    fun testInteractionPositionProvenanceColumns() {
        for (column in listOf(
            "position_source", "node_bounds_left", "node_bounds_top",
            "node_bounds_right", "node_bounds_bottom", "display_id",
        )) {
            assertTrue("Missing ${INTERACTION_EVENTS.name}.$column", columnExists(INTERACTION_EVENTS.name, column))
        }
        assertTrue(
            columnExists(ANDROID_DEVICE_SENSOR_AVAILABILITY.name, "interaction_pointer_capture_capability"),
        )
    }

    // =========================================================================
    // Upload-buffer drain index
    // =========================================================================
    @Test
    fun testUploadBufferDrainIndex() {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM pg_indexes
                WHERE tablename = 'upload_buffer' AND indexname = 'upload_buffer_type_uploaded_at_idx'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun retiredRetentionHoldsLeaveNoTableOrStatus() {
        getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT to_regclass('public.retention_holds')").use { rs ->
                    assertTrue(rs.next())
                    assertNull(rs.getString(1))
                }
                st.executeQuery(
                    """
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid = 'public.data_deletion_operations'::regclass AND contype = 'c'
                    """.trimIndent(),
                ).use { rs ->
                    val checks = generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    assertTrue(checks.none { "'HELD'" in it || "'READY'" in it })
                }
            }
        }
    }

    @Test
    fun tablesWithoutAReaderOrWriterAreNotCreated() {
        val orphans = listOf(
            "local_blob_store", "organization_members", "organization_quotas", "refresh_tokens",
            "study_event_stream", "study_realtime_stats", "usage_event_annotations",
        )
        getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT name FROM unnest(?::text[]) AS name WHERE to_regclass('public.' || name) IS NOT NULL",
            ).use { ps ->
                ps.setArray(1, conn.createArrayOf("text", orphans.toTypedArray()))
                ps.executeQuery().use { rs ->
                    assertEquals(emptyList<String>(), generateSequence { if (rs.next()) rs.getString(1) else null }.toList())
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT to_regprocedure('public.chronicle_classify_legacy_webhook_delivery()')").use { rs ->
                    assertTrue(rs.next())
                    assertNull(rs.getString(1))
                }
            }
        }
    }

    @Test
    fun usageEventTimeWindowReadsHaveAnIndex() {
        getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'chronicle_usage_events'
                    AND indexdef LIKE '%(study_id, participant_id, event_timestamp)'
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun eachIndexIsDefinedOnceAlongsideTheBootstrapIndexes() {
        getConnection().use { conn ->
            conn.createStatement().use { st ->
                // Production bootstrap creates the Kotlin-declared indexes as well as V1's.
                ChronicleContractTestSchema.allChronicleTableDefinitions()
                    .flatMap { it.indexes }
                    .forEach { st.execute(it.sql()) }
                val duplicates = st.executeQuery(
                    """
                    SELECT string_agg(indexrelid::regclass::text, ' = ' ORDER BY indexrelid::regclass::text)
                    FROM pg_index
                    WHERE indrelid::regclass::text NOT LIKE 'pg_%'
                    GROUP BY indrelid, indkey::text, indclass::text, COALESCE(pg_get_expr(indpred, indrelid), ''),
                        COALESCE(pg_get_expr(indexprs, indrelid), '')
                    HAVING COUNT(*) > 1
                    """.trimIndent(),
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                assertEquals(emptyList<String>(), duplicates)
            }
        }
    }

    // =========================================================================
    // Re-issued orphan objects (webhooks, anonymization, orgs, refresh tokens)
    // =========================================================================
    

    @Test
    fun testDurableWebhookRetryUsesStableDeliveryIdentity() {
        val studyId = UUID.randomUUID()
        val webhookId = UUID.randomUUID()
        val requests = mutableListOf<Request>()
        val requestBodies = mutableListOf<String>()
        val responseNumber = AtomicInteger()
        val queuedTasks = mutableListOf<Runnable>()
        val executor = Mockito.mock(ExecutorService::class.java)
        Mockito.doAnswer { invocation ->
            queuedTasks += invocation.getArgument<Runnable>(0)
            null
        }.`when`(executor).execute(Mockito.any(Runnable::class.java))
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                requestBodies += Buffer().use { buffer ->
                    chain.request().body?.writeTo(buffer)
                    buffer.readUtf8()
                }
                val status = if (responseNumber.getAndIncrement() == 0) 503 else 204
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 204) "No Content" else "Unavailable")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        val webhookService = WebhookService(
            storageResolver = storageResolver,
            idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java),
            deliveryExecutor = executor,
            httpClientTemplate = httpClient,
            hostResolver = { arrayOf(java.net.InetAddress.getByName("93.184.216.34")) },
        )

        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("GRANT USAGE ON SCHEMA public TO chronicle_app")
                statement.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON " +
                        "webhook_registrations, webhook_deliveries TO chronicle_app"
                )
            }
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "outbox-migration-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO webhook_registrations (
                    webhook_id, study_id, url, secret_hash, event_types, created_by
                ) VALUES (?, ?, 'https://example.com/callback', 'stable-signing-key',
                          ARRAY['DATA_SUBMITTED']::text[], 'migration-test')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, webhookId)
                statement.setObject(2, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        // Rollback proves enqueueEvent participates in the caller's transaction.
        getConnection().use { connection ->
            connection.autoCommit = false
            assertEquals(
                1,
                webhookService.enqueueEvent(
                    connection,
                    studyId,
                    WebhookEventType.DATA_SUBMITTED,
                    WebhookPayload.Test(),
                ),
            )
            connection.rollback()
        }
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM webhook_deliveries WHERE webhook_id = ?",
            ).use { statement ->
                statement.setObject(1, webhookId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(0, resultSet.getInt(1))
                }
            }
        }

        getConnection().use { connection ->
            assertEquals(
                1,
                webhookService.enqueueEvent(
                    connection,
                    studyId,
                    WebhookEventType.DATA_SUBMITTED,
                    WebhookPayload.Test(),
                ),
            )
        }

        webhookService.dispatchPendingDeliveries()
        assertEquals(4, queuedTasks.size)
        queuedTasks.removeAt(0).run()

        val deliveryId = getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT delivery_id, delivery_state, attempt_count, status, outcome_code
                FROM webhook_deliveries
                WHERE webhook_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, webhookId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("PENDING", resultSet.getString("delivery_state"))
                    assertEquals(1, resultSet.getInt("attempt_count"))
                    assertEquals(503, resultSet.getInt("status"))
                    assertEquals("http_status", resultSet.getString("outcome_code"))
                    resultSet.getObject("delivery_id", UUID::class.java)
                }
            }
        }

        getConnection().use { connection ->
            connection.prepareStatement(
                "UPDATE webhook_deliveries SET available_at = now() WHERE delivery_id = ?",
            ).use { statement ->
                statement.setObject(1, deliveryId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        queuedTasks.clear()
        webhookService.dispatchPendingDeliveries()
        queuedTasks.removeAt(0).run()

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT delivery_state, attempt_count, status, outcome_code, completed_at
                FROM webhook_deliveries
                WHERE delivery_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deliveryId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("SUCCEEDED", resultSet.getString("delivery_state"))
                    assertEquals(2, resultSet.getInt("attempt_count"))
                    assertEquals(204, resultSet.getInt("status"))
                    assertEquals(null, resultSet.getString("outcome_code"))
                    assertTrue(resultSet.getObject("completed_at") != null)
                }
            }
        }
        assertEquals(2, requests.size)
        assertEquals(
            listOf(deliveryId.toString(), deliveryId.toString()),
            requests.map { it.header("X-Chronicle-Delivery") },
        )
        assertEquals(requestBodies[0], requestBodies[1])
        assertEquals(
            requests[0].header("X-Chronicle-Signature"),
            requests[1].header("X-Chronicle-Signature"),
        )

        getConnection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM webhook_registrations WHERE webhook_id = ?",
            ).use { statement ->
                statement.setObject(1, webhookId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    // =========================================================================
    // Database-authoritative last-active-owner invariant
    // =========================================================================
    @Test
    fun testLastActiveAclOwnerConstraintSerializesCompetingDeletes() {
        val aclId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        val firstOwner = "owner-${UUID.randomUUID()}"
        val secondOwner = "owner-${UUID.randomUUID()}"

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO securable_objects (acl_key, securable_object_type, id, name)
                VALUES (?::uuid[], 'STUDY', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "{$aclId}")
                statement.setObject(2, objectId)
                statement.setString(3, "migration-owner-$objectId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO permissions (
                    acl_key, principal_type, principal_id, permissions, expiration_date
                ) VALUES (?::uuid[], 'USER', ?, ARRAY['OWNER']::text[], 'infinity')
                """.trimIndent(),
            ).use { statement ->
                for (owner in listOf(firstOwner, secondOwner)) {
                    statement.setString(1, "{$aclId}")
                    statement.setString(2, owner)
                    statement.addBatch()
                }
                assertTrue(statement.executeBatch().all { it == 1 })
            }
        }

        val firstConnection = getConnection()
        val secondConnection = getConnection()
        try {
            firstConnection.autoCommit = false
            secondConnection.autoCommit = false
            firstConnection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            secondConnection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ

            for (connection in listOf(firstConnection, secondConnection)) {
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM permissions WHERE acl_key = ?::uuid[]",
                ).use { statement ->
                    statement.setString(1, "{$aclId}")
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(2, resultSet.getInt(1))
                    }
                }
            }

            val commitGate = CountDownLatch(1)
            val workersReady = CountDownLatch(2)
            val commitExecutor = Executors.newFixedThreadPool(2)
            try {
                val firstCommit = commitExecutor.submit<SQLException?> {
                    workersReady.countDown()
                    commitGate.await()
                    try {
                        firstConnection.prepareStatement(
                            "DELETE FROM permissions WHERE acl_key = ?::uuid[] AND principal_id = ?",
                        ).use { statement ->
                            statement.setString(1, "{$aclId}")
                            statement.setString(2, firstOwner)
                            check(statement.executeUpdate() == 1)
                        }
                        firstConnection.commit()
                        null
                    } catch (ex: SQLException) {
                        ex
                    }
                }
                val secondCommit = commitExecutor.submit<SQLException?> {
                    workersReady.countDown()
                    commitGate.await()
                    try {
                        secondConnection.prepareStatement(
                            "DELETE FROM permissions WHERE acl_key = ?::uuid[] AND principal_id = ?",
                        ).use { statement ->
                            statement.setString(1, "{$aclId}")
                            statement.setString(2, secondOwner)
                            check(statement.executeUpdate() == 1)
                        }
                        secondConnection.commit()
                        null
                    } catch (ex: SQLException) {
                        ex
                    }
                }
                assertTrue("both owner mutation workers must be ready", workersReady.await(10, TimeUnit.SECONDS))
                commitGate.countDown()
                val commitResults = listOf(
                    firstCommit.get(10, TimeUnit.SECONDS),
                    secondCommit.get(10, TimeUnit.SECONDS),
                )
                assertEquals("exactly one competing owner deletion must commit", 1, commitResults.count { it == null })
                assertEquals("exactly one competing owner deletion must fail", 1, commitResults.count { it != null })
                assertEquals(
                    "repeatable-read loser must fail on the fencing-row serialization conflict",
                    "40001",
                    commitResults.single { it != null }?.sqlState,
                )
                if (commitResults[0] != null) {
                    firstConnection.rollback()
                }
                if (commitResults[1] != null) {
                    secondConnection.rollback()
                }
            } finally {
                commitExecutor.shutdownNow()
                assertTrue(commitExecutor.awaitTermination(10, TimeUnit.SECONDS))
            }
        } finally {
            firstConnection.close()
            secondConnection.close()
        }

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT principal_id
                FROM permissions
                WHERE acl_key = ?::uuid[]
                  AND 'OWNER' = ANY(permissions)
                  AND expiration_date > now()
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "{$aclId}")
                statement.executeQuery().use { resultSet ->
                    assertTrue("one owner must survive the competing deletes", resultSet.next())
                    assertFalse("exactly one owner must survive", resultSet.next())
                }
            }

            connection.autoCommit = false
            connection.prepareStatement(
                """
                UPDATE permissions
                SET expiration_date = now() + interval '1 day'
                WHERE acl_key = ?::uuid[]
                  AND 'OWNER' = ANY(permissions)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "{$aclId}")
                assertEquals(1, statement.executeUpdate())
            }
            val finiteOwnerFailure = assertThrows(SQLException::class.java) {
                connection.commit()
            }
            assertEquals("23514", finiteOwnerFailure.sqlState)
            connection.rollback()

            connection.prepareStatement(
                "DELETE FROM permissions WHERE acl_key = ?::uuid[]",
            ).use { statement ->
                statement.setString(1, "{$aclId}")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "DELETE FROM securable_objects WHERE acl_key = ?::uuid[]",
            ).use { statement ->
                statement.setString(1, "{$aclId}")
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()
        }
    }

    // =========================================================================
    // Deletion ledger, quarantine, and the services that ride on it
    // =========================================================================
    

    @Test
    fun testConcurrentDataCollectionUpdatesReceiveDistinctLockedRevisions() {
        val studyId = UUID.randomUUID()
        val initialSettings = StudySettings(
            mapOf(StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 1)),
        )
        val competingSettings = listOf(
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false),
                ),
                settingsVersion = 99,
            ),
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = false),
                ),
                settingsVersion = 99,
            ),
        )
        val ready = CountDownLatch(competingSettings.size)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(competingSettings.size)

        try {
            studyFixture.insertStudySettings(studyId, "locked-revision-$studyId", initialSettings, collectionEnabled = true)
            val updates = competingSettings.map { setting ->
                executor.submit<Int> {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    persistLockedDataCollectionUpdate(studyId, setting)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()

            assertEquals(setOf(2, 3), updates.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(3, loadPersistedDataCollectionSettings(studyId).settingsVersion)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            getConnection().use { connection ->
                executeSingleUpdate(connection, "DELETE FROM studies WHERE study_id = ?", expected = null) {
                    it.setObject(1, studyId)
                }
            }
        }
    }

    @Test
    fun testUnrelatedSettingDeltaPreservesDataCollectionCommittedWhileWaitingForLock() {
        val studyId = UUID.randomUUID()
        val initialSettings = StudySettings(
            mapOf(StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 1)),
        )
        val committedDataCollection = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = false),
            ),
            settingsVersion = 99,
        )
        val sensorDelta = SurveySettings(appUsageThresholdInSeconds = 42)
        val updateLocked = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val deltaStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            studyFixture.insertStudySettings(studyId, "locked-delta-$studyId", initialSettings, collectionEnabled = true)

            val dataCollectionUpdate = executor.submit<Int> {
                persistLockedDataCollectionUpdate(studyId, committedDataCollection) {
                    updateLocked.countDown()
                    check(releaseUpdate.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(updateLocked.await(10, TimeUnit.SECONDS))

            val unrelatedUpdate = executor.submit<StudySettings> {
                deltaStarted.countDown()
                persistLockedStudySettingDelta(
                    studyId,
                    StudySettingType.Survey,
                    sensorDelta,
                )
            }
            assertTrue(deltaStarted.await(10, TimeUnit.SECONDS))
            releaseUpdate.countDown()

            assertEquals(2, dataCollectionUpdate.get(10, TimeUnit.SECONDS))
            val persistedSettings = unrelatedUpdate.get(10, TimeUnit.SECONDS)
            assertEquals(sensorDelta, persistedSettings[StudySettingType.Survey])
            assertEquals(
                committedDataCollection.copy(
                    settingsVersion = 2,
                ),
                persistedSettings[StudySettingType.DataCollection],
            )
        } finally {
            releaseUpdate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            studyFixture.deleteStudyRow(studyId)
        }
    }

    @Test
    fun testImmutableRevisionConflictRollsBackStudySettingsUpdate() {
        val studyId = UUID.randomUUID()
        val initial = AndroidDataCollectionSetting(settingsVersion = 1)
        val reservedRevision = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            ),
            settingsVersion = 2,
        )
        val conflictingUpdate = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true),
            ),
            settingsVersion = 2,
        )

        try {
            studyFixture.insertStudySettings(
                studyId,
                "revision-rollback-$studyId",
                StudySettings(mapOf(StudySettingType.DataCollection to initial)),
            )
            getConnection().use { connection ->
                executeSingleUpdate(
                    connection,
                    """
                    INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting)
                    VALUES (?, 2, ?::jsonb)
                    """.trimIndent(),
                ) { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, ObjectMappers.getJsonMapper().writeValueAsString(reservedRevision))
                }
            }

            getConnection().use { connection ->
                connection.autoCommit = false
                val exception = assertThrows(SQLException::class.java) {
                    executeSingleUpdate(
                        connection,
                        "UPDATE studies SET settings = ?::jsonb WHERE study_id = ?",
                    ) { statement ->
                        statement.setString(
                            1,
                            ObjectMappers.getJsonMapper().writeValueAsString(
                                StudySettings(mapOf(StudySettingType.DataCollection to conflictingUpdate)),
                            ),
                        )
                        statement.setObject(2, studyId)
                    }
                }
                assertEquals("23505", exception.sqlState)
                connection.rollback()
            }

            assertEquals(initial, loadPersistedDataCollectionSettings(studyId))
        } finally {
            studyFixture.deleteStudyRow(studyId)
        }
    }

    private fun persistLockedDataCollectionUpdate(
        studyId: UUID,
        requested: AndroidDataCollectionSetting,
        beforeCommit: () -> Unit = {},
    ): Int = getConnection().use { connection ->
        connection.autoCommit = false
        try {
            val lockedUpdate = mergeAndStampDataCollectionSettingsVersionLocked(
                connection,
                studyId,
                StudyUpdate(
                    settings = StudySettings(mapOf(StudySettingType.DataCollection to requested)),
                ),
            )
            executeSingleUpdate(connection, "UPDATE studies SET settings = ?::jsonb WHERE study_id = ?") { statement ->
                statement.setString(1, ObjectMappers.getJsonMapper().writeValueAsString(lockedUpdate.stampedStudy.settings))
                statement.setObject(2, studyId)
            }
            beforeCommit()
            connection.commit()
            val persisted = lockedUpdate.stampedStudy.settings
                ?.get(StudySettingType.DataCollection) as AndroidDataCollectionSetting
            persisted.settingsVersion
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun persistLockedStudySettingDelta(
        studyId: UUID,
        settingType: StudySettingType,
        setting: StudySetting,
    ): StudySettings = getConnection().use { connection ->
        connection.autoCommit = false
        try {
            val priorSettings = loadLockedStudySettings(connection, studyId)
            val mergedSettings = mergeStudySetting(priorSettings, settingType, setting)
            val features = StudySnapshotRepository(connection).loadLockedStudyFeatures(studyId)
            val stampedUpdate = stampDataCollectionSettingsVersion(
                priorSettings,
                StudyUpdate(settings = mergedSettings),
                priorFeatures = features,
                requestedFeatures = features,
            )
            executeSingleUpdate(connection, "UPDATE studies SET settings = ?::jsonb WHERE study_id = ?") { statement ->
                statement.setString(1, ObjectMappers.getJsonMapper().writeValueAsString(stampedUpdate.settings))
                statement.setObject(2, studyId)
            }
            connection.commit()
            checkNotNull(stampedUpdate.settings)
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun loadPersistedDataCollectionSettings(studyId: UUID): AndroidDataCollectionSetting =
        getConnection().use { connection ->
            connection.prepareStatement("SELECT settings FROM studies WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    ObjectMappers.getJsonMapper()
                        .readValue(resultSet.getString(1), StudySettings::class.java)
                        .getValue(StudySettingType.DataCollection) as AndroidDataCollectionSetting
                }
            }
        }

    

    // =========================================================================
    // audit_buffer drained into audit; direct writes; deletion blocked;
    // legacy-path writes forwarded (rolling old replicas / emergency binary rollback)
    // =========================================================================
    @Test
    fun testDirectAppendOnlyAuditPreservesLongDescriptions() {
        val participantIds = (1..8).map { "bulk-${UUID.randomUUID()}" }.toSet()
        val directDescription = "Participant refs ${com.openlattice.chronicle.util.LogSanitizer.stableFingerprints(participantIds, "participant")} " +
            "entered inaccessible seven-day deletion quarantine"
        val directEvent = AuditableEvent(
            aclKey = AclKey(UUID.randomUUID()),
            securablePrincipalId = UUID.randomUUID(),
            principal = Principal(PrincipalType.USER, "migration-test"),
            eventType = AuditEventType.DELETE_PARTICIPANTS,
            description = directDescription,
            study = UUID.randomUUID(),
            organization = UUID.randomUUID(),
        )
        assertEquals(1, PostgresAuditingManager(storageResolver).recordObservations(
            AuditObservation.CommittedEffect(listOf(directEvent)),
        ))

        getConnection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE description = ?").use { statement ->
                statement.setString(1, directDescription)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1, resultSet.getInt(1))
                }
            }
            assertThrows(SQLException::class.java) {
                connection.prepareStatement("DELETE FROM audit WHERE description = ?").use { statement ->
                    statement.setString(1, directDescription)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testParticipantErasureDeletesPendingPausedAndTerminalScopedJobs() {
        val studyId = UUID.randomUUID()
        val otherStudyId = UUID.randomUUID()
        val participantId = "job-erasure-${UUID.randomUUID()}"
        val otherParticipantId = "job-control-${UUID.randomUUID()}"
        val targetPending = UUID.randomUUID()
        val targetPaused = UUID.randomUUID()
        val targetFinished = UUID.randomUUID()
        val sameStudyControl = UUID.randomUUID()
        val otherStudyControl = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        var operationId: UUID? = null

        fun insertScopedJob(
            connection: Connection,
            jobId: UUID,
            targetStudyId: UUID,
            targetParticipantId: String,
            status: String,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO jobs (
                    job_id, securable_principal_id, principal_type, principal_id,
                    status, definition, message, deleted_rows, completed_at
                ) VALUES (
                    ?, ?, 'USER', 'job-erasure-test', ?, ?::jsonb, '', 0,
                    CASE WHEN ? THEN now() ELSE 'infinity'::timestamptz END
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, jobId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(3, status)
                statement.setString(
                    4,
                    """
                    {
                      "@type": "EmptyJobDefinition",
                      "studyId": "$targetStudyId",
                      "participantIds": ["$targetParticipantId"]
                    }
                    """.trimIndent(),
                )
                statement.setBoolean(5, status == "FINISHED" || status == "CANCELED")
                assertEquals(1, statement.executeUpdate())
            }
        }

        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO studies (study_id, title) VALUES (?, ?), (?, ?)",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "job-erasure-target")
                    statement.setObject(3, otherStudyId)
                    statement.setString(4, "job-erasure-control")
                    assertEquals(2, statement.executeUpdate())
                }
                insertScopedJob(connection, targetPending, studyId, participantId, "PENDING")
                insertScopedJob(connection, targetPaused, studyId, participantId, "PAUSED")
                insertScopedJob(connection, targetFinished, studyId, participantId, "FINISHED")
                insertScopedJob(connection, sameStudyControl, studyId, otherParticipantId, "PENDING")
                insertScopedJob(connection, otherStudyControl, otherStudyId, participantId, "PENDING")
            }
            operationId = orchestrator.quarantineParticipant(
                studyId,
                participantId,
                DataDeletionMode.WITHDRAW_AND_ERASE,
                "migration-test",
                UUID.randomUUID(),
            )
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET ROLE chronicle_app")
                    statement.execute(
                        "SELECT set_config('app.current_user_id', 'job-quarantine-policy-test', false)"
                    )
                    statement.execute(
                        "SELECT set_config('app.authorized_studies', '$studyId,$otherStudyId', false)"
                    )
                    statement.execute("SELECT set_config('app.is_admin', 'false', false)")
                }
                try {
                    connection.prepareStatement(
                        """
                        SELECT job_id
                        FROM jobs
                        WHERE job_id IN (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, targetPending)
                        statement.setObject(2, targetPaused)
                        statement.setObject(3, targetFinished)
                        statement.setObject(4, sameStudyControl)
                        statement.setObject(5, otherStudyControl)
                        statement.executeQuery().use { resultSet ->
                            val visible = buildSet {
                                while (resultSet.next()) {
                                    add(resultSet.getObject("job_id", UUID::class.java))
                                }
                            }
                            assertEquals(
                                "Quarantine must hide every job containing the erased participant",
                                setOf(sameStudyControl, otherStudyControl),
                                visible,
                            )
                        }
                    }
                } finally {
                    connection.createStatement().use { it.execute("RESET ROLE") }
                }
            }

            assertEquals("COMPLETED", orchestrator.eraseNow(operationId!!, studyId, "migration-test").status)
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT job_id
                    FROM jobs
                    WHERE job_id IN (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, targetPending)
                    statement.setObject(2, targetPaused)
                    statement.setObject(3, targetFinished)
                    statement.setObject(4, sameStudyControl)
                    statement.setObject(5, otherStudyControl)
                    statement.executeQuery().use { resultSet ->
                        val remaining = buildSet {
                            while (resultSet.next()) {
                                add(resultSet.getObject("job_id", UUID::class.java))
                            }
                        }
                        assertEquals(setOf(sameStudyControl, otherStudyControl), remaining)
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT status, expected_rows, deleted_rows, residual_rows
                    FROM data_deletion_steps
                    WHERE operation_id = ? AND asset_id = 'jobs'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("VERIFIED", resultSet.getString("status"))
                        assertEquals(3L, resultSet.getLong("expected_rows"))
                        assertEquals(3L, resultSet.getLong("deleted_rows"))
                        assertEquals(0L, resultSet.getLong("residual_rows"))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "DELETE FROM jobs WHERE job_id IN (?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, targetPending)
                    statement.setObject(2, targetPaused)
                    statement.setObject(3, targetFinished)
                    statement.setObject(4, sameStudyControl)
                    statement.setObject(5, otherStudyControl)
                    statement.executeUpdate()
                }
                operationId?.let { id ->
                    for (table in listOf("data_deletion_tombstones", "data_deletion_audit_outbox")) {
                        connection.prepareStatement("DELETE FROM $table WHERE operation_id = ?").use {
                            it.setObject(1, id)
                            it.executeUpdate()
                        }
                    }
                    connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id IN (?, ?)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setObject(2, otherStudyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testWithdrawalReceiptRetentionDoesNotBlockParticipantOrStudyErasure() {
        val fixture = ErasureRetentionFixture()
        val operationIds = mutableListOf<UUID>()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )

        try {
            seedErasureRetentionFixture(fixture)
            operationIds += processParticipantErasure(orchestrator, fixture)
            assertParticipantErasureRetention(fixture)
            operationIds += processStudyErasure(orchestrator, fixture)
            assertStudyErasureRetention(fixture)
        } finally {
            cleanupErasureRetentionFixture(fixture, operationIds)
        }
    }

    @Test
    fun testResearcherKeyRotationRollsBackRevocationOnReplacementFailure() {
        val fixture = ConcurrentWithdrawalFixture()
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        val service = ApiKeyService(storageResolver, ids, PostgresAuditingManager(storageResolver))
        TestSecurityUtils.setupSecurityContext("migration-test")
        try {
            seedConcurrentWithdrawalFixture(fixture)
            val original = service.createApiKey(fixture.studyId, "migration-test",
                com.openlattice.chronicle.apikey.ApiKeyCreateRequest("rotation", com.openlattice.chronicle.apikey.ApiKeyScope.READ_ONLY))
            Mockito.`when`(ids.getNextId()).thenThrow(IllegalStateException("injected id service failure"))
            assertThrows(IllegalStateException::class.java) {
                service.rotateApiKey(fixture.studyId, original.keyId, "migration-test")
            }
            assertNotNull(service.authenticateApiKey(original.rawKey))
            getConnection().use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE study_id = ? AND audit_event_type = ?").use {
                    it.setString(1, fixture.studyId.toString())
                    it.setString(2, AuditEventType.REVOKE_API_KEY.name)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
            Mockito.doAnswer { UUID.randomUUID() }.`when`(ids).getNextId()
            val replacement = service.rotateApiKey(fixture.studyId, original.keyId, "migration-test")
            assertNull(service.authenticateApiKey(original.rawKey))
            assertNotNull(service.authenticateApiKey(replacement.rawKey))
            assertThrows(IllegalArgumentException::class.java) {
                service.rotateApiKey(fixture.studyId, original.keyId, "migration-test")
            }
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testClonedStudyCanEnrollAndSummarizeUuidAndTextBackedData() {
        val fixture = ConcurrentWithdrawalFixture()
        val clonedId = UUID.randomUUID()
        val studyService = Mockito.mock(StudyService::class.java)
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenReturn(clonedId)
        Mockito.`when`(studyService.getStudy(fixture.studyId)).thenReturn(
            Study(studyId = fixture.studyId, title = "clone-source", contact = "test@example.com"))
        val limits = com.openlattice.chronicle.services.studies.StudyLimitsService(storageResolver,
            Mockito.mock(com.hazelcast.core.HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS))
        val candidates = com.openlattice.chronicle.services.candidates.CandidateService(storageResolver, authorization, ids)
        val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(storageResolver, ids, candidates)
        val audit = Mockito.mock(AuditingManager::class.java)
        val lifecycle = StudyLifecycleService(storageResolver, studyService, authorization, ids, audit,
            DataDeletionOrchestrator(storageResolver, audit), Mockito.mock(WebhookService::class.java), limits)
        TestSecurityUtils.setupSecurityContext("migration-test")
        try {
            seedConcurrentWithdrawalFixture(fixture)
            assertEquals(clonedId, lifecycle.cloneStudy(fixture.studyId, "migration-test",
                com.openlattice.chronicle.study.StudyCloneRequest()))
            getConnection().use { connection ->
                connection.autoCommit = false
                val candidate = candidates.registerCandidate(connection, com.openlattice.chronicle.candidates.Candidate())
                enrollment.registerParticipant(connection, clonedId, fixture.participantId, candidate,
                    com.openlattice.chronicle.data.ParticipationStatus.ENROLLED)
                connection.commit()
            }
            assertEquals(1L, studyRowCount(clonedId, "study_participants"))
            assertEquals(0L, studyRowCount(clonedId, "chronicle_usage_events"))
            assertEquals(com.openlattice.chronicle.data.ParticipationStatus.ENROLLED,
                enrollment.getDeviceParticipationStatus(clonedId, fixture.participantId))
        } finally {
            getConnection().use { connection ->
                executeSingleUpdate(connection, "DELETE FROM study_participants WHERE study_id = ?", expected = null) {
                    it.setObject(1, clonedId)
                }
                deleteById(connection, "study_limits", "study_id", clonedId)
                deleteById(connection, "candidates", "candidate_id", clonedId)
                deleteById(connection, "studies", "study_id", clonedId)
            }
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun testConcurrentTwoDeviceWithdrawalsHaveExactlyOnePurgeOwner() {
        val fixture = ConcurrentWithdrawalFixture()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val service = createParticipantPurgeService(auditingManager)

        try {
            seedConcurrentWithdrawalFixture(fixture)
            val attempts = fixture.devices.map { device ->
                TestSecurityUtils.initPrincipalsIfNeeded("apikey:${device.keyId}", admin = false)
                executor.submit<MobileSelfWithdrawalResult> {
                    withDeviceRequestContext(fixture, device) {
                        ready.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        service.executeSelfWithdrawal(
                            fixture.studyId,
                            fixture.participantId,
                            device.randomDeviceId,
                            device.keyId,
                            device.requestId,
                        )
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(setOf(false, true), results.map { it.alreadyWithdrawn }.toSet())
            TestSecurityUtils.setupSecurityContext("mobile-withdrawal-replay")
            try {
                results.zip(fixture.devices).forEach { (first, device) ->
                    val replay = withDeviceRequestContext(fixture, device) {
                        service.executeSelfWithdrawal(
                            fixture.studyId,
                            fixture.participantId,
                            device.randomDeviceId,
                            device.keyId,
                            device.requestId,
                        )
                    }
                    assertEquals(first, replay)
                }
            } finally {
                TestSecurityUtils.clearSecurityContext()
            }
            assertConcurrentWithdrawalPostconditions(fixture)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    @Test
    fun testWithdrawalOperationFailureRollsBackReceiptAndParticipationStatus() {
        val fixture = ConcurrentWithdrawalFixture()
        val device = fixture.devices.first()
        val triggerName = "fail_withdrawal_operation_${UUID.randomUUID().toString().replace("-", "")}"
        val triggerFunction = "${triggerName}_fn"
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val service = createParticipantPurgeService(auditingManager)

        try {
            seedConcurrentWithdrawalFixture(fixture)
            installWithdrawalOperationFailureTrigger(triggerName, triggerFunction, device.requestId)

            assertThrows(Exception::class.java) {
                service.executeSelfWithdrawal(
                    fixture.studyId,
                    fixture.participantId,
                    device.randomDeviceId,
                    device.keyId,
                    device.requestId,
                )
            }
            assertWithdrawalOperationRolledBack(fixture, device)
        } finally {
            dropWithdrawalOperationFailureTrigger(triggerName, triggerFunction)
            cleanupConcurrentWithdrawalFixture(fixture)
        }
    }

    private fun createParticipantPurgeService(auditingManager: AuditingManager): ParticipantPurgeService {
        val apiKeyService = ApiKeyService(
            storageResolver,
            Mockito.mock(HazelcastIdGenerationService::class.java),
            auditingManager,
        )
        return ParticipantPurgeService(
            storageResolver,
            DataDeletionOrchestrator(storageResolver, auditingManager),
            apiKeyService,
            auditingManager,
        )
    }

    private fun installWithdrawalOperationFailureTrigger(
        triggerName: String,
        triggerFunction: String,
        requestId: UUID,
    ) {
        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE FUNCTION $triggerFunction() RETURNS trigger LANGUAGE plpgsql AS ${'$'}body${'$'}
                    BEGIN
                        IF NEW.idempotency_key = '$requestId'::uuid THEN
                            RAISE EXCEPTION 'injected deletion-ledger failure';
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}body${'$'}
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TRIGGER $triggerName
                    BEFORE INSERT ON data_deletion_operations
                    FOR EACH ROW EXECUTE FUNCTION $triggerFunction()
                    """.trimIndent(),
                )
            }
        }
    }

    private fun assertWithdrawalOperationRolledBack(
        fixture: ConcurrentWithdrawalFixture,
        device: ConcurrentWithdrawalDevice,
    ) {
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT participation_status,
                       (SELECT count(*) FROM mobile_withdrawal_requests WHERE request_id = ?) AS receipts,
                       (SELECT count(*) FROM data_deletion_operations WHERE idempotency_key = ?) AS operations,
                       (SELECT revoked FROM api_keys WHERE key_id = ?) AS key_revoked
                FROM study_participants WHERE study_id = ? AND participant_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, device.requestId)
                statement.setObject(2, device.requestId)
                statement.setObject(3, device.keyId)
                statement.setObject(4, fixture.studyId)
                statement.setString(5, fixture.participantId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("ENROLLED", resultSet.getString("participation_status"))
                    assertEquals(0, resultSet.getInt("receipts"))
                    assertEquals(0, resultSet.getInt("operations"))
                    assertFalse(resultSet.getBoolean("key_revoked"))
                }
            }
        }
    }

    private fun dropWithdrawalOperationFailureTrigger(triggerName: String, triggerFunction: String) {
        getConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS $triggerName ON data_deletion_operations")
                statement.execute("DROP FUNCTION IF EXISTS $triggerFunction()")
            }
        }
    }

    private fun seedConcurrentWithdrawalFixture(fixture: ConcurrentWithdrawalFixture,
        withdrawalMode: com.openlattice.chronicle.study.WithdrawalMode? = com.openlattice.chronicle.study.WithdrawalMode.ERASE) {
        getConnection().use { connection ->
            executeSingleUpdate(connection, "INSERT INTO studies (study_id, title) VALUES (?, ?)") { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setString(2, "concurrent-withdrawal-${fixture.studyId}")
            }
            withdrawalMode?.let { mode ->
                executeSingleUpdate(connection, "UPDATE studies SET settings = ?::jsonb WHERE study_id = ?") { statement ->
                    statement.setString(1, ObjectMappers.getJsonMapper().writeValueAsString(
                        StudySettings(mapOf(StudySettingType.Withdrawal to com.openlattice.chronicle.study.StudyWithdrawalSetting(mode)))))
                    statement.setObject(2, fixture.studyId)
                }
            }
            executeSingleUpdate(
                connection,
                """
                INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ) { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setString(2, fixture.participantId)
                statement.setObject(3, seedCandidate(connection))
            }
            fixture.devices.forEachIndexed { index, device ->
                executeSingleUpdate(
                    connection,
                    """
                    INSERT INTO api_keys (
                        key_id, study_id, key_hash, key_prefix, name, scope, created_by,
                        expires_at, revoked, participant_id, random_device_id
                    ) VALUES (?, ?, ?, ?, ?, 'WRITE', 'migration-test',
                              now() + interval '1 day', false, ?, ?)
                    """.trimIndent(),
                ) { statement ->
                    statement.setObject(1, device.keyId)
                    statement.setObject(2, fixture.studyId)
                    statement.setString(3, device.keyId.toString().replace("-", "").repeat(2))
                    statement.setString(4, "device-$index")
                    statement.setString(5, "withdrawal-device-$index")
                    statement.setString(6, fixture.participantId)
                    statement.setObject(7, device.randomDeviceId)
                }
            }
        }
    }

    private fun assertConcurrentWithdrawalPostconditions(fixture: ConcurrentWithdrawalFixture) {
        getConnection().use { connection ->
            assertConcurrentWithdrawalLedger(connection, fixture)
            assertConcurrentWithdrawalKeysRevoked(connection, fixture)
        }
    }

    @Test fun testStudyWithdrawalWithoutPolicyRetainsPriorData() =
        assertStudyWithdrawalPolicy(null)

    @Test fun testStudyWithdrawalEraseQuarantinesDataAndReportsWithdrawal() =
        assertStudyWithdrawalPolicy(WithdrawalMode.ERASE)

    private fun assertStudyWithdrawalPolicy(mode: WithdrawalMode?) {
        val fixture = ConcurrentWithdrawalFixture()
        val erase = mode == WithdrawalMode.ERASE
        TestSecurityUtils.setupSecurityContext()
        try {
            seedConcurrentWithdrawalFixture(fixture, mode)
            val audit = Mockito.mock(AuditingManager::class.java)
            val service = createParticipantPurgeService(audit)
            val device = fixture.devices.first()
            val result = service.executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                device.randomDeviceId, device.keyId, device.requestId)
            assertFalse(result.alreadyWithdrawn)
            if (erase) assertNotNull("ERASE withdrawal creates an operation", result.deletionOperationId)
            else assertNull("KEEP_PRIOR_DATA withdrawal creates no operation", result.deletionOperationId)
            assertEquals(result, service.executeSelfWithdrawal(fixture.studyId, fixture.participantId,
                device.randomDeviceId, device.keyId, device.requestId))
            assertEquals(if (erase) 1L else 0L, sweepCount(fixture, "data_deletion_operations"))
            assertEquals(1L, sweepCount(fixture, "mobile_withdrawal_requests"))
            assertFalse("Withdrawal revokes the device credential",
                apiKeys().listApiKeys(fixture.studyId).any { it.keyId == device.keyId })
            assertWithdrawalPriorDataVisibility(fixture, erase)
            result.deletionOperationId?.let {
                processDeletion(DataDeletionOrchestrator(storageResolver, audit), it)
            }
            asDeviceApplication(fixture, device.keyId) { resolver ->
                val enrollment = com.openlattice.chronicle.services.enrollment.EnrollmentService(resolver,
                    Mockito.mock(HazelcastIdGenerationService::class.java),
                    Mockito.mock(com.openlattice.chronicle.services.candidates.CandidateManager::class.java))
                val participant = enrollment.getStudyParticipants(fixture.studyId).single()
                assertEquals(fixture.participantId, participant.participantId)
                assertEquals(com.openlattice.chronicle.data.ParticipationStatus.NOT_ENROLLED, participant.participationStatus)
                assertNotNull(participant.withdrawnAt)
            }
            assertWithdrawalAudits(audit, fixture, result)
        } finally {
            cleanupConcurrentWithdrawalFixture(fixture)
            TestSecurityUtils.clearSecurityContext()
        }
    }

    private fun assertWithdrawalPriorDataVisibility(fixture: ConcurrentWithdrawalFixture, erase: Boolean) {
        getConnection().use { connection ->
            connection.prepareStatement("SELECT chronicle_participant_data_visible(?, ?)").use { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setString(2, fixture.participantId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Only ERASE withdrawal quarantines prior data", !erase, rows.getBoolean(1))
                }
            }
            assertThrows(SQLException::class.java) {
                executeSingleUpdate(
                    connection,
                    "UPDATE study_participants SET participation_status = ? WHERE study_id = ?",
                ) {
                    it.setString(1, ParticipationStatus.ENROLLED.name)
                    it.setObject(2, fixture.studyId)
                }
            }
        }
    }

    private fun assertWithdrawalAudits(
        audit: AuditingManager, fixture: ConcurrentWithdrawalFixture, result: MobileSelfWithdrawalResult,
    ) {
        val erase = result.deletionOperationId != null
        val expectedMode = if (erase) DataDeletionMode.WITHDRAW_AND_ERASE.name else WithdrawalMode.KEEP_PRIOR_DATA.name
        val recorded = Mockito.mockingDetails(audit).invocations.flatMap { invocation ->
            when (val argument = invocation.arguments.lastOrNull()) {
                is AuditObservation -> argument.events
                is List<*> -> argument.filterIsInstance<AuditableEvent>()
                else -> emptyList()
            }
        }
        assertTrue(recorded.any { it.eventType == AuditEventType.UPDATE_PARTICIPATION_STATUS && it.study == fixture.studyId })
        assertTrue(recorded.any { it.study == fixture.studyId && it.data["mode"] == expectedMode &&
            (erase || ("operationId" !in it.data && it.description.contains("without erasure"))) })
    }

    private fun assertConcurrentWithdrawalLedger(connection: Connection, fixture: ConcurrentWithdrawalFixture) {
        connection.prepareStatement(
            """
            SELECT participation_status,
                   (SELECT count(*) FROM mobile_withdrawal_requests
                    WHERE study_id = ? AND participant_id = ? AND already_withdrawn = false) AS owners,
                   (SELECT count(*) FROM mobile_withdrawal_requests
                    WHERE study_id = ? AND participant_id = ?) AS receipts,
                   (SELECT count(*) FROM data_deletion_operations
                    WHERE study_id = ? AND participant_id = ? AND mode = 'WITHDRAW_AND_ERASE') AS operations,
                   (SELECT count(*)
                    FROM mobile_withdrawal_requests AS receipt
                    LEFT JOIN data_deletion_operations AS operation
                      ON operation.idempotency_key = receipt.request_id
                     AND operation.study_id = receipt.study_id
                     AND operation.participant_id = receipt.participant_id
                     AND operation.mode = 'WITHDRAW_AND_ERASE'
                    WHERE receipt.study_id = ? AND receipt.participant_id = ?
                      AND receipt.already_withdrawn = false
                      AND operation.operation_id IS NULL) AS owners_without_operation
            FROM study_participants WHERE study_id = ? AND participant_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, fixture.studyId)
            statement.setString(2, fixture.participantId)
            statement.setObject(3, fixture.studyId)
            statement.setString(4, fixture.participantId)
            statement.setObject(5, fixture.studyId)
            statement.setString(6, fixture.participantId)
            statement.setObject(7, fixture.studyId)
            statement.setString(8, fixture.participantId)
            statement.setObject(9, fixture.studyId)
            statement.setString(10, fixture.participantId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                assertEquals("NOT_ENROLLED", resultSet.getString("participation_status"))
                assertEquals(1, resultSet.getInt("owners"))
                assertEquals(2, resultSet.getInt("receipts"))
                assertEquals(1, resultSet.getInt("operations"))
                assertEquals(0, resultSet.getInt("owners_without_operation"))
            }
        }
    }

    private fun assertConcurrentWithdrawalKeysRevoked(
        connection: Connection,
        fixture: ConcurrentWithdrawalFixture,
    ) {
        connection.prepareStatement(
            """
            SELECT count(*) FILTER (WHERE revoked = false) AS active_keys,
                   count(*) FILTER (WHERE revoked = true) AS revoked_keys
            FROM api_keys WHERE study_id = ? AND participant_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, fixture.studyId)
            statement.setString(2, fixture.participantId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                assertEquals(0, resultSet.getInt("active_keys"))
                assertEquals(fixture.devices.size, resultSet.getInt("revoked_keys"))
            }
        }
    }

    private fun cleanupConcurrentWithdrawalFixture(fixture: ConcurrentWithdrawalFixture) {
        getConnection().use { connection ->
            executeSingleUpdate(connection, "DELETE FROM data_deletion_audit_outbox WHERE study_id = ?", expected = null) {
                it.setObject(1, fixture.studyId)
            }
            executeSingleUpdate(
                connection,
                "DELETE FROM data_deletion_operations WHERE study_id = ?",
                expected = null,
            ) { it.setObject(1, fixture.studyId) }
            executeSingleUpdate(
                connection,
                "DELETE FROM mobile_withdrawal_requests WHERE study_id = ?",
                expected = null,
            ) { it.setObject(1, fixture.studyId) }
            executeSingleUpdate(connection, "DELETE FROM api_keys WHERE study_id = ?", expected = null) {
                it.setObject(1, fixture.studyId)
            }
            executeSingleUpdate(connection, "DELETE FROM study_participants WHERE study_id = ?", expected = null) {
                it.setObject(1, fixture.studyId)
            }
            studyFixture.deleteStudyRow(fixture.studyId)
        }
    }

    private data class ConcurrentWithdrawalFixture(
        val studyId: UUID = UUID.randomUUID(),
        val participantId: String = "concurrent-withdrawal-${UUID.randomUUID()}",
        val devices: List<ConcurrentWithdrawalDevice> = List(2) {
            ConcurrentWithdrawalDevice(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        },
    )

    private data class ConcurrentWithdrawalDevice(
        val randomDeviceId: UUID,
        val keyId: UUID,
        val requestId: UUID,
    )

    private fun seedErasureRetentionFixture(fixture: ErasureRetentionFixture) {
        getConnection().use { connection ->
            seedErasureRetentionSubject(connection, fixture)
            seedErasureRetentionCredentials(connection, fixture)
            seedErasureRetentionEvidence(connection, fixture)
        }
    }

    private fun seedErasureRetentionSubject(connection: Connection, fixture: ErasureRetentionFixture) {
        executeSingleUpdate(connection, "INSERT INTO studies (study_id, title) VALUES (?, ?)") { statement ->
            statement.setObject(1, fixture.studyId)
            statement.setString(2, "withdrawal-retention-${fixture.studyId}")
        }
        executeSingleUpdate(
            connection,
            """
            INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
            VALUES (?, ?, ?, 'ENROLLED')
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.studyId)
            statement.setString(2, fixture.participantId)
            statement.setObject(3, seedCandidate(connection))
        }
    }

    private fun seedErasureRetentionCredentials(connection: Connection, fixture: ErasureRetentionFixture) {
        executeSingleUpdate(
            connection,
            """
            INSERT INTO participant_form_access_codes (
                access_code_id, token_hash, study_id, participant_id, form_kind,
                issuer_type, issued_by, expires_at
            ) VALUES (?, ?, ?, ?, 'ENROLLMENT', 'RESEARCHER', 'migration-test', now() + interval '1 day')
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.accessCodeId)
            statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
            statement.setObject(3, fixture.studyId)
            statement.setString(4, fixture.participantId)
        }
        executeSingleUpdate(
            connection,
            """
            INSERT INTO api_keys (
                key_id, study_id, key_hash, key_prefix, name, scope, created_by,
                expires_at, revoked, participant_id, random_device_id
            ) VALUES (?, ?, ?, ?, 'withdrawal-retention', 'WRITE', 'migration-test',
                      now() + interval '1 day', true, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.apiKeyId)
            statement.setObject(2, fixture.studyId)
            statement.setString(3, UUID.randomUUID().toString().replace("-", "").repeat(2))
            statement.setString(4, "ck_test_")
            statement.setString(5, fixture.participantId)
            statement.setObject(6, fixture.randomDeviceId)
        }
    }

    private fun seedErasureRetentionEvidence(connection: Connection, fixture: ErasureRetentionFixture) {
        executeSingleUpdate(
            connection,
            """
            INSERT INTO mobile_withdrawal_requests (
                request_id, api_key_id, study_id, participant_id, random_device_id, already_withdrawn
            ) VALUES (?, ?, ?, ?, ?, false)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.withdrawalRequestId)
            statement.setObject(2, fixture.apiKeyId)
            statement.setObject(3, fixture.studyId)
            statement.setString(4, fixture.participantId)
            statement.setObject(5, fixture.randomDeviceId)
        }
        executeSingleUpdate(
            connection,
            """
            INSERT INTO participant_collection_acknowledgment (
                id, study_id, participant_id, source_random_device_id, acknowledged_modules,
                acknowledged_at, settings_version, disclosure_version, manifest_digest,
                evidence_access_code_id, evidence_api_key_id, decision_seq
            ) VALUES (?, ?, ?, ?, '[]'::jsonb, now(), 1, 'v1', ?, ?, ?, 1)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.acknowledgmentId)
            statement.setObject(2, fixture.studyId)
            statement.setString(3, fixture.participantId)
            statement.setString(4, fixture.randomDeviceId.toString())
            statement.setString(5, "a".repeat(64))
            statement.setObject(6, fixture.accessCodeId)
            statement.setObject(7, fixture.apiKeyId)
        }
    }

    private fun processParticipantErasure(
        orchestrator: DataDeletionOrchestrator,
        fixture: ErasureRetentionFixture,
    ): UUID {
        val operationId = orchestrator.quarantineParticipant(
            fixture.studyId,
            fixture.participantId,
            DataDeletionMode.WITHDRAW_AND_ERASE,
            "migration-test",
            UUID.randomUUID(),
        )
        processDeletion(orchestrator, operationId)
        return operationId
    }

    private fun processStudyErasure(
        orchestrator: DataDeletionOrchestrator,
        fixture: ErasureRetentionFixture,
    ): UUID {
        val operationId = orchestrator.quarantineStudy(fixture.studyId, "migration-test", UUID.randomUUID())
        processDeletion(orchestrator, operationId)
        return operationId
    }

    private fun processDeletion(orchestrator: DataDeletionOrchestrator, operationId: UUID) =
        RLSRequestContext.withDeletionWorkerContext {
            val studyId = orchestrator.getOperation(operationId).studyId
            assertEquals("COMPLETED", orchestrator.eraseNow(operationId, studyId, "migration-test").status)
        }

    private fun assertParticipantErasureRetention(fixture: ErasureRetentionFixture) {
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    (SELECT count(*) FROM mobile_withdrawal_requests WHERE request_id = ?) AS withdrawals,
                    (SELECT count(*) FROM participant_collection_acknowledgment WHERE id = ?) AS acknowledgments,
                    (SELECT count(*) FROM participant_form_access_codes WHERE access_code_id = ?) AS access_codes
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, fixture.withdrawalRequestId)
                statement.setObject(2, fixture.acknowledgmentId)
                statement.setObject(3, fixture.accessCodeId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("withdrawal replay receipt must survive participant erasure", 1, resultSet.getInt("withdrawals"))
                    assertEquals("consent evidence must survive participant erasure", 1, resultSet.getInt("acknowledgments"))
                    assertEquals("erasable invitation must be removed", 0, resultSet.getInt("access_codes"))
                }
            }
        }
    }

    private fun assertStudyErasureRetention(fixture: ErasureRetentionFixture) {
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    (SELECT count(*) FROM studies WHERE study_id = ?) AS studies,
                    (SELECT count(*) FROM api_keys WHERE key_id = ?) AS api_keys,
                    (SELECT count(*) FROM mobile_withdrawal_requests WHERE request_id = ?) AS withdrawals,
                    (SELECT count(*) FROM participant_collection_acknowledgment WHERE id = ?) AS acknowledgments
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setObject(2, fixture.apiKeyId)
                statement.setObject(3, fixture.withdrawalRequestId)
                statement.setObject(4, fixture.acknowledgmentId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(0, resultSet.getInt("studies"))
                    assertEquals(0, resultSet.getInt("api_keys"))
                    assertEquals("full study erasure must remove the replay receipt", 0, resultSet.getInt("withdrawals"))
                    assertEquals("append-only consent evidence is retained without source-row FKs", 1, resultSet.getInt("acknowledgments"))
                }
            }
        }
    }

    private fun cleanupErasureRetentionFixture(
        fixture: ErasureRetentionFixture,
        operationIds: List<UUID>,
    ) {
        getConnection().use { connection ->
            deleteById(connection, "participant_collection_acknowledgment", "id", fixture.acknowledgmentId)
            deleteById(connection, "mobile_withdrawal_requests", "request_id", fixture.withdrawalRequestId)
            deleteById(connection, "api_keys", "key_id", fixture.apiKeyId)
            deleteById(connection, "participant_form_access_codes", "access_code_id", fixture.accessCodeId)
            operationIds.forEach { operationId ->
                deleteById(connection, "data_deletion_tombstones", "operation_id", operationId)
                deleteById(connection, "data_deletion_audit_outbox", "operation_id", operationId)
                deleteById(connection, "data_deletion_operations", "operation_id", operationId)
            }
            executeSingleUpdate(connection, "DELETE FROM study_participants WHERE study_id = ?", expected = null) {
                it.setObject(1, fixture.studyId)
            }
            executeSingleUpdate(connection, "DELETE FROM studies WHERE study_id = ?", expected = null) {
                it.setObject(1, fixture.studyId)
            }
        }
    }

    private class ErasureRetentionFixture {
        val studyId: UUID = UUID.randomUUID()
        val participantId: String = "withdrawal-retention-${UUID.randomUUID()}"
        val randomDeviceId: UUID = UUID.randomUUID()
        val apiKeyId: UUID = UUID.randomUUID()
        val accessCodeId: UUID = UUID.randomUUID()
        val acknowledgmentId: UUID = UUID.randomUUID()
        val withdrawalRequestId: UUID = UUID.randomUUID()
    }

    @Test
    fun testStudyFinalizationReconcilesTablesCreatedDuringQuarantine() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val dynamicTable = "test_dynamic_study_$suffix"
        val studyId = UUID.randomUUID()
        val controlStudyId = UUID.randomUUID()
        val studyJobId = UUID.randomUUID()
        val controlJobId = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        var operationId: UUID? = null

        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO studies (study_id, title) VALUES (?, ?), (?, ?)",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "dynamic-target-$suffix")
                    statement.setObject(3, controlStudyId)
                    statement.setString(4, "dynamic-control-$suffix")
                    assertEquals(2, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO jobs (
                        job_id, securable_principal_id, principal_type, principal_id,
                        status, definition, message, deleted_rows
                    ) VALUES
                        (?, ?, 'USER', 'dynamic-study-job', 'PENDING', ?::jsonb, '', 0),
                        (?, ?, 'USER', 'dynamic-study-control', 'PENDING', ?::jsonb, '', 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, studyJobId)
                    statement.setObject(2, UUID.randomUUID())
                    statement.setString(
                        3,
                        """{"@type":"EmptyJobDefinition","studyId":"$studyId","participantIds":[]}""",
                    )
                    statement.setObject(4, controlJobId)
                    statement.setObject(5, UUID.randomUUID())
                    statement.setString(
                        6,
                        """{"@type":"EmptyJobDefinition","studyId":"$controlStudyId","participantIds":[]}""",
                    )
                    assertEquals(2, statement.executeUpdate())
                }
            }
            operationId = orchestrator.quarantineStudy(
                studyId,
                "migration-test",
                UUID.randomUUID(),
            )
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE $dynamicTable (
                            row_id UUID PRIMARY KEY,
                            study_id UUID NOT NULL,
                            value TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    "INSERT INTO $dynamicTable (row_id, study_id, value) VALUES (?, ?, ?), (?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.setString(3, "erase-me")
                    statement.setObject(4, UUID.randomUUID())
                    statement.setObject(5, controlStudyId)
                    statement.setString(6, "keep-me")
                    assertEquals(2, statement.executeUpdate())
                }
            }

            assertEquals("COMPLETED", orchestrator.eraseNow(operationId!!, studyId, "migration-test").status)
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT study_id, value FROM $dynamicTable ORDER BY value",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(controlStudyId, resultSet.getObject("study_id", UUID::class.java))
                        assertEquals("keep-me", resultSet.getString("value"))
                        assertFalse(resultSet.next())
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT status, residual_rows
                    FROM data_deletion_steps
                    WHERE operation_id = ? AND asset_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setString(2, "study-table:$dynamicTable")
                    statement.executeQuery().use { resultSet ->
                        assertTrue("the late table must be added to the proof inventory", resultSet.next())
                        assertEquals("VERIFIED", resultSet.getString("status"))
                        assertEquals(0L, resultSet.getLong("residual_rows"))
                    }
                }
                connection.prepareStatement(
                    "SELECT job_id FROM jobs WHERE job_id IN (?, ?)",
                ).use { statement ->
                    statement.setObject(1, studyJobId)
                    statement.setObject(2, controlJobId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(controlJobId, resultSet.getObject("job_id", UUID::class.java))
                        assertFalse(resultSet.next())
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS $dynamicTable")
                }
                connection.prepareStatement("DELETE FROM jobs WHERE job_id IN (?, ?)").use { statement ->
                    statement.setObject(1, studyJobId)
                    statement.setObject(2, controlJobId)
                    statement.executeUpdate()
                }
                operationId?.let { id ->
                    for (table in listOf("data_deletion_tombstones", "data_deletion_audit_outbox")) {
                        connection.prepareStatement("DELETE FROM $table WHERE operation_id = ?").use {
                            it.setObject(1, id)
                            it.executeUpdate()
                        }
                    }
                    connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id IN (?, ?)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setObject(2, controlStudyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testStudyFinalizationFailsClosedOnUnsafeLateTableIdentifier() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val unsafeTable = "UnsafeStudy$suffix"
        val studyId = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        var operationId: UUID? = null

        try {
            getConnection().use { connection ->
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "unsafe-late-table-$suffix")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            operationId = orchestrator.quarantineStudy(
                studyId,
                "migration-test",
                UUID.randomUUID(),
            )
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE "$unsafeTable" (
                            row_id UUID PRIMARY KEY,
                            study_id UUID NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    """INSERT INTO "$unsafeTable" (row_id, study_id) VALUES (?, ?)""",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertThrows(Exception::class.java) { orchestrator.eraseNow(operationId!!, studyId, "migration-test") }
            assertEquals("FAILED", orchestrator.getOperation(operationId).status)
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                        (SELECT COUNT(*) FROM data_deletion_tombstones WHERE operation_id = ?) AS tombstones,
                        (SELECT failure_code FROM data_deletion_operations WHERE operation_id = ?) AS failure_code
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setObject(2, operationId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("unsafe inventory must never produce a tombstone", 0, resultSet.getInt("tombstones"))
                        assertEquals(
                            "the failure must come from identifier validation, not an unrelated table privilege",
                            "IllegalStateException",
                            resultSet.getString("failure_code"),
                        )
                    }
                }
                connection.prepareStatement(
                    """SELECT COUNT(*) FROM "$unsafeTable" WHERE study_id = ?""",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("failure must be explicit rather than silently omitting the table", 1, resultSet.getInt(1))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("""DROP TABLE IF EXISTS "$unsafeTable"""")
                }
                operationId?.let { id ->
                    for (table in listOf("data_deletion_tombstones", "data_deletion_audit_outbox")) {
                        connection.prepareStatement("DELETE FROM $table WHERE operation_id = ?").use {
                            it.setObject(1, id)
                            it.executeUpdate()
                        }
                    }
                    connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    // =========================================================================
    // Base schema integrity after the full corpus
    // =========================================================================
    @Test
    fun testBaseSchemaIntegrity() {
        val criticalTables = listOf(
            STUDIES.name, STUDY_PARTICIPANTS.name, CANDIDATES.name, PARTICIPANT_STATS.name,
            ORGANIZATIONS.name, NOTIFICATIONS.name, QUESTIONNAIRES.name,
            FILTERED_APPS.name, ANDROID_SENSOR_DATA.name, UPLOAD_BUFFER.name,
            ANDROID_DEVICE_SENSOR_AVAILABILITY.name, "flyway_schema_history",
        )
        for (table in criticalTables) {
            assertTrue("Table $table should exist", tableExists(table))
        }
        val studyCols = getColumnNames(STUDIES.name)
        assertTrue(studyCols.contains("study_id"))
        assertTrue(studyCols.contains("title"))
        assertTrue(studyCols.contains("settings"))
    }

    @Test fun auditRetryOfOneEventIsStoredOnceAndUnreadableToResearchers() {
        assertTrue("The request role must be able to append audits", roleHasPrivilege("chronicle_app", "audit", "INSERT"))
        val event = AuditableEvent(AclKey(UUID.randomUUID()), UUID.randomUUID(), Principal(PrincipalType.USER, "test-audit"),
            AuditEventType.LOGIN, description = "retry-audit-${UUID.randomUUID()}")
        val manager = PostgresAuditingManager(storageResolver)
        val context = RLSConnectionContext("test-audit", emptySet(), false)
        RLSRequestContext.set(context)
        try {
            assertEquals(1, manager.recordObservations(AuditObservation.Intent(listOf(event))))
            manager.recordObservations(AuditObservation.Intent(listOf(event)))
            assertEquals("Researchers may append audits without reading them", "chronicle_app" to 0, auditRowsVisibleTo(context))
        } finally { RLSRequestContext.clear() }
        getConnection().use { connection -> connection.prepareStatement("SELECT count(*) FROM audit WHERE event_id = ?").use {
            it.setObject(1, event.eventId); it.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
        } }
    }

    /** The request role and the audit rows it can read, even with a table grant that RLS must still refuse. */
    private fun auditRowsVisibleTo(context: RLSConnectionContext): Pair<String, Int> =
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                readAuditAs(connection, context)
            } finally {
                connection.rollback()
            }
        }

    private fun readAuditAs(connection: Connection, context: RLSConnectionContext): Pair<String, Int> {
        connection.createStatement().use { it.execute("GRANT SELECT ON audit TO chronicle_app") }
        RLSConnectionSql.applyContext(connection, context)
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_user, count(*) FROM audit").use { rows ->
                assertTrue(rows.next())
                rows.getString(1) to rows.getInt(2)
            }
        }
    }

    @Test fun stagedSuccessAuditRollsBackAndCommitsWithItsOwner() {
        val event = AuditableEvent(AclKey(UUID.randomUUID()), UUID.randomUUID(), Principal(PrincipalType.USER, "test-audit"),
            AuditEventType.UPDATE_STUDY, description = "transaction-audit-${UUID.randomUUID()}",
            request = com.openlattice.chronicle.auditing.AuditRequest("ip:0123456789ab", "agent/1", "/chronicle/v3/study", "PATCH"))
        val manager = PostgresAuditingManager(storageResolver)
        getConnection().use { connection ->
            connection.autoCommit = false
            com.openlattice.chronicle.storage.PinnedPlatformConnection.committing(
                storageResolver.getPlatformStorage(), connection, commitWhen = { false },
            ) { assertEquals(1, manager.recordEvents(connection, listOf(event))) }
        }
        fun count() = postgres.createConnection("").use { connection -> connection.prepareStatement(
            "SELECT count(*) FROM audit WHERE description = ?").use {
            it.setString(1, event.description); it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        } }
        assertEquals(0, count())
        getConnection().use { connection ->
            connection.autoCommit = false
            com.openlattice.chronicle.storage.PinnedPlatformConnection.committing(storageResolver.getPlatformStorage(), connection) {
                manager.recordEvents(connection, listOf(event))
            }
        }
        assertEquals(1, count())
        // The buffer trigger forwards the request columns with the event.
        postgres.createConnection("").use { connection -> connection.prepareStatement(
            "SELECT event_id, client_ip_ref, user_agent, request_path, request_method FROM audit WHERE description = ?").use {
            it.setString(1, event.description)
            it.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals(event.eventId, rows.getObject(1))
                assertEquals(listOf("ip:0123456789ab", "agent/1", "/chronicle/v3/study", "PATCH"), (2..5).map(rows::getString))
            }
        } }
    }

    @Test fun jwtRevocationsAndActivitySurviveEmptyHazelcastMaps() {
        fun fresh(): com.openlattice.chronicle.authorization.JwtBlocklist {
            val hz = org.mockito.kotlin.mock<com.hazelcast.core.HazelcastInstance>()
            val revoked = org.mockito.kotlin.mock<com.hazelcast.map.IMap<String, Long>>()
            val activity = org.mockito.kotlin.mock<com.hazelcast.map.IMap<String, Long>>()
            org.mockito.kotlin.whenever(revoked.name).thenReturn("JWT_BLOCKLIST")
            org.mockito.kotlin.whenever(activity.name).thenReturn("JWT_SESSION_ACTIVITY")
            org.mockito.kotlin.whenever(hz.getMap<String, Long>("JWT_BLOCKLIST")).thenReturn(revoked)
            org.mockito.kotlin.whenever(hz.getMap<String, Long>("JWT_SESSION_ACTIVITY")).thenReturn(activity)
            org.mockito.kotlin.whenever(activity.tryLock(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(true)
            return com.openlattice.chronicle.authorization.JwtBlocklist(hz, storageResolver)
        }
        val first = fresh()
        val jti = UUID.randomUUID().toString()
        val active = UUID.randomUUID().toString()
        val now = Instant.now()
        val issued = now.minus(Duration.ofMinutes(20))
        val expires = now.plus(Duration.ofHours(1))
        first.blockToken(jti, expires)
        val changedSubject = UUID.randomUUID().toString()
        first.revokePrincipalBefore(changedSubject, now.minus(Duration.ofDays(31)))
        assertTrue(first.touchActivity(active, issued, expires, Duration.ofMinutes(15),
            JwtBlocklist.ActivityTiming(now.minus(Duration.ofMinutes(10)))))
        val restarted = fresh()
        assertTrue(restarted.isBlocked(jti))
        assertEquals("Token was issued before a privilege change", restarted.credentialRejectionReason(null,
            UUID.randomUUID().toString(), now.minus(Duration.ofDays(32)), expires, Duration.ofMinutes(15), subject = changedSubject))
        assertTrue(restarted.isSessionActive(active, issued, expires, Duration.ofMinutes(15), now))
    }

    @Test fun bothDrainPathsContendOnTheSharedInsertionLocks() {
        getConnection().use { first ->
            first.autoCommit = false
            com.openlattice.chronicle.storage.DeletionTableLockOrder.lockDrain(first, "chronicle_usage_events")
            first.createStatement().use { statement ->
                statement.executeQuery("""
                    SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid()
                      AND locktype = 'relation' AND mode = 'ShareRowExclusiveLock'
                """.trimIndent()).use { locks ->
                    assertTrue(locks.next())
                    assertEquals("Drains must not block unrelated study writes or erasure", 0L, locks.getLong(1))
                }
            }
            getConnection().use { second ->
                second.autoCommit = false
                second.createStatement().use { it.execute("SET LOCAL lock_timeout = '100ms'") }
                val failure = assertThrows(SQLException::class.java) {
                    com.openlattice.chronicle.storage.DeletionTableLockOrder.lockDrain(second, "chronicle_usage_events")
                }
                assertEquals("55P03", failure.sqlState)
                second.rollback()
            }
            first.rollback()
        }
    }

    @Test fun nulBearingUsageAudioAndNotificationTextIsNormalizedBeforeStorage() {
        val study = UUID.randomUUID()
        val participant = "nul-${UUID.randomUUID()}"
        studyFixture.ensureStudyParticipant(study, participant)
        val timestamp = OffsetDateTime.now()
        val usage = com.openlattice.chronicle.android.ChronicleUsageEvent(studyId = study, participantId = participant,
            appPackageName = "com.example\u0000.app", interactionType = "Foreground\u0000", timestamp = timestamp,
            timezone = "UTC", user = "user\u0000", applicationLabel = "label\u0000", activityClass = "activity\u0000",
            clientEventId = UUID.randomUUID())
        val service = com.openlattice.chronicle.services.upload.AppDataUploadService(storageResolver, Mockito.mock(
            com.openlattice.chronicle.services.studies.StudyManager::class.java))
        assertEquals(setOf(usage.clientEventId), service.uploadAndroidUsageEvents(study, participant, UUID.randomUUID(), listOf(usage)))
        val audio = com.openlattice.chronicle.collection.AndroidAudioContentEvent(UUID.randomUUID().toString(), timestamp, "UTC",
            "media\u0000", title = "title\u0000", artist = "artist\u0000", album = "album\u0000")
        assertEquals(1, com.openlattice.chronicle.services.upload.AppAudioContentUploadService(storageResolver).upload(study, participant, listOf(
            audio)))
        val notification = com.openlattice.chronicle.collection.AndroidNotificationActivityEvent(UUID.randomUUID().toString(), timestamp, "UTC",
            com.openlattice.chronicle.collection.NotificationEventType.POSTED, "package\u0000", category = "category\u0000")
        val notifications = com.openlattice.chronicle.services.upload.NotificationActivityUploadService(storageResolver)
        assertEquals(1, notifications.upload(study, participant, listOf(notification)))
        assertEquals(1, notifications.upload(study, participant, listOf(notification.copy(id = UUID.randomUUID().toString(), packageName = "valid"))))
        getConnection().use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT audio_package, title, artist, album FROM app_audio_content WHERE study_id = '$study'").use { rows ->
                assertTrue(rows.next()); assertEquals("media", rows.getString(1)); assertEquals("title", rows.getString(2))
                assertEquals("artist", rows.getString(3)); assertEquals("album", rows.getString(4))
            }
            statement.executeQuery(
                "SELECT package_name, category FROM notification_activity WHERE study_id = '$study' AND event_id = '${notification.id}'").use {
                rows ->
                assertTrue(rows.next()); assertEquals("package", rows.getString(1)); assertEquals("category", rows.getString(2))
            }
            statement.executeQuery("SELECT data::text FROM upload_buffer WHERE study_id = '$study'").use { rows ->
                assertTrue(rows.next()); assertFalse(rows.getString(1).contains("\\u0000"))
            }
        } }
    }

    @Test fun testStudyWithdrawalKeepPriorDataDoesNotCreateErasure() =
        assertStudyWithdrawalPolicy(com.openlattice.chronicle.study.WithdrawalMode.KEEP_PRIOR_DATA)

    @Test fun testErasurePredicatesUseUuidAndTextStudyIndexes() {
        getConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
                connection.createStatement().use { statement ->
                    listOf("chronicle_delete_study_rows", "chronicle_count_study_rows_for_erasure").forEach { function ->
                        statement.executeQuery("SELECT pg_get_functiondef('$function(text,uuid)'::regprocedure)").use { rows ->
                            assertTrue(rows.next())
                            assertFalse("Dynamic erasure must also preserve native indexes", rows.getString(1).contains("study_id::text"))
                        }
                    }
                }
                listOf("android_sensor_data", "chronicle_usage_events").forEach { table ->
                    connection.prepareStatement(
                        "EXPLAIN SELECT * FROM $table WHERE ${com.openlattice.chronicle.services.delete.STUDY_ERASURE_PREDICATE}").use {
                        it.bindErasureStudyId(UUID.randomUUID())
                        it.executeQuery().use { rows ->
                            val plan = buildString { while (rows.next()) appendLine(rows.getString(1)) }
                            assertTrue("Native study index must be usable for $table: $plan", plan.contains("Index Cond:") && plan.contains(
                                "study_id"))
                        }
                    }
                }
            } finally { connection.rollback() }
        }
    }

}
