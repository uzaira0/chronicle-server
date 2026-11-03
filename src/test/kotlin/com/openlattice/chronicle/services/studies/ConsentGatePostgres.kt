package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.contract.seedCandidate

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.mockito.Mockito
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One migrated PostgreSQL, a single-connection pinned pool and the consent-trail writers the collection gate tests share. */
internal class ConsentGatePostgres(name: String) {
    companion object {
        const val PARTICIPANT_ID = "participant-1"
    }

    val postgres: PostgreSQLContainer<*> = ChronicleContractTestSchema.prodPostgresContainer(name)
    private val hds: HikariDataSource
    val storageResolver: StorageResolver
    val service: ParticipantCollectionAcknowledgmentService
    private val mapper = ObjectMappers.getJsonMapper()

    init {
        postgres.start()
        ChronicleContractTestSchema.waitForQueryReady(postgres)
        ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE guard_probe (id UUID PRIMARY KEY, study_id UUID NOT NULL)")
            }
        }
        // maximumPoolSize = 1 proves the guarded write joins the recheck's connection instead
        // of borrowing a second one: without the pin, the nested borrow would block and fail.
        hds = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 1
                connectionTimeout = TimeUnit.SECONDS.toMillis(5)
            },
        )
        storageResolver = object : StorageResolver(
            Mockito.mock(DataSourceManager::class.java),
            Mockito.mock(ChronicleStorageConfiguration::class.java),
        ) {
            override fun getPlatformStorage(requiredFlavor: PostgresFlavor): HikariDataSource =
                PinnedPlatformConnection.resolve(hds)
        }
        service = ParticipantCollectionAcknowledgmentService(storageResolver)
    }

    fun close() {
        hds.close()
        postgres.stop()
    }

    fun seedDecision(connection: java.sql.Connection, study: UUID, device: UUID, key: UUID,
                     decision: CollectionAcknowledgment) =
        seedCollectionDecision(connection, study, PARTICIPANT_ID, device, key, decision)

    /** [sharedDevices] also collects user identification, which the app-usage survey runs on. */
    fun newStudy(required: Boolean, settingsVersion: Int, collection: AndroidDataCollectionSetting? = null,
                 sharedDevices: Boolean = false): UUID {
        val studyId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO studies (study_id, title, settings, modules) " +
                    "VALUES (?, 'atomicity', ?::jsonb, jsonb_build_object(?::text, '{}'::jsonb))",
            ).use { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, settingsJson(required, settingsVersion, collection = collection, sharedDevices = sharedDevices))
                statement.setString(3, StudyFeature.CHRONICLE_DATA_COLLECTION.name)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, 'ENROLLED')").use {
                it.setObject(1, studyId); it.setString(2, PARTICIPANT_ID); it.setObject(3, seedCandidate(connection)); it.executeUpdate()
            }
        }
        return studyId
    }

    fun updateCollectionSettings(
        connection: java.sql.Connection,
        studyId: UUID,
        required: Boolean,
        settingsVersion: Int,
        enabled: Boolean = true,
        collection: AndroidDataCollectionSetting? = null,
    ) {
        connection.prepareStatement("UPDATE studies SET settings = ?::jsonb WHERE study_id = ?").use { statement ->
            statement.setString(1, settingsJson(required, settingsVersion, enabled, collection))
            statement.setObject(2, studyId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun settingsJson(required: Boolean, settingsVersion: Int, enabled: Boolean = true,
                             collection: AndroidDataCollectionSetting? = null, sharedDevices: Boolean = false): String = mapper.writeValueAsString(
        StudySettings(
            mapOf(
                StudySettingType.DataCollection to (collection ?: AndroidDataCollectionSetting(
                    modules = linkedMapOf(
                        CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                            enabled = enabled,
                            required = required,
                        ),
                    ) + listOfNotNull((CollectionModuleId.USER_IDENTIFICATION to CollectionModuleSetting(enabled = true))
                        .takeIf { sharedDevices }),
                    settingsVersion = settingsVersion,
                )),
            ),
        ),
    )

    /** Writes through the same seam every upload service uses, so the pin is what is under test. */
    fun insertProbe(probeId: UUID, studyId: UUID) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement("INSERT INTO guard_probe (id, study_id) VALUES (?, ?)").use { statement ->
                statement.setObject(1, probeId)
                statement.setObject(2, studyId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    fun probeExists(probeId: UUID): Boolean = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT 1 FROM guard_probe WHERE id = ?").use { statement ->
            statement.setObject(1, probeId)
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }
    }

    fun diagnosticStoredOnce(studyId: UUID, eventId: String): Boolean = postgres.createConnection("").use { connection ->
        connection.prepareStatement("SELECT count(*) FROM upload_diagnostics WHERE study_id = ? AND event_id = ?").use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, eventId)
            statement.executeQuery().use { resultSet -> resultSet.next() && resultSet.getLong(1) == 1L }
        }
    }
}

/** Consent evidence for a participant/device, shared by upload and collection-gate integration tests. */
internal fun seedCollectionDecision(connection: java.sql.Connection, study: UUID, participantId: String,
                                    device: UUID, key: UUID, decision: CollectionAcknowledgment) {
    val mapper = ObjectMappers.getJsonMapper()
    connection.prepareStatement("""
        INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, scope, created_by,
            expires_at, participant_id, random_device_id)
        VALUES (?, ?, ?, 'test', 'WRITE', 'atomicity', now() + interval '1 day', ?, ?)
        ON CONFLICT (key_id) DO NOTHING
    """.trimIndent()).use {
        it.setObject(1, key); it.setObject(2, study); it.setString(3, key.toString())
        it.setString(4, participantId); it.setObject(5, device); it.executeUpdate()
    }
    connection.prepareStatement("""
        INSERT INTO participant_collection_acknowledgment (id, study_id, participant_id,
            source_random_device_id, acknowledged_modules, declined_modules, acknowledged_at, settings_version,
            evidence_access_code_id, evidence_api_key_id, collection_trigger, decision_seq)
        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, COALESCE(?::bigint, (SELECT COALESCE(max(decision_seq), 0) + 1 FROM participant_collection_acknowledgment WHERE evidence_api_key_id = ?)))
    """.trimIndent()).use {
        it.setObject(1, UUID.randomUUID()); it.setObject(2, study); it.setString(3, participantId)
        it.setString(4, device.toString())
        it.setString(5, mapper.writeValueAsString(decision.acknowledgedModules.map { module -> module.id }))
        it.setString(6, mapper.writeValueAsString(decision.declinedModules.map { module -> module.id }))
        it.setObject(7, decision.acknowledgedAt)
        it.setInt(8, checkNotNull(decision.settingsVersion)); it.setObject(9, UUID.randomUUID()); it.setObject(10, key)
        it.setString(11, decision.trigger.name)
        it.setObject(12, decision.decisionSeq, java.sql.Types.BIGINT); it.setObject(13, key); it.executeUpdate()
    }
}
