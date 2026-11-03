package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeCollectionAcknowledgmentMigrationTest {
    @Test
    fun currentSchemaContainsDurableAuthorityFields() {
        val columns = migrationColumnNames("participant_collection_acknowledgment")
        assertTrue(columns.containsAll(setOf("unavailable_modules", "evidence_access_code_id", "evidence_api_key_id",
            "settings_version", "disclosure_version", "manifest_digest")))
    }
}
