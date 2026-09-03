-- =============================================================================
-- connectivity_state_events table — Android `connectivity_state` collection module
-- =============================================================================
-- Stores one row per AndroidConnectivityStateEvent
-- (com.openlattice.chronicle.collection.AndroidConnectivityStateEvent) uploaded by the
-- Android `connectivity_state` collection module, scoped to a study + participant.
--
-- DEVICE_STATE_METADATA-class data (default OFF) — derived from ConnectivityManager /
-- NetworkCapabilities: the active transport plus metered and validated-internet flags.
-- No SSID, BSSID, IP, or cell identifiers are captured (those would be a location proxy).
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- CONNECTIVITY_STATE_EVENTS PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) and — like V31 — retrofits Row-Level
-- Security so the table is study-isolated like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidConnectivityStateEvent.id is a
-- free-form String). The (study_id, participant_id, event_id) PK makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS connectivity_state_events (
    study_id          UUID        NOT NULL,
    participant_id    TEXT        NOT NULL,
    event_id          TEXT        NOT NULL,
    sample_timestamp  TIMESTAMPTZ NOT NULL,
    timezone          TEXT        NOT NULL,
    event_type        TEXT        NOT NULL,
    transport         TEXT        NOT NULL,
    connected         BOOLEAN     NOT NULL,
    metered           BOOLEAN,
    validated         BOOLEAN,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_connectivity_state_events_study_participant_ts
    ON connectivity_state_events (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'connectivity_state_events') THEN
        ALTER TABLE connectivity_state_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE connectivity_state_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_connectivity_state_events ON connectivity_state_events;
        CREATE POLICY study_isolation_connectivity_state_events ON connectivity_state_events
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V41__add_connectivity_state_events', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
