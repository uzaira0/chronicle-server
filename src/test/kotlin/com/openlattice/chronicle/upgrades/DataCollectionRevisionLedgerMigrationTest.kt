package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class DataCollectionRevisionLedgerMigrationTest {
    @Test
    fun currentSchemaContainsDurableAuthorityFields() {
        val columns = migrationColumnNames("data_collection_settings_revisions")
        assertTrue(columns.containsAll(setOf("study_id", "settings_version", "setting", "issued_at")))
    }
}
