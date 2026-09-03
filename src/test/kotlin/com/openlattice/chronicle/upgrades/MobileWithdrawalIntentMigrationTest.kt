package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWithdrawalIntentMigrationTest {
    @Test
    fun `migration durably binds one withdrawal request to the exact mobile key tuple`() {
        val sql = checkNotNull(javaClass.getResource("/db/migration/V90__bind_mobile_withdrawal_intents.sql"))
            .readText()

        listOf(
            "mobile_withdrawal_requests",
            "request_id UUID PRIMARY KEY",
            "api_key_id UUID NOT NULL UNIQUE",
            "study_id UUID NOT NULL",
            "participant_id TEXT NOT NULL",
            "device_id UUID NOT NULL",
            "already_withdrawn BOOLEAN NOT NULL",
            "Retained through participant erasure for replay; deleted by full study erasure",
            "intentionally has no FK",
        ).forEach { expected -> assertTrue("missing $expected", sql.contains(expected)) }

        assertFalse("withdrawal evidence must not block credential erasure", sql.contains("REFERENCES api_keys"))
        assertFalse("withdrawal evidence must not impose deletion order", sql.contains("ON DELETE RESTRICT"))
        assertFalse("immutable withdrawal evidence must not have a FOR ALL policy", sql.contains("FOR ALL"))
        assertTrue(sql.contains("FOR SELECT"))
        assertTrue(sql.contains("FOR INSERT"))
        assertFalse(sql.contains("FOR UPDATE"))
        assertFalse(sql.contains("FOR DELETE"))
        assertTrue(sql.contains("REVOKE UPDATE, DELETE, TRUNCATE"))
        assertTrue(sql.contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON mobile_withdrawal_requests FROM chronicle_admin"))
        assertFalse(sql.contains("GRANT SELECT, INSERT ON mobile_withdrawal_requests TO chronicle_admin"))
    }
}
