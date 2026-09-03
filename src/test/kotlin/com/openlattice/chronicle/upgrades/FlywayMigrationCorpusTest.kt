package com.openlattice.chronicle.upgrades

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.rhizome.jobs.JobStatus
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.controllers.loadLockedStudySettings
import com.openlattice.chronicle.controllers.mergeStudySetting
import com.openlattice.chronicle.controllers.stampDataCollectionSettingsVersion
import com.openlattice.chronicle.controllers.stampDataCollectionSettingsVersionLocked
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsDeletionGuard
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.pipeline.PipelineConfig
import com.openlattice.chronicle.pipeline.PipelineJobDefinition
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.MobileSelfWithdrawalResult
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.download.DataDownloadManager
import com.openlattice.chronicle.services.export.ExportFileWriter
import com.openlattice.chronicle.services.export.ExportResourceLimitException
import com.openlattice.chronicle.services.export.ExportService
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.services.jobs.ChronicleJobRunner
import com.openlattice.chronicle.services.jobs.EmptyJobDefinition
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.enrollment.loadLockedStudy
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeCommand
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptBinding
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.participantaccess.ParticipantSubmissionConflictException
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
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
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPGRADES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.study.StudySetting
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.webhooks.WebhookEventType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    @Test
    // Real JDBC resources are deliberately nested so each one closes before the next assertion.
    @Suppress("NestedBlockDepth")
    fun testUploadDiagnosticsAreIdempotentIdentityScopedAndRetentionBounded() {
        val studyId = UUID.randomUUID()
        val participantId = "diagnostic-participant-${UUID.randomUUID()}"
        val deviceId = UUID.randomUUID()
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
            assertEquals(listOf(eventId), service.upload(studyId, participantId, deviceId, listOf(diagnostic(1, now))))
            assertEquals(listOf(eventId), service.upload(studyId, participantId, deviceId, listOf(diagnostic(4, now))))

            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT occurrence_count, device_id FROM upload_diagnostics WHERE event_id = ?",
                ).use { statement ->
                    statement.setString(1, eventId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(4, resultSet.getInt("occurrence_count"))
                        assertEquals(deviceId, resultSet.getObject("device_id", UUID::class.java))
                    }
                }
            }

            val expired = diagnostic(1, now.minusDays(31))
                .copy(id = UUID.randomUUID().toString())
            service.upload(studyId, participantId, deviceId, listOf(expired))

            // A client-controlled future event time must not bypass the server's hard retention
            // bound. uploaded_at is the immutable server receipt time and independently expires it.
            val futureDated = diagnostic(1, now.plusYears(1))
                .copy(id = UUID.randomUUID().toString())
            service.upload(studyId, participantId, deviceId, listOf(futureDated))
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM upload_diagnostics WHERE event_id = ?",
                ).use { statement ->
                    statement.setString(1, expired.id)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(0, resultSet.getInt(1))
                    }
                }
                connection.prepareStatement(
                    "UPDATE upload_diagnostics SET uploaded_at = ? WHERE event_id = ?",
                ).use { statement ->
                    statement.setObject(1, now.minusDays(31))
                    statement.setString(2, futureDated.id)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertEquals(1, service.cleanupExpired())
        } finally {
            RLSRequestContext.clear()
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
        }
    }


    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var storageResolver: StorageResolver
        private lateinit var migrateResult: MigrateResult
        private lateinit var legacyAuditDescription: String
        private lateinit var legacyFinishedJobId: UUID
        private lateinit var legacyInterruptedJobId: UUID
        private lateinit var legacyInterruptedRunId: UUID
        private lateinit var legacyOrphanRunId: UUID

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_migration_test")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)

            val hikariProps = Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "5")
            }
            val pgConfig = PostgresConfiguration(
                hikariConfiguration = hikariProps,
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false, // schema comes from the shared harness below
            )
            val dsm = DataSourceManager(
                mapOf("default" to pgConfig, "chronicle" to pgConfig, "platform_read" to pgConfig),
                HealthCheckRegistry(),
                MetricRegistry(),
            )
            storageResolver = StorageResolver(dsm, ChronicleStorageConfiguration())

            postgres.createConnection("").use { conn ->
                // Framework schema only — no stub tables. The corpus (V10 etc.) creates its own
                // tables and V50 covers every registered participant asset on top; pre-created
                // stubs would shadow the real definitions under CREATE TABLE IF NOT EXISTS.
                ChronicleContractTestSchema.applyFrameworkSchema(conn)
                // Legacy state seeded BEFORE the corpus runs: V54 must drain audit_buffer
                // into audit. This is the one data-migration behavior still exercised.
                legacyAuditDescription = "legacy-audit-${UUID.randomUUID()}"
                conn.prepareStatement(
                    """
                    INSERT INTO audit_buffer (
                        acl_key, id, principal_type, principal_id, audit_event_type,
                        study_id, organization_id, description, data, event_timestamp
                    ) VALUES (?, ?, 'USER', 'migration-test', 'GET_STUDY', ?, ?, ?, '{}', now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString().replace("-", ""))
                    statement.setString(2, UUID.randomUUID().toString())
                    statement.setString(3, UUID.randomUUID().toString())
                    statement.setString(4, UUID.randomUUID().toString())
                    statement.setString(5, legacyAuditDescription)
                    statement.executeUpdate()
                }

                legacyFinishedJobId = UUID.randomUUID()
                legacyInterruptedJobId = UUID.randomUUID()
                legacyInterruptedRunId = UUID.randomUUID()
                legacyOrphanRunId = UUID.randomUUID()
                val securablePrincipalId = UUID.randomUUID()
                conn.prepareStatement(
                    """
                    INSERT INTO jobs (
                        job_id, securable_principal_id, principal_type, principal_id,
                        status, definition, message, deleted_rows
                    ) VALUES
                        (?, ?, 'USER', 'migration-test', 'FINISHED', '{}'::jsonb, '', 0),
                        (?, ?, 'USER', 'migration-test', 'RUNNING', '{}'::jsonb, '', 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, legacyFinishedJobId)
                    statement.setObject(2, securablePrincipalId)
                    statement.setObject(3, legacyInterruptedJobId)
                    statement.setObject(4, securablePrincipalId)
                    assertTrue(statement.executeUpdate() == 2)
                }
                conn.prepareStatement(
                    """
                    INSERT INTO pipeline_runs (
                        run_id, study_id, job_id, status, total_steps
                    ) VALUES
                        (?, ?, ?, 'PENDING', 2),
                        (?, ?, ?, 'PENDING', 2)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, legacyInterruptedRunId)
                    statement.setObject(2, UUID.randomUUID())
                    statement.setObject(3, legacyInterruptedJobId)
                    statement.setObject(4, legacyOrphanRunId)
                    statement.setObject(5, UUID.randomUUID())
                    statement.setObject(6, UUID.randomUUID())
                    assertTrue(statement.executeUpdate() == 2)
                }
            }

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
    private fun getConnection(): Connection = storageResolver.getPlatformStorage().connection

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
                stmt.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '80'
                      AND script = 'V80__hide_study_participants_during_deletion_quarantine.sql'
                      AND success
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("The study-participant quarantine migration must be applied exactly once", 1, rs.getInt(1))
                }
                stmt.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '83'
                      AND script = 'V83__own_secret_rotation_tracking.sql'
                      AND success
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Flyway must own secret-rotation tracking", 1, rs.getInt(1))
                }
                stmt.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '84'
                      AND script = 'V84__own_notification_and_local_blob_tables.sql'
                      AND success
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Flyway must own notification and local-blob tables", 1, rs.getInt(1))
                }
                stmt.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '85'
                      AND script = 'V85__link_study_deletion_schedule_to_erasure.sql'
                      AND success
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Flyway must own durable study-deletion schedule links", 1, rs.getInt(1))
                }
                stmt.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '96'
                      AND script = 'V96__close_legacy_audit_truncate_gap.sql'
                      AND success
                    """.trimIndent(),
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("Flyway must close the legacy audit TRUNCATE privilege gap", 1, rs.getInt(1))
                }
            }
        }
        assertTrue("Corpus must contain the re-issued orphans", migrateResult.migrationsExecuted >= 58)
    }

    @Test
    fun testFlywayCreatesServiceTablesWithResearcherRlsBoundaries() {
        assertTrue(tableExists("researcher_phone_numbers"))
        assertTrue(tableExists("researcher_notification_settings"))
        assertTrue(tableExists("local_blob_store"))

        assertEquals(
            setOf("principal_id", "phone_number", "verified", "created_at", "updated_at"),
            getColumnNames("researcher_phone_numbers"),
        )
        assertEquals(
            setOf("study_id", "principal_id", "settings", "created_at", "updated_at"),
            getColumnNames("researcher_notification_settings"),
        )
        assertEquals(setOf("key", "object"), getColumnNames("local_blob_store"))

        assertEquals(true to true, rlsState("researcher_phone_numbers"))
        assertEquals(true to true, rlsState("researcher_notification_settings"))
        assertEquals(1, policyCount("researcher_phone_numbers"))
        assertEquals(1, policyCount("researcher_notification_settings"))

        listOf(
            "researcher_phone_numbers",
            "researcher_notification_settings",
            "local_blob_store",
        ).forEach { tableName ->
            listOf("SELECT", "INSERT", "UPDATE", "DELETE").forEach { privilege ->
                assertTrue(
                    "chronicle_app must have $privilege on $tableName",
                    roleHasPrivilege("chronicle_app", tableName, privilege),
                )
            }
        }
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
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, visibleStudyId)
                statement.setString(5, visibleParticipantId)
                statement.setObject(6, UUID.randomUUID())
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, mode, status, requested_by,
                    idempotency_key, registry_version, quarantine_until
                ) VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'migration-test', ?, 1, now() + interval '7 days')
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
                        (study_id, participant_id, data, uploaded_at, upload_type, device_id)
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
                        idempotency_key, registry_version, quarantine_until
                    ) VALUES (
                        ?, ?, 'STUDY_ERASURE', 'QUARANTINED', 'migration-test',
                        ?, 1, now() + interval '7 days'
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
                        idempotency_key, registry_version, quarantine_until
                    ) VALUES (
                        ?, ?, 'completed-participant-ref', NULL, md5(?::text || ':' || ?),
                        'WITHDRAW_AND_ERASE', 'COMPLETED', 'migration-test',
                        ?, 1, now() - interval '1 day'
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
    fun testScopedUploadBufferDrainWaitsForConcurrentClaim() {
        val studyId = UUID.randomUUID()
        val participantId = "scoped-buffer-${UUID.randomUUID()}"
        postgres.createConnection("").use { setupConnection ->
            setupConnection.prepareStatement(
                """
                INSERT INTO upload_buffer
                    (study_id, participant_id, data, uploaded_at, upload_type, device_id)
                VALUES (?, ?, '[]'::jsonb, now(), 'Android', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.setObject(3, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
        }

        try {
            postgres.createConnection("").use { firstConnection ->
                postgres.createConnection("").use { secondConnection ->
                    firstConnection.autoCommit = false
                    secondConnection.autoCommit = false
                    val scopedSql = ChroniclePostgresTables.getScopedMoveSql(128, UploadType.Android)
                    firstConnection.prepareStatement(scopedSql).use { statement ->
                        statement.setObject(1, studyId)
                        statement.setString(2, participantId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertFalse(resultSet.next())
                        }
                    }

                    secondConnection.createStatement().use { statement ->
                        statement.execute("SET LOCAL lock_timeout = '100ms'")
                    }
                    val lockFailure = assertThrows(SQLException::class.java) {
                        secondConnection.prepareStatement(scopedSql).use { statement ->
                            statement.setObject(1, studyId)
                            statement.setString(2, participantId)
                            statement.executeQuery().use { resultSet ->
                                while (resultSet.next()) {
                                    // Consume the result if the lock contract regresses.
                                }
                            }
                        }
                    }
                    assertEquals(
                        "A scoped completion claim must wait, not skip another drainer's row",
                        "55P03",
                        lockFailure.sqlState,
                    )
                    secondConnection.rollback()
                    firstConnection.rollback()

                    secondConnection.prepareStatement(scopedSql).use { statement ->
                        statement.setObject(1, studyId)
                        statement.setString(2, participantId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue("The row must be claimable after its prior owner rolls back", resultSet.next())
                            assertFalse(resultSet.next())
                        }
                    }
                    secondConnection.rollback()
                }
            }
        } finally {
            postgres.createConnection("").use { cleanupConnection ->
                cleanupConnection.prepareStatement(
                    "DELETE FROM upload_buffer WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, participantId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testPipelineAndJobMigrationRepairsLegacyState() {
        val finishedState = requireNotNull(readJobState(legacyFinishedJobId))
        assertEquals("FINISHED", finishedState.status)
        assertTrue("legacy terminal jobs must receive a finite completion time", finishedState.completedAtIsFinite)
        assertNull(finishedState.leaseToken)

        val interruptedState = requireNotNull(readJobState(legacyInterruptedJobId))
        assertEquals("CANCELED", interruptedState.status)
        assertTrue(interruptedState.completedAtIsFinite)
        assertNull(interruptedState.leaseToken)
        assertTrue(interruptedState.message.orEmpty().contains("restarted"))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT run_id, status, isfinite(completed_at) AS completed_at_is_finite, error_message
                FROM pipeline_runs
                WHERE run_id IN (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, legacyInterruptedRunId)
                statement.setObject(2, legacyOrphanRunId)
                statement.executeQuery().use { resultSet ->
                    val repaired = buildMap {
                        while (resultSet.next()) {
                            put(
                                resultSet.getObject("run_id", UUID::class.java),
                                Triple(
                                    resultSet.getString("status"),
                                    resultSet.getBoolean("completed_at_is_finite"),
                                    resultSet.getString("error_message"),
                                ),
                            )
                        }
                    }
                    assertEquals(setOf(legacyInterruptedRunId, legacyOrphanRunId), repaired.keys)
                    repaired.values.forEach { (status, completedAtIsFinite, errorMessage) ->
                        assertEquals("FAILED", status)
                        assertTrue(completedAtIsFinite)
                        assertTrue(errorMessage.isNotBlank())
                    }
                }
            }
        }
    }

    @Test
    fun testJobWorkerPersistsLeasedTerminalStatesAndFailsCanceledPipeline() {
        val principal = Principal(PrincipalType.USER, "job-worker-test")
        val securablePrincipalId = UUID.randomUUID()
        val successJobId = UUID.randomUUID()
        val canceledPipelineJobId = UUID.randomUUID()
        val canceledPipelineRunId = UUID.randomUUID()
        val expiredPipelineJobId = UUID.randomUUID()
        val expiredPipelineRunId = UUID.randomUUID()
        val studyId = UUID.randomUUID()
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val jobService = JobService(
            Mockito.mock(HazelcastIdGenerationService::class.java),
            storageResolver,
            auditingManager,
        )
        jobService.registerJobHandlers(
            setOf(
                object : ChronicleJobRunner<EmptyJobDefinition> {
                    override fun run(connection: Connection, job: ChronicleJob): List<AuditableEvent> = emptyList()
                    override fun accepts(): Class<EmptyJobDefinition> = EmptyJobDefinition::class.java
                },
            ),
        )

        try {
            getConnection().use { connection ->
                jobService.createJob(
                    connection,
                    ChronicleJob(
                        id = successJobId,
                        securablePrincipalId = securablePrincipalId,
                        principal = principal,
                        definition = EmptyJobDefinition(),
                    ),
                )
            }
            jobService.tryAndAcquireTaskForExecutor()
            val successState = awaitJobStatus(successJobId, JobStatus.FINISHED.name)
            assertTrue(successState.completedAtIsFinite)
            assertNull(successState.leaseToken)

            getConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    connection.autoCommit = false
                    jobService.createJob(
                        connection,
                        ChronicleJob(
                            id = canceledPipelineJobId,
                            securablePrincipalId = securablePrincipalId,
                            principal = principal,
                            definition = PipelineJobDefinition(studyId, PipelineConfig(enabled = true)),
                        ),
                    )
                    connection.prepareStatement(
                        """
                        INSERT INTO pipeline_runs (
                            run_id, study_id, job_id, status, total_steps
                        ) VALUES (?, ?, ?, 'PENDING', 2)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, canceledPipelineRunId)
                        statement.setObject(2, studyId)
                        statement.setObject(3, canceledPipelineJobId)
                        assertEquals(1, statement.executeUpdate())
                    }
                    connection.commit()
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }

            jobService.tryAndAcquireTaskForExecutor()
            val canceledState = awaitJobStatus(canceledPipelineJobId, JobStatus.CANCELED.name)
            assertTrue(canceledState.completedAtIsFinite)
            assertNull(canceledState.leaseToken)
            assertTrue(canceledState.message.orEmpty().contains("No job handler"))

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, isfinite(completed_at), error_message
                    FROM pipeline_runs
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, canceledPipelineRunId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("FAILED", resultSet.getString(1))
                        assertTrue(resultSet.getBoolean(2))
                        assertTrue(resultSet.getString(3).contains("No job handler"))
                    }
                }
            }

            getConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    connection.autoCommit = false
                    connection.prepareStatement(
                        """
                        INSERT INTO jobs (
                            job_id, securable_principal_id, principal_type, principal_id,
                            status, definition, message, deleted_rows, updated_at,
                            completed_at, lease_token, lease_expires_at
                        ) VALUES (
                            ?, ?, 'USER', 'job-worker-test', 'RUNNING',
                            '{"@type":"PipelineJobDefinition","studyId":"$studyId","config":{"enabled":true}}'::jsonb,
                            '', 0, now() - interval '10 minutes', 'infinity', ?,
                            now() - interval '5 minutes'
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, expiredPipelineJobId)
                        statement.setObject(2, securablePrincipalId)
                        statement.setObject(3, UUID.randomUUID())
                        assertEquals(1, statement.executeUpdate())
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO pipeline_runs (
                            run_id, study_id, job_id, status, total_steps
                        ) VALUES (?, ?, ?, 'PENDING', 2)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, expiredPipelineRunId)
                        statement.setObject(2, studyId)
                        statement.setObject(3, expiredPipelineJobId)
                        assertEquals(1, statement.executeUpdate())
                    }
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }

            jobService.tryAndAcquireTaskForExecutor()
            val expiredState = awaitJobStatus(expiredPipelineJobId, JobStatus.STOPPING.name)
            assertFalse(expiredState.completedAtIsFinite)
            assertNull(expiredState.leaseToken)
            assertTrue(expiredState.message.orEmpty().contains("lease expired"))

            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM pipeline_runs WHERE run_id = ?",
                ).use { statement ->
                    statement.setObject(1, expiredPipelineRunId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("FAILED", resultSet.getString(1))
                    }
                }
            }

        } finally {
            jobService.shutdown()
            getConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    connection.autoCommit = false
                    connection.prepareStatement(
                        "DELETE FROM pipeline_runs WHERE run_id IN (?, ?)",
                    ).use { statement ->
                        statement.setObject(1, canceledPipelineRunId)
                        statement.setObject(2, expiredPipelineRunId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        "DELETE FROM jobs WHERE job_id IN (?, ?, ?)",
                    ).use { statement ->
                        statement.setObject(1, successJobId)
                        statement.setObject(2, canceledPipelineJobId)
                        statement.setObject(3, expiredPipelineJobId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = previousAutoCommit
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
    fun testPipelineAndJobStateMachineRejectsImpossibleTransitions() {
        val jobId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val nonPipelineJobId = UUID.randomUUID()
        val studyId = UUID.randomUUID()
        val securablePrincipalId = UUID.randomUUID()

        getConnection().use { connection ->
            val previousAutoCommit = connection.autoCommit
            try {
                connection.autoCommit = false
                connection.prepareStatement(
                    """
                    INSERT INTO jobs (
                        job_id, securable_principal_id, principal_type, principal_id,
                        status, definition, message, deleted_rows
                    ) VALUES (
                        ?, ?, 'USER', 'pipeline-state-test', 'PENDING',
                        '{"@type":"PipelineJobDefinition","studyId":"$studyId","config":{"enabled":true}}'::jsonb,
                        '', 0
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, securablePrincipalId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    """
                    INSERT INTO pipeline_runs (
                        run_id, study_id, job_id, status, total_steps
                    ) VALUES (?, ?, ?, 'PENDING', 2)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, runId)
                    statement.setObject(2, studyId)
                    statement.setObject(3, jobId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }

            val prematureFinish = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    "UPDATE jobs SET status = 'FINISHED', completed_at = now() WHERE job_id = ?",
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", prematureFinish.sqlState)

            val prematurePipelineCompletion = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    UPDATE pipeline_runs
                    SET status = 'COMPLETED', steps_completed = total_steps, completed_at = now()
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, runId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", prematurePipelineCompletion.sqlState)

            val duplicateMapping = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO pipeline_runs (
                        run_id, study_id, job_id, status, total_steps
                    ) VALUES (?, ?, ?, 'PENDING', 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.setObject(3, jobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23505", duplicateMapping.sqlState)

            val crossStudyMapping = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO pipeline_runs (
                        run_id, study_id, job_id, status, total_steps
                    ) VALUES (?, ?, ?, 'PENDING', 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setObject(3, jobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", crossStudyMapping.sqlState)

            connection.prepareStatement(
                """
                INSERT INTO jobs (
                    job_id, securable_principal_id, principal_type, principal_id,
                    status, definition, message, deleted_rows
                ) VALUES (
                    ?, ?, 'USER', 'pipeline-state-test', 'PENDING',
                    '{"@type":"EmptyJobDefinition","studyId":"00000000-0000-0000-0000-000000000000"}'::jsonb,
                    '', 0
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, nonPipelineJobId)
                statement.setObject(2, securablePrincipalId)
                assertEquals(1, statement.executeUpdate())
            }
            val nonPipelineMapping = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO pipeline_runs (
                        run_id, study_id, job_id, status, total_steps
                    ) VALUES (?, ?, ?, 'PENDING', 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.setObject(3, nonPipelineJobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23503", nonPipelineMapping.sqlState)

            val pipelineTypeChange = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    UPDATE jobs
                    SET definition =
                        '{"@type":"EmptyJobDefinition","studyId":"00000000-0000-0000-0000-000000000000"}'::jsonb
                    WHERE job_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23503", pipelineTypeChange.sqlState)

            val activeJobDelete = assertThrows(SQLException::class.java) {
                connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                    statement.setObject(1, jobId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23503", activeJobDelete.sqlState)

            connection.prepareStatement(
                """
                UPDATE jobs
                SET status = 'CANCELED', completed_at = now(), message = 'test cleanup'
                WHERE job_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, jobId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM pipeline_runs WHERE run_id = ?").use { statement ->
                statement.setObject(1, runId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                statement.setObject(1, jobId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                statement.setObject(1, nonPipelineJobId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    @Test
    fun testConcurrentPipelineMappingAndJobDeletionCannotCommitAnOrphan() {
        val studyId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO jobs (
                    job_id, securable_principal_id, principal_type, principal_id,
                    status, definition, message, deleted_rows, completed_at
                ) VALUES (
                    ?, ?, 'USER', 'pipeline-race-test', 'CANCELED',
                    ?::jsonb, 'terminal fixture', 0, now()
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, jobId)
                statement.setObject(2, UUID.randomUUID())
                statement.setString(
                    3,
                    """{"@type":"PipelineJobDefinition","studyId":"$studyId","config":{"enabled":true}}""",
                )
                assertEquals(1, statement.executeUpdate())
            }
        }

        try {
            val deleteResult = executor.submit<String> {
                getConnection().use { connection ->
                    connection.autoCommit = false
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                            statement.setObject(1, jobId)
                            statement.executeUpdate()
                        }
                        connection.commit()
                        "deleted"
                    } catch (exception: SQLException) {
                        connection.rollback()
                        "rejected:${exception.sqlState}"
                    }
                }
            }
            val insertResult = executor.submit<String> {
                getConnection().use { connection ->
                    connection.autoCommit = false
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        connection.prepareStatement(
                            """
                            INSERT INTO pipeline_runs (
                                run_id, study_id, job_id, status, total_steps,
                                completed_at, error_message
                            ) VALUES (?, ?, ?, 'FAILED', 1, now(), 'terminal fixture')
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setObject(1, runId)
                            statement.setObject(2, studyId)
                            statement.setObject(3, jobId)
                            statement.executeUpdate()
                        }
                        connection.commit()
                        "inserted"
                    } catch (exception: SQLException) {
                        connection.rollback()
                        "rejected:${exception.sqlState}"
                    }
                }
            }
            start.countDown()
            val outcomes = listOf(
                deleteResult.get(10, TimeUnit.SECONDS),
                insertResult.get(10, TimeUnit.SECONDS),
            )
            assertEquals(
                "the common advisory lock must allow exactly one side of the race to commit",
                1,
                outcomes.count { it == "deleted" || it == "inserted" },
            )
            assertTrue(
                "the losing transaction must fail closed",
                outcomes.any { it == "rejected:23503" || it == "rejected:23514" },
            )

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                        EXISTS (SELECT 1 FROM jobs WHERE job_id = ?) AS job_exists,
                        EXISTS (SELECT 1 FROM pipeline_runs WHERE run_id = ?) AS run_exists
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setObject(2, runId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(
                            "a retained run must always have its backing job",
                            resultSet.getBoolean("job_exists"),
                            resultSet.getBoolean("run_exists"),
                        )
                    }
                }
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            getConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    connection.autoCommit = false
                    connection.prepareStatement("DELETE FROM pipeline_runs WHERE run_id = ?").use { statement ->
                        statement.setObject(1, runId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                        statement.setObject(1, jobId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (exception: Exception) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = previousAutoCommit
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
                    throw IllegalStateException("injected lifecycle failure")
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
                    throw IllegalStateException("injected lifecycle failure")
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
    fun testLifecycleServiceSchedulesAndCancelsOneAtomicArchivedStudyErasure() {
        val studyId = UUID.randomUUID()
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val orchestrator = DataDeletionOrchestrator(storageResolver, auditingManager)
        val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(idGenerationService.getNextId()).thenAnswer { UUID.randomUUID() }
        val lifecycleService = StudyLifecycleService(
            storageResolver = storageResolver,
            studyService = Mockito.mock(StudyService::class.java),
            authorizationService = Mockito.mock(AuthorizationManager::class.java),
            idGenerationService = idGenerationService,
            auditingManager = auditingManager,
            dataDeletionOrchestrator = orchestrator,
            webhookService = Mockito.mock(WebhookService::class.java),
        )
        val deleteAfter = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14).withNano(0)

        TestSecurityUtils.setupSecurityContext("lifecycle-migration-test")
        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO studies (study_id, title, lifecycle_status) VALUES (?, ?, 'ARCHIVED')",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "atomic-lifecycle-$studyId")
                    assertEquals(1, statement.executeUpdate())
                }
            }

            val operationId = lifecycleService.scheduleStudyDeletion(
                studyId,
                "lifecycle-migration-test",
                deleteAfter,
            )
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT study.lifecycle_status, schedule.delete_after, operation.status,
                           schedule.operation_id, schedule.previous_status
                    FROM studies study
                    JOIN study_deletion_schedule schedule ON schedule.study_id = study.study_id
                    JOIN data_deletion_operations operation ON operation.study_id = study.study_id
                    WHERE study.study_id = ? AND operation.operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setObject(2, operationId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name, resultSet.getString(1))
                        assertTrue(deleteAfter.isEqual(resultSet.getObject(2, OffsetDateTime::class.java)))
                        assertEquals("QUARANTINED", resultSet.getString(3))
                        assertEquals(operationId, resultSet.getObject("operation_id", UUID::class.java))
                        assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString("previous_status"))
                    }
                }
            }

            assertEquals(
                "an exact schedule retry must resolve the original operation",
                operationId,
                lifecycleService.scheduleStudyDeletion(
                    studyId,
                    "lifecycle-migration-test",
                    deleteAfter,
                ),
            )
            assertThrows(IllegalStateException::class.java) {
                lifecycleService.scheduleStudyDeletion(
                    studyId,
                    "lifecycle-migration-test",
                    deleteAfter.plusDays(1),
                )
            }
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM data_deletion_operations WHERE study_id = ?",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("a changed date must roll back its speculative operation", 1, resultSet.getInt(1))
                    }
                }
            }
            lifecycleService.cancelScheduledDeletion(studyId, "lifecycle-migration-test")
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT study.lifecycle_status, operation.status,
                           (SELECT COUNT(*) FROM study_deletion_schedule WHERE study_id = ?) AS schedules
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
                        assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString(1))
                        assertEquals("CANCELLED", resultSet.getString(2))
                        assertEquals(0, resultSet.getInt("schedules"))
                    }
                }
            }

            val rescheduledOperationId = lifecycleService.scheduleStudyDeletion(
                studyId,
                "lifecycle-migration-test",
                deleteAfter,
            )
            assertTrue(
                "a new request after explicit cancellation must use a new operation",
                rescheduledOperationId != operationId,
            )
            assertEquals(
                "an uncertain retry after rescheduling must resolve the new operation",
                rescheduledOperationId,
                lifecycleService.scheduleStudyDeletion(
                    studyId,
                    "lifecycle-migration-test",
                    deleteAfter,
                ),
            )
            getConnection().use { connection ->
                connection.prepareStatement(
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
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setObject(2, studyId)
                    statement.setObject(3, studyId)
                    statement.setObject(4, studyId)
                    statement.setObject(5, studyId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(
                            StudyLifecycleStatus.SCHEDULED_FOR_DELETION.name,
                            resultSet.getString("lifecycle_status"),
                        )
                        assertEquals(rescheduledOperationId, resultSet.getObject("operation_id", UUID::class.java))
                        assertEquals(StudyLifecycleStatus.ARCHIVED.name, resultSet.getString("previous_status"))
                        assertEquals(
                            "the replacement request must own exactly one schedule",
                            1,
                            resultSet.getInt("schedules"),
                        )
                        assertEquals(
                            "cancellation history and the replacement request must both remain durable",
                            2,
                            resultSet.getInt("operations"),
                        )
                        assertEquals(1, resultSet.getInt("cancelled_operations"))
                        assertEquals(1, resultSet.getInt("quarantined_operations"))
                    }
                }
            }
            lifecycleService.cancelScheduledDeletion(studyId, "lifecycle-migration-test")
        } finally {
            TestSecurityUtils.clearSecurityContext()
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
                statement.setObject(3, UUID.randomUUID())
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
        val holdReason = "Preserve the study while the retention review is completed"
        val releaseReason = "The documented retention review has now been completed"
        val reviewAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0)

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
                    idempotency_key, registry_version, quarantine_until
                ) VALUES (?, ?, 'STUDY_ERASURE', 'QUARANTINED', ?, ?, 1, now() + interval '7 days')
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
                throw IllegalStateException("injected audit outage")
            }
        }
        val unavailableAuditOrchestrator = DataDeletionOrchestrator(
            storageResolver,
            unavailableAuditingManager,
        )

        val holdId = unavailableAuditOrchestrator.placeHold(
            operationId,
            studyId,
            holdReason,
            actor,
            reviewAt,
        )
        assertEquals(
            "an exact placement retry must resolve the original hold",
            holdId,
            unavailableAuditOrchestrator.placeHold(
                operationId,
                studyId,
                holdReason,
                actor,
                reviewAt,
            ),
        )

        unavailableAuditOrchestrator.releaseHold(
            operationId,
            holdId,
            studyId,
            actor,
            releaseReason,
        )
        // A client retry after an uncertain response is a successful no-op, not
        // "hold not found" and not a second audit event.
        unavailableAuditOrchestrator.releaseHold(
            operationId,
            holdId,
            studyId,
            actor,
            releaseReason,
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
                        setOf(
                            AuditEventType.CANCEL_DATA_DELETION.name,
                            AuditEventType.PLACE_RETENTION_HOLD.name,
                            AuditEventType.RELEASE_RETENTION_HOLD.name,
                        ),
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
                assertEquals(3, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                UPDATE data_deletion_audit_outbox
                SET lease_token = ?, lease_expires_at = now() - interval '1 minute'
                WHERE operation_id = ? AND event_type = 'PLACE_RETENTION_HOLD'
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
        assertEquals(3, recoveryOrchestrator.publishPendingDeletionAuditEvents())
        assertEquals(0, recoveryOrchestrator.publishPendingDeletionAuditEvents())

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT audit_event_type, principal_id, data::jsonb ->> 'actor' AS actor
                FROM audit
                WHERE study_id = ?
                  AND audit_event_type IN (
                      'CANCEL_DATA_DELETION',
                      'PLACE_RETENTION_HOLD',
                      'RELEASE_RETENTION_HOLD'
                  )
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
                        setOf(
                            AuditEventType.CANCEL_DATA_DELETION.name,
                            AuditEventType.PLACE_RETENTION_HOLD.name,
                            AuditEventType.RELEASE_RETENTION_HOLD.name,
                        ),
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
                    assertEquals(3, resultSet.getInt(1))
                }
            }

            connection.prepareStatement(
                "DELETE FROM data_deletion_audit_outbox WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(3, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM retention_holds WHERE operation_id = ?").use { statement ->
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
    fun testStartedStudyErasureCannotBeCancelledOrHeldAsIfReversible() {
        val studyId = UUID.randomUUID()
        val participantId = "started-erasure-${UUID.randomUUID()}"
        val accessCodeId = UUID.randomUUID()

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "started-erasure-$studyId")
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
                statement.setObject(3, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO participant_form_access_codes (
                    access_code_id, token_hash, study_id, participant_id, form_kind,
                    issuer_type, issued_by, expires_at
                ) VALUES (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'migration-test', now() + interval '1 day')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, accessCodeId)
                statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
                statement.setObject(3, studyId)
                statement.setString(4, participantId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        val operationId = orchestrator.quarantineStudy(
            studyId,
            "migration-test",
            UUID.randomUUID(),
        )

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'FAILED', started_at = now(), operation_attempt_count = 1,
                    failure_code = 'InjectedPartialFailure', next_attempt_at = now()
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertEquals(0, orchestrator.cancelStudyErasure(studyId, "migration-test"))
        var lifecycleCallbackRan = false
        assertThrows(IllegalStateException::class.java) {
            orchestrator.cancelStudyErasureAtomically(studyId, "migration-test") { _, _ ->
                lifecycleCallbackRan = true
            }
        }
        assertFalse("started erasure must block lifecycle restoration", lifecycleCallbackRan)
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT revoked_at FROM participant_form_access_codes WHERE access_code_id = ?",
            ).use { statement ->
                statement.setObject(1, accessCodeId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNotNull("started erasure must keep form access revoked", resultSet.getObject(1))
                }
            }
        }

        assertEquals("FAILED", orchestrator.getOperation(operationId).status)

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'ERASING', updated_at = now(),
                    worker_lease_token = ?, worker_lease_expires_at = now() + interval '30 minutes'
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertThrows(IllegalStateException::class.java) {
            orchestrator.placeHold(
                operationId,
                studyId,
                "An active worker cannot be relabeled as held",
                "migration-test",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
            )
        }

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'FAILED', worker_lease_token = NULL, worker_lease_expires_at = NULL
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement("DELETE FROM data_deletion_operations WHERE operation_id = ?").use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
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
        assertTrue(functionExists("chronicle_has_study_access"))
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
    // V47 — participation-status check constraint (behavioral)
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
            conn.prepareStatement(
                """
                INSERT INTO ${STUDY_PARTICIPANTS.name}
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, "valid-status-${UUID.randomUUID()}")
                ps.setObject(3, UUID.randomUUID())
                ps.executeUpdate()
            }
            assertThrows(SQLException::class.java) {
                conn.prepareStatement(
                    """
                    INSERT INTO ${STUDY_PARTICIPANTS.name}
                        (study_id, participant_id, candidate_id, participation_status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, "invalid-status-${UUID.randomUUID()}")
                    ps.setObject(3, UUID.randomUUID())
                    ps.executeUpdate()
                }
            }
        }
    }

    // =========================================================================
    // V48 — iOS Screen Time metadata columns on the real event table
    // =========================================================================
    @Test
    fun testIosScreenTimeMetadataColumns() {
        val sensorDataColumns = getColumnNames(PostgresEventTables.IOS_SENSOR_DATA.name)
        for (column in listOf(
            "ios_screen_time_source",
            "ios_screen_time_confidence",
            "ios_screen_time_row_kind",
            "ios_screen_time_app_label",
            "ios_screen_time_bundle_id",
            "ios_screen_time_web_domain",
            "ios_screen_time_raw_source_label",
        )) {
            assertTrue(
                "${PostgresEventTables.IOS_SENSOR_DATA.name} should have $column",
                sensorDataColumns.contains(column),
            )
        }
    }

    // =========================================================================
    // V81 — append-only Screen Time snapshots and UTC-safe delta projection
    // =========================================================================
    @Test
    fun testIosScreenTimeDeltaProjectionAcrossDstAndMidnight() {
        val studyId = UUID.randomUUID().toString()
        val participantId = "screen-time-delta-${UUID.randomUUID()}"
        val fallBackFirstHour = OffsetDateTime.parse("2026-11-01T06:00:00Z")
        val fallBackSecondHour = OffsetDateTime.parse("2026-11-01T07:00:00Z")
        val beforeMidnight = OffsetDateTime.parse("2026-07-20T04:00:00Z")
        val afterMidnight = OffsetDateTime.parse("2026-07-20T05:00:00Z")

        getConnection().use { connection ->
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, fallBackFirstHour,
                fallBackFirstHour.plusHours(1), fallBackFirstHour.plusMinutes(15), 300.0, 1, 1,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, fallBackFirstHour,
                fallBackFirstHour.plusHours(1), fallBackFirstHour.plusMinutes(30), 480.0, 2, 1,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, fallBackFirstHour,
                fallBackFirstHour.plusHours(1), fallBackFirstHour.plusMinutes(65), 600.0, 3, 2,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, fallBackSecondHour,
                fallBackSecondHour.plusHours(1), fallBackSecondHour.plusMinutes(15), 120.0, 0, 0,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, beforeMidnight,
                beforeMidnight.plusHours(1), beforeMidnight.plusMinutes(65), 180.0, 1, 1,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, afterMidnight,
                afterMidnight.plusHours(1), afterMidnight.plusMinutes(15), 60.0, 1, 1,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, afterMidnight,
                afterMidnight.plusHours(1), afterMidnight.plusMinutes(30), 40.0, 1, 1,
            )
            insertDirectScreenTimeSnapshot(
                connection, studyId, participantId, afterMidnight,
                afterMidnight.plusHours(5), afterMidnight.plusMinutes(45), 999.0, 99, 99,
            )

            data class DeltaRow(
                val bucketStart: OffsetDateTime,
                val intervalStart: OffsetDateTime,
                val intervalEnd: OffsetDateTime,
                val usageDelta: Double?,
                val status: String,
            )

            val projected = mutableListOf<DeltaRow>()
            connection.prepareStatement(
                """
                SELECT bucket_start_utc, interval_start_utc, interval_end_utc,
                       usage_delta_seconds, delta_status
                FROM screen_time_usage_deltas
                WHERE study_id = ? AND participant_id = ?
                ORDER BY bucket_start_utc, interval_end_utc
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        projected += DeltaRow(
                            bucketStart = rows.getObject("bucket_start_utc", OffsetDateTime::class.java),
                            intervalStart = rows.getObject("interval_start_utc", OffsetDateTime::class.java),
                            intervalEnd = rows.getObject("interval_end_utc", OffsetDateTime::class.java),
                            usageDelta = (rows.getObject("usage_delta_seconds") as? Number)?.toDouble(),
                            status = rows.getString("delta_status"),
                        )
                    }
                }
            }

            assertEquals(7, projected.size)
            assertEquals(
                listOf(300.0, 180.0, 120.0),
                projected.filter { it.bucketStart.toInstant() == fallBackFirstHour.toInstant() }.map { it.usageDelta },
            )
            assertEquals(
                listOf(120.0),
                projected.filter { it.bucketStart.toInstant() == fallBackSecondHour.toInstant() }.map { it.usageDelta },
            )
            assertEquals(
                "the repeated local 01:00 hour must start a new absolute bucket",
                fallBackSecondHour.toInstant(),
                projected.single { it.bucketStart.toInstant() == fallBackSecondHour.toInstant() }
                    .intervalStart.toInstant(),
            )
            assertEquals(
                listOf(180.0),
                projected.filter { it.bucketStart.toInstant() == beforeMidnight.toInstant() }.map { it.usageDelta },
            )
            val afterMidnightRows = projected.filter { it.bucketStart.toInstant() == afterMidnight.toInstant() }
            assertEquals(60.0, requireNotNull(afterMidnightRows.first().usageDelta), 0.001)
            assertEquals(afterMidnight.toInstant(), afterMidnightRows.first().intervalStart.toInstant())
            assertEquals(null, afterMidnightRows.last().usageDelta)
            assertEquals("counter_decreased", afterMidnightRows.last().status)

            // Fresh-install role grants normally run after Flyway. Mirror the underlying-table
            // SELECT grant here, then prove that the view executes as the invoker: no context
            // sees no rows, and an explicit study context sees only that study.
            connection.createStatement().use { statement ->
                statement.execute("GRANT SELECT ON sensor_data TO chronicle_app")
                statement.execute(
                    "GRANT EXECUTE ON FUNCTION chronicle_participant_data_visible(TEXT, TEXT) TO chronicle_app"
                )
                statement.execute("SET ROLE chronicle_app")
            }
            try {
                connection.prepareStatement(
                    "SELECT count(*) FROM screen_time_usage_deltas WHERE participant_id = ?"
                ).use { statement ->
                    statement.setString(1, participantId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
                connection.prepareStatement(
                    "SELECT set_config('app.authorized_studies', ?, false)"
                ).use { statement ->
                    statement.setString(1, studyId)
                    statement.execute()
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM screen_time_usage_deltas WHERE participant_id = ?"
                ).use { statement ->
                    statement.setString(1, participantId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(7, rows.getInt(1))
                    }
                }
            } finally {
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        }

        assertTrue(
            "chronicle_app must be able to select the RLS-invoker view",
            roleHasPrivilege("chronicle_app", "screen_time_usage_deltas", "SELECT"),
        )
    }

    @Suppress("LongParameterList")
    private fun insertDirectScreenTimeSnapshot(
        connection: Connection,
        studyId: String,
        participantId: String,
        bucketStart: OffsetDateTime,
        bucketEnd: OffsetDateTime,
        capturedAt: OffsetDateTime,
        usageSeconds: Double,
        notificationCount: Int,
        pickupCount: Int,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO sensor_data (
                study_id, participant_id, sample_id, sensor_type, sample_duration,
                recordeddate, datetimestart, datetimeend, exact_recordeddate, timezone,
                device_version, device_name, device_model, device_system_name,
                total_screen_wakes, total_unlock_duration, total_unlocks,
                app_category, app_usage_time, bundle_identifier,
                ios_screen_time_source, ios_screen_time_confidence, ios_screen_time_row_kind,
                ios_screen_time_app_label, ios_screen_time_bundle_id,
                ios_screen_time_notification_count, ios_screen_time_pickup_count
            ) VALUES (
                ?, ?, ?, 'deviceUsage', 3600.0,
                ?, ?, ?, ?, 'America/Chicago',
                '26.5', 'U15', 'iPhone16,1', 'iOS',
                ?, 0.0, ?,
                'Fixture App', ?, 'com.example.fixture',
                'deviceActivityExport', 'appleDeviceActivity', 'application',
                'Fixture App', 'com.example.fixture', ?, ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, studyId)
            statement.setString(2, participantId)
            statement.setString(3, UUID.randomUUID().toString())
            statement.setObject(4, capturedAt)
            statement.setObject(5, bucketStart)
            statement.setObject(6, bucketEnd)
            statement.setObject(7, capturedAt)
            statement.setInt(8, pickupCount)
            statement.setInt(9, pickupCount)
            statement.setDouble(10, usageSeconds)
            statement.setInt(11, notificationCount)
            statement.setInt(12, pickupCount)
            statement.executeUpdate()
        }
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
    // V53 — interaction position provenance columns
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
    // V52 — upload-buffer drain index
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

    // =========================================================================
    // V55–V58 — re-issued orphan objects (webhooks, anonymization, orgs, refresh tokens)
    // =========================================================================
    @Test
    fun testReissuedOrphanObjects() {
        val rlsTables = listOf(
            "webhook_registrations", "webhook_deliveries",
            "study_anonymization_config", "participant_pseudonyms",
            "organization_members", "organization_quotas",
            "refresh_tokens",
        )
        for (table in rlsTables) {
            assertTrue("$table must exist", tableExists(table))
            val (enabled, forced) = rlsState(table)
            assertTrue("RLS should be enabled on $table", enabled)
            assertTrue("RLS should be forced on $table", forced)
        }
        // Policy-bearing tables.
        for (table in rlsTables - "refresh_tokens") {
            assertTrue("RLS policy should exist on $table", policyCount(table) > 0)
        }
        // V60: exactly one policy, scoped to chronicle_app (the W1 request-path role).
        // Every other non-BYPASSRLS role stays deny-all under FORCE RLS.
        assertEquals(
            "refresh_tokens must have exactly the V60 service-path policy",
            1,
            policyCount("refresh_tokens"),
        )
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT roles::text FROM pg_policies WHERE tablename = 'refresh_tokens'"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(
                        "the refresh_tokens policy must be scoped to chronicle_app only",
                        "{chronicle_app}",
                        rs.getString(1),
                    )
                }
            }
        }

        for (column in listOf(
            "delivery_state",
            "available_at",
            "lease_expires_at",
            "lease_token",
            "outcome_code",
            "completed_at",
            "updated_at",
        )) {
            assertTrue("webhook_deliveries must have $column", columnExists("webhook_deliveries", column))
        }
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE tablename = 'webhook_deliveries'
                  AND indexname IN (
                      'idx_webhook_deliveries_pending',
                      'idx_webhook_deliveries_expired_lease'
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("durable webhook queue indexes must exist", 2, resultSet.getInt(1))
                }
            }
        }
    }

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
                    mapOf("rolledBack" to true),
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
                    mapOf("records" to 2),
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

        // A pre-V66 binary records history only after sending and omits every
        // outbox column. Those writes must remain terminal after the migration,
        // otherwise a rollback/overlap replays an already-sent request.
        val legacySuccessId = UUID.randomUUID()
        val legacyFailureId = UUID.randomUUID()
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO webhook_deliveries (
                    delivery_id, webhook_id, event_type, payload,
                    status, attempt_count, last_attempt_at
                ) VALUES (?, ?, 'DATA_SUBMITTED', '{}'::jsonb, ?, 1, now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, legacySuccessId)
                statement.setObject(2, webhookId)
                statement.setInt(3, 204)
                assertEquals(1, statement.executeUpdate())
                statement.setObject(1, legacyFailureId)
                statement.setInt(3, 503)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                SELECT delivery_id, delivery_state, completed_at
                FROM webhook_deliveries
                WHERE delivery_id IN (?, ?)
                ORDER BY delivery_id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, legacySuccessId)
                statement.setObject(2, legacyFailureId)
                statement.executeQuery().use { resultSet ->
                    val states = mutableMapOf<UUID, String>()
                    while (resultSet.next()) {
                        states[resultSet.getObject("delivery_id", UUID::class.java)] =
                            resultSet.getString("delivery_state")
                        assertTrue(resultSet.getObject("completed_at") != null)
                    }
                    assertEquals("SUCCEEDED", states[legacySuccessId])
                    assertEquals("FAILED", states[legacyFailureId])
                }
            }
        }

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

    @Test
    fun testExportQueueLeaseRecoveryAndActiveErasureRevocation() {
        val studyId = UUID.randomUUID()
        val dueExportId = UUID.randomUUID()
        val activeExportId = UUID.randomUUID()
        val revokedExportId = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val participantId = "export-revocation-${UUID.randomUUID()}"
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
        )
        val requestJson = ObjectMappers.newJsonMapper().writeValueAsString(request)

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "export-lease-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    available_at, lease_token, lease_expires_at
                ) VALUES
                    (?, ?, 'PENDING', 'CSV', ?::jsonb, 'migration-test', '-infinity', NULL, NULL),
                    (?, ?, 'RUNNING', 'CSV', ?::jsonb, 'migration-test', now(), ?, now() + interval '1 hour')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, dueExportId)
                statement.setObject(2, studyId)
                statement.setString(3, requestJson)
                statement.setObject(4, activeExportId)
                statement.setObject(5, studyId)
                statement.setString(6, requestJson)
                statement.setObject(7, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_ref, participant_id,
                    mode, status, requested_by, idempotency_key,
                    registry_version, quarantine_until
                ) VALUES (
                    ?, ?, 'participant-ref', ?,
                    'WITHDRAW_AND_ERASE', 'QUARANTINED', 'migration-test', ?,
                    1, now() + interval '7 days'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setObject(2, studyId)
                statement.setString(3, participantId)
                statement.setObject(4, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by
                ) VALUES (?, ?, 'PENDING', 'CSV', '{"participantIds":"malformed"}'::jsonb, 'migration-test')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, revokedExportId)
                statement.setObject(2, studyId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM export_job_revocations
                WHERE export_id = ? AND operation_id = ? AND study_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, revokedExportId)
                statement.setObject(2, operationId)
                statement.setObject(3, studyId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("late malformed export must be revoked without a JSONB error", 1, resultSet.getInt(1))
                }
            }
        }

        val exportService = ExportService(
            storageResolver,
            Mockito.mock(DataDownloadManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(WebhookService::class.java),
        )
        try {
            val firstClaim = requireNotNull(exportService.claimNextExport())
            assertEquals(dueExportId, firstClaim.exportId)
            assertEquals(0, firstClaim.attemptCount)
            assertEquals(0, firstClaim.recoveryCount)

            repeat(3) { recoveryIndex ->
                getConnection().use { connection ->
                    connection.prepareStatement(
                        "UPDATE export_jobs SET lease_expires_at = now() - interval '1 second' WHERE export_id = ?",
                    ).use { statement ->
                        statement.setObject(1, dueExportId)
                        assertEquals(1, statement.executeUpdate())
                    }
                }

                val replayClaim = requireNotNull(exportService.claimNextExport())
                assertEquals(dueExportId, replayClaim.exportId)
                assertEquals("lease expiry must not consume an execution attempt", 0, replayClaim.attemptCount)
                assertEquals(recoveryIndex + 1, replayClaim.recoveryCount)
            }

            getConnection().use { connection ->
                connection.prepareStatement(
                    "UPDATE export_jobs SET lease_expires_at = now() - interval '1 second' WHERE export_id = ?",
                ).use { statement ->
                    statement.setObject(1, dueExportId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertTrue(exportService.terminalizeOneExhaustedRecovery())

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT export_id, status, attempt_count, recovery_count,
                           isfinite(completed_at) AS completed_at_is_finite,
                           lease_token
                    FROM export_jobs
                    WHERE export_id IN (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, dueExportId)
                    statement.setObject(2, activeExportId)
                    statement.setObject(3, revokedExportId)
                    statement.executeQuery().use { resultSet ->
                        val states = buildMap {
                            while (resultSet.next()) {
                                put(
                                    resultSet.getObject("export_id", UUID::class.java),
                                    listOf(
                                        resultSet.getString("status"),
                                        resultSet.getInt("attempt_count"),
                                        resultSet.getInt("recovery_count"),
                                        resultSet.getBoolean("completed_at_is_finite"),
                                        resultSet.getObject("lease_token"),
                                    ),
                                )
                            }
                        }
                        assertEquals(setOf(dueExportId, activeExportId, revokedExportId), states.keys)
                        assertEquals(listOf("FAILED", 0, 3, true, null), states[dueExportId])
                        assertEquals("RUNNING", states.getValue(activeExportId)[0])
                        assertEquals(0, states.getValue(activeExportId)[1])
                        assertEquals(0, states.getValue(activeExportId)[2])
                        assertEquals("PENDING", states.getValue(revokedExportId)[0])
                        assertEquals(0, states.getValue(revokedExportId)[1])
                        assertEquals(0, states.getValue(revokedExportId)[2])
                    }
                }
            }
        } finally {
            exportService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement(
                    "DELETE FROM export_jobs WHERE export_id IN (?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, dueExportId)
                    statement.setObject(2, activeExportId)
                    statement.setObject(3, revokedExportId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM data_deletion_operations WHERE operation_id = ?",
                ).use { statement ->
                    statement.setObject(1, operationId)
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
    fun testPoisonedRecoveryCleanupBacksOffWithoutStarvingPendingWork() {
        val studyId = UUID.randomUUID()
        val poisonedExportId = UUID.randomUUID()
        val pendingExportId = UUID.randomUUID()
        val poisonedRequest = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
        )
        val mismatchedPendingRequest = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.JSON,
        )
        val mapper = ObjectMappers.newJsonMapper()
        val downloadManager = Mockito.mock(DataDownloadManager::class.java)
        val webhookService = Mockito.mock(WebhookService::class.java)

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "poisoned-export-recovery-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    file_path, lease_token, lease_expires_at, recovery_count, created_at
                ) VALUES (
                    ?, ?, 'RUNNING', 'CSV', ?::jsonb, 'poisoned-worker',
                    ?, ?, now() - interval '1 minute', 3, now() - interval '2 days'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, poisonedExportId)
                statement.setObject(2, studyId)
                statement.setString(3, mapper.writeValueAsString(poisonedRequest))
                statement.setString(4, "/outside-managed-export-storage/$poisonedExportId.csv")
                statement.setObject(5, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by, available_at
                ) VALUES (?, ?, 'PENDING', 'CSV', ?::jsonb, 'pending-worker', '-infinity')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, pendingExportId)
                statement.setObject(2, studyId)
                statement.setString(3, mapper.writeValueAsString(mismatchedPendingRequest))
                assertEquals(1, statement.executeUpdate())
            }
        }

        val exportService = ExportService(
            storageResolver,
            downloadManager,
            Mockito.mock(HazelcastIdGenerationService::class.java),
            webhookService,
        )
        try {
            exportService.runNextExportTask()

            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT export_id, status, attempt_count, recovery_count,
                           recovery_cleanup_available_at > now() AS cleanup_deferred,
                           error_message, lease_token
                    FROM export_jobs
                    WHERE export_id IN (?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, poisonedExportId)
                    statement.setObject(2, pendingExportId)
                    statement.executeQuery().use { resultSet ->
                        val states = buildMap {
                            while (resultSet.next()) {
                                put(
                                    resultSet.getObject("export_id", UUID::class.java),
                                    listOf(
                                        resultSet.getString("status"),
                                        resultSet.getInt("attempt_count"),
                                        resultSet.getInt("recovery_count"),
                                        resultSet.getBoolean("cleanup_deferred"),
                                        resultSet.getString("error_message"),
                                        resultSet.getObject("lease_token"),
                                    ),
                                )
                            }
                        }
                        assertEquals(setOf(poisonedExportId, pendingExportId), states.keys)
                        val poisoned = states.getValue(poisonedExportId)
                        assertEquals("RUNNING", poisoned[0])
                        assertEquals(0, poisoned[1])
                        assertEquals(3, poisoned[2])
                        assertEquals(true, poisoned[3])
                        assertTrue(poisoned[4].toString().contains("cleanup deferred"))
                        assertNotNull(poisoned[5])

                        val pending = states.getValue(pendingExportId)
                        assertEquals("FAILED", pending[0])
                        assertEquals(1, pending[1])
                        assertEquals(0, pending[2])
                        assertNull(pending[5])
                    }
                }
            }
            Mockito.verifyNoInteractions(downloadManager)
            Mockito.verifyNoInteractions(webhookService)
        } finally {
            exportService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement(
                    "DELETE FROM export_jobs WHERE export_id IN (?, ?)",
                ).use { statement ->
                    statement.setObject(1, poisonedExportId)
                    statement.setObject(2, pendingExportId)
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
    fun testExportQueueRejectsFormatCorruptionAndTerminalizesRequestMismatch() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val downloadManager = Mockito.mock(DataDownloadManager::class.java)
        val webhookService = Mockito.mock(WebhookService::class.java)
        val mismatchedRequest = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.JSON,
        )

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "export-format-mismatch-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            val invalidFormat = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO export_jobs (
                        export_id, study_id, status, format, request, created_by
                    ) VALUES (?, ?, 'PENDING', 'YAML', '{}'::jsonb, 'migration-test')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", invalidFormat.sqlState)
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by, available_at
                ) VALUES (?, ?, 'PENDING', 'CSV', ?::jsonb, 'migration-test', '-infinity')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, exportId)
                statement.setObject(2, studyId)
                statement.setString(3, ObjectMappers.newJsonMapper().writeValueAsString(mismatchedRequest))
                assertEquals(1, statement.executeUpdate())
            }
        }

        val exportService = ExportService(
            storageResolver,
            downloadManager,
            Mockito.mock(HazelcastIdGenerationService::class.java),
            webhookService,
        )
        try {
            exportService.runNextExportTask()
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, attempt_count, recovery_count,
                           isfinite(completed_at), lease_token, file_path
                    FROM export_jobs
                    WHERE export_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, exportId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("FAILED", resultSet.getString("status"))
                        assertEquals(1, resultSet.getInt("attempt_count"))
                        assertEquals(0, resultSet.getInt("recovery_count"))
                        assertTrue(resultSet.getBoolean(4))
                        assertNull(resultSet.getObject("lease_token"))
                        assertNull(resultSet.getString("file_path"))
                    }
                }
            }
            Mockito.verifyNoInteractions(downloadManager)
            Mockito.verifyNoInteractions(webhookService)
            Files.list(ExportFileWriter.EXPORT_DIR).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().startsWith(exportId.toString()) })
            }
        } finally {
            exportService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM export_jobs WHERE export_id = ?").use { statement ->
                    statement.setObject(1, exportId)
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
    fun testExportCapacityReservationsFenceIndependentWorkersAndLeaseOwnership() {
        val firstStudyId = UUID.randomUUID()
        val secondStudyId = UUID.randomUUID()
        val firstExportId = UUID.randomUUID()
        val secondExportId = UUID.randomUUID()
        val firstLease = UUID.randomUUID()
        val secondLease = UUID.randomUUID()
        val request = ExportRequest(
            dataTypes = setOf(ParticipantDataType.UsageEvents),
            format = ExportFormat.CSV,
        )
        val requestJson = ObjectMappers.newJsonMapper().writeValueAsString(request)

        getConnection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title) VALUES (?, ?), (?, ?)",
            ).use { statement ->
                statement.setObject(1, firstStudyId)
                statement.setString(2, "capacity-first-$firstStudyId")
                statement.setObject(3, secondStudyId)
                statement.setString(4, "capacity-second-$secondStudyId")
                assertEquals(2, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    lease_token, lease_expires_at
                ) VALUES
                    (?, ?, 'RUNNING', 'CSV', ?::jsonb, 'capacity-worker', ?, now() + interval '5 minutes'),
                    (?, ?, 'RUNNING', 'CSV', ?::jsonb, 'capacity-worker', ?, now() + interval '5 minutes')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, firstExportId)
                statement.setObject(2, firstStudyId)
                statement.setString(3, requestJson)
                statement.setObject(4, firstLease)
                statement.setObject(5, secondExportId)
                statement.setObject(6, secondStudyId)
                statement.setString(7, requestJson)
                statement.setObject(8, secondLease)
                assertEquals(2, statement.executeUpdate())
            }
        }

        val exportService = ExportService(
            storageResolver,
            Mockito.mock(DataDownloadManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(WebhookService::class.java),
        )
        val firstClaim = ExportService.ClaimedExport(
            firstExportId,
            firstStudyId,
            requestJson,
            ExportFormat.CSV,
            "capacity-worker",
            0,
            0,
            firstLease,
        )
        val secondClaim = ExportService.ClaimedExport(
            secondExportId,
            secondStudyId,
            requestJson,
            ExportFormat.CSV,
            "capacity-worker",
            0,
            0,
            secondLease,
        )

        try {
            assertTrue(exportService.reserveExportCapacity(firstClaim))
            assertFalse(
                "a different lease must not release another worker's reservation",
                exportService.releaseExportCapacityReservation(
                    firstClaim.copy(leaseToken = UUID.randomUUID()),
                ),
            )
            val overCapacity = assertThrows(ExportResourceLimitException::class.java) {
                exportService.reserveExportCapacity(secondClaim)
            }
            assertTrue(overCapacity.message.orEmpty().contains("managed artifact limit"))

            assertTrue(exportService.releaseExportCapacityReservation(firstClaim))
            assertTrue(exportService.reserveExportCapacity(secondClaim))
            assertTrue(exportService.releaseExportCapacityReservation(secondClaim))

            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM export_capacity_reservations WHERE export_id IN (?, ?)",
                ).use { statement ->
                    statement.setObject(1, firstExportId)
                    statement.setObject(2, secondExportId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(0, resultSet.getInt(1))
                    }
                }
            }
        } finally {
            exportService.releaseExportCapacityReservation(firstClaim)
            exportService.releaseExportCapacityReservation(secondClaim)
            exportService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement(
                    "DELETE FROM export_jobs WHERE export_id IN (?, ?)",
                ).use { statement ->
                    statement.setObject(1, firstExportId)
                    statement.setObject(2, secondExportId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM studies WHERE study_id IN (?, ?)").use { statement ->
                    statement.setObject(1, firstStudyId)
                    statement.setObject(2, secondStudyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun testMissingCompletedExportIsPersistedAsFailed() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        val missingPath = ExportFileWriter.EXPORT_DIR.resolve("$exportId.csv")
        Files.deleteIfExists(missingPath)

        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, "missing-export-$studyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    completed_at, file_path
                ) VALUES (?, ?, 'COMPLETED', 'CSV', '{}'::jsonb, 'migration-test', now(), ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, exportId)
                statement.setObject(2, studyId)
                statement.setString(3, missingPath.toString())
                assertEquals(1, statement.executeUpdate())
            }
        }

        val exportService = ExportService(
            storageResolver,
            Mockito.mock(DataDownloadManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java),
            Mockito.mock(WebhookService::class.java),
        )
        try {
            assertThrows(IllegalStateException::class.java) {
                exportService.streamExportFile(
                    studyId,
                    exportId,
                    "migration-test",
                    ByteArrayOutputStream(),
                )
            }
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT status, file_path, error_message FROM export_jobs WHERE export_id = ?",
                ).use { statement ->
                    statement.setObject(1, exportId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("FAILED", resultSet.getString("status"))
                        assertNull(resultSet.getString("file_path"))
                        assertTrue(resultSet.getString("error_message").contains("unavailable"))
                    }
                }
            }
        } finally {
            exportService.shutdown()
            getConnection().use { connection ->
                connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use { statement ->
                    statement.setObject(1, studyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    // =========================================================================
    // V67 — database-authoritative last-active-owner invariant
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
    // V50/V51 — deletion ledger, quarantine, and the services that ride on it
    // =========================================================================
    @Test
    fun testDeletionLedgerAndParticipantAccess() {
        for (table in listOf(
            "participant_form_access_codes", "participant_form_sessions",
            "participant_form_submission_receipts", "data_deletion_operations",
            "data_deletion_steps", "retention_holds", "data_deletion_tombstones",
            "data_deletion_audit_outbox",
        )) {
            assertTrue("$table must exist", tableExists(table))
        }
        assertTrue(getColumnNames("data_deletion_operations").contains("operation_attempt_count"))
        assertTrue(getColumnNames("data_deletion_operations").contains("next_attempt_at"))
        assertTrue(getColumnNames("data_deletion_operations").contains("cancelled_by"))
        assertTrue(getColumnNames("data_deletion_operations").contains("cancelled_at"))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) FROM pg_policies
                WHERE policyname LIKE 'deletion_quarantine_%' AND permissive = 'RESTRICTIVE'
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(33, resultSet.getInt(1))
                }
            }
            assertEquals(true to true, rlsState("jobs"))

            val studyId = UUID.randomUUID()
            val participantId = "participant-v50-${UUID.randomUUID()}"
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations
                    (operation_id, study_id, participant_ref, participant_id, mode, status,
                     requested_by, idempotency_key, registry_version, quarantine_until)
                VALUES (?, ?, 'participant-ref', ?, 'COLLECTED_DATA_PURGE', 'QUARANTINED',
                        'migration-test', ?, 1, now() + interval '7 days')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.setString(3, participantId)
                statement.setObject(4, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT chronicle_participant_data_visible(?, ?)").use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertFalse("Quarantined participant data must be inaccessible", resultSet.getBoolean(1))
                }
                statement.setString(2, "different-participant")
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertTrue("Other participants must remain visible", resultSet.getBoolean(1))
                }
            }
        }

        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
            Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC),
        )
        val erasureStudyId = UUID.randomUUID()
        val erasureParticipantId = "participant-erasure-${UUID.randomUUID()}"
        val erasureExportId = UUID.randomUUID()
        val erasureExport = ExportFileWriter.writeMultiDataTypeExport(
            mapOf(
                ParticipantDataType.UsageEvents.name to listOf(
                    mapOf("participant_id" to erasureParticipantId, "value" to "derived"),
                ),
            ),
            ExportFormat.CSV,
            erasureExportId,
        )
        getConnection().use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                statement.setObject(1, erasureStudyId)
                statement.setString(2, "erasure-export-$erasureStudyId")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO export_jobs (
                    export_id, study_id, status, format, request, created_by,
                    completed_at, row_count, file_path
                ) VALUES (
                    ?, ?, 'COMPLETED', 'CSV', ?::jsonb, 'migration-request',
                    now(), ?, ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, erasureExportId)
                statement.setObject(2, erasureStudyId)
                statement.setString(
                    3,
                    ObjectMappers.newJsonMapper().writeValueAsString(
                        ExportRequest(
                            dataTypes = setOf(ParticipantDataType.UsageEvents),
                            participantIds = setOf(erasureParticipantId),
                            format = ExportFormat.CSV,
                        ),
                    ),
                )
                statement.setLong(4, erasureExport.rowCount)
                statement.setString(5, erasureExport.path.toString())
                assertEquals(1, statement.executeUpdate())
            }
        }
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "migration-request",
                authorizedStudyIds = setOf(erasureStudyId),
                isAdmin = false,
            ),
        )
        val verifiedOperationId = try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO data_quality_alerts (
                        alert_id, study_id, participant_id, alert_type, message, score
                    ) VALUES (?, ?, ?, 'MISSING_DATA', 'migration test', 1.0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, erasureStudyId)
                    statement.setString(3, erasureParticipantId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            orchestrator.quarantineParticipant(
                studyId = erasureStudyId,
                participantId = erasureParticipantId,
                mode = DataDeletionMode.COLLECTED_DATA_PURGE,
                requestedBy = "migration-test",
                idempotencyKey = UUID.randomUUID(),
            )
        } finally {
            RLSRequestContext.clear()
        }
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "migration-request",
                authorizedStudyIds = setOf(erasureStudyId),
                isAdmin = false,
            ),
        )
        try {
            val exportService = ExportService(
                storageResolver,
                Mockito.mock(DataDownloadManager::class.java),
                Mockito.mock(HazelcastIdGenerationService::class.java),
                Mockito.mock(WebhookService::class.java),
            )
            try {
                assertThrows(IllegalStateException::class.java) {
                    exportService.streamExportFile(
                        erasureStudyId,
                        erasureExportId,
                        "migration-request",
                        ByteArrayOutputStream(),
                    )
                }
            } finally {
                exportService.shutdown()
            }
        } finally {
            RLSRequestContext.clear()
        }
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT expected_rows
                FROM data_deletion_steps
                WHERE operation_id = ? AND asset_id = 'data-quality-alerts'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, verifiedOperationId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(
                        "PREVIEW counts must observe rows before quarantine activates",
                        1L,
                        resultSet.getLong(1),
                    )
                }
            }
        }
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "migration-request",
                authorizedStudyIds = setOf(erasureStudyId),
                isAdmin = false,
            ),
        )
        try {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM data_quality_alerts WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, erasureStudyId)
                    statement.setString(2, erasureParticipantId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("ordinary requests must not see quarantined rows", 0, resultSet.getInt(1))
                    }
                }
            }
        } finally {
            RLSRequestContext.clear()
        }
        RLSRequestContext.withDeletionWorkerContext {
            getConnection().use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM data_quality_alerts WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, erasureStudyId)
                    statement.setString(2, erasureParticipantId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("the deletion worker must observe quarantined rows", 1, resultSet.getInt(1))
                    }
                }
            }
        }
        assertEquals(1, orchestrator.processDueOperations(limit = 1))
        assertEquals("COMPLETED", orchestrator.getOperation(verifiedOperationId).status)
        assertFalse(
            "derived export artifact must be deleted before erasure completes",
            java.nio.file.Files.exists(erasureExport.path),
        )
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT status, file_path FROM export_jobs WHERE export_id = ?",
            ).use { statement ->
                statement.setObject(1, erasureExportId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("FAILED", resultSet.getString("status"))
                    assertNull(resultSet.getString("file_path"))
                }
            }
        }
        getConnection().use { connection ->
            connection.prepareStatement(
                "SELECT proof_hash FROM data_deletion_tombstones WHERE operation_id = ?"
            ).use { statement ->
                statement.setObject(1, verifiedOperationId)
                statement.executeQuery().use { resultSet ->
                    assertTrue("Verified erasure must produce a proof tombstone", resultSet.next())
                    assertTrue(resultSet.getString("proof_hash").matches(Regex("^[0-9a-f]{64}$")))
                }
            }
        }

        val receiptStudyId = UUID.randomUUID()
        val receiptParticipantId = "participant-receipt-${UUID.randomUUID()}"
        val accessCodeId = UUID.randomUUID()
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, receiptStudyId)
                statement.setString(2, receiptParticipantId)
                statement.setObject(3, UUID.randomUUID())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO participant_form_access_codes
                    (access_code_id, token_hash, study_id, participant_id, form_kind,
                     issuer_type, issued_by, expires_at)
                VALUES (?, ?, ?, ?, 'QUESTIONNAIRE', 'RESEARCHER', 'migration-test', now() + interval '1 day')
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, accessCodeId)
                statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
                statement.setObject(3, receiptStudyId)
                statement.setString(4, receiptParticipantId)
                statement.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS participant_submission_effects (submission_id UUID PRIMARY KEY)"
                )
            }
        }
        val receiptService = ParticipantFormSubmissionReceiptService(
            storageResolver,
            ObjectMappers.newJsonMapper(),
        )
        val receiptScope = ParticipantFormAccessScope(
            accessCodeId = accessCodeId,
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.QUESTIONNAIRE,
            resourceId = UUID.randomUUID(),
            logicalDate = null,
            absoluteExpiresAt = OffsetDateTime.now().plusHours(1),
        )
        val receiptKey = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val firstSubmission = receiptService.executeWithSubmissionId(
            receiptScope,
            ParticipantFormKind.QUESTIONNAIRE,
            "questionnaire:${receiptScope.resourceId}",
            receiptKey,
            mapOf("answer" to "yes"),
        ) { connection ->
            connection.prepareStatement(
                "INSERT INTO participant_submission_effects (submission_id) VALUES (?)"
            ).use { statement ->
                statement.setObject(1, submissionId)
                statement.executeUpdate()
            }
            submissionId
        }
        val replayedSubmission = receiptService.executeWithSubmissionId(
            receiptScope,
            ParticipantFormKind.QUESTIONNAIRE,
            "questionnaire:${receiptScope.resourceId}",
            receiptKey,
            mapOf("answer" to "yes"),
        ) { error("Replay must not execute the submission action") }
        assertFalse(firstSubmission.replayed)
        assertTrue(replayedSubmission.replayed)
        assertEquals(submissionId, replayedSubmission.value)
        assertThrows(ParticipantSubmissionConflictException::class.java) {
            receiptService.executeWithSubmissionId(
                receiptScope,
                ParticipantFormKind.QUESTIONNAIRE,
                "questionnaire:${receiptScope.resourceId}",
                receiptKey,
                mapOf("answer" to "different"),
            ) { error("Conflicting replay must not execute the submission action") }
        }

        // PostgreSQL stores TIMESTAMPTZ at microsecond precision. A fixed whole-second instant
        // keeps the strict replay-deadline equality check deterministic across JDBC round trips.
        val accessClock = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC)
        val accessService = ParticipantFormAccessService(storageResolver, accessClock)
        val reviewerReady = CountDownLatch(2)
        val releaseReviewers = CountDownLatch(1)
        val reviewerExecutor = Executors.newFixedThreadPool(2)
        try {
            val reviewerCalls = (1..2).map {
                reviewerExecutor.submit {
                    reviewerReady.countDown()
                    check(releaseReviewers.await(10, TimeUnit.SECONDS))
                    accessService.createReplacingAccessCode(
                        studyId = receiptStudyId,
                        participantId = receiptParticipantId,
                        formKind = ParticipantFormKind.ENROLLMENT,
                        resourceId = null,
                        logicalDate = null,
                        requestedExpiresAt = OffsetDateTime.ofInstant(accessClock.instant(), ZoneOffset.UTC)
                            .plusMinutes(15),
                        issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
                        issuedBy = "play-reviewer-bootstrap",
                    )
                }
            }
            assertTrue(reviewerReady.await(10, TimeUnit.SECONDS))
            releaseReviewers.countDown()
            reviewerCalls.forEach { call -> call.get(10, TimeUnit.SECONDS) }
        } finally {
            releaseReviewers.countDown()
            reviewerExecutor.shutdownNow()
            assertTrue(reviewerExecutor.awaitTermination(10, TimeUnit.SECONDS))
        }
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) FROM participant_form_access_codes
                WHERE study_id = ? AND participant_id = ? AND form_kind = 'ENROLLMENT'
                  AND issuer_type = 'RESEARCHER' AND issued_by = 'play-reviewer-bootstrap'
                  AND exchanged_at IS NULL AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, receiptStudyId)
                statement.setString(2, receiptParticipantId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("reviewer rotation must leave exactly one live invitation", 1, resultSet.getInt(1))
                }
            }
            val duplicate = assertThrows(SQLException::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO participant_form_access_codes (
                        access_code_id, token_hash, study_id, participant_id, form_kind,
                        issuer_type, issued_by, expires_at
                    ) VALUES (?, ?, ?, ?, 'ENROLLMENT', 'RESEARCHER',
                              'play-reviewer-bootstrap', now() + interval '15 minutes')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setBytes(2, UUID.randomUUID().toString().toByteArray())
                    statement.setObject(3, receiptStudyId)
                    statement.setString(4, receiptParticipantId)
                    statement.executeUpdate()
                }
            }
            assertEquals("23505", duplicate.sqlState)
        }
        accessService.createAccessCode(
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.APP_USAGE,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.DEVICE,
            issuedBy = "device:migration-test",
        )
        val enrollmentCode = accessService.createAccessCode(
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.ENROLLMENT,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "researcher:migration-test",
        )
        assertFalse(
            accessService.consumeEnrollmentAccessCode(
                enrollmentCode.accessCode,
                UUID.randomUUID(),
                receiptParticipantId,
            )
        )
        assertNull(accessService.exchangeAccessCode(enrollmentCode.accessCode))
        assertNotNull(
            accessService.resolveEnrollmentAccessCode(
                enrollmentCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
            )
        )
        assertTrue(
            accessService.consumeEnrollmentAccessCode(
                enrollmentCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
            )
        )
        assertNull(
            accessService.resolveEnrollmentAccessCode(
                enrollmentCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
            )
        )
        assertFalse(
            accessService.consumeEnrollmentAccessCode(
                enrollmentCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            accessService.createAccessCodes(
                listOf(
                    ParticipantAccessCodeCommand(
                        studyId = receiptStudyId,
                        participantId = receiptParticipantId,
                        formKind = ParticipantFormKind.APP_USAGE,
                        resourceId = null,
                        logicalDate = null,
                        requestedExpiresAt = null,
                        issuerType = ParticipantAccessCodeIssuerType.DEVICE,
                        issuedBy = "device:migration-test",
                    ),
                    ParticipantAccessCodeCommand(
                        studyId = receiptStudyId,
                        participantId = receiptParticipantId,
                        formKind = ParticipantFormKind.QUESTIONNAIRE,
                        resourceId = UUID.randomUUID(),
                        logicalDate = null,
                        requestedExpiresAt = OffsetDateTime.ofInstant(accessClock.instant(), ZoneOffset.UTC),
                        issuerType = ParticipantAccessCodeIssuerType.DEVICE,
                        issuedBy = "device:migration-test",
                    ),
                )
            )
        }
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) FROM participant_form_access_codes
                WHERE study_id = ? AND participant_id = ? AND form_kind = 'APP_USAGE'
                  AND issuer_type = 'DEVICE' AND issued_by = 'device:migration-test'
                  AND revoked_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, receiptStudyId)
                statement.setString(2, receiptParticipantId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("Failed batches must preserve the prior code", 1, resultSet.getInt(1))
                }
            }
        }

        val proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        val proposedApiKeyHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(proposedApiKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun enrollmentBinding(
            attemptId: UUID,
            requestMarker: Char = '3',
            deviceId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        ) = EnrollmentAttemptBinding(
            attemptId = attemptId,
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            sourceDeviceHash = "1".repeat(64),
            deviceId = deviceId,
            manifestDigest = "2".repeat(64),
            requestHash = requestMarker.toString().repeat(64),
            proposedApiKeyHash = proposedApiKeyHash,
        )

        val replayCode = accessService.createAccessCode(
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.ENROLLMENT,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "researcher:replay-migration-test",
        )
        val replayBinding = enrollmentBinding(UUID.randomUUID())
        assertFalse(
            accessService.authorizeEnrollmentAttempt(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding,
            ) { _, _ -> false },
        )
        assertNotNull(
            accessService.resolveEnrollmentAccessCode(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
            ),
        )
        assertTrue(
            accessService.authorizeEnrollmentAttempt(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding,
            ) { _, _ -> true },
        )
        assertTrue(
            "lost-response retry must not re-run disclosure validation after the exact receipt is bound",
            accessService.authorizeEnrollmentAttempt(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding.copy(),
            ) { _, _ -> error("Exact replay must use the durable binding") },
        )
        assertFalse(
            accessService.authorizeEnrollmentAttempt(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding.copy(requestHash = "4".repeat(64)),
            ) { _, _ -> true },
        )
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT enrollment_proposed_key_hash, row_to_json(code)::text
                FROM participant_form_access_codes code
                WHERE token_hash = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(
                    1,
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(replayCode.accessCode.toByteArray()),
                )
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(proposedApiKeyHash, resultSet.getString(1))
                    assertFalse(resultSet.getString(2).contains(proposedApiKey))
                }
            }
        }

        val concurrentCode = accessService.createAccessCode(
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.ENROLLMENT,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "researcher:concurrent-migration-test",
        )
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val enrollmentExecutor = Executors.newFixedThreadPool(2)
        val concurrentDeviceId = UUID.fromString("00000000-0000-0000-0000-000000000004")
        try {
            val firstAttempt = enrollmentExecutor.submit<Boolean> {
                accessService.authorizeEnrollmentAttempt(
                    concurrentCode.accessCode,
                    receiptStudyId,
                    receiptParticipantId,
                    enrollmentBinding(UUID.randomUUID(), '5', concurrentDeviceId),
                ) { _, _ ->
                    firstLocked.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS))
            val competingAttempt = enrollmentExecutor.submit<Boolean> {
                accessService.authorizeEnrollmentAttempt(
                    concurrentCode.accessCode,
                    receiptStudyId,
                    receiptParticipantId,
                    enrollmentBinding(UUID.randomUUID(), '6', concurrentDeviceId),
                ) { _, _ -> true }
            }
            releaseFirst.countDown()
            assertTrue(firstAttempt.get(10, TimeUnit.SECONDS))
            assertFalse("a competing binding must lose after the row lock", competingAttempt.get(10, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            enrollmentExecutor.shutdownNow()
            assertTrue(enrollmentExecutor.awaitTermination(10, TimeUnit.SECONDS))
        }

        val expiredReplayService = ParticipantFormAccessService(
            storageResolver,
            Clock.offset(accessClock, Duration.ofHours(24)),
        )
        assertFalse(
            "the replay deadline is strict; equality is expired",
            expiredReplayService.authorizeEnrollmentAttempt(
                replayCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding,
            ) { _, _ -> true },
        )
        val recoveryCode = expiredReplayService.createAccessCode(
            studyId = receiptStudyId,
            participantId = receiptParticipantId,
            formKind = ParticipantFormKind.ENROLLMENT,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "researcher:fresh-recovery-migration-test",
        )
        assertTrue(
            "a fresh invitation must recover the same participant after an old receipt expires",
            expiredReplayService.authorizeEnrollmentAttempt(
                recoveryCode.accessCode,
                receiptStudyId,
                receiptParticipantId,
                replayBinding.copy(
                    attemptId = UUID.randomUUID(),
                    proposedApiKeyHash = "7".repeat(64),
                ),
            ) { _, _ -> true },
        )
    }

    @Test
    fun testConcurrentDataCollectionUpdatesReceiveDistinctLockedRevisions() {
        val studyId = UUID.randomUUID()
        val initialSettings = StudySettings(
            mapOf(StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 1)),
        )
        val competingSettings = listOf(
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
                ),
                settingsVersion = 99,
            ),
            AndroidDataCollectionSetting(
                modules = mapOf(
                    CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true),
                ),
                settingsVersion = 99,
            ),
        )
        val ready = CountDownLatch(competingSettings.size)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(competingSettings.size)

        try {
            getConnection().use { connection ->
                executeSingleUpdate(
                    connection,
                    "INSERT INTO studies (study_id, title, settings) VALUES (?, ?, ?::jsonb)",
                ) { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "locked-revision-$studyId")
                    statement.setString(3, ObjectMappers.getJsonMapper().writeValueAsString(initialSettings))
                }
            }
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
                CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            ),
            settingsVersion = 99,
        )
        val sensorDelta = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))
        val updateLocked = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val deltaStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            insertStudySettings(studyId, "locked-delta-$studyId", initialSettings)

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
                    StudySettingType.AndroidSensor,
                    sensorDelta,
                )
            }
            assertTrue(deltaStarted.await(10, TimeUnit.SECONDS))
            releaseUpdate.countDown()

            assertEquals(2, dataCollectionUpdate.get(10, TimeUnit.SECONDS))
            val persistedSettings = unrelatedUpdate.get(10, TimeUnit.SECONDS)
            assertEquals(sensorDelta, persistedSettings[StudySettingType.AndroidSensor])
            assertEquals(
                committedDataCollection.copy(settingsVersion = 2),
                persistedSettings[StudySettingType.DataCollection],
            )
        } finally {
            releaseUpdate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            deleteStudyRow(studyId)
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
            insertStudySettings(
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
            deleteStudyRow(studyId)
        }
    }

    private fun insertStudySettings(studyId: UUID, title: String, settings: StudySettings) {
        getConnection().use { connection ->
            executeSingleUpdate(
                connection,
                "INSERT INTO studies (study_id, title, settings) VALUES (?, ?, ?::jsonb)",
            ) { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, title)
                statement.setString(3, ObjectMappers.getJsonMapper().writeValueAsString(settings))
            }
        }
    }

    private fun deleteStudyRow(studyId: UUID) {
        getConnection().use { connection ->
            executeSingleUpdate(connection, "DELETE FROM studies WHERE study_id = ?", expected = null) {
                it.setObject(1, studyId)
            }
        }
    }

    private fun persistLockedDataCollectionUpdate(
        studyId: UUID,
        requested: AndroidDataCollectionSetting,
        beforeCommit: () -> Unit = {},
    ): Int = getConnection().use { connection ->
        connection.autoCommit = false
        try {
            val lockedUpdate = stampDataCollectionSettingsVersionLocked(
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
            val stampedUpdate = stampDataCollectionSettingsVersion(
                priorSettings,
                StudyUpdate(settings = mergedSettings),
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

    @Test
    fun testDeletionMutationBarrierHonorsModeLifecycle() {
        val guardedTables = 31 // 29 participant registry tables, study_participants, and ambient audio
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_trigger
                WHERE NOT tgisinternal
                  AND tgname IN (
                      'deletion_mutation_guard_insert',
                      'deletion_mutation_guard_update'
                  )
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(guardedTables * 2, resultSet.getInt(1))
                }
            }
        }

        val studyId = UUID.randomUUID()
        val participantId = "mutation-barrier-${UUID.randomUUID()}"
        val operationId = UUID.randomUUID()
        val participantKey = ParticipantKey(studyId, participantId)
        val statsDataSource = RLSDataSources.wrapWithSystemContext(storageResolver.getPlatformStorage())
        val statsGuard = ParticipantStatsDeletionGuard(statsDataSource)
        val statsMapstore = ParticipantStatsMapstore(statsDataSource)
        val originalPing = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val rejectedPing = originalPing.plusDays(1)
        statsMapstore.store(
            participantKey,
            ParticipantStats(studyId, participantId, androidLastPing = originalPing),
        )
        assertFalse(statsGuard.isBlocked(participantKey))

        fun assertStoredPing(expected: OffsetDateTime) {
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT android_last_ping
                    FROM participant_stats
                    WHERE study_id = ? AND participant_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, participantId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(expected, resultSet.getObject(1, OffsetDateTime::class.java))
                    }
                }
            }
        }

        fun insertAlert(): Int = getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO data_quality_alerts (
                    alert_id, study_id, participant_id, alert_type, message, score
                ) VALUES (?, ?, ?, 'MISSING_DATA', 'mutation barrier test', 1.0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, studyId)
                statement.setString(3, participantId)
                statement.executeUpdate()
            }
        }

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO data_deletion_operations (
                    operation_id, study_id, participant_ref, participant_id,
                    mode, status, requested_by, idempotency_key,
                    registry_version, quarantine_until
                ) VALUES (
                    ?, ?, 'participant-ref', ?,
                    'COLLECTED_DATA_PURGE', 'QUARANTINED', 'migration-test', ?,
                    1, now() + interval '7 days'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                statement.setObject(2, studyId)
                statement.setString(3, participantId)
                statement.setObject(4, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertEquals(
            "collection remains allowed during a collected-data-purge quarantine",
            1,
            insertAlert(),
        )
        assertTrue(
            "participant-stats upserts must not bypass quarantine visibility through Hazelcast",
            statsGuard.isBlocked(participantKey),
        )
        statsMapstore.store(
            participantKey,
            ParticipantStats(studyId, participantId, androidLastPing = rejectedPing),
        )
        assertStoredPing(originalPing)
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'ERASING', updated_at = now(),
                    worker_lease_token = ?, worker_lease_expires_at = now() + interval '30 minutes'
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val collectedBlocked = assertThrows(SQLException::class.java) { insertAlert() }
        assertEquals("55000", collectedBlocked.sqlState)
        assertTrue(statsGuard.isBlocked(participantKey))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET mode = 'WITHDRAW_AND_ERASE', status = 'QUARANTINED',
                    worker_lease_token = NULL, worker_lease_expires_at = NULL
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val withdrawalBlocked = assertThrows(SQLException::class.java) { insertAlert() }
        assertEquals("55000", withdrawalBlocked.sqlState)
        assertTrue(statsGuard.isBlocked(participantKey))

        getConnection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE data_deletion_operations
                SET status = 'COMPLETED', participant_id = NULL,
                    worker_lease_token = NULL, worker_lease_expires_at = NULL
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        val completedWithdrawalBlocked = assertThrows(SQLException::class.java) { insertAlert() }
        assertEquals("55000", completedWithdrawalBlocked.sqlState)
        assertTrue(statsGuard.isBlocked(participantKey))
        statsMapstore.store(
            participantKey,
            ParticipantStats(studyId, participantId, androidLastPing = rejectedPing),
        )
        assertStoredPing(originalPing)

        getConnection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM data_quality_alerts WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM data_deletion_operations WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFalse(statsGuard.isBlocked(participantKey))
        statsMapstore.store(
            participantKey,
            ParticipantStats(studyId, participantId, androidLastPing = rejectedPing),
        )
        assertStoredPing(rejectedPing)
        getConnection().use { connection ->
            connection.prepareStatement(
                "DELETE FROM participant_stats WHERE study_id = ? AND participant_id = ?",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    // =========================================================================
    // V54/V59 — audit_buffer drained into audit; direct writes; deletion blocked;
    // legacy-path writes forwarded (rolling old replicas / emergency binary rollback)
    // =========================================================================
    @Test
    fun testDirectAppendOnlyAudit() {
        getConnection().use { connection ->
            // The legacy row seeded before the corpus ran must have been drained into audit.
            connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE description = ?").use { statement ->
                statement.setString(1, legacyAuditDescription)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(1, resultSet.getInt(1))
                }
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM audit_buffer").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(0, resultSet.getInt(1))
                }
            }
        }

        val directDescription = "direct-audit-${UUID.randomUUID()}"
        val directEvent = AuditableEvent(
            aclKey = AclKey(UUID.randomUUID()),
            securablePrincipalId = UUID.randomUUID(),
            principal = Principal(PrincipalType.USER, "migration-test"),
            eventType = AuditEventType.GET_STUDY,
            description = directDescription,
            study = UUID.randomUUID(),
            organization = UUID.randomUUID(),
        )
        assertEquals(1, PostgresAuditingManager(storageResolver).recordEvents(listOf(directEvent)))

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

        // V59: a legacy-path write AFTER the cutover (rolling old replica, or an emergency
        // binary rollback to a pre-V54 build) must be forwarded into audit by the
        // BEFORE INSERT trigger — never stranded in the staging table.
        val rollbackDescription = "rollback-audit-${UUID.randomUUID()}"
        getConnection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO audit_buffer (
                    acl_key, id, principal_type, principal_id, audit_event_type,
                    study_id, organization_id, description, data, event_timestamp
                ) VALUES (?, ?, 'USER', 'migration-test', 'GET_STUDY', ?, ?, ?, '{}', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString().replace("-", ""))
                statement.setString(2, UUID.randomUUID().toString())
                statement.setString(3, UUID.randomUUID().toString())
                statement.setString(4, UUID.randomUUID().toString())
                statement.setString(5, rollbackDescription)
                assertEquals("the forwarding trigger must suppress the staging row", 0, statement.executeUpdate())
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM audit_buffer").use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("audit_buffer must stay empty after the handoff", 0, resultSet.getInt(1))
                }
            }
            connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE description = ?").use { statement ->
                statement.setString(1, rollbackDescription)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("the legacy-path write must be durable in audit", 1, resultSet.getInt(1))
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
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET quarantine_until = now() - interval '1 minute'
                    WHERE operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
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
                    connection.prepareStatement("DELETE FROM data_deletion_tombstones WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
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
    fun testFinalDeletionSweepTerminalizesExportCreatedAfterInitialSweep() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val seedTable = "test_late_export_seed_$suffix"
        val triggerFunction = "test_late_export_insert_$suffix"
        val triggerName = "test_late_export_trigger_$suffix"
        val studyId = UUID.randomUUID()
        val participantId = "late-export-$suffix"
        val exportId = UUID.randomUUID()
        val orchestrator = DataDeletionOrchestrator(
            storageResolver,
            Mockito.mock(AuditingManager::class.java),
        )
        var operationId: UUID? = null
        var exportPath: java.nio.file.Path? = null

        try {
            getConnection().use { connection ->
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "late-export-$suffix")
                    assertEquals(1, statement.executeUpdate())
                }
            }
            operationId = orchestrator.quarantineParticipant(
                studyId,
                participantId,
                DataDeletionMode.COLLECTED_DATA_PURGE,
                "migration-test",
                UUID.randomUUID(),
            )
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET quarantine_until = now() - interval '1 minute'
                    WHERE operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            val artifact = ExportFileWriter.writeMultiDataTypeExport(
                mapOf(
                    ParticipantDataType.UsageEvents.name to listOf(
                        mapOf("participant_id" to participantId, "value" to "late-derived"),
                    ),
                ),
                ExportFormat.CSV,
                exportId,
            )
            exportPath = artifact.path
            val requestJson = ObjectMappers.newJsonMapper().writeValueAsString(
                ExportRequest(
                    dataTypes = setOf(ParticipantDataType.UsageEvents),
                    participantIds = setOf(participantId),
                    format = ExportFormat.CSV,
                ),
            )

            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE $seedTable (
                            operation_id UUID PRIMARY KEY,
                            export_id UUID NOT NULL,
                            target_study_id UUID NOT NULL,
                            target_request JSONB NOT NULL,
                            target_file_path TEXT NOT NULL,
                            target_row_count BIGINT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE FUNCTION $triggerFunction() RETURNS trigger
                        LANGUAGE plpgsql
                        SECURITY DEFINER
                        SET search_path = pg_catalog, public
                        AS ${'$'}body${'$'}
                        DECLARE
                            seeded RECORD;
                        BEGIN
                            IF NEW.status = 'VERIFIED'
                               AND OLD.status IS DISTINCT FROM 'VERIFIED' THEN
                                DELETE FROM $seedTable
                                WHERE operation_id = NEW.operation_id
                                RETURNING * INTO seeded;
                                IF FOUND THEN
                                    INSERT INTO export_jobs (
                                        export_id, study_id, status, format, request, created_by,
                                        completed_at, row_count, file_path
                                    ) VALUES (
                                        seeded.export_id, seeded.target_study_id, 'COMPLETED',
                                        'CSV', seeded.target_request, 'late-export-trigger',
                                        now(), seeded.target_row_count, seeded.target_file_path
                                    );
                                END IF;
                            END IF;
                            RETURN NEW;
                        END
                        ${'$'}body${'$'}
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TRIGGER $triggerName
                        AFTER UPDATE OF status ON data_deletion_steps
                        FOR EACH ROW EXECUTE FUNCTION $triggerFunction()
                        """.trimIndent(),
                    )
                }
                connection.prepareStatement(
                    """
                    INSERT INTO $seedTable (
                        operation_id, export_id, target_study_id, target_request,
                        target_file_path, target_row_count
                    ) VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.setObject(2, exportId)
                    statement.setObject(3, studyId)
                    statement.setString(4, requestJson)
                    statement.setString(5, artifact.path.toString())
                    statement.setLong(6, artifact.rowCount)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
            assertFalse(
                "the final sweep must delete an export inserted after the initial sweep",
                Files.exists(artifact.path),
            )
            getConnection().use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, file_path, completed_at
                    FROM export_jobs
                    WHERE export_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, exportId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("FAILED", resultSet.getString("status"))
                        assertNull(resultSet.getString("file_path"))
                        assertNotNull(resultSet.getObject("completed_at"))
                    }
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM $seedTable WHERE operation_id = ?",
                ).use { statement ->
                    statement.setObject(1, operationId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals("the trigger must have exercised the intended race", 0, resultSet.getInt(1))
                    }
                }
            }
        } finally {
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $triggerName ON data_deletion_steps")
                    statement.execute("DROP FUNCTION IF EXISTS $triggerFunction()")
                    statement.execute("DROP TABLE IF EXISTS $seedTable")
                }
                connection.prepareStatement("DELETE FROM export_jobs WHERE export_id = ?").use { statement ->
                    statement.setObject(1, exportId)
                    statement.executeUpdate()
                }
                operationId?.let { id ->
                    connection.prepareStatement("DELETE FROM data_deletion_tombstones WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
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
            exportPath?.let(Files::deleteIfExists)
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
                executor.submit<MobileSelfWithdrawalResult> {
                    TestSecurityUtils.setupSecurityContext("mobile-withdraw-${device.keyId}")
                    try {
                        ready.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        service.executeSelfWithdrawal(
                            fixture.studyId,
                            fixture.participantId,
                            device.deviceId,
                            device.keyId,
                            device.requestId,
                        )
                    } finally {
                        TestSecurityUtils.clearSecurityContext()
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
                    val replay = service.executeSelfWithdrawal(
                        fixture.studyId,
                        fixture.participantId,
                        device.deviceId,
                        device.keyId,
                        device.requestId,
                    )
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
                    device.deviceId,
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

    private fun seedConcurrentWithdrawalFixture(fixture: ConcurrentWithdrawalFixture) {
        getConnection().use { connection ->
            executeSingleUpdate(connection, "INSERT INTO studies (study_id, title) VALUES (?, ?)") { statement ->
                statement.setObject(1, fixture.studyId)
                statement.setString(2, "concurrent-withdrawal-${fixture.studyId}")
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
                statement.setObject(3, UUID.randomUUID())
            }
            fixture.devices.forEachIndexed { index, device ->
                executeSingleUpdate(
                    connection,
                    """
                    INSERT INTO api_keys (
                        key_id, study_id, key_hash, key_prefix, name, scope, created_by,
                        expires_at, revoked, participant_id, device_id
                    ) VALUES (?, ?, ?, ?, ?, 'WRITE', 'migration-test',
                              now() + interval '1 day', false, ?, ?)
                    """.trimIndent(),
                ) { statement ->
                    statement.setObject(1, device.keyId)
                    statement.setObject(2, fixture.studyId)
                    statement.setString(3, (index + 1).toString().repeat(64))
                    statement.setString(4, "device-$index")
                    statement.setString(5, "withdrawal-device-$index")
                    statement.setString(6, fixture.participantId)
                    statement.setObject(7, device.deviceId)
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
            deleteStudyRow(fixture.studyId)
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
        val deviceId: UUID,
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
            statement.setObject(3, UUID.randomUUID())
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
                expires_at, revoked, participant_id, device_id
            ) VALUES (?, ?, ?, ?, 'withdrawal-retention', 'WRITE', 'migration-test',
                      now() + interval '1 day', true, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.apiKeyId)
            statement.setObject(2, fixture.studyId)
            statement.setString(3, UUID.randomUUID().toString().replace("-", "").repeat(2))
            statement.setString(4, "ck_test_")
            statement.setString(5, fixture.participantId)
            statement.setObject(6, fixture.deviceId)
        }
    }

    private fun seedErasureRetentionEvidence(connection: Connection, fixture: ErasureRetentionFixture) {
        executeSingleUpdate(
            connection,
            """
            INSERT INTO mobile_withdrawal_requests (
                request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn
            ) VALUES (?, ?, ?, ?, ?, false)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.withdrawalRequestId)
            statement.setObject(2, fixture.apiKeyId)
            statement.setObject(3, fixture.studyId)
            statement.setString(4, fixture.participantId)
            statement.setObject(5, fixture.deviceId)
        }
        executeSingleUpdate(
            connection,
            """
            INSERT INTO participant_collection_acknowledgment (
                id, study_id, participant_id, source_device_id, acknowledged_modules,
                acknowledged_at, settings_version, disclosure_version, manifest_digest,
                evidence_access_code_id, evidence_api_key_id
            ) VALUES (?, ?, ?, ?, '[]'::jsonb, now(), 1, 'v1', ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, fixture.acknowledgmentId)
            statement.setObject(2, fixture.studyId)
            statement.setString(3, fixture.participantId)
            statement.setString(4, fixture.deviceId.toString())
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

    private fun processDeletion(orchestrator: DataDeletionOrchestrator, operationId: UUID) {
        makeDeletionDue(operationId)
        assertEquals(1, orchestrator.processDueOperations(limit = 1))
        assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
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

    private fun executeSingleUpdate(
        connection: Connection,
        sql: String,
        expected: Int? = 1,
        bind: (PreparedStatement) -> Unit,
    ) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            val updated = statement.executeUpdate()
            expected?.let { assertEquals(it, updated) }
        }
    }

    private fun deleteById(connection: Connection, table: String, column: String, id: UUID) {
        executeSingleUpdate(connection, "DELETE FROM $table WHERE $column = ?", expected = null) {
            it.setObject(1, id)
        }
    }

    private class ErasureRetentionFixture {
        val studyId: UUID = UUID.randomUUID()
        val participantId: String = "withdrawal-retention-${UUID.randomUUID()}"
        val deviceId: UUID = UUID.randomUUID()
        val apiKeyId: UUID = UUID.randomUUID()
        val accessCodeId: UUID = UUID.randomUUID()
        val acknowledgmentId: UUID = UUID.randomUUID()
        val withdrawalRequestId: UUID = UUID.randomUUID()
    }

    private fun makeDeletionDue(operationId: UUID) {
        getConnection().use { connection ->
            connection.prepareStatement(
                "UPDATE data_deletion_operations SET quarantine_until = now() - interval '1 minute' WHERE operation_id = ?",
            ).use { statement ->
                statement.setObject(1, operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
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
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET quarantine_until = now() - interval '1 minute'
                    WHERE operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertEquals(1, orchestrator.processDueOperations(limit = 1))
            assertEquals("COMPLETED", orchestrator.getOperation(operationId).status)
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
                    connection.prepareStatement("DELETE FROM data_deletion_tombstones WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
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
                connection.prepareStatement(
                    """
                    UPDATE data_deletion_operations
                    SET quarantine_until = now() - interval '1 minute'
                    WHERE operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, operationId)
                    assertEquals(1, statement.executeUpdate())
                }
            }

            assertEquals(0, orchestrator.processDueOperations(limit = 1))
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
                    connection.prepareStatement("DELETE FROM data_deletion_tombstones WHERE operation_id = ?").use {
                        it.setObject(1, id)
                        it.executeUpdate()
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
            ORGANIZATIONS.name, UPGRADES.name, NOTIFICATIONS.name, QUESTIONNAIRES.name,
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
}
