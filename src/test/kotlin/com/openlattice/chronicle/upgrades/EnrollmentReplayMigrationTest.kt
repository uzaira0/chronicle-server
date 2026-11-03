package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentReplayMigrationTest {
    @Test
    fun currentSchemaContainsDurableAuthorityFields() {
        val columns = migrationColumnNames("participant_form_access_codes")
        assertTrue(columns.containsAll(setOf("enrollment_attempt_id", "enrollment_source_device_hash",
            "enrollment_random_device_id", "enrollment_manifest_digest", "enrollment_request_hash", "enrollment_proposed_key_hash",
            "enrollment_replay_expires_at")))
        assertTrue(columns.none { it == "proposed_api_key" || it == "raw_api_key" })
    }
}
