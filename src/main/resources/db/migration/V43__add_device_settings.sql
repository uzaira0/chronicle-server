-- =============================================================================
-- device_settings table — Android `device_settings` collection module
-- =============================================================================
-- Stores one row per AndroidDeviceSettingsEvent
-- (com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent) uploaded by the Android
-- `device_settings` collection module, scoped to a study + participant.
--
-- DEVICE_STATE_METADATA-class data (default OFF) — a content-free, identity-free snapshot of
-- display / sound / accessibility / system toggles. Nothing here reveals what the participant
-- does, only how the device is configured. All descriptive columns are nullable so a partial
-- snapshot (a value unavailable on a given OS/OEM) still persists.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- DEVICE_SETTINGS PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) and — like V31 — retrofits Row-Level Security so the
-- table is study-isolated like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidDeviceSettingsEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) PK makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS device_settings (
    study_id                   UUID             NOT NULL,
    participant_id             TEXT             NOT NULL,
    event_id                   TEXT             NOT NULL,
    sample_timestamp           TIMESTAMPTZ      NOT NULL,
    timezone                   TEXT             NOT NULL,
    dark_mode                  BOOLEAN,
    font_scale                 DOUBLE PRECISION,
    accessibility_enabled      BOOLEAN,
    dnd_active                 BOOLEAN,
    battery_saver              BOOLEAN,
    thermal_status             TEXT,
    auto_rotate                BOOLEAN,
    location_services_enabled  BOOLEAN,
    storage_free_bytes         BIGINT,
    storage_total_bytes        BIGINT,
    uploaded_at                TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_device_settings_study_participant_ts
    ON device_settings (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'device_settings') THEN
        ALTER TABLE device_settings ENABLE ROW LEVEL SECURITY;
        ALTER TABLE device_settings FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_device_settings ON device_settings;
        CREATE POLICY study_isolation_device_settings ON device_settings
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V43__add_device_settings', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
