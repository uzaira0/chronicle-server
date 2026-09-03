package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DataCollectionRevisionLedgerMigrationTest {
    private val migration = checkNotNull(
        javaClass.classLoader.getResource(
            "db/migration/V93__make_data_collection_revisions_transactional.sql",
        ),
    ).readText()

    @Test
    fun `server issued settings ledger is immutable and transaction bound to study writes`() {
        assertTrue(migration.contains("CREATE TABLE data_collection_settings_revisions"))
        assertTrue(migration.contains("PRIMARY KEY (study_id, settings_version)"))
        assertTrue(migration.contains("AFTER INSERT OR UPDATE OF settings ON studies"))
        assertTrue(migration.contains("record_data_collection_settings_revision"))
        assertTrue(migration.contains("ON CONFLICT (study_id, settings_version) DO NOTHING"))
        assertTrue(migration.contains("ERRCODE = '23505'"))
        assertTrue(migration.contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE"))
        assertTrue(migration.contains("FOR INSERT"))
        assertTrue(migration.contains("pg_catalog.pg_trigger_depth() > 0"))
        assertTrue(migration.contains("SET search_path = pg_catalog, public, pg_temp"))
        assertTrue(migration.contains("TG_RELID IS DISTINCT FROM 'public.studies'::pg_catalog.regclass"))
        assertTrue(migration.contains("TG_OP NOT IN ('INSERT', 'UPDATE')"))
        assertTrue(migration.contains("IF NOT (candidate ? 'settingsVersion') THEN"))
        assertTrue(migration.contains("COALESCE(candidate ->> 'settingsVersion', '') !~"))
        assertTrue(migration.contains("current_user IN ('chronicle_app', 'chronicle_admin')"))
        assertTrue(migration.contains("rolbypassrls"))
        assertTrue(
            migration.contains(
                "REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_app",
            ),
        )
        assertTrue(
            migration.contains(
                "REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_admin",
            ),
        )
        assertFalse(migration.contains("GRANT INSERT ON data_collection_settings_revisions TO chronicle_app"))
        assertFalse(migration.contains("GRANT INSERT ON data_collection_settings_revisions TO chronicle_admin"))
    }

    @Test
    fun `legacy audit backfill admits only unambiguous historical revisions`() {
        assertTrue(migration.contains("HAVING count(DISTINCT setting) = 1"))
        assertTrue(migration.contains("HAVING count(DISTINCT setting) > 1"))
        assertTrue(migration.contains("Ambiguous legacy DataCollection revision evidence"))
        assertTrue(migration.contains("Current DataCollection revision conflicts"))
        assertTrue(migration.contains("FROM ambiguous"))
        assertTrue(migration.contains("JOIN studies AS study"))

        val ambiguityGate = migration.indexOf("Ambiguous legacy DataCollection revision evidence")
        val currentRevisionInsert = migration.indexOf("Always register the currently published revision")
        assertTrue("ambiguity must abort before current settings can become authority", ambiguityGate in 0 until currentRevisionInsert)
    }
}
