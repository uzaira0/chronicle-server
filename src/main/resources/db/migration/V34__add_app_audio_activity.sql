-- =============================================================================
-- app_audio_activity table — Android audio_activity collection module
-- =============================================================================
-- Stores one row per AndroidAudioActivityEvent
-- (com.openlattice.chronicle.collection.AndroidAudioActivityEvent) uploaded by the
-- Android `audio_activity` module, scoped to a study + participant.
--
-- BEHAVIORAL_METADATA-class data — mic-free by construction: each row carries the
-- device's own playback/output state (active/route/volume/ringer/DND/call) and, when a
-- notification-listener grant exists, the active media session's package/content/playback
-- state, but never an audio waveform.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- APP_AUDIO_ACTIVITY PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent
-- regardless of migration/framework ordering, and — like V31 — retrofits Row-Level
-- Security so app_audio_activity is study-isolated exactly like the other Android
-- collection tables.
--
-- event_id is the per-event de-duplication key (AndroidAudioActivityEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS app_audio_activity (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    event_id            TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    event_type          TEXT        NOT NULL,
    audio_active        BOOLEAN     NOT NULL,
    audio_package       TEXT,
    content_type        TEXT,
    playback_state      TEXT,
    output_route        TEXT,
    route_connected     BOOLEAN,
    media_volume        INTEGER,
    max_media_volume    INTEGER,
    ringer_mode         TEXT,
    dnd_active          BOOLEAN,
    call_active         BOOLEAN,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_app_audio_activity_study_participant_ts
    ON app_audio_activity (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V31__add_interaction_events
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'app_audio_activity') THEN
        ALTER TABLE app_audio_activity ENABLE ROW LEVEL SECURITY;
        ALTER TABLE app_audio_activity FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_app_audio_activity ON app_audio_activity;
        CREATE POLICY study_isolation_app_audio_activity ON app_audio_activity
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
        VALUES ('V34__add_app_audio_activity', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
