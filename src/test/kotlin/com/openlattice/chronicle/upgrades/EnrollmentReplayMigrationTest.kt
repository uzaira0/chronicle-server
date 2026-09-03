package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentReplayMigrationTest {
    @Test
    fun `migration persists only replay binding hashes and enforces an all or nothing receipt`() {
        val sql = checkNotNull(javaClass.getResource("/db/migration/V89__bind_replay_safe_enrollment_attempts.sql"))
            .readText()

        listOf(
            "enrollment_attempt_id",
            "enrollment_source_device_hash",
            "enrollment_device_id",
            "enrollment_manifest_digest",
            "enrollment_request_hash",
            "enrollment_proposed_key_hash",
            "enrollment_replay_expires_at",
        ).forEach { assertTrue("missing $it", sql.contains(it)) }
        assertTrue(sql.contains("CHECK"))
        assertFalse(sql.contains("proposed_api_key TEXT"))
        assertFalse(sql.contains("raw_api_key"))
    }
}
