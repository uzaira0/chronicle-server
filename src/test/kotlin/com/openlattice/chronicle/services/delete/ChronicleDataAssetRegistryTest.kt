package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.deletion.DeleteParticipantRegisteredAssetData
import com.openlattice.chronicle.controllers.TestSecurityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class ChronicleDataAssetRegistryTest {
    @Before
    fun authenticate() {
        TestSecurityUtils.setupSecurityContext()
    }

    @After
    fun clearAuthentication() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun registryContainsPreviouslyOmittedParticipantAssets() {
        val tables = ChronicleDataAssetRegistry.participantAssets.map { it.tableName }.toSet()

        assertTrue("encrypted_payloads" in tables)
        assertTrue("data_quality_alerts" in tables)
        assertTrue("time_use_diary_summarized" in tables)
        assertTrue("study_event_stream" in tables)
        assertTrue("android_device_sensor_availability" in tables)
    }

    @Test
    fun accessArtifactsAreVerifiedBeforeTheirCascadingParent() {
        val ids = ChronicleDataAssetRegistry.participantAssets.map { it.id }

        assertTrue(ids.indexOf("participant-form-receipts") < ids.indexOf("participant-form-access-codes"))
        assertTrue(ids.indexOf("participant-form-sessions") < ids.indexOf("participant-form-access-codes"))
    }

    @Test
    fun deletionQuarantinePolicyCoversEveryRegisteredAsset() {
        // V50 swept the then-existing tables; tables added after it (e.g. V65's
        // ambient_audio_events) must ship their own deletion_quarantine_<table>
        // policy in their own migration — applied migrations are immutable under
        // Flyway, so the whole corpus is scanned, not just V50.
        val v50 = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V50__participant_access_deletion_ledger.sql")
        ).bufferedReader().use { it.readText() }
        assertTrue("Quarantine must be a restrictive RLS policy", "AS RESTRICTIVE FOR SELECT" in v50)

        val migrationDir = sequenceOf(
            File("src/main/resources/db/migration"),
            File("chronicle-server/src/main/resources/db/migration"),
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate db/migration from cwd=${File(".").absolutePath}")
        val corpus = migrationDir.listFiles { f -> f.extension == "sql" }!!
            .joinToString("\n") { it.readText() }

        // Opaque replay identities use trigger-only INSERT and deletion-worker DELETE policies.
        ChronicleDataAssetRegistry.participantAssets.filterNot {
            it.participantScope == ParticipantScope.BLOCK_TOKEN_COLUMN
        }.forEach { asset ->
            assertTrue(
                "No migration defines the deletion-quarantine policy for ${asset.tableName} " +
                    "(V50 registry literal or a later deletion_quarantine_${asset.tableName} policy)",
                "'${asset.tableName}'" in v50 ||
                    "deletion_quarantine_${asset.tableName}" in corpus,
            )
        }
    }

    @Test
    fun everyRegisteredParticipantTableExceptJobsHasBothMutationGuards() {
        val migrationDir = sequenceOf(
            File("src/main/resources/db/migration"),
            File("chronicle-server/src/main/resources/db/migration"),
        ).first { it.isDirectory }
        val corpus = migrationDir.listFiles { file -> file.extension == "sql" }!!
            .joinToString("\n") { it.readText() }
        val v68 = File(migrationDir, "V68__make_deletion_proofs_observable_and_stable.sql").readText()
        val guardedByV68 = Regex("'([a-z_]+)'")
            .findAll(v68.substringAfter("FOREACH table_name IN ARRAY ARRAY[").substringBefore("] LOOP"))
            .map { it.groupValues[1] }
            .toSet()

        ChronicleDataAssetRegistry.participantAssets.filterNot {
            it.tableName == "jobs" || it.participantScope == ParticipantScope.BLOCK_TOKEN_COLUMN
        }.forEach { asset ->
            if (asset.tableName in guardedByV68) return@forEach
            for (operation in listOf("insert", "update")) {
                val trigger = Regex(
                    """CREATE TRIGGER deletion_mutation_guard_$operation\s+AFTER ${operation.uppercase()} ON ${asset.tableName}\b""",
                    RegexOption.IGNORE_CASE,
                )
                assertTrue("Missing $operation guard on ${asset.tableName}", trigger.containsMatchIn(corpus))
            }
        }
    }

    @Test
    fun everyMigrationTableWithAParticipantIdIsDeletedOrDeliberatelyRetained() {
        // Participant tables carry no FK to study_participants, so participant deletion reaches
        // only what this registry names. usage_event_annotations and participant_pseudonyms were
        // missed until V105; this scan catches the next one.
        val retained = mapOf(
            "api_keys" to "revoked credential kept as withdrawal replay evidence (V90)",
            "mobile_withdrawal_requests" to "withdrawal receipt kept for replay (V90)",
            "data_deletion_operations" to "the deletion ledger itself",
        )
        val migrationDir = sequenceOf(
            File("src/main/resources/db/migration"),
            File("chronicle-server/src/main/resources/db/migration"),
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate db/migration from cwd=${File(".").absolutePath}")
        val corpus = migrationDir.listFiles { f -> f.extension == "sql" }!!
            .joinToString("\n") { it.readText() }
        val created = Regex(
            """CREATE TABLE(?: IF NOT EXISTS)?\s+(?:public\.)?"?(\w+)"?\s*\((.*?)\)\s*(?:USING\s+\w+\s*)?(?:PARTITION BY [^;]*)?;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(corpus)
            .filter { Regex("""\bparticipant_id\b""").containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1].lowercase() }
        val added = Regex(
            """ALTER TABLE(?: IF EXISTS)?\s+(?:ONLY\s+)?(?:public\.)?"?(\w+)"?\s+ADD COLUMN(?: IF NOT EXISTS)?\s+participant_id\b""",
            RegexOption.IGNORE_CASE,
        ).findAll(corpus).map { it.groupValues[1].lowercase() }
        val participantTables = (created + added).toSet()
        assertTrue("scan found no participant tables", "usage_event_annotations" in participantTables)

        val registered = ChronicleDataAssetRegistry.participantAssets.map { it.tableName }.toSet()
        assertEquals(emptySet<String>(), participantTables - registered - retained.keys)
    }

    @Test
    fun kotlinDefinedParticipantTablesAreDeletedOrDeliberatelyRetained() {
        val retained = setOf("study_participants", "api_keys", "participant_collection_acknowledgment")
        val registered = (ChronicleDataAssetRegistry.participantAssets + ChronicleDataAssetRegistry.withdrawalAssets)
            .map { it.tableName }.toSet()
        val tables = listOf(
            com.openlattice.chronicle.storage.ChroniclePostgresTables::class.java,
            com.openlattice.chronicle.storage.PostgresEventTables::class.java,
        ).flatMap { owner -> owner.fields.mapNotNull { field ->
            field.get(null) as? com.geekbeast.postgres.PostgresTableDefinition
        } }.filter { table -> table.columns.any { it.name == "participant_id" } }
            .map { it.name.lowercase() }.toSet()
        assertTrue("Kotlin inventory must include devices", "devices" in tables)
        assertEquals(emptySet<String>(), tables - registered - retained)
    }

    @Test
    fun deletionPlanCreatesExactlyOneJobPerRegisteredAsset() {
        var ordinal = 0L
        val jobs = ParticipantDeletionPlan.jobs(
            studyId = UUID.randomUUID(),
            participantIds = setOf("participant:test"),
            contact = "test",
            nextJobId = { UUID(0, ++ordinal) },
        )

        assertEquals(ChronicleDataAssetRegistry.participantAssets.size, jobs.size)
        val genericAssetIds = jobs.mapNotNull { job ->
            (job.definition as? DeleteParticipantRegisteredAssetData)?.assetId
        }.toSet()
        assertEquals(
            ChronicleDataAssetRegistry.participantAssets
                .filterNot { it.handledByDedicatedJob }
                .map { it.id }
                .toSet(),
            genericAssetIds,
        )
    }
}
