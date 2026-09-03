-- =============================================================================
-- app_audio_content table — Android audio_content collection module
-- =============================================================================
-- Stores one row per AndroidAudioContentEvent
-- (com.openlattice.chronicle.collection.AndroidAudioContentEvent) uploaded by the
-- Android `audio_content` module, scoped to a study + participant.
--
-- MEDIA_CONTENT-class data — *what* the participant is playing: the active media session's
-- title/artist/album published by the producing app, plus its package and playback timing.
-- Still mic-free (no audio waveform). Opt-in, gated behind explicit consent.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- APP_AUDIO_CONTENT PostgresTableDefinition in ChroniclePostgresTables.kt. This
-- migration CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent
-- regardless of migration/framework ordering, and — like V31 — retrofits Row-Level
-- Security so app_audio_content is study-isolated exactly like the other Android
-- collection tables.
--
-- event_id is the per-event de-duplication key (AndroidAudioContentEvent.id is a free-form
-- String). The (study_id, participant_id, event_id) primary key makes re-uploads idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS app_audio_content (
    study_id            UUID        NOT NULL,
    participant_id      TEXT        NOT NULL,
    event_id            TEXT        NOT NULL,
    sample_timestamp    TIMESTAMPTZ NOT NULL,
    timezone            TEXT        NOT NULL,
    audio_package       TEXT        NOT NULL,
    title               TEXT,
    artist              TEXT,
    album               TEXT,
    duration_millis     BIGINT,
    position_millis     BIGINT,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_app_audio_content_study_participant_ts
    ON app_audio_content (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V31__add_interaction_events
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'app_audio_content') THEN
        ALTER TABLE app_audio_content ENABLE ROW LEVEL SECURITY;
        ALTER TABLE app_audio_content FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_app_audio_content ON app_audio_content;
        CREATE POLICY study_isolation_app_audio_content ON app_audio_content
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
        VALUES ('V35__add_app_audio_content', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
