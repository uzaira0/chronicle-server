package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeCollectionAcknowledgmentMigrationTest {
    @Test
    fun `migration binds enrollment consent evidence and unavailable capability modules`() {
        val sql = checkNotNull(
            javaClass.getResource("/db/migration/V91__bind_collection_acknowledgments_to_authority.sql"),
        ).readText()

        listOf(
            "enrollment_settings_version",
            "enrollment_disclosure_version",
            "enrollment_enabled_modules",
            "enrollment_required_modules",
            "unavailable_modules",
            "evidence_access_code_id",
            "evidence_api_key_id",
            "jsonb_typeof",
        ).forEach { expected -> assertTrue("missing $expected", sql.contains(expected)) }
        assertTrue(sql.contains("CREATE UNIQUE INDEX"))
        assertTrue(sql.contains("collection_trigger = 'ENROLLMENT'"))
    }
}
