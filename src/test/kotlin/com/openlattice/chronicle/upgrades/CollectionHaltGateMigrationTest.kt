package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHaltGateMigrationTest {
    private val migration = checkNotNull(
        javaClass.classLoader.getResource("db/migration/V94__index_collection_halt_evidence.sql"),
    ).readText()

    @Test
    fun `immutable decision history has an active enrollment gate index`() {
        assertTrue(migration.contains("evidence_api_key_id"))
        assertTrue(migration.contains("settings_version"))
        assertTrue(migration.contains("recorded_at"))
        assertTrue(migration.contains("id"))
        assertFalse(migration.contains("acknowledged_at"))
        assertTrue(migration.contains("WHERE evidence_api_key_id IS NOT NULL"))
    }
}
