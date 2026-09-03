-- =============================================================================
-- interaction_events table — Android interaction-salience collection module
-- =============================================================================
-- Stores one row per AndroidInteractionEvent
-- (com.openlattice.chronicle.collection.AndroidInteractionEvent) uploaded by the
-- Android `:collection-interaction` module, scoped to a study + participant.
--
-- INTERACTION_METADATA-class data — content-free by construction: each row carries the
-- screen-region grid cell, the element role (view class name), and the foreground
-- package, but never element text or contentDescription.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- INTERACTION_EVENTS PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent
-- regardless of migration/framework ordering, and — like V24 — retrofits Row-Level
-- Security so interaction_events is study-isolated exactly like the other Android
-- collection tables.
--
-- event_id is the per-event de-duplication key (AndroidInteractionEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS interaction_events (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    event_id            TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    event_type          TEXT        NOT NULL,
    grid_rows           INTEGER     NOT NULL,
    grid_cols           INTEGER     NOT NULL,
    grid_row            INTEGER     NOT NULL,
    grid_col            INTEGER     NOT NULL,
    element_role        TEXT        NOT NULL,
    foreground_package  TEXT        NOT NULL,
    scroll_delta_x      INTEGER,
    scroll_delta_y      INTEGER,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_interaction_events_study_participant_ts
    ON interaction_events (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V24__add_battery_telemetry
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'interaction_events') THEN
        ALTER TABLE interaction_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE interaction_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_interaction_events ON interaction_events;
        CREATE POLICY study_isolation_interaction_events ON interaction_events
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
        VALUES ('V31__add_interaction_events', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
