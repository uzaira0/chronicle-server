package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class UploadDiagnosticsMigrationTest {
    private val migration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V99__add_upload_diagnostics.sql"),
    ).bufferedReader().use { it.readText() }
    private val minimizationMigration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V101__minimize_upload_diagnostics.sql"),
    ).bufferedReader().use { it.readText() }

    @Test
    fun `migration enforces isolation quarantine retention identity and idempotency`() {
        assertTrue("PRIMARY KEY (study_id, participant_id, device_id, event_id)" in migration)
        assertTrue("FORCE ROW LEVEL SECURITY" in migration)
        assertTrue("chronicle_has_study_access(study_id)" in migration)
        assertTrue("deletion_quarantine_upload_diagnostics" in migration)
        assertTrue("chronicle_participant_data_visible(study_id, participant_id)" in migration)
        assertTrue("last_occurred_at >= first_occurred_at" in migration)
    }

    @Test
    fun `follow-up migration removes redundant origin and unrestricted error detail`() {
        assertTrue("DROP COLUMN IF EXISTS server_origin" in minimizationMigration)
        assertTrue("DROP COLUMN IF EXISTS error_message" in minimizationMigration)
    }
}
