package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class UploadDiagnosticsMigrationTest {
    private val migration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V99__add_upload_diagnostics.sql"),
    ).bufferedReader().use { it.readText() }
    private val minimizationMigration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V101__minimize_upload_diagnostics.sql"),
    ).bufferedReader().use { it.readText() }
    private val widenMigration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V104__widen_upload_diagnostic_codes.sql"),
    ).bufferedReader().use { it.readText() }

    private fun event(moduleFamily: String, issueCode: String) = AndroidUploadDiagnosticEvent(
        id = UUID.randomUUID().toString(),
        day = LocalDate.parse("2026-09-24"),
        moduleFamily = moduleFamily,
        issueCode = issueCode,
        count = 3,
        firstOccurredAt = OffsetDateTime.parse("2026-09-24T10:00:00Z"),
        lastOccurredAt = OffsetDateTime.parse("2026-09-24T10:00:00Z"),
    )

    @Test
    fun `dead-letter and process-exit codes are accepted by the model and the table`() {
        listOf(
            "SENSOR" to "SENSOR_SAMPLE_QUARANTINED",
            "SENSOR" to "SENSOR_DEAD_LETTER_DROPPED",
            "APP_RUNTIME" to "APP_CRASH",
            "APP_RUNTIME" to "APP_CRASH_NATIVE",
            "APP_RUNTIME" to "APP_ANR",
        ).forEach { (family, code) ->
            event(family, code)
            assertTrue(family, "'$family'" in widenMigration)
            assertTrue(code, "'$code'" in widenMigration)
        }
        assertTrue("'UPLOAD_FAILURE'" in widenMigration)
        assertThrows(IllegalArgumentException::class.java) { event("SENSOR", "RAW_STACK_TRACE") }
    }

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
