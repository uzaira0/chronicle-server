-- =============================================================================
-- ambient_audio_events table — ambient_audio collection module (iOS SoundAnalysis)
-- =============================================================================
-- Stores one row per AmbientAudioClassificationEvent
-- (com.openlattice.chronicle.collection.AmbientAudioClassificationEvent) uploaded by the
-- `ambient_audio` module, scoped to a study + participant.
--
-- AMBIENT_AUDIO_CONTEXT-class data — labels-only by construction: sound is classified ON
-- DEVICE in short duty-cycled microphone windows and immediately discarded; each row carries
-- only a sound-class label (music / speech / television / …) with a confidence score and the
-- listen-window bounds. No recording, transcript, voice, or waveform exists anywhere in the
-- pipeline. This is deliberately a DIFFERENT module (and table) from the mic-free
-- app_audio_activity, which never touches the microphone.
--
-- The table is normally created by the rhizome PostgresTables framework from the
-- AMBIENT_AUDIO_EVENTS PostgresTableDefinition in ChroniclePostgresTables.kt. This migration
-- CREATEs it defensively (IF NOT EXISTS) so a fresh database is consistent regardless of
-- migration/framework ordering, and — like V34 — retrofits Row-Level Security so
-- ambient_audio_events is study-isolated exactly like the other collection tables.
--
-- event_id is the per-event de-duplication key (AmbientAudioClassificationEvent.id is a
-- free-form String). The (study_id, participant_id, event_id) primary key makes re-uploads
-- idempotent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ambient_audio_events (
    study_id             UUID             NOT NULL,
    participant_id       TEXT             NOT NULL,
    event_id             TEXT             NOT NULL,
    sample_timestamp     TIMESTAMPTZ      NOT NULL,
    timezone             TEXT             NOT NULL,
    window_start_millis  BIGINT           NOT NULL,
    window_end_millis    BIGINT           NOT NULL,
    label                TEXT             NOT NULL,
    confidence           DOUBLE PRECISION NOT NULL,
    classifier_version   TEXT,
    uploaded_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, participant_id, event_id)
);

-- Index supporting RLS-filtered, study/participant-scoped time-range queries.
CREATE INDEX IF NOT EXISTS idx_ambient_audio_events_study_participant_ts
    ON ambient_audio_events (study_id, participant_id, sample_timestamp);

-- =============================================================================
-- Enable Row-Level Security (study isolation) — mirrors V34__add_app_audio_activity
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ambient_audio_events') THEN
        ALTER TABLE ambient_audio_events ENABLE ROW LEVEL SECURITY;
        ALTER TABLE ambient_audio_events FORCE ROW LEVEL SECURITY;

        DROP POLICY IF EXISTS study_isolation_ambient_audio_events ON ambient_audio_events;
        CREATE POLICY study_isolation_ambient_audio_events ON ambient_audio_events
            FOR ALL
            USING (chronicle_has_study_access(study_id))
            WITH CHECK (chronicle_has_study_access(study_id));

        -- Deletion-quarantine coverage (V50 registry extension): ambient_audio_events is the
        -- first new participant-data table created after V50 swept the existing tables, so it
        -- must add its own RESTRICTIVE quarantine policy. Keep the pinned policy count in
        -- FlywayMigrationCorpusTest.testDeletionLedgerAndParticipantAccess in sync.
        IF EXISTS (
            SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = 'public' AND p.proname = 'chronicle_participant_data_visible'
        ) THEN
            DROP POLICY IF EXISTS deletion_quarantine_ambient_audio_events ON ambient_audio_events;
            CREATE POLICY deletion_quarantine_ambient_audio_events ON ambient_audio_events
                AS RESTRICTIVE FOR SELECT
                USING (chronicle_participant_data_visible(study_id, participant_id));
        END IF;
    END IF;
END $$;

-- =============================================================================
-- Record migration completion
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'upgrades') THEN
        INSERT INTO upgrades (upgrade_class, upgrade_status, last_update)
        VALUES ('V65__add_ambient_audio_events', 'Complete', NOW())
        ON CONFLICT (upgrade_class) DO UPDATE SET upgrade_status = 'Complete', last_update = NOW();
    END IF;
END $$;
