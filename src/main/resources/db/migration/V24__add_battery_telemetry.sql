-- =============================================================================
-- battery_telemetry table — Android battery-telemetry collection module
-- =============================================================================
-- Stores one row per BatterySample (com.openlattice.chronicle.collection.BatterySample)
-- uploaded by the Android `:collection-battery` module, scoped to a study + participant.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- BATTERY_TELEMETRY PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent
-- regardless of migration/framework ordering, and — like V1 — retrofits Row-Level
-- Security so battery_telemetry is study-isolated exactly like the other Android
-- collection tables (android_sensor_data, chronicle_usage_events, upload_buffer).
--
-- sample_id is the per-sample de-duplication key (BatterySample.id is a free-form
-- String, not a UUID). The (study_id, participant_id, sample_id) primary key makes
-- re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS battery_telemetry (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    sample_id           TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    level_percent       INTEGER     NOT NULL,
    charging_state      TEXT        NOT NULL,
    plug_type           TEXT        NOT NULL,
    health              TEXT        NOT NULL,
    temperature_deci_c  INTEGER     NOT NULL,
    voltage_millivolts  INTEGER     NOT NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, sample_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_battery_telemetry_study_participant_ts
    ON battery_telemetry (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V1__enable_row_level_security
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'battery_telemetry') THEN
        ALTER TABLE battery_telemetry ENABLE ROW LEVEL SECURITY;
        ALTER TABLE battery_telemetry FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_battery_telemetry ON battery_telemetry;
        CREATE POLICY study_isolation_battery_telemetry ON battery_telemetry
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V24__add_battery_telemetry', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
