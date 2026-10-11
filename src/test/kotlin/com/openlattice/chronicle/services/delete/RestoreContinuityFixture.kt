package com.openlattice.chronicle.services.delete

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.health.HealthCheckRegistry
import com.geekbeast.configuration.postgres.PostgresConfiguration
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.Properties
import java.util.UUID

internal class RestoreContinuityFixture {
    companion object {
        lateinit var postgres: PostgreSQLContainer<*>
        lateinit var storageResolver: StorageResolver

        fun start() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_restore_continuity")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

            val hikariProperties = Properties().apply {
                setProperty("jdbcUrl", postgres.jdbcUrl)
                setProperty("username", postgres.username)
                setProperty("password", postgres.password)
                setProperty("maximumPoolSize", "4")
            }
            val postgresConfiguration = PostgresConfiguration(
                hikariConfiguration = hikariProperties,
                usingCitus = false,
                flavor = PostgresFlavor.VANILLA,
                initializeIndices = false,
                initializeTables = false,
            )
            val dataSourceManager = DataSourceManager(
                mapOf(
                    "default" to postgresConfiguration,
                    "chronicle" to postgresConfiguration,
                    "platform_read" to postgresConfiguration,
                ),
                HealthCheckRegistry(),
                MetricRegistry(),
            )
            storageResolver = StorageResolver(dataSourceManager, ChronicleStorageConfiguration())
        }

        fun stop() {
            postgres.stop()
        }
    }

    val checkpointId: UUID = UUID.randomUUID()
    val studyId: UUID = UUID.randomUUID()
    val participantId: String = "restore-participant-${UUID.randomUUID()}"
    val deviceId: UUID = UUID.randomUUID()
    val apiKeyId: UUID = UUID.randomUUID()
    val requestId: UUID = UUID.randomUUID()
    val operationId: UUID = UUID.randomUUID()
    val heldOperationId: UUID = UUID.randomUUID()
    val heldRequestId: UUID = UUID.randomUUID()
    val holdId: UUID = UUID.randomUUID()
    val accessCodeId: UUID = UUID.randomUUID()
    val enrollmentAttemptId: UUID = UUID.randomUUID()

    fun cleanFixture() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS chronicle_restore_continuity CASCADE")
            }
            connection.prepareStatement(
                """
                DELETE FROM data_deletion_tombstones
                WHERE operation_id = ?
                   OR operation_id IN (SELECT operation_id FROM data_deletion_operations WHERE study_id = ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, operationId)
                it.setObject(2, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM data_deletion_audit_outbox WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM data_deletion_operations WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM mobile_withdrawal_requests WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            for (table in listOf("participant_collection_acknowledgment", "participant_purge_cutoffs")) {
                connection.prepareStatement("DELETE FROM $table WHERE study_id = ?").use {
                    it.setObject(1, studyId); it.executeUpdate()
                }
            }
            connection.prepareStatement("DELETE FROM api_keys WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM erased_device_key_tombstones WHERE key_hash = ?").use {
                it.setString(1, "restore-hash-${apiKeyId}")
                it.executeUpdate()
            }
            for (table in listOf("battery_telemetry", "upload_buffer")) {
                connection.prepareStatement("DELETE FROM $table WHERE study_id = ?").use {
                    it.setObject(1, studyId)
                    it.executeUpdate()
                }
            }
            connection.prepareStatement("DELETE FROM participant_form_access_codes WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM studies WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
        }
    }

    fun seedRestoredOlderState(includeStudy: Boolean = true) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            if (includeStudy) connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, ?)").use {
                it.setObject(1, studyId)
                it.setString(2, "restore-continuity-${studyId}")
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, 'ENROLLED')
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, participantId)
                it.setObject(3, UUID.randomUUID())
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO api_keys (
                    key_id, study_id, key_hash, key_prefix, name, scope, created_by,
                    expires_at, revoked, participant_id, device_id
                ) VALUES (?, ?, ?, 'restore-key', 'restore key', 'MOBILE', 'fixture',
                          now() + interval '1 day', false, ?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, apiKeyId)
                it.setObject(2, studyId)
                it.setString(3, "restore-hash-${apiKeyId}")
                it.setString(4, participantId)
                it.setObject(5, deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO battery_telemetry (
                    study_id, participant_id, sample_id, sample_timestamp, timezone,
                    level_percent, charging_state
                ) VALUES (?, ?, 'restored-sample', now(), 'UTC', 80, 'NOT_CHARGING')
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, participantId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    fun seedContinuityCheckpoint() {
        storageResolver.getPlatformStorage().connection.use { connection ->
            createContinuitySchema(connection)
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.withdrawal_requests
                    (request_id, api_key_id, study_id, participant_id, device_id,
                     already_withdrawn, created_at)
                VALUES (?, ?, ?, ?, ?, false, '2026-08-20T12:00:00Z')
                """.trimIndent(),
            ).use {
                it.setObject(1, requestId)
                it.setObject(2, apiKeyId)
                it.setObject(3, studyId)
                it.setString(4, participantId)
                it.setObject(5, deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.revoked_api_keys
                    (key_id, study_id, participant_id, device_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, apiKeyId)
                it.setObject(2, studyId)
                it.setString(3, participantId)
                it.setObject(4, deviceId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.withdrawn_participants
                    (study_id, participant_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, participantId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_operations (
                    operation_id, study_id, participant_ref, participant_id, mode, status,
                    requested_by, idempotency_key, registry_version, quarantine_until,
                    completed_at, proof_hash, cancelled_by, cancelled_at
                ) VALUES (?, ?, 'participant-source-proof', ?, 'WITHDRAW_AND_ERASE', 'COMPLETED',
                          'mobile-self-withdrawal', ?, 1, '2026-08-20T12:00:00Z',
                          '2026-08-20T12:01:00Z', 'source-proof', NULL, NULL)
                """.trimIndent(),
            ).use {
                it.setObject(1, operationId)
                it.setObject(2, studyId)
                it.setString(3, participantId)
                it.setObject(4, requestId)
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO chronicle_restore_continuity.deletion_tombstones (
                    operation_id, study_ref, participant_ref, mode, registry_version,
                    completed_at, proof_hash
                ) VALUES (?, 'study-source-proof', 'participant-source-proof',
                          'WITHDRAW_AND_ERASE', 1, '2026-08-20T12:01:00Z', 'source-proof')
                """.trimIndent(),
            ).use {
                it.setObject(1, operationId)
                assertEquals(1, it.executeUpdate())
            }
            sealContinuityCheckpoint(connection)
        }
    }

    fun sealContinuityCheckpoint(connection: Connection) {
        val digest = RestoreContinuityReconciler.computeCheckpointSha256(connection)
        connection.prepareStatement(
            """
            INSERT INTO chronicle_restore_continuity.checkpoint (
                contract_version, checkpoint_id, created_at, source_schema_version,
                checkpoint_sha256, withdrawal_receipt_count, revoked_api_key_count,
                withdrawn_participant_count, deletion_operation_count, source_tombstone_count,
                collection_revision_count, published_collection_settings_count,
                enrollment_invitation_count, erased_device_key_tombstone_count,
                restricted_participant_count, archived_study_count, purge_cutoff_count, collection_acknowledgment_count
            ) SELECT ${RestoreContinuityReconciler.CONTRACT_VERSION}, ?, '2026-08-20T12:02:00Z', '100', ?,
                     (SELECT count(*) FROM chronicle_restore_continuity.withdrawal_requests),
                     (SELECT count(*) FROM chronicle_restore_continuity.revoked_api_keys),
                     (SELECT count(*) FROM chronicle_restore_continuity.withdrawn_participants),
                     (SELECT count(*) FROM chronicle_restore_continuity.deletion_operations),
                     (SELECT count(*) FROM chronicle_restore_continuity.deletion_tombstones),
                     (SELECT count(*) FROM chronicle_restore_continuity.data_collection_settings_revisions),
                     (SELECT count(*) FROM chronicle_restore_continuity.published_data_collection_settings),
                     (SELECT count(*) FROM chronicle_restore_continuity.enrollment_invitations),
                     (SELECT count(*) FROM chronicle_restore_continuity.erased_device_key_tombstones),
                     (SELECT count(*) FROM chronicle_restore_continuity.restricted_participants),
                     (SELECT count(*) FROM chronicle_restore_continuity.archived_studies),
                     (SELECT count(*) FROM chronicle_restore_continuity.participant_purge_cutoffs),
                     (SELECT count(*) FROM chronicle_restore_continuity.participant_collection_acknowledgment)
            """.trimIndent(),
        ).use {
            it.setObject(1, checkpointId)
            it.setString(2, digest)
            assertEquals(1, it.executeUpdate())
        }
    }

    fun createContinuitySchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA chronicle_restore_continuity")
            statement.execute(RestoreContinuityReconciler.restoreResource("restore-restrictions-schema.sql"))
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.checkpoint (
                    contract_version INTEGER NOT NULL,
                    checkpoint_id UUID PRIMARY KEY,
                    created_at TIMESTAMPTZ NOT NULL,
                    source_schema_version TEXT NOT NULL,
                    checkpoint_sha256 TEXT NOT NULL,
                    withdrawal_receipt_count BIGINT NOT NULL,
                    revoked_api_key_count BIGINT NOT NULL,
                    withdrawn_participant_count BIGINT NOT NULL,
                    deletion_operation_count BIGINT NOT NULL,
                    source_tombstone_count BIGINT NOT NULL,
                    collection_revision_count BIGINT NOT NULL,
                    published_collection_settings_count BIGINT NOT NULL,
                    enrollment_invitation_count BIGINT NOT NULL,
                    erased_device_key_tombstone_count BIGINT NOT NULL,
                    restricted_participant_count BIGINT NOT NULL,
                    archived_study_count BIGINT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.withdrawal_requests (
                    request_id UUID PRIMARY KEY, api_key_id UUID NOT NULL UNIQUE,
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    device_id UUID NOT NULL, already_withdrawn BOOLEAN NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.revoked_api_keys (
                    key_id UUID PRIMARY KEY, study_id UUID NOT NULL,
                    participant_id TEXT, device_id UUID
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.withdrawn_participants (
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    PRIMARY KEY (study_id, participant_id)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.deletion_operations (
                    operation_id UUID PRIMARY KEY, study_id UUID NOT NULL,
                    participant_ref TEXT, participant_id TEXT,
                    -- What V68 derives in public; these fixtures never seed a completed participant shape.
                    participant_block_token TEXT GENERATED ALWAYS AS
                        (md5(study_id::text || ':' || participant_id)) STORED,
                    mode TEXT NOT NULL,
                    status TEXT NOT NULL, requested_by TEXT NOT NULL,
                    idempotency_key UUID NOT NULL UNIQUE, registry_version INTEGER NOT NULL,
                    quarantine_until TIMESTAMPTZ, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
                    proof_hash TEXT, cancelled_by TEXT, cancelled_at TIMESTAMPTZ
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.retention_holds (
                    hold_id UUID PRIMARY KEY, operation_id UUID NOT NULL,
                    study_id UUID NOT NULL, reason TEXT NOT NULL, created_by TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL, review_at TIMESTAMPTZ NOT NULL,
                    released_by TEXT, released_at TIMESTAMPTZ, release_reason TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.deletion_tombstones (
                    operation_id UUID PRIMARY KEY, study_ref TEXT NOT NULL,
                    participant_ref TEXT, mode TEXT NOT NULL, registry_version INTEGER NOT NULL,
                    completed_at TIMESTAMPTZ NOT NULL, proof_hash TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.data_collection_settings_revisions (
                    study_id UUID NOT NULL, settings_version INTEGER NOT NULL,
                    setting JSONB NOT NULL, issued_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.published_data_collection_settings (
                    study_id UUID NOT NULL, settings_version INTEGER NOT NULL,
                    setting JSONB NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.enrollment_invitations (
                    access_code_id UUID NOT NULL, token_hash BYTEA NOT NULL,
                    study_id UUID NOT NULL, participant_id TEXT NOT NULL,
                    form_kind TEXT NOT NULL, resource_id UUID, logical_date DATE,
                    issuer_type TEXT NOT NULL, issued_by TEXT NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL, exchanged_at TIMESTAMPTZ,
                    revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
                    enrollment_attempt_id UUID, enrollment_source_device_hash TEXT,
                    enrollment_device_id UUID, enrollment_manifest_digest TEXT,
                    enrollment_request_hash TEXT, enrollment_proposed_key_hash TEXT,
                    enrollment_replay_expires_at TIMESTAMPTZ,
                    enrollment_settings_version INTEGER, enrollment_disclosure_version TEXT,
                    enrollment_enabled_modules JSONB, enrollment_required_modules JSONB
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE chronicle_restore_continuity.erased_device_key_tombstones (
                    key_hash TEXT PRIMARY KEY, kind TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(RestoreContinuityReconciler.restoreResource("restore-authorities-schema.sql"))
        }
    }

    fun schemaExists(schema: String): Boolean =
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("SELECT to_regnamespace(?) IS NOT NULL").use {
                it.setString(1, schema)
                it.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getBoolean(1)
                }
            }
        }

    fun participationStatus(connection: Connection): String =
        connection.prepareStatement(
            "SELECT participation_status FROM study_participants WHERE study_id = ? AND participant_id = ?",
        ).use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString(1)
            }
        }

    fun count(connection: Connection, table: String, column: String, value: Any, extra: String = ""): Long {
        require(table.matches(Regex("^[a-z_]+$")))
        require(column.matches(Regex("^[a-z_]+$")))
        return connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?$extra").use {
            it.setObject(1, value)
            it.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }
    }
}
