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
    private val localDropMigration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V106__add_local_drop_diagnostic_codes.sql"),
    ).bufferedReader().use { it.readText() }
    private val retainedCatalogMigration = requireNotNull(
        javaClass.getResourceAsStream("/db/migration/V107__retain_and_extend_upload_diagnostics.sql"),
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
    fun `local drop codes are accepted by the model and the table, and V106 keeps every V104 code`() {
        listOf(
            "SENSOR" to "SENSOR_AGE_EXPIRED",
            "SENSOR" to "SENSOR_CAPACITY_DROPPED",
            "USAGE_LIFECYCLE" to "USAGE_QUEUE_EVICTED",
        ).forEach { (family, code) ->
            event(family, code)
            assertTrue(code, "'$code'" in localDropMigration)
        }
        Regex("""'([A-Z_]+)'""").findAll(widenMigration.substringAfter("issue_code_check")).forEach {
            assertTrue(it.groupValues[1], "'${it.groupValues[1]}'" in localDropMigration)
        }
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

    @Test
    fun `every shared catalog family and issue code is accepted by model and V107`() {
        val families = listOf(
            "USAGE_LIFECYCLE", "BATTERY", "DEVICE_TELEMETRY", "SENSOR", "APP_RUNTIME",
            "INTERACTION", "AUDIO_ACTIVITY", "AUDIO_CONTENT", "NOTIFICATION", "SLEEP",
            "ACTIVITY_RECOGNITION", "HEALTH", "CONNECTIVITY", "APP_NETWORK", "DEVICE_SETTINGS", "LOCAL_STORE",
        )
        val codes = listOf(
            "DESTINATION_MISSING", "DESTINATION_IDENTITY_MISMATCH", "DESTINATION_SOURCE_DEVICE_MISSING",
            "DESTINATION_SETUP_INCOMPLETE", "DESTINATION_DISABLED", "DESTINATION_NONCANONICAL",
            "DESTINATION_CREDENTIAL_INCOMPLETE", "HTTP_SERVER_ERROR", "HTTP_CLIENT_ERROR", "TIMEOUT",
            "DNS_FAILURE", "TLS_FAILURE", "CONNECTION_FAILURE", "UPLOAD_FAILURE", "SENSOR_SAMPLE_QUARANTINED",
            "SENSOR_DEAD_LETTER_DROPPED", "APP_CRASH", "APP_CRASH_NATIVE", "APP_ANR", "SENSOR_AGE_EXPIRED",
            "SENSOR_CAPACITY_DROPPED", "USAGE_QUEUE_EVICTED", "SAMPLE_QUARANTINED", "LOCAL_BUFFER_OVERFLOW",
            "LOCAL_REQUEUE_OVERFLOW", "LOCAL_WRITE_FAILED", "LOCAL_SHUTDOWN_DROPPED", "COLLECTION_GATE_DROPPED",
            "MODULE_POLICY_ERASED", "DISTRIBUTION_POLICY_ERASED", "DIRECT_BOOT_CAPACITY_DROPPED",
            "DIRECT_BOOT_CORRUPT_RECORD", "COLLECTION_PAUSED_STORAGE",
        )
        families.forEach { family ->
            assertTrue(family, "'$family'" in retainedCatalogMigration)
            codes.forEach { code ->
                event(family, code)
                assertTrue(code, "'$code'" in retainedCatalogMigration)
            }
        }
        assertTrue("ON upload_diagnostics (study_id, participant_id, diagnostic_day)" in retainedCatalogMigration)
    }
}
