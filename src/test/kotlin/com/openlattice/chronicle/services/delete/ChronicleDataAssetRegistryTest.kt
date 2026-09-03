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

        ChronicleDataAssetRegistry.participantAssets.forEach { asset ->
            assertTrue(
                "No migration defines the deletion-quarantine policy for ${asset.tableName} " +
                    "(V50 registry literal or a later deletion_quarantine_${asset.tableName} policy)",
                "'${asset.tableName}'" in v50 ||
                    "deletion_quarantine_${asset.tableName}" in corpus,
            )
        }
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
