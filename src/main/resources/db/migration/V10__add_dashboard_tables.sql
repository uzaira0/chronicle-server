-- V10: Real-time Dashboard Support

-- Rolling window stats (updated periodically)
CREATE TABLE IF NOT EXISTS study_realtime_stats (
    study_id                UUID NOT NULL PRIMARY KEY,
    active_participants_24h INTEGER NOT NULL DEFAULT 0,
    data_submissions_24h    BIGINT NOT NULL DEFAULT 0,
    total_participants      INTEGER NOT NULL DEFAULT 0,
    last_data_received      TIMESTAMPTZ,
    submissions_by_type     JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Event stream (24h ring buffer, auto-cleaned)
CREATE TABLE IF NOT EXISTS study_event_stream (
    event_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    study_id        UUID NOT NULL,
    event_type      TEXT NOT NULL,
    participant_id  TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_event_stream_study_created ON study_event_stream (study_id, created_at DESC);

-- Auto-cleanup: delete events older than 24h
CREATE OR REPLACE FUNCTION cleanup_old_events() RETURNS trigger AS $$
BEGIN
    DELETE FROM study_event_stream WHERE created_at < now() - interval '24 hours';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Fire cleanup on every 100th insert (approximate)
DROP TRIGGER IF EXISTS trg_cleanup_events ON study_event_stream;
CREATE TRIGGER trg_cleanup_events
    AFTER INSERT ON study_event_stream
    FOR EACH STATEMENT
    EXECUTE FUNCTION cleanup_old_events();

-- Enable RLS
ALTER TABLE study_realtime_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_realtime_stats FORCE ROW LEVEL SECURITY;
ALTER TABLE study_event_stream ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_event_stream FORCE ROW LEVEL SECURITY;

CREATE POLICY stats_policy ON study_realtime_stats
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );

CREATE POLICY event_stream_policy ON study_event_stream
    FOR ALL USING (
        current_setting('app.is_admin', true) = 'true'
        OR study_id::text = ANY(string_to_array(current_setting('app.authorized_studies', true), ','))
    );
