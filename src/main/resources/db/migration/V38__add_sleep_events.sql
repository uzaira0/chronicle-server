-- =============================================================================
-- sleep_events table — Android `sleep` collection module
-- =============================================================================
-- Stores one row per AndroidSleepEvent
-- (com.openlattice.chronicle.collection.AndroidSleepEvent) uploaded by the Android
-- `sleep` collection module, scoped to a study + participant.
--
-- HEALTH_METRICS-class data — content-free and mic-free by construction: each row carries
-- a sleep label/confidence and the coarse light/motion levels the Play Services Sleep API
-- classifier reports; no raw sensor stream and no model run on this app.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- SLEEP_EVENTS PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent regardless of
-- migration/framework ordering, and — like V31 — retrofits Row-Level Security so
-- sleep_events is study-isolated exactly like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidSleepEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sleep_events (
    study_id              UUID        NOT NULL,
    participant_id        TEXT        NOT NULL,
    event_id              TEXT        NOT NULL,
    sample_timestamp      TIMESTAMPTZ NOT NULL,
    timezone              TEXT        NOT NULL,
    event_type            TEXT        NOT NULL,
    segment_start_millis  BIGINT,
    segment_end_millis    BIGINT,
    segment_status        TEXT,
    confidence            INTEGER,
    light                 INTEGER,
    motion                INTEGER,
    uploaded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_sleep_events_study_participant_ts
    ON sleep_events (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'sleep_events') THEN
        ALTER TABLE sleep_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE sleep_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_sleep_events ON sleep_events;
        CREATE POLICY study_isolation_sleep_events ON sleep_events
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V38__add_sleep_events', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
