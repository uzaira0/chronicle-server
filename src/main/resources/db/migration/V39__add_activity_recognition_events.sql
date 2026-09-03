-- =============================================================================
-- activity_recognition_events table — Android `activity_recognition` collection module
-- =============================================================================
-- Stores one row per AndroidActivityRecognitionEvent
-- (com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent) uploaded by the
-- Android `activity_recognition` collection module, scoped to a study + participant.
--
-- BEHAVIORAL_METADATA-class data (default OFF) — content-free by construction: each row
-- carries the detected activity label + confidence (and optionally a transition type); no
-- raw sensor stream and no location ever leave the device.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- ACTIVITY_RECOGNITION_EVENTS PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) and — like V31 — retrofits Row-Level
-- Security so the table is study-isolated like the other Android collection tables.
--
-- event_id is the per-event de-duplication key (AndroidActivityRecognitionEvent.id is a
-- free-form String). The (study_id, participant_id, event_id) PK makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS activity_recognition_events (
    study_id           UUID        NOT NULL,
    participant_id     TEXT        NOT NULL,
    event_id           TEXT        NOT NULL,
    sample_timestamp   TIMESTAMPTZ NOT NULL,
    timezone           TEXT        NOT NULL,
    activity_type      TEXT        NOT NULL,
    confidence         INTEGER     NOT NULL,
    transition_type    TEXT,
    uploaded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_activity_recognition_events_study_participant_ts
    ON activity_recognition_events (study_id, participant_id, sample_timestamp);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'activity_recognition_events') THEN
        ALTER TABLE activity_recognition_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE activity_recognition_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_activity_recognition_events ON activity_recognition_events;
        CREATE POLICY study_isolation_activity_recognition_events ON activity_recognition_events
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V39__add_activity_recognition_events', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
